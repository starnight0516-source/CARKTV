package com.car.taiwanktv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class KkboxChartType(
    val apiCategory: String,
    val apiType: String,
    val langCode: String,
    val label: String
) {
    ZH_SONG("297", "song", "ZH", "KKBOX 華語單曲日榜"),
    ZH_NEW("297", "newrelease", "ZH", "KKBOX 華語新歌日榜"),
    TW_SONG("304", "song", "TW", "KKBOX 台語單曲日榜"),
    TW_NEW("304", "newrelease", "TW", "KKBOX 台語新歌日榜")
}

object KkboxChartRepository {

    private const val PREFS_NAME = "taiwan_car_ktv_prefs"
    private const val KEY_KK_PREFIX = "saved_kkbox_"

    private val cache = mutableMapOf<KkboxChartType, List<SongItem>>()

    private val client = OkHttpClient.Builder()
        .readTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun getChart(
        context: Context,
        type: KkboxChartType,
        forceRefresh: Boolean = false
    ): List<SongItem> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cache[type]?.let { return@withContext it }
        }

        val savedLocal = loadFromPrefs(context, type)
        val shouldFetch = forceRefresh || CorpSongRepository.isAutoUpdateEnabled(context) || savedLocal.isEmpty()

        val fetched = if (shouldFetch) {
            val remote = fetchFromKkboxApi(type)
            if (remote.isNotEmpty()) {
                saveToPrefs(context, type, remote)
                remote
            } else {
                savedLocal
            }
        } else {
            savedLocal
        }

        val finalResult = if (fetched.isNotEmpty()) fetched else getBuiltInFallback(type)
        cache[type] = finalResult
        finalResult
    }

    suspend fun refreshAllCharts(context: Context): Int = withContext(Dispatchers.IO) {
        cache.clear()
        var total = 0
        for (type in KkboxChartType.values()) {
            val remote = fetchFromKkboxApi(type)
            if (remote.isNotEmpty()) {
                saveToPrefs(context, type, remote)
                cache[type] = remote
                total += remote.size
            }
        }
        total
    }

    fun getAllCachedKkboxSongs(context: Context): List<SongItem> {
        return getCachedKkboxSongsByLang(context, null)
    }

    fun getCachedKkboxSongsByLang(context: Context, langCode: String?): List<SongItem> {
        val merged = LinkedHashMap<String, SongItem>()
        for (type in KkboxChartType.values()) {
            if (langCode != null && type.langCode != langCode) continue
            val list = cache[type] ?: loadFromPrefs(context, type).ifEmpty { getBuiltInFallback(type) }
            for (song in list) {
                val key = "${song.title}_${song.artist}"
                if (!merged.containsKey(key)) {
                    merged[key] = song
                }
            }
        }
        return merged.values.toList()
    }

    private fun fetchFromKkboxApi(type: KkboxChartType): List<SongItem> {
        val url = "https://kma.kkbox.com/charts/api/v1/daily?category=${type.apiCategory}&lang=tc&limit=50&terr=tw&type=${type.apiType}"
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .addHeader("Accept", "application/json")
                .addHeader("Referer", "https://kma.kkbox.com/charts/")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseKkboxJson(body, type.langCode)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseKkboxJson(jsonStr: String, langCode: String): List<SongItem> {
        val results = mutableListOf<SongItem>()
        try {
            val root = JSONObject(jsonStr)
            val data = root.optJSONObject("data") ?: return emptyList()
            val charts = data.optJSONObject("charts") ?: return emptyList()

            // API 可能回傳 song 或 newrelease 陣列
            val arr = charts.optJSONArray("song")
                ?: charts.optJSONArray("newrelease")
                ?: run {
                    val keys = charts.keys()
                    var found: JSONArray? = null
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val candidate = charts.optJSONArray(k)
                        if (candidate != null && candidate.length() > 0) {
                            found = candidate
                            break
                        }
                    }
                    found
                } ?: return emptyList()

            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rawTitle = item.optString("song_name", "").trim()
                val rawArtist = item.optString("artist_name", "").trim()
                if (rawTitle.isBlank()) continue

                val cleanTitle = cleanSongTitle(rawTitle)
                val cleanArtist = cleanArtistName(rawArtist)
                val key = "${cleanTitle}_${cleanArtist}"
                if (seen.contains(key)) continue
                seen.add(key)

                results.add(
                    SongItem(
                        title = cleanTitle,
                        artist = cleanArtist,
                        lang = langCode
                    )
                )
            }
        } catch (_: Exception) {
        }
        return results
    }

    /**
     * 清理 KKBOX 歌名常見的戲劇副標（例如：運轉人生 - 影集《華麗計程車行》插曲 -> 運轉人生）
     * 讓 YouTube 搜尋官方 MV 的精準度大幅提升
     */
    fun cleanSongTitle(raw: String): String {
        var s = raw
            .replace(Regex("\\s*[-–—]\\s*(?:電影|電視劇|影集|戲劇|三立|民視|台視|公視|網劇|動畫|遊戲|廣告|OT:|《).*$"), "")
            .replace(Regex("[\\(（](?:電影|電視劇|影集|戲劇|三立|民視|台視|公視|《).*?[\\)）]"), "")
            .replace(Regex("《.*?》(?:原聲帶|主題曲|片頭曲|片尾曲|插曲|宣傳曲).*$"), "")
            .trim()
        if (s.isBlank()) s = raw.trim()
        return s
    }

    private fun cleanArtistName(raw: String): String {
        return raw
            .replace(Regex("\\([A-Za-z0-9\\s.&'-]+\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun saveToPrefs(context: Context, type: KkboxChartType, songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val arr = JSONArray()
        for (s in songs) {
            val obj = JSONObject()
            obj.put("title", s.title)
            obj.put("artist", s.artist)
            obj.put("lang", s.lang)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_KK_PREFIX + type.name, arr.toString())
            .apply()
    }

    private fun loadFromPrefs(context: Context, type: KkboxChartType): List<SongItem> {
        val jsonStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_KK_PREFIX + type.name, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<SongItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SongItem(
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        lang = obj.optString("lang", type.langCode)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getBuiltInFallback(type: KkboxChartType): List<SongItem> {
        return when (type) {
            KkboxChartType.ZH_SONG -> listOf(
                SongItem("甲乙丙丁", "李佳薇", lang = "ZH"),
                SongItem("欲落未落", "DIOR 大穎", lang = "ZH"),
                SongItem("棠棣賦", "五月天阿信", lang = "ZH"),
                SongItem("再一次愛你", "黃霆睿", lang = "ZH"),
                SongItem("如果可以", "韋禮安", lang = "ZH"),
                SongItem("摯友", "A-Lin", lang = "ZH"),
                SongItem("披星戴月的想你", "告五人", lang = "ZH"),
                SongItem("在這座城市遺失了你", "告五人", lang = "ZH"),
                SongItem("愛人錯過", "告五人", lang = "ZH"),
                SongItem("我很好騙", "動力火車", lang = "ZH"),
                SongItem("以後別做朋友", "周興哲", lang = "ZH"),
                SongItem("怎麼了", "周興哲", lang = "ZH"),
                SongItem("晴天", "周杰倫", lang = "ZH"),
                SongItem("擱淺", "周杰倫", lang = "ZH"),
                SongItem("孤勇者", "陳奕迅", lang = "ZH")
            )
            KkboxChartType.ZH_NEW -> listOf(
                SongItem("棠棣賦", "五月天阿信", lang = "ZH"),
                SongItem("再一次愛你", "黃霆睿", lang = "ZH"),
                SongItem("欲落未落", "DIOR 大穎", lang = "ZH"),
                SongItem("甲乙丙丁", "李佳薇", lang = "ZH"),
                SongItem("不愛就不愛", "艾薇", lang = "ZH"),
                SongItem("好不容易", "告五人", lang = "ZH"),
                SongItem("又到天黑", "告五人", lang = "ZH"),
                SongItem("都是因為愛", "動力火車", lang = "ZH"),
                SongItem("烏梅子醬", "李榮浩", lang = "ZH"),
                SongItem("無人知曉", "田馥甄", lang = "ZH")
            )
            KkboxChartType.TW_SONG -> listOf(
                SongItem("必巡", "曾瑋中", lang = "TW"),
                SongItem("你講的話", "江蕙", lang = "TW"),
                SongItem("運轉人生", "邱軍", lang = "TW"),
                SongItem("自信勇敢咱的名", "滅火器", lang = "TW"),
                SongItem("綿綿無絕期", "黃奇斌", lang = "TW"),
                SongItem("我愛你一句話", "蕭秉治, 洋蔥", lang = "TW"),
                SongItem("人生的歌", "黃乙玲", lang = "TW"),
                SongItem("我甘願重新愛過", "洋蔥, 狗柏", lang = "TW"),
                SongItem("心裡有針", "蕭煌奇", lang = "TW"),
                SongItem("夢中的情話", "江蕙", lang = "TW"),
                SongItem("咱到遮就為止", "黃奇斌", lang = "TW"),
                SongItem("心花開", "李千娜", lang = "TW"),
                SongItem("島嶼天光", "滅火器", lang = "TW"),
                SongItem("講袂出喙的情話", "許富凱", lang = "TW"),
                SongItem("棉照被", "許富凱", lang = "TW"),
                SongItem("無字的情批", "黃乙玲", lang = "TW"),
                SongItem("愛到無命不知驚", "王識賢", lang = "TW"),
                SongItem("寫一條歌，寫你我爾爾", "蕭煌奇, 茄子蛋", lang = "TW"),
                SongItem("落雨聲", "江蕙", lang = "TW"),
                SongItem("對我祝福", "許富凱", lang = "TW"),
                SongItem("鹹酸苦汫", "麋先生聖皓, 王識賢", lang = "TW"),
                SongItem("有你有我", "春風, 王識賢", lang = "TW"),
                SongItem("別人的", "徐若瑄", lang = "TW"),
                SongItem("無夢無望", "王識賢", lang = "TW"),
                SongItem("查某囡仔", "李千娜", lang = "TW")
            )
            KkboxChartType.TW_NEW -> listOf(
                SongItem("我愛你一句話", "蕭秉治, 洋蔥", lang = "TW"),
                SongItem("鹹酸苦汫", "麋先生聖皓, 王識賢", lang = "TW"),
                SongItem("一見鍾情", "翁立友, 林琇琪", lang = "TW"),
                SongItem("長恨曲", "翁立友", lang = "TW"),
                SongItem("憨膽", "琳誼", lang = "TW"),
                SongItem("天光以前", "草屯囝仔", lang = "TW"),
                SongItem("若無你", "蕭煌奇", lang = "TW"),
                SongItem("按呢敢閣有意義", "草屯囝仔", lang = "TW"),
                SongItem("愛甲心無留", "謝宜君", lang = "TW"),
                SongItem("緣份只是借過的名", "丁姵均", lang = "TW"),
                SongItem("陪你到老", "吳申梅", lang = "TW"),
                SongItem("郎有情妹有意", "陳隨意, 謝宜君", lang = "TW"),
                SongItem("為愛守孤單", "袁小迪", lang = "TW"),
                SongItem("轉念", "翁立友", lang = "TW"),
                SongItem("牽做伙", "李明洋", lang = "TW")
            )
        }
    }
}
