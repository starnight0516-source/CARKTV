package com.car.taiwanktv

data class SongItem(
    val title: String,
    val artist: String = "",
    var videoId: String? = null,
    val corpMvUrl: String? = null,
    var candidateUrls: List<String> = emptyList(),
    var currentCandidateIndex: Int = 0
) {
    fun displayName(): String {
        return if (artist.isNotBlank()) "$title - $artist" else title
    }
}

data class SingerInfo(
    val name: String,
    val tag: String
)
