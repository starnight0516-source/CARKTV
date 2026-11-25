package com.car.taiwanktv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit

data class ResolvedStream(
    val videoUrl: String?,
    val audioUrl: String?,
    val hlsUrl: String?,
    val dashUrl: String?,
    val muxedUrl: String?,
    val qualityLabel: String,
    val title: String
)

object YouTubeStreamResolver {
    private var initialized = false

    private val pageClient = OkHttpClient.Builder()
        .readTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Synchronized
    fun init() {
        if (!initialized) {
            NewPipe.init(
                OkHttpDownloader(),
                Localization("zh", "TW"),
                ContentCountry("TW")
            )
            initialized = true
        }
    }

    suspend fun extractVideoIdFromCorpPage(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(pageUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .build()
            pageClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string() ?: return@withContext null
                val ytPatterns = listOf(
                    Regex("youtube(?:-nocookie)?\\.com/embed/([a-zA-Z0-9_-]{11})"),
                    Regex("youtube\\.com/watch\\?v=([a-zA-Z0-9_-]{11})"),
                    Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
                    Regex("videoId['\"\\s:=]+['\"]([a-zA-Z0-9_-]{11})['\"]")
                )
                for (pattern in ytPatterns) {
                    val m = pattern.find(html)
                    if (m != null) return@withContext m.groupValues[1]
                }
            }
        } catch (_: Exception) {
        }
        null
    }

    suspend fun searchSongItems(query: String, limit: Int = 30): List<SongItem> = withContext(Dispatchers.IO) {
        init()
        val yt = ServiceList.YouTube
        val extractor = yt.getSearchExtractor("$query 官方MV HD")
        extractor.fetchPage()

        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { item -> item.duration in 90..720 }
            .take(limit)
            .map { item ->
                val vid = extractYouTubeId(item.url)
                SongItem(
                    title = item.name ?: query,
                    artist = item.uploaderName ?: "YouTube MV",
                    videoId = vid
                )
            }
    }

    suspend fun searchCandidates(query: String): List<String> = withContext(Dispatchers.IO) {
        init()
        val yt = ServiceList.YouTube
        val searchQuery = "$query 官方MV 1080p"
        val extractor = yt.getSearchExtractor(searchQuery)
        extractor.fetchPage()

        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { item -> item.duration in 90..720 }
            .take(5)
            .map { it.url }
    }

    private fun getEffectiveHeight(stream: VideoStream): Int {
        if (stream.height > 0) return stream.height
        val res = stream.resolution ?: return 0
        val match = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE).find(res)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    }

    /**
     * 高畫質多層解析策略（v9）：
     * 1. 透過 resolution 字串 + height 雙重判斷，避免 height==0 導致 1080p/720p 分離流被誤濾而掉回 360p。
     * 2. 同時保留 videoOnly+audio、HLS、DASH、muxed 四種來源，供播放器自動降級備援。
     */
    suspend fun resolveStream(videoUrlOrId: String): ResolvedStream = withContext(Dispatchers.IO) {
        init()
        val fullUrl = if (videoUrlOrId.startsWith("http")) {
            videoUrlOrId
        } else {
            "https://www.youtube.com/watch?v=$videoUrlOrId"
        }

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, fullUrl)

        val validVideoOnly = streamInfo.videoOnlyStreams
            .filter { it.isUrl && getEffectiveHeight(it) in 480..1440 }

        // 優先找 1080p MP4 -> 1080p 任意格式 -> 最高畫質 MP4 (720p/1440p) -> 最高畫質任意格式
        val bestVideo = validVideoOnly
            .filter { getEffectiveHeight(it) == 1080 && it.format?.suffix == "mp4" }
            .maxByOrNull { it.fps }
            ?: validVideoOnly
                .filter { getEffectiveHeight(it) == 1080 }
                .maxByOrNull { it.fps }
            ?: validVideoOnly
                .filter { it.format?.suffix == "mp4" }
                .maxByOrNull { getEffectiveHeight(it) }
            ?: validVideoOnly
                .maxByOrNull { getEffectiveHeight(it) }

        val validAudio = streamInfo.audioStreams.filter { it.isUrl }
        val bestAudio = validAudio
            .filter { it.format?.suffix == "m4a" || it.format?.mimeType?.contains("mp4") == true }
            .maxByOrNull { it.averageBitrate }
            ?: validAudio.maxByOrNull { it.averageBitrate }

        val hls = streamInfo.hlsUrl?.takeIf { it.isNotBlank() }
        val dash = streamInfo.dashMpdUrl?.takeIf { it.isNotBlank() }

        val muxed = streamInfo.videoStreams
            .filter { it.isUrl }
            .maxByOrNull { getEffectiveHeight(it) }

        val bestHeight = bestVideo?.let { getEffectiveHeight(it) } ?: 0
        val label = when {
            hls != null -> "HLS HD"
            bestVideo != null && bestAudio != null -> "${bestHeight}P HD"
            dash != null -> "DASH HD"
            muxed != null -> "${getEffectiveHeight(muxed)}P"
            else -> "HD"
        }

        ResolvedStream(
            videoUrl = bestVideo?.content,
            audioUrl = bestAudio?.content,
            hlsUrl = hls,
            dashUrl = dash,
            muxedUrl = muxed?.content,
            qualityLabel = label,
            title = streamInfo.name ?: ""
        )
    }

    fun extractYouTubeId(url: String): String? {
        val regex = Regex("(?:v=|youtu\\.be/|embed/)([a-zA-Z0-9_-]{11})")
        return regex.find(url)?.groupValues?.getOrNull(1)
    }
}
