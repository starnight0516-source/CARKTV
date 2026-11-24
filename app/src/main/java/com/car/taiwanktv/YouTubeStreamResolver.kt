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
import java.util.concurrent.TimeUnit

data class ResolvedStream(
    val videoUrl: String?,
    val audioUrl: String?,
    val muxedUrl: String?,
    val hlsUrl: String?,
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
        val extractor = yt.getSearchExtractor("$query MV")
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
        val searchQuery = "$query 官方MV"
        val extractor = yt.getSearchExtractor(searchQuery)
        extractor.fetchPage()

        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { item -> item.duration in 90..720 }
            .take(5)
            .map { it.url }
    }

    suspend fun resolveStream(videoUrlOrId: String): ResolvedStream = withContext(Dispatchers.IO) {
        init()
        val fullUrl = if (videoUrlOrId.startsWith("http")) {
            videoUrlOrId
        } else {
            "https://www.youtube.com/watch?v=$videoUrlOrId"
        }

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, fullUrl)

        val bestVideo = streamInfo.videoOnlyStreams
            .filter { it.isUrl && it.format?.suffix == "mp4" }
            .sortedByDescending { it.height }
            .firstOrNull { it.height <= 1080 }
            ?: streamInfo.videoOnlyStreams.firstOrNull { it.isUrl }

        val bestAudio = streamInfo.audioStreams
            .filter { it.isUrl }
            .sortedByDescending { it.averageBitrate }
            .firstOrNull()

        val muxed = streamInfo.videoStreams
            .filter { it.isUrl }
            .sortedByDescending { it.height }
            .firstOrNull()

        val hls = streamInfo.hlsUrl?.takeIf { it.isNotBlank() }

        ResolvedStream(
            videoUrl = bestVideo?.content,
            audioUrl = bestAudio?.content,
            muxedUrl = muxed?.content,
            hlsUrl = hls,
            title = streamInfo.name ?: ""
        )
    }

    fun extractYouTubeId(url: String): String? {
        val regex = Regex("(?:v=|youtu\\.be/|embed/)([a-zA-Z0-9_-]{11})")
        return regex.find(url)?.groupValues?.getOrNull(1)
    }
}
