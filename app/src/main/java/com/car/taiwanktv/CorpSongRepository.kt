package com.car.taiwanktv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

enum class SongCategory {
    HOT, TAIWANESE, MANDARIN, CLASSICS
}

object CorpSongRepository {

    private val cache = mutableMapOf<SongCategory, List<SongItem>>()

    suspend fun getSongs(category: SongCategory): List<SongItem> = withContext(Dispatchers.IO) {
        cache[category]?.let { return@withContext it }

        val scraped = when (category) {
            SongCategory.HOT -> scrapeCorpPage("https://song.corp.com.tw/")
            SongCategory.TAIWANESE -> scrapeCorpPage("https://song.corp.com.tw/songs.aspx?company=%E5%BC%98%E9%9F%B3")
            SongCategory.MANDARIN -> scrapeCorpPage("https://song.corp.com.tw/songs.aspx?company=%E9%9F%B3%E5%9C%93&lang=%E5%9C%8B")
            SongCategory.CLASSICS -> emptyList()
        }

        val fallback = getBuiltInSongs(category)
        val merged = LinkedHashMap<String, SongItem>()
        for (song in scraped) {
            merged[song.title] = song
        }
        for (song in fallback) {
            if (!merged.containsKey(song.title)) {
                merged[song.title] = song
            }
        }

        val result = merged.values.toList()
        cache[category] = result
        result
    }

    private fun scrapeCorpPage(url: String): List<SongItem> {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .timeout(10000)
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

                // 嘗試分離歌名與歌手（點歌王常以空白或換行分隔，或包在子元素中）
                val parts = rawText.split(" ").filter { it.isNotBlank() }
                val title = parts.firstOrNull() ?: rawText
                val artist = if (parts.size > 1) parts.drop(1).joinToString(" ") else "台灣點歌王精選"

                results.add(
                    SongItem(
                        title = title,
                        artist = artist,
                        corpMvUrl = href
                    )
                )
                if (results.size >= 40) break
            }
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getBuiltInSongs(category: SongCategory): List<SongItem> {
        return when (category) {
            SongCategory.HOT -> listOf(
                SongItem("轉念", "翁立友"),
                SongItem("落花年月", "謝宜君"),
                SongItem("愛到徹底", "吳勇濱"),
                SongItem("一見鍾情", "翁立友 + 林琇琪"),
                SongItem("若是無你", "謝舒騏 + 許峻榮"),
                SongItem("舞影", "謝金晶 + 吳俊宏"),
                SongItem("不愛就不愛", "艾薇"),
                SongItem("擱淺", "周杰倫"),
                SongItem("披星戴月的想你", "告五人"),
                SongItem("浪子回頭", "茄子蛋"),
                SongItem("愛情你比我想的閣較偉大", "茄子蛋"),
                SongItem("刻在我心底的名字", "盧廣仲"),
                SongItem("想見你想見你想見你", "八三夭"),
                SongItem("以後別做朋友", "周興哲"),
                SongItem("孤勇者", "陳奕迅")
            )
            SongCategory.TAIWANESE -> listOf(
                SongItem("家後", "江蕙"),
                SongItem("甲你攬牢牢", "江蕙"),
                SongItem("落雨聲", "江蕙"),
                SongItem("堅持", "翁立友"),
                SongItem("迷魂香", "翁立友"),
                SongItem("我問天", "翁立友"),
                SongItem("阿嬤的話", "蕭煌奇"),
                SongItem("心愛的再會啦", "伍佰"),
                SongItem("樹枝孤鳥", "伍佰"),
                SongItem("浪流連", "茄子蛋"),
                SongItem("魚仔", "盧廣仲"),
                SongItem("追追追", "黃妃"),
                SongItem("無人熟識", "張清芳"),
                SongItem("世界第一等", "劉德華"),
                SongItem("愛拚才會贏", "葉啟田"),
                SongItem("深情海岸", "詹雅雯"),
                SongItem("想厝的人", "詹雅雯"),
                SongItem("人生的歌", "黃乙玲"),
                SongItem("海波浪", "郭桂彬 + 黃乙玲"),
                SongItem("歡喜就好", "陳雷")
            )
            SongCategory.MANDARIN -> listOf(
                SongItem("晴天", "周杰倫"),
                SongItem("稻香", "周杰倫"),
                SongItem("告白氣球", "周杰倫"),
                SongItem("突然好想你", "五月天"),
                SongItem("倔強", "五月天"),
                SongItem("溫柔", "五月天"),
                SongItem("挪威的森林", "伍佰"),
                SongItem("浪人情歌", "伍佰"),
                SongItem("Last Dance", "伍佰"),
                SongItem("光年之外", "鄧紫棋"),
                SongItem("泡沫", "鄧紫棋"),
                SongItem("修煉愛情", "林俊傑"),
                SongItem("江南", "林俊傑"),
                SongItem("吻別", "張學友"),
                SongItem("一路上有你", "張學友"),
                SongItem("聽海", "張惠妹"),
                SongItem("原來你什麼都不要", "張惠妹"),
                SongItem("十年", "陳奕迅"),
                SongItem("K歌之王", "陳奕迅"),
                SongItem("紅豆", "王菲")
            )
            SongCategory.CLASSICS -> listOf(
                SongItem("蝴蝶夢", "蔡小虎 + 龍千玉"),
                SongItem("三生石", "李明洋 + 朱海君"),
                SongItem("香水", "江志豐 + 張秀卿"),
                SongItem("男人情女人心", "袁小迪 + 龍千玉"),
                SongItem("雲中月圓", "王識賢 + 孫淑媚"),
                SongItem("雪中紅", "王識賢 + 陳亮吟"),
                SongItem("夢中的情話", "江蕙 + 阿杜"),
                SongItem("傷心酒店", "江蕙 + 施文彬"),
                SongItem("屋頂", "吳宗憲 + 溫嵐"),
                SongItem("珊瑚海", "周杰倫 + 梁心頤"),
                SongItem("廣島之戀", "張洪量 + 莫文蔚"),
                SongItem("你最珍貴", "張學友 + 高慧君"),
                SongItem("選擇", "林子祥 + 葉蒨文"),
                SongItem("製造浪漫", "鄭中基 + 陳慧琳"),
                SongItem("今天妳要嫁給我", "陶喆 + 蔡依林"),
                SongItem("私奔到月球", "五月天 + 陳綺貞")
            )
        }
    }
}
