package com.car.taiwanktv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SongCategory {
    HOT, TAIWANESE, MANDARIN, CLASSICS
}

object CorpSongRepository {

    private const val PREFS_NAME = "taiwan_car_ktv_prefs"
    private const val KEY_AUTO_UPDATE = "auto_update_catalog"
    private const val KEY_LAST_UPDATE = "last_update_time"
    private const val KEY_SAVED_PREFIX = "saved_songs_"

    private val cache = mutableMapOf<SongCategory, List<SongItem>>()

    val featuredSingers: List<SingerInfo> = listOf(
        SingerInfo("周杰倫", "國語天王"),
        SingerInfo("江蕙", "台語天后"),
        SingerInfo("伍佰", "搖滾天王"),
        SingerInfo("五月天", "天團金曲"),
        SingerInfo("翁立友", "台語金曲"),
        SingerInfo("張學友", "歌神經典"),
        SingerInfo("張惠妹", "國語天后"),
        SingerInfo("陳奕迅", "K歌天王"),
        SingerInfo("林俊傑", "創作天王"),
        SingerInfo("鄧紫棋", "鐵肺天后"),
        SingerInfo("詹雅雯", "台語歌后"),
        SingerInfo("蕭煌奇", "金曲歌王"),
        SingerInfo("蔡小虎", "台語男神"),
        SingerInfo("龍千玉", "台語歌后"),
        SingerInfo("王識賢", "台語經典"),
        SingerInfo("黃乙玲", "台語歌后"),
        SingerInfo("茄子蛋", "台語搖滾"),
        SingerInfo("告五人", "人氣樂團"),
        SingerInfo("周興哲", "情歌王子"),
        SingerInfo("蔡依林", "流行天后"),
        SingerInfo("盧廣仲", "三金歌王"),
        SingerInfo("動力火車", "搖滾天團"),
        SingerInfo("田馥甄", "文青女神"),
        SingerInfo("梁靜茹", "情歌天后"),
        SingerInfo("王菲", "傳奇天后"),
        SingerInfo("孫燕姿", "金曲歌后"),
        SingerInfo("李宗盛", "音樂教父"),
        SingerInfo("張宇", "苦情歌王"),
        SingerInfo("任賢齊", "國民天王"),
        SingerInfo("劉德華", "不老天王"),
        SingerInfo("林宥嘉", "迷幻歌王"),
        SingerInfo("八三夭", "熱血樂團"),
        SingerInfo("陳雷", "台語招牌"),
        SingerInfo("葉啟田", "寶島歌王"),
        SingerInfo("黃妃", "台語歌姬"),
        SingerInfo("謝金燕", "電音女神"),
        SingerInfo("張秀卿", "台語歌后"),
        SingerInfo("袁小迪", "台語男聲"),
        SingerInfo("施文彬", "台語經典"),
        SingerInfo("張清芳", "東方不敗"),
        SingerInfo("陶喆", "R&B教父"),
        SingerInfo("王力宏", "全能天王")
    )

    fun isAutoUpdateEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_UPDATE, true)
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    fun getLastUpdateTime(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_UPDATE, "尚未更新") ?: "尚未更新"
    }

    /**
     * 一鍵同步/強制更新最新歌單（同時寫入本機永久快取）
     */
    suspend fun refreshAllFromWeb(context: Context): Int = withContext(Dispatchers.IO) {
        cache.clear()
        var totalScraped = 0
        for (cat in listOf(SongCategory.HOT, SongCategory.TAIWANESE, SongCategory.MANDARIN)) {
            val scraped = fetchScrapedForCategory(cat)
            totalScraped += scraped.size
            saveScrapedToPrefs(context, cat, scraped)
            val merged = mergeLists(scraped, getBuiltInSongs(cat))
            cache[cat] = merged
        }
        cache[SongCategory.CLASSICS] = getBuiltInSongs(SongCategory.CLASSICS)

        val timeStr = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UPDATE, timeStr)
            .apply()

        totalScraped
    }

    suspend fun getSongs(context: Context, category: SongCategory, allowNetwork: Boolean = true): List<SongItem> =
        withContext(Dispatchers.IO) {
            cache[category]?.let { return@withContext it }

            val builtIn = getBuiltInSongs(category)
            val savedLocal = loadScrapedFromPrefs(context, category)

            val scraped = if (allowNetwork && isAutoUpdateEnabled(context)) {
                val fresh = fetchScrapedForCategory(category)
                if (fresh.isNotEmpty()) {
                    saveScrapedToPrefs(context, category, fresh)
                    val timeStr = SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN).format(Date())
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST_UPDATE, timeStr)
                        .apply()
                    fresh
                } else {
                    savedLocal
                }
            } else {
                savedLocal
            }

            val merged = mergeLists(scraped, builtIn)
            cache[category] = merged
            merged
        }

    /**
     * 依公播模式（混合 / 純國語 / 純台語）回傳對應的連播歌庫
     */
    fun getAutoPlayPool(context: Context, mode: AutoPlayMode): List<SongItem> {
        val all = getAllBuiltInAndCachedSongs(context)
        return when (mode) {
            AutoPlayMode.MIXED -> all
            AutoPlayMode.MANDARIN -> all.filter { it.lang == "ZH" }
            AutoPlayMode.TAIWANESE -> all.filter { it.lang == "TW" }
            AutoPlayMode.KKBOX_ALL -> KkboxChartRepository.getCachedKkboxSongsByLang(context, null)
            AutoPlayMode.KKBOX_ZH -> KkboxChartRepository.getCachedKkboxSongsByLang(context, "ZH")
            AutoPlayMode.KKBOX_TW -> KkboxChartRepository.getCachedKkboxSongsByLang(context, "TW")
        }.ifEmpty { all }
    }

    fun getAllBuiltInAndCachedSongs(context: Context? = null): List<SongItem> {
        val merged = LinkedHashMap<String, SongItem>()
        for (cat in SongCategory.values()) {
            val list = cache[cat] ?: run {
                val saved = if (context != null) loadScrapedFromPrefs(context, cat) else emptyList()
                mergeLists(saved, getBuiltInSongs(cat))
            }
            for (song in list) {
                val key = "${song.title}_${song.artist}"
                if (!merged.containsKey(key)) {
                    merged[key] = song
                }
            }
        }
        if (context != null) {
            for (song in KkboxChartRepository.getAllCachedKkboxSongs(context)) {
                val key = "${song.title}_${song.artist}"
                if (!merged.containsKey(key)) {
                    merged[key] = song
                }
            }
        }
        return merged.values.toList()
    }

    fun filterBySingerLocal(context: Context, singerName: String): List<SongItem> {
        return getAllBuiltInAndCachedSongs(context).filter {
            it.artist.contains(singerName, ignoreCase = true)
        }
    }

    fun filterByWordCount(context: Context, wordCount: Int): List<SongItem> {
        val all = getAllBuiltInAndCachedSongs(context)
        if (wordCount <= 0) return all
        return all.filter { song ->
            val len = countTitleWords(song.title)
            if (wordCount >= 6) len >= 6 else len == wordCount
        }
    }

    fun countTitleWords(title: String): Int {
        val cleaned = title
            .replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
            .replace(Regex("[\\s\\p{Punct}，。！？、～—・]+"), "")
            .trim()
        return cleaned.length
    }

    private fun fetchScrapedForCategory(category: SongCategory): List<SongItem> {
        val urlsAndLang = when (category) {
            SongCategory.HOT -> listOf(
                "https://song.corp.com.tw/" to "MIX",
                "https://song.corp.com.tw/songs.aspx?company=%E9%8C%A2%E6%AB%83" to "ZH",
                "https://song.corp.com.tw/songs.aspx?company=%E5%A5%BD%E6%A8%82%E8%BF%AA" to "ZH"
            )
            SongCategory.TAIWANESE -> listOf(
                "https://song.corp.com.tw/songs.aspx?company=%E5%BC%98%E9%9F%B3" to "TW",
                "https://song.corp.com.tw/songs.aspx?company=%E9%87%91%E5%97%93" to "TW"
            )
            SongCategory.MANDARIN -> listOf(
                "https://song.corp.com.tw/songs.aspx?company=%E9%9F%B3%E5%9C%93&lang=%E5%9C%8B" to "ZH"
            )
            SongCategory.CLASSICS -> emptyList()
        }

        val merged = LinkedHashMap<String, SongItem>()
        for ((url, lang) in urlsAndLang) {
            for (song in scrapeCorpPage(url, lang)) {
                val key = "${song.title}_${song.artist}"
                if (!merged.containsKey(key)) merged[key] = song
            }
        }
        return merged.values.toList()
    }

    private fun scrapeCorpPage(url: String, defaultLang: String): List<SongItem> {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .timeout(8000)
                .get()

            val results = mutableListOf<SongItem>()
            val seenUrls = HashSet<String>()

            val links = doc.select("a[href*=mv.aspx]")
            for (el in links) {
                val href = el.absUrl("href")
                if (href.isBlank() || seenUrls.contains(href)) continue

                val rawText = el.text().replace(Regex("\\s+"), " ").trim()
                if (rawText.length < 2 || rawText.contains("更多") || rawText.contains("首頁")) continue

                seenUrls.add(href)

                val parts = rawText.split(" ").filter { it.isNotBlank() }
                val title = parts.firstOrNull() ?: rawText
                val artist = if (parts.size > 1) parts.drop(1).joinToString(" ") else "台灣點歌王"

                results.add(
                    SongItem(
                        title = title,
                        artist = artist,
                        corpMvUrl = href,
                        lang = defaultLang
                    )
                )
                if (results.size >= 60) break
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergeLists(primary: List<SongItem>, secondary: List<SongItem>): List<SongItem> {
        val merged = LinkedHashMap<String, SongItem>()
        for (song in primary) {
            val key = "${song.title}_${song.artist}"
            if (!merged.containsKey(key)) merged[key] = song
        }
        for (song in secondary) {
            val key = "${song.title}_${song.artist}"
            if (!merged.containsKey(key)) merged[key] = song
        }
        return merged.values.toList()
    }

    private fun saveScrapedToPrefs(context: Context, category: SongCategory, songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val arr = JSONArray()
        for (s in songs) {
            val obj = JSONObject()
            obj.put("title", s.title)
            obj.put("artist", s.artist)
            obj.put("corpMvUrl", s.corpMvUrl ?: "")
            obj.put("lang", s.lang)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SAVED_PREFIX + category.name, arr.toString())
            .apply()
    }

    private fun loadScrapedFromPrefs(context: Context, category: SongCategory): List<SongItem> {
        val jsonStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAVED_PREFIX + category.name, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<SongItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    SongItem(
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        corpMvUrl = obj.optString("corpMvUrl").takeIf { it.isNotBlank() },
                        lang = obj.optString("lang", "MIX")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getBuiltInSongs(category: SongCategory): List<SongItem> {
        return when (category) {
            SongCategory.HOT -> listOf(
                SongItem("轉念", "翁立友", lang = "TW"),
                SongItem("落花年月", "謝宜君", lang = "TW"),
                SongItem("愛到徹底", "吳勇濱", lang = "TW"),
                SongItem("一見鍾情", "翁立友 + 林琇琪", lang = "TW"),
                SongItem("若是無你", "謝舒騏 + 許峻榮", lang = "TW"),
                SongItem("舞影", "謝金晶 + 吳俊宏", lang = "TW"),
                SongItem("不愛就不愛", "艾薇", lang = "ZH"),
                SongItem("披星戴月的想你", "告五人", lang = "ZH"),
                SongItem("在這座城市遺失了你", "告五人", lang = "ZH"),
                SongItem("愛人錯過", "告五人", lang = "ZH"),
                SongItem("好不容易", "告五人", lang = "ZH"),
                SongItem("又到天黑", "告五人", lang = "ZH"),
                SongItem("浪子回頭", "茄子蛋", lang = "TW"),
                SongItem("浪流連", "茄子蛋", lang = "TW"),
                SongItem("愛情你比我想的閣較偉大", "茄子蛋", lang = "TW"),
                SongItem("閣愛妳一擺", "茄子蛋", lang = "TW"),
                SongItem("日常", "茄子蛋", lang = "TW"),
                SongItem("刻在我心底的名字", "盧廣仲", lang = "ZH"),
                SongItem("魚仔", "盧廣仲", lang = "TW"),
                SongItem("幾分之幾", "盧廣仲", lang = "ZH"),
                SongItem("想見你想見你想見你", "八三夭", lang = "ZH"),
                SongItem("東區東區", "八三夭", lang = "ZH"),
                SongItem("致青春", "八三夭", lang = "ZH"),
                SongItem("以後別做朋友", "周興哲", lang = "ZH"),
                SongItem("你，好不好？", "周興哲", lang = "ZH"),
                SongItem("怎麼了", "周興哲", lang = "ZH"),
                SongItem("如果雨之後", "周興哲", lang = "ZH"),
                SongItem("永不失聯的愛", "周興哲", lang = "ZH"),
                SongItem("孤勇者", "陳奕迅", lang = "ZH"),
                SongItem("我很好騙", "動力火車", lang = "ZH"),
                SongItem("都是因為愛", "動力火車", lang = "ZH"),
                SongItem("嘉賓", "張遠", lang = "ZH"),
                SongItem("烏梅子醬", "李榮浩", lang = "ZH"),
                SongItem("年少有為", "李榮浩", lang = "ZH"),
                SongItem("李白", "李榮浩", lang = "ZH"),
                SongItem("模特", "李榮浩", lang = "ZH"),
                SongItem("體面", "于文文", lang = "ZH"),
                SongItem("說散就散", "JC", lang = "ZH"),
                SongItem("小幸運", "田馥甄", lang = "ZH"),
                SongItem("你就不要想起我", "田馥甄", lang = "ZH"),
                SongItem("魔鬼中的天使", "田馥甄", lang = "ZH"),
                SongItem("寂寞寂寞就好", "田馥甄", lang = "ZH"),
                SongItem("無人知曉", "田馥甄", lang = "ZH"),
                SongItem("兜圈", "林宥嘉", lang = "ZH"),
                SongItem("說謊", "林宥嘉", lang = "ZH"),
                SongItem("浪費", "林宥嘉", lang = "ZH"),
                SongItem("成全", "林宥嘉", lang = "ZH"),
                SongItem("天真有邪", "林宥嘉", lang = "ZH"),
                SongItem("如果可以", "韋禮安", lang = "ZH"),
                SongItem("慢慢等", "韋禮安", lang = "ZH"),
                SongItem("還是會", "韋禮安", lang = "ZH"),
                SongItem("女孩", "韋禮安", lang = "ZH"),
                SongItem("摯友", "A-Lin", lang = "ZH"),
                SongItem("有一種悲傷", "A-Lin", lang = "ZH"),
                SongItem("失戀無罪", "A-Lin", lang = "ZH"),
                SongItem("給我一個理由忘記", "A-Lin", lang = "ZH"),
                SongItem("我們不一樣", "大壯", lang = "ZH"),
                SongItem("起風了", "買辣椒也用券", lang = "ZH"),
                SongItem("熱愛105°C的你", "阿肆", lang = "ZH"),
                SongItem("黑桃A", "DJ小魚兒", lang = "ZH")
            )
            SongCategory.TAIWANESE -> listOf(
                SongItem("家後", "江蕙", lang = "TW"),
                SongItem("甲你攬牢牢", "江蕙", lang = "TW"),
                SongItem("落雨聲", "江蕙", lang = "TW"),
                SongItem("酒後的心聲", "江蕙", lang = "TW"),
                SongItem("惜別的海岸", "江蕙", lang = "TW"),
                SongItem("感情放一邊", "江蕙", lang = "TW"),
                SongItem("藝界人生", "江蕙", lang = "TW"),
                SongItem("半醉半清醒", "江蕙", lang = "TW"),
                SongItem("無言花", "江蕙", lang = "TW"),
                SongItem("博杯", "江蕙", lang = "TW"),
                SongItem("堅持", "翁立友", lang = "TW"),
                SongItem("迷魂香", "翁立友", lang = "TW"),
                SongItem("我問天", "翁立友", lang = "TW"),
                SongItem("送行", "翁立友", lang = "TW"),
                SongItem("行棋", "翁立友", lang = "TW"),
                SongItem("手中情", "翁立友", lang = "TW"),
                SongItem("不能講的秘密", "翁立友", lang = "TW"),
                SongItem("夜市人生", "翁立友", lang = "TW"),
                SongItem("阿嬤的話", "蕭煌奇", lang = "TW"),
                SongItem("你是我的眼", "蕭煌奇", lang = "ZH"),
                SongItem("末班車", "蕭煌奇", lang = "ZH"),
                SongItem("思念會驚", "蕭煌奇", lang = "TW"),
                SongItem("上水的花", "蕭煌奇", lang = "TW"),
                SongItem("心愛的再會啦", "伍佰", lang = "TW"),
                SongItem("樹枝孤鳥", "伍佰", lang = "TW"),
                SongItem("煞到妳", "伍佰", lang = "TW"),
                SongItem("空襲警報", "伍佰", lang = "TW"),
                SongItem("飛在風中的小雨", "伍佰", lang = "TW"),
                SongItem("世界第一等", "伍佰", lang = "TW"),
                SongItem("墓仔埔也敢去", "伍佰", lang = "TW"),
                SongItem("追追追", "黃妃", lang = "TW"),
                SongItem("非常女", "黃妃", lang = "TW"),
                SongItem("風塊哭", "黃妃", lang = "TW"),
                SongItem("無人熟識", "張清芳", lang = "TW"),
                SongItem("愛拚才會贏", "葉啟田", lang = "TW"),
                SongItem("故鄉", "葉啟田", lang = "TW"),
                SongItem("浪子的心情", "葉啟田", lang = "TW"),
                SongItem("忍", "葉啟田", lang = "TW"),
                SongItem("深情海岸", "詹雅雯", lang = "TW"),
                SongItem("想厝的人", "詹雅雯", lang = "TW"),
                SongItem("今年一定會好過", "詹雅雯", lang = "TW"),
                SongItem("人生公路", "詹雅雯", lang = "TW"),
                SongItem("愛情的騙子我問你", "陳小雲", lang = "TW"),
                SongItem("舞女", "陳小雲", lang = "TW"),
                SongItem("人生的歌", "黃乙玲", lang = "TW"),
                SongItem("無字的情批", "黃乙玲", lang = "TW"),
                SongItem("愛到才知痛", "黃乙玲", lang = "TW"),
                SongItem("海波浪", "郭桂彬 + 黃乙玲", lang = "TW"),
                SongItem("歡喜就好", "陳雷", lang = "TW"),
                SongItem("風真透", "陳雷", lang = "TW"),
                SongItem("往事就是我的安慰", "陳雷", lang = "TW"),
                SongItem("心愛的甭哭", "陳雷", lang = "TW"),
                SongItem("車站", "張秀卿", lang = "TW"),
                SongItem("辣妹駕到", "張秀卿", lang = "TW"),
                SongItem("練舞功", "謝金燕", lang = "TW"),
                SongItem("姐姐", "謝金燕", lang = "TW"),
                SongItem("嗶嗶嗶", "謝金燕", lang = "TW"),
                SongItem("含淚跳恰恰", "謝金燕", lang = "TW"),
                SongItem("再會啦心愛的無緣的人", "施文彬", lang = "TW"),
                SongItem("天涯", "王識賢", lang = "TW"),
                SongItem("一片天", "王識賢", lang = "TW"),
                SongItem("勝者為王", "王識賢", lang = "TW"),
                SongItem("腳踏車", "王識賢", lang = "TW"),
                SongItem("反背", "王識賢", lang = "TW"),
                SongItem("情難忘", "王識賢", lang = "TW"),
                SongItem("春夏秋冬", "蔡小虎", lang = "TW"),
                SongItem("一生只有你", "蔡小虎", lang = "TW"),
                SongItem("愛人醉落去", "蔡小虎", lang = "TW"),
                SongItem("問感情", "蔡小虎", lang = "TW"),
                SongItem("心借過", "蔡小虎", lang = "TW"),
                SongItem("阿霞相思夢", "蔡小虎", lang = "TW"),
                SongItem("重出江湖", "袁小迪", lang = "TW"),
                SongItem("人生啊人生", "袁小迪", lang = "TW"),
                SongItem("紙雲煙", "袁小迪", lang = "TW"),
                SongItem("雙人枕頭", "王識賢", lang = "TW"),
                SongItem("望春風", "鄧雨賢", lang = "TW"),
                SongItem("雨夜花", "江蕙", lang = "TW"),
                SongItem("思慕的人", "洪一峰", lang = "TW"),
                SongItem("舊情綿綿", "洪一峰", lang = "TW"),
                SongItem("港都夜雨", "吳晉淮", lang = "TW"),
                SongItem("一支小雨傘", "洪榮宏", lang = "TW"),
                SongItem("相思雨", "洪榮宏", lang = "TW"),
                SongItem("阿宏的心聲", "洪榮宏", lang = "TW"),
                SongItem("乾一杯", "葉啟田", lang = "TW"),
                SongItem("金包銀", "蔡秋鳳", lang = "TW"),
                SongItem("雨水我問你", "蔡秋鳳", lang = "TW"),
                SongItem("醉英雄", "蔡秋鳳", lang = "TW"),
                SongItem("空笑夢", "王中平", lang = "TW"),
                SongItem("向天要一點愛", "王中平", lang = "TW"),
                SongItem("茫茫到深更", "羅時豐", lang = "TW"),
                SongItem("歡喜來恰恰", "羅時豐", lang = "TW")
            )
            SongCategory.MANDARIN -> listOf(
                SongItem("晴天", "周杰倫", lang = "ZH"),
                SongItem("稻香", "周杰倫", lang = "ZH"),
                SongItem("告白氣球", "周杰倫", lang = "ZH"),
                SongItem("七里香", "周杰倫", lang = "ZH"),
                SongItem("青花瓷", "周杰倫", lang = "ZH"),
                SongItem("夜曲", "周杰倫", lang = "ZH"),
                SongItem("擱淺", "周杰倫", lang = "ZH"),
                SongItem("安靜", "周杰倫", lang = "ZH"),
                SongItem("簡單愛", "周杰倫", lang = "ZH"),
                SongItem("雙截棍", "周杰倫", lang = "ZH"),
                SongItem("聽媽媽的話", "周杰倫", lang = "ZH"),
                SongItem("說好不哭", "周杰倫", lang = "ZH"),
                SongItem("不能說的秘密", "周杰倫", lang = "ZH"),
                SongItem("一路向北", "周杰倫", lang = "ZH"),
                SongItem("楓", "周杰倫", lang = "ZH"),
                SongItem("突然好想你", "五月天", lang = "ZH"),
                SongItem("倔強", "五月天", lang = "ZH"),
                SongItem("溫柔", "五月天", lang = "ZH"),
                SongItem("知足", "五月天", lang = "ZH"),
                SongItem("憨人", "五月天", lang = "TW"),
                SongItem("志明與春嬌", "五月天", lang = "TW"),
                SongItem("離開地球表面", "五月天", lang = "ZH"),
                SongItem("乾杯", "五月天", lang = "ZH"),
                SongItem("後來的我們", "五月天", lang = "ZH"),
                SongItem("我不願讓你一個人", "五月天", lang = "ZH"),
                SongItem("挪威的森林", "伍佰", lang = "ZH"),
                SongItem("浪人情歌", "伍佰", lang = "ZH"),
                SongItem("Last Dance", "伍佰", lang = "ZH"),
                SongItem("愛你一萬年", "伍佰", lang = "ZH"),
                SongItem("夢醒時分", "伍佰", lang = "ZH"),
                SongItem("痛哭的人", "伍佰", lang = "ZH"),
                SongItem("被動", "伍佰", lang = "ZH"),
                SongItem("淚橋", "伍佰", lang = "ZH"),
                SongItem("光年之外", "鄧紫棋", lang = "ZH"),
                SongItem("泡沫", "鄧紫棋", lang = "ZH"),
                SongItem("倒數", "鄧紫棋", lang = "ZH"),
                SongItem("喜歡你", "鄧紫棋", lang = "ZH"),
                SongItem("句號", "鄧紫棋", lang = "ZH"),
                SongItem("修煉愛情", "林俊傑", lang = "ZH"),
                SongItem("江南", "林俊傑", lang = "ZH"),
                SongItem("曹操", "林俊傑", lang = "ZH"),
                SongItem("不為誰而作的歌", "林俊傑", lang = "ZH"),
                SongItem("可惜沒如果", "林俊傑", lang = "ZH"),
                SongItem("她說", "林俊傑", lang = "ZH"),
                SongItem("豆漿油條", "林俊傑", lang = "ZH"),
                SongItem("吻別", "張學友", lang = "ZH"),
                SongItem("一路上有你", "張學友", lang = "ZH"),
                SongItem("祝福", "張學友", lang = "ZH"),
                SongItem("一千個傷心的理由", "張學友", lang = "ZH"),
                SongItem("情網", "張學友", lang = "ZH"),
                SongItem("心如刀割", "張學友", lang = "ZH"),
                SongItem("餓狼傳說", "張學友", lang = "ZH"),
                SongItem("她來聽我的演唱會", "張學友", lang = "ZH"),
                SongItem("聽海", "張惠妹", lang = "ZH"),
                SongItem("原來你什麼都不要", "張惠妹", lang = "ZH"),
                SongItem("姊妹", "張惠妹", lang = "ZH"),
                SongItem("三天三夜", "張惠妹", lang = "ZH"),
                SongItem("記得", "張惠妹", lang = "ZH"),
                SongItem("剪愛", "張惠妹", lang = "ZH"),
                SongItem("我最親愛的", "張惠妹", lang = "ZH"),
                SongItem("連名帶姓", "張惠妹", lang = "ZH"),
                SongItem("十年", "陳奕迅", lang = "ZH"),
                SongItem("K歌之王", "陳奕迅", lang = "ZH"),
                SongItem("浮誇", "陳奕迅", lang = "ZH"),
                SongItem("富士山下", "陳奕迅", lang = "ZH"),
                SongItem("愛情轉移", "陳奕迅", lang = "ZH"),
                SongItem("好久不見", "陳奕迅", lang = "ZH"),
                SongItem("紅豆", "王菲", lang = "ZH"),
                SongItem("我願意", "王菲", lang = "ZH"),
                SongItem("傳奇", "王菲", lang = "ZH"),
                SongItem("勇氣", "梁靜茹", lang = "ZH"),
                SongItem("分手快樂", "梁靜茹", lang = "ZH"),
                SongItem("可惜不是你", "梁靜茹", lang = "ZH"),
                SongItem("會呼吸的痛", "梁靜茹", lang = "ZH"),
                SongItem("寧夏", "梁靜茹", lang = "ZH"),
                SongItem("天黑黑", "孫燕姿", lang = "ZH"),
                SongItem("遇見", "孫燕姿", lang = "ZH"),
                SongItem("我懷念的", "孫燕姿", lang = "ZH"),
                SongItem("開始懂了", "孫燕姿", lang = "ZH"),
                SongItem("綠光", "孫燕姿", lang = "ZH"),
                SongItem("山丘", "李宗盛", lang = "ZH"),
                SongItem("凡人歌", "李宗盛", lang = "ZH"),
                SongItem("鬼迷心竅", "李宗盛", lang = "ZH"),
                SongItem("用心良苦", "張宇", lang = "ZH"),
                SongItem("月亮惹的禍", "張宇", lang = "ZH"),
                SongItem("雨一直下", "張宇", lang = "ZH"),
                SongItem("趁早", "張宇", lang = "ZH"),
                SongItem("心太軟", "任賢齊", lang = "ZH"),
                SongItem("對面的女孩看過來", "任賢齊", lang = "ZH"),
                SongItem("傷心太平洋", "任賢齊", lang = "ZH"),
                SongItem("忘情水", "劉德華", lang = "ZH"),
                SongItem("冰雨", "劉德華", lang = "ZH"),
                SongItem("練習", "劉德華", lang = "ZH"),
                SongItem("世界第一等", "劉德華", lang = "TW"),
                SongItem("忠孝東路走九遍", "動力火車", lang = "ZH"),
                SongItem("當", "動力火車", lang = "ZH"),
                SongItem("無情的情書", "動力火車", lang = "ZH"),
                SongItem("彩虹", "動力火車", lang = "ZH"),
                SongItem("倒帶", "蔡依林", lang = "ZH"),
                SongItem("說愛你", "蔡依林", lang = "ZH"),
                SongItem("日不落", "蔡依林", lang = "ZH"),
                SongItem("舞孃", "蔡依林", lang = "ZH"),
                SongItem("愛很簡單", "陶喆", lang = "ZH"),
                SongItem("小鎮姑娘", "陶喆", lang = "ZH"),
                SongItem("普通朋友", "陶喆", lang = "ZH"),
                SongItem("唯一", "王力宏", lang = "ZH"),
                SongItem("大城小愛", "王力宏", lang = "ZH"),
                SongItem("你不知道的事", "王力宏", lang = "ZH"),
                SongItem("情非得已", "庾澄慶", lang = "ZH"),
                SongItem("春泥", "庾澄慶", lang = "ZH"),
                SongItem("海闊天空", "Beyond", lang = "ZH"),
                SongItem("光輝歲月", "Beyond", lang = "ZH"),
                SongItem("真的愛你", "Beyond", lang = "ZH"),
                SongItem("朋友", "周華健", lang = "ZH"),
                SongItem("花心", "周華健", lang = "ZH"),
                SongItem("讓我歡喜讓我憂", "周華健", lang = "ZH"),
                SongItem("愛如潮水", "張信哲", lang = "ZH"),
                SongItem("過火", "張信哲", lang = "ZH"),
                SongItem("太想愛你", "張信哲", lang = "ZH"),
                SongItem("大約在冬季", "齊秦", lang = "ZH"),
                SongItem("夜夜夜夜", "齊秦", lang = "ZH"),
                SongItem("童話", "光良", lang = "ZH"),
                SongItem("情歌王", "古巨基", lang = "ZH"),
                SongItem("領悟", "辛曉琪", lang = "ZH"),
                SongItem("味道", "辛曉琪", lang = "ZH"),
                SongItem("新不了情", "萬芳", lang = "ZH"),
                SongItem("愛江山更愛美人", "李麗芬", lang = "ZH"),
                SongItem("魯冰花", "曾淑勤", lang = "ZH"),
                SongItem("外婆的澎湖灣", "潘安邦", lang = "ZH"),
                SongItem("甜蜜蜜", "鄧麗君", lang = "ZH"),
                SongItem("月亮代表我的心", "鄧麗君", lang = "ZH"),
                SongItem("我只在乎你", "鄧麗君", lang = "ZH"),
                SongItem("小城故事", "鄧麗君", lang = "ZH"),
                SongItem("恰似你的溫柔", "蔡琴", lang = "ZH"),
                SongItem("被遺忘的時光", "蔡琴", lang = "ZH"),
                SongItem("讀你", "蔡琴", lang = "ZH"),
                SongItem("掌聲響起", "鳳飛飛", lang = "ZH"),
                SongItem("流水年華", "鳳飛飛", lang = "ZH"),
                SongItem("追夢人", "鳳飛飛", lang = "ZH")
            )
            SongCategory.CLASSICS -> listOf(
                SongItem("蝴蝶夢", "蔡小虎 + 龍千玉", lang = "TW"),
                SongItem("三生石", "李明洋 + 朱海君", lang = "TW"),
                SongItem("香水", "江志豐 + 張秀卿", lang = "TW"),
                SongItem("男人情女人心", "袁小迪 + 龍千玉", lang = "TW"),
                SongItem("雲中月圓", "王識賢 + 孫淑媚", lang = "TW"),
                SongItem("雪中紅", "王識賢 + 陳亮吟", lang = "TW"),
                SongItem("夢中的情話", "江蕙 + 阿杜", lang = "TW"),
                SongItem("傷心酒店", "江蕙 + 施文彬", lang = "TW"),
                SongItem("屋頂", "吳宗憲 + 溫嵐", lang = "ZH"),
                SongItem("珊瑚海", "周杰倫 + 梁心頤", lang = "ZH"),
                SongItem("千里之外", "周杰倫 + 費玉清", lang = "ZH"),
                SongItem("廣島之戀", "張洪量 + 莫文蔚", lang = "ZH"),
                SongItem("你最珍貴", "張學友 + 高慧君", lang = "ZH"),
                SongItem("選擇", "林子祥 + 葉蒨文", lang = "ZH"),
                SongItem("製造浪漫", "鄭中基 + 陳慧琳", lang = "ZH"),
                SongItem("今天妳要嫁給我", "陶喆 + 蔡依林", lang = "ZH"),
                SongItem("私奔到月球", "五月天 + 陳綺貞", lang = "ZH"),
                SongItem("小酒窩", "林俊傑 + 蔡卓妍", lang = "ZH"),
                SongItem("梁山伯與茱麗葉", "曹格 + 卓文萱", lang = "ZH"),
                SongItem("戀愛達人", "羅志祥 + 小S", lang = "ZH"),
                SongItem("不得不愛", "潘瑋柏 + 弦子", lang = "ZH"),
                SongItem("快樂崇拜", "潘瑋柏 + 張韶涵", lang = "ZH"),
                SongItem("有一點動心", "張信哲 + 劉嘉玲", lang = "ZH"),
                SongItem("明明白白我的心", "成龍 + 陳淑樺", lang = "ZH"),
                SongItem("當愛已成往事", "林憶蓮 + 李宗盛", lang = "ZH"),
                SongItem("你是我胸口永遠的痛", "王傑 + 葉歡", lang = "ZH"),
                SongItem("鐵血丹心", "羅文 + 甄妮", lang = "ZH"),
                SongItem("無言的結局", "林淑容 + 羅時豐", lang = "ZH"),
                SongItem("小姐請你給我愛", "羅時豐 + 王瑞霞", lang = "TW"),
                SongItem("雙人枕頭", "王識賢 + 孫淑媚", lang = "TW"),
                SongItem("繁華攏是夢", "陳美鳳 + 游鴻明", lang = "TW"),
                SongItem("愛情限時批", "伍佰 + 萬芳", lang = "TW"),
                SongItem("愛我你會死", "董事長樂團", lang = "TW"),
                SongItem("心所愛的人", "洪榮宏 + 龍千玉", lang = "TW"),
                SongItem("男人KTV", "胡彥斌", lang = "ZH"),
                SongItem("因為愛情", "陳奕迅 + 王菲", lang = "ZH"),
                SongItem("一眼瞬間", "張惠妹 + 蕭敬騰", lang = "ZH"),
                SongItem("漂向北方", "黃明志 + 王力宏", lang = "ZH"),
                SongItem("手掌心", "丁噹", lang = "ZH"),
                SongItem("我愛他", "丁噹", lang = "ZH"),
                SongItem("猜不透", "丁噹", lang = "ZH"),
                SongItem("戀愛ing", "五月天", lang = "ZH")
            )
        }
    }
}
