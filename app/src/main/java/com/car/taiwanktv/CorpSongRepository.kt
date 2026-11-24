package com.car.taiwanktv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

enum class SongCategory {
    HOT, TAIWANESE, MANDARIN, CLASSICS
}

object CorpSongRepository {

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

    suspend fun getSongs(category: SongCategory): List<SongItem> = withContext(Dispatchers.IO) {
        cache[category]?.let { return@withContext it }

        val urlsToScrape = when (category) {
            SongCategory.HOT -> listOf(
                "https://song.corp.com.tw/",
                "https://song.corp.com.tw/songs.aspx?company=%E9%8C%A2%E6%AB%83",
                "https://song.corp.com.tw/songs.aspx?company=%E5%A5%BD%E6%A8%82%E8%BF%AA"
            )
            SongCategory.TAIWANESE -> listOf(
                "https://song.corp.com.tw/songs.aspx?company=%E5%BC%98%E9%9F%B3",
                "https://song.corp.com.tw/songs.aspx?company=%E9%87%91%E5%97%93"
            )
            SongCategory.MANDARIN -> listOf(
                "https://song.corp.com.tw/songs.aspx?company=%E9%9F%B3%E5%9C%93&lang=%E5%9C%8B"
            )
            SongCategory.CLASSICS -> emptyList()
        }

        val merged = LinkedHashMap<String, SongItem>()
        for (url in urlsToScrape) {
            for (song in scrapeCorpPage(url)) {
                val key = "${song.title}_${song.artist}"
                if (!merged.containsKey(key)) merged[key] = song
            }
        }
        for (song in getBuiltInSongs(category)) {
            val key = "${song.title}_${song.artist}"
            if (!merged.containsKey(key)) merged[key] = song
        }

        val result = merged.values.toList()
        cache[category] = result
        result
    }

    /**
     * 取得目前所有分類整合去重後的完整大歌庫（供字數分類、歌星本地快篩與自動連播使用）
     */
    fun getAllBuiltInAndCachedSongs(): List<SongItem> {
        val merged = LinkedHashMap<String, SongItem>()
        for (cat in SongCategory.values()) {
            val list = cache[cat] ?: getBuiltInSongs(cat)
            for (song in list) {
                val key = "${song.title}_${song.artist}"
                if (!merged.containsKey(key)) {
                    merged[key] = song
                }
            }
        }
        return merged.values.toList()
    }

    /**
     * 依歌星篩選本地歌庫
     */
    fun filterBySingerLocal(singerName: String): List<SongItem> {
        return getAllBuiltInAndCachedSongs().filter {
            it.artist.contains(singerName, ignoreCase = true)
        }
    }

    /**
     * 依歌名字數篩選（0 代表全部，6 代表 6 字以上）
     */
    fun filterByWordCount(wordCount: Int): List<SongItem> {
        val all = getAllBuiltInAndCachedSongs()
        if (wordCount <= 0) return all
        return all.filter { song ->
            val len = countTitleWords(song.title)
            if (wordCount >= 6) len >= 6 else len == wordCount
        }
    }

    /**
     * 計算歌名字數（排除括號附註、空白與標點，符合台灣 KTV 點歌機字數規則）
     */
    fun countTitleWords(title: String): Int {
        val cleaned = title
            .replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
            .replace(Regex("[\\s\\p{Punct}，。！？、～—・]+"), "")
            .trim()
        return cleaned.length
    }

    private fun scrapeCorpPage(url: String): List<SongItem> {
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
                        corpMvUrl = href
                    )
                )
                if (results.size >= 50) break
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
                SongItem("披星戴月的想你", "告五人"),
                SongItem("在這座城市遺失了你", "告五人"),
                SongItem("愛人錯過", "告五人"),
                SongItem("好不容易", "告五人"),
                SongItem("又到天黑", "告五人"),
                SongItem("浪子回頭", "茄子蛋"),
                SongItem("浪流連", "茄子蛋"),
                SongItem("愛情你比我想的閣較偉大", "茄子蛋"),
                SongItem("閣愛妳一擺", "茄子蛋"),
                SongItem("日常", "茄子蛋"),
                SongItem("刻在我心底的名字", "盧廣仲"),
                SongItem("魚仔", "盧廣仲"),
                SongItem("幾分之幾", "盧廣仲"),
                SongItem("想見你想見你想見你", "八三夭"),
                SongItem("東區東區", "八三夭"),
                SongItem("致青春", "八三夭"),
                SongItem("以後別做朋友", "周興哲"),
                SongItem("你，好不好？", "周興哲"),
                SongItem("怎麼了", "周興哲"),
                SongItem("如果雨之後", "周興哲"),
                SongItem("永不失聯的愛", "周興哲"),
                SongItem("孤勇者", "陳奕迅"),
                SongItem("我很好騙", "動力火車"),
                SongItem("都是因為愛", "動力火車"),
                SongItem("嘉賓", "張遠"),
                SongItem("烏梅子醬", "李榮浩"),
                SongItem("年少有為", "李榮浩"),
                SongItem("李白", "李榮浩"),
                SongItem("模特", "李榮浩"),
                SongItem("體面", "于文文"),
                SongItem("說散就散", "JC"),
                SongItem("小幸運", "田馥甄"),
                SongItem("你就不要想起我", "田馥甄"),
                SongItem("魔鬼中的天使", "田馥甄"),
                SongItem("寂寞寂寞就好", "田馥甄"),
                SongItem("無人知曉", "田馥甄"),
                SongItem("兜圈", "林宥嘉"),
                SongItem("說謊", "林宥嘉"),
                SongItem("浪費", "林宥嘉"),
                SongItem("成全", "林宥嘉"),
                SongItem("天真有邪", "林宥嘉"),
                SongItem("如果可以", "韋禮安"),
                SongItem("慢慢等", "韋禮安"),
                SongItem("還是會", "韋禮安"),
                SongItem("女孩", "韋禮安"),
                SongItem("摯友", "A-Lin"),
                SongItem("有一種悲傷", "A-Lin"),
                SongItem("失戀無罪", "A-Lin"),
                SongItem("給我一個理由忘記", "A-Lin"),
                SongItem("我們不一樣", "大壯"),
                SongItem("起風了", "買辣椒也用券"),
                SongItem("熱愛105°C的你", "阿肆"),
                SongItem("黑桃A", "DJ小魚兒")
            )
            SongCategory.TAIWANESE -> listOf(
                SongItem("家後", "江蕙"),
                SongItem("甲你攬牢牢", "江蕙"),
                SongItem("落雨聲", "江蕙"),
                SongItem("酒後的心聲", "江蕙"),
                SongItem("惜別的海岸", "江蕙"),
                SongItem("感情放一邊", "江蕙"),
                SongItem("藝界人生", "江蕙"),
                SongItem("半醉半清醒", "江蕙"),
                SongItem("無言花", "江蕙"),
                SongItem("博杯", "江蕙"),
                SongItem("堅持", "翁立友"),
                SongItem("迷魂香", "翁立友"),
                SongItem("我問天", "翁立友"),
                SongItem("送行", "翁立友"),
                SongItem("行棋", "翁立友"),
                SongItem("手中情", "翁立友"),
                SongItem("不能講的秘密", "翁立友"),
                SongItem("夜市人生", "翁立友"),
                SongItem("阿嬤的話", "蕭煌奇"),
                SongItem("你是我的眼", "蕭煌奇"),
                SongItem("末班車", "蕭煌奇"),
                SongItem("思念會驚", "蕭煌奇"),
                SongItem("上水的花", "蕭煌奇"),
                SongItem("心愛的再會啦", "伍佰"),
                SongItem("樹枝孤鳥", "伍佰"),
                SongItem("煞到妳", "伍佰"),
                SongItem("空襲警報", "伍佰"),
                SongItem("飛在風中的小雨", "伍佰"),
                SongItem("世界第一等", "伍佰"),
                SongItem("墓仔埔也敢去", "伍佰"),
                SongItem("追追追", "黃妃"),
                SongItem("非常女", "黃妃"),
                SongItem("風塊哭", "黃妃"),
                SongItem("無人熟識", "張清芳"),
                SongItem("愛拚才會贏", "葉啟田"),
                SongItem("故鄉", "葉啟田"),
                SongItem("浪子的心情", "葉啟田"),
                SongItem("忍", "葉啟田"),
                SongItem("深情海岸", "詹雅雯"),
                SongItem("想厝的人", "詹雅雯"),
                SongItem("今年一定會好過", "詹雅雯"),
                SongItem("人生公路", "詹雅雯"),
                SongItem("愛情的騙子我問你", "陳小雲"),
                SongItem("舞女", "陳小雲"),
                SongItem("人生的歌", "黃乙玲"),
                SongItem("無字的情批", "黃乙玲"),
                SongItem("愛到才知痛", "黃乙玲"),
                SongItem("海波浪", "郭桂彬 + 黃乙玲"),
                SongItem("歡喜就好", "陳雷"),
                SongItem("風真透", "陳雷"),
                SongItem("往事就是我的安慰", "陳雷"),
                SongItem("心愛的甭哭", "陳雷"),
                SongItem("車站", "張秀卿"),
                SongItem("辣妹駕到", "張秀卿"),
                SongItem("練舞功", "謝金燕"),
                SongItem("姐姐", "謝金燕"),
                SongItem("嗶嗶嗶", "謝金燕"),
                SongItem("含淚跳恰恰", "謝金燕"),
                SongItem("再會啦心愛的無緣的人", "施文彬"),
                SongItem("天涯", "王識賢"),
                SongItem("一片天", "王識賢"),
                SongItem("勝者為王", "王識賢"),
                SongItem("腳踏車", "王識賢"),
                SongItem("反背", "王識賢"),
                SongItem("情難忘", "王識賢"),
                SongItem("春夏秋冬", "蔡小虎"),
                SongItem("一生只有你", "蔡小虎"),
                SongItem("愛人醉落去", "蔡小虎"),
                SongItem("問感情", "蔡小虎"),
                SongItem("心借過", "蔡小虎"),
                SongItem("阿霞相思夢", "蔡小虎"),
                SongItem("重出江湖", "袁小迪"),
                SongItem("人生啊人生", "袁小迪"),
                SongItem("紙雲煙", "袁小迪"),
                SongItem("雙人枕頭", "王識賢"),
                SongItem("望春風", "鄧雨賢"),
                SongItem("雨夜花", "江蕙"),
                SongItem("思慕的人", "洪一峰"),
                SongItem("舊情綿綿", "洪一峰"),
                SongItem("港都夜雨", "吳晉淮"),
                SongItem("一支小雨傘", "洪榮宏"),
                SongItem("相思雨", "洪榮宏"),
                SongItem("阿宏的心聲", "洪榮宏"),
                SongItem("乾一杯", "葉啟田"),
                SongItem("金包銀", "蔡秋鳳"),
                SongItem("雨水我問你", "蔡秋鳳"),
                SongItem("醉英雄", "蔡秋鳳"),
                SongItem("空笑夢", "王中平"),
                SongItem("向天要一點愛", "王中平"),
                SongItem("茫茫到深更", "羅時豐"),
                SongItem("歡喜來恰恰", "羅時豐")
            )
            SongCategory.MANDARIN -> listOf(
                SongItem("晴天", "周杰倫"),
                SongItem("稻香", "周杰倫"),
                SongItem("告白氣球", "周杰倫"),
                SongItem("七里香", "周杰倫"),
                SongItem("青花瓷", "周杰倫"),
                SongItem("夜曲", "周杰倫"),
                SongItem("擱淺", "周杰倫"),
                SongItem("安靜", "周杰倫"),
                SongItem("簡單愛", "周杰倫"),
                SongItem("雙截棍", "周杰倫"),
                SongItem("聽媽媽的話", "周杰倫"),
                SongItem("說好不哭", "周杰倫"),
                SongItem("不能說的秘密", "周杰倫"),
                SongItem("一路向北", "周杰倫"),
                SongItem("楓", "周杰倫"),
                SongItem("突然好想你", "五月天"),
                SongItem("倔強", "五月天"),
                SongItem("溫柔", "五月天"),
                SongItem("知足", "五月天"),
                SongItem("憨人", "五月天"),
                SongItem("志明與春嬌", "五月天"),
                SongItem("離開地球表面", "五月天"),
                SongItem("乾杯", "五月天"),
                SongItem("後來的我們", "五月天"),
                SongItem("我不願讓你一個人", "五月天"),
                SongItem("挪威的森林", "伍佰"),
                SongItem("浪人情歌", "伍佰"),
                SongItem("Last Dance", "伍佰"),
                SongItem("愛你一萬年", "伍佰"),
                SongItem("夢醒時分", "伍佰"),
                SongItem("痛哭的人", "伍佰"),
                SongItem("被動", "伍佰"),
                SongItem("淚橋", "伍佰"),
                SongItem("光年之外", "鄧紫棋"),
                SongItem("泡沫", "鄧紫棋"),
                SongItem("倒數", "鄧紫棋"),
                SongItem("喜歡你", "鄧紫棋"),
                SongItem("句號", "鄧紫棋"),
                SongItem("修煉愛情", "林俊傑"),
                SongItem("江南", "林俊傑"),
                SongItem("曹操", "林俊傑"),
                SongItem("不為誰而作的歌", "林俊傑"),
                SongItem("可惜沒如果", "林俊傑"),
                SongItem("她說", "林俊傑"),
                SongItem("豆漿油條", "林俊傑"),
                SongItem("吻別", "張學友"),
                SongItem("一路上有你", "張學友"),
                SongItem("祝福", "張學友"),
                SongItem("一千個傷心的理由", "張學友"),
                SongItem("情網", "張學友"),
                SongItem("心如刀割", "張學友"),
                SongItem("餓狼傳說", "張學友"),
                SongItem("她來聽我的演唱會", "張學友"),
                SongItem("聽海", "張惠妹"),
                SongItem("原來你什麼都不要", "張惠妹"),
                SongItem("姊妹", "張惠妹"),
                SongItem("三天三夜", "張惠妹"),
                SongItem("記得", "張惠妹"),
                SongItem("剪愛", "張惠妹"),
                SongItem("我最親愛的", "張惠妹"),
                SongItem("連名帶姓", "張惠妹"),
                SongItem("十年", "陳奕迅"),
                SongItem("K歌之王", "陳奕迅"),
                SongItem("浮誇", "陳奕迅"),
                SongItem("富士山下", "陳奕迅"),
                SongItem("愛情轉移", "陳奕迅"),
                SongItem("好久不見", "陳奕迅"),
                SongItem("紅豆", "王菲"),
                SongItem("我願意", "王菲"),
                SongItem("傳奇", "王菲"),
                SongItem("勇氣", "梁靜茹"),
                SongItem("分手快樂", "梁靜茹"),
                SongItem("可惜不是你", "梁靜茹"),
                SongItem("會呼吸的痛", "梁靜茹"),
                SongItem("寧夏", "梁靜茹"),
                SongItem("天黑黑", "孫燕姿"),
                SongItem("遇見", "孫燕姿"),
                SongItem("我懷念的", "孫燕姿"),
                SongItem("開始懂了", "孫燕姿"),
                SongItem("綠光", "孫燕姿"),
                SongItem("山丘", "李宗盛"),
                SongItem("凡人歌", "李宗盛"),
                SongItem("鬼迷心竅", "李宗盛"),
                SongItem("用心良苦", "張宇"),
                SongItem("月亮惹的禍", "張宇"),
                SongItem("雨一直下", "張宇"),
                SongItem("趁早", "張宇"),
                SongItem("心太軟", "任賢齊"),
                SongItem("對面的女孩看過來", "任賢齊"),
                SongItem("傷心太平洋", "任賢齊"),
                SongItem("忘情水", "劉德華"),
                SongItem("冰雨", "劉德華"),
                SongItem("練習", "劉德華"),
                SongItem("世界第一等", "劉德華"),
                SongItem("忠孝東路走九遍", "動力火車"),
                SongItem("當", "動力火車"),
                SongItem("無情的情書", "動力火車"),
                SongItem("彩虹", "動力火車"),
                SongItem("倒帶", "蔡依林"),
                SongItem("說愛你", "蔡依林"),
                SongItem("日不落", "蔡依林"),
                SongItem("舞孃", "蔡依林"),
                SongItem("愛很簡單", "陶喆"),
                SongItem("小鎮姑娘", "陶喆"),
                SongItem("普通朋友", "陶喆"),
                SongItem("唯一", "王力宏"),
                SongItem("大城小愛", "王力宏"),
                SongItem("你不知道的事", "王力宏"),
                SongItem("情非得已", "庾澄慶"),
                SongItem("春泥", "庾澄慶"),
                SongItem("海闊天空", "Beyond"),
                SongItem("光輝歲月", "Beyond"),
                SongItem("真的愛你", "Beyond"),
                SongItem("朋友", "周華健"),
                SongItem("花心", "周華健"),
                SongItem("讓我歡喜讓我憂", "周華健"),
                SongItem("愛如潮水", "張信哲"),
                SongItem("過火", "張信哲"),
                SongItem("太想愛你", "張信哲"),
                SongItem("大約在冬季", "齊秦"),
                SongItem("夜夜夜夜", "齊秦"),
                SongItem("童話", "光良"),
                SongItem("情歌王", "古巨基"),
                SongItem("挪威的森林", "伍佰"),
                SongItem("領悟", "辛曉琪"),
                SongItem("味道", "辛曉琪"),
                SongItem("新不了情", "萬芳"),
                SongItem("愛江山更愛美人", "李麗芬"),
                SongItem("魯冰花", "曾淑勤"),
                SongItem("外婆的澎湖灣", "潘安邦"),
                SongItem("甜蜜蜜", "鄧麗君"),
                SongItem("月亮代表我的心", "鄧麗君"),
                SongItem("我只在乎你", "鄧麗君"),
                SongItem("小城故事", "鄧麗君"),
                SongItem("恰似你的溫柔", "蔡琴"),
                SongItem("被遺忘的時光", "蔡琴"),
                SongItem("讀你", "蔡琴"),
                SongItem("掌聲響起", "鳳飛飛"),
                SongItem("流水年華", "鳳飛飛"),
                SongItem("追夢人", "鳳飛飛")
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
                SongItem("酒後的心聲", "江蕙"),
                SongItem("屋頂", "吳宗憲 + 溫嵐"),
                SongItem("珊瑚海", "周杰倫 + 梁心頤"),
                SongItem("千里之外", "周杰倫 + 費玉清"),
                SongItem("廣島之戀", "張洪量 + 莫文蔚"),
                SongItem("你最珍貴", "張學友 + 高慧君"),
                SongItem("選擇", "林子祥 + 葉蒨文"),
                SongItem("製造浪漫", "鄭中基 + 陳慧琳"),
                SongItem("今天妳要嫁給我", "陶喆 + 蔡依林"),
                SongItem("私奔到月球", "五月天 + 陳綺貞"),
                SongItem("小酒窩", "林俊傑 + 蔡卓妍"),
                SongItem("梁山伯與茱麗葉", "曹格 + 卓文萱"),
                SongItem("戀愛達人", "羅志祥 + 小S"),
                SongItem("不得不愛", "潘瑋柏 + 弦子"),
                SongItem("快樂崇拜", "潘瑋柏 + 張韶涵"),
                SongItem("有一點動心", "張信哲 + 劉嘉玲"),
                SongItem("明明白白我的心", "成龍 + 陳淑樺"),
                SongItem("當愛已成往事", "林憶蓮 + 李宗盛"),
                SongItem("你是我胸口永遠的痛", "王傑 + 葉歡"),
                SongItem("鐵血丹心", "羅文 + 甄妮"),
                SongItem("無言的結局", "林淑容 + 羅時豐"),
                SongItem("小姐請你給我愛", "羅時豐 + 王瑞霞"),
                SongItem("雙人枕頭", "王識賢 + 孫淑媚"),
                SongItem("繁華攏是夢", "陳美鳳 + 游鴻明"),
                SongItem("愛情限時批", "伍佰 + 萬芳"),
                SongItem("愛我你會死", "董事長樂團"),
                SongItem("心所愛的人", "洪榮宏 + 龍千玉"),
                SongItem("男人KTV", "胡彥斌"),
                SongItem("因為愛情", "陳奕迅 + 王菲"),
                SongItem("一眼瞬間", "張惠妹 + 蕭敬騰"),
                SongItem("漂向北方", "黃明志 + 王力宏"),
                SongItem("手掌心", "丁噹"),
                SongItem("我愛他", "丁噹"),
                SongItem("猜不透", "丁噹"),
                SongItem("以後別做朋友", "周興哲"),
                SongItem("怎麼了", "周興哲"),
                SongItem("戀愛ing", "五月天")
            )
        }
    }
}
