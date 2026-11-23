package com.car.taiwanktv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class ResolvedStream(
    val videoUrl: String?,
    val audioUrl: String?,
    val muxedUrl: String?,
    val title: String
)

object YouTubeStreamResolver {
    private var initialized = false

    @Synchronized
    fun init() {
        if (!initialized) {
            NewPipe.init(OkHttpDownloader())
            initialized = true
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
            .filter { item ->
                item.duration in 90..720
            }
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
            .filter { it.format?.suffix == "mp4" }
            .sortedByDescending { it.height }
            .firstOrNull { it.height <= 1080 }
            ?: streamInfo.videoOnlyStreams.firstOrNull()

        val bestAudio = streamInfo.audioStreams
            .sortedByDescending { it.averageBitrate }
            .firstOrNull()

        val muxed = streamInfo.videoStreams
            .sortedByDescending { it.height }
            .firstOrNull()

        ResolvedStream(
            videoUrl = bestVideo?.content,
            audioUrl = bestAudio?.content,
            muxedUrl = muxed?.content,
            title = streamInfo.name ?: ""
        )
    }
}
