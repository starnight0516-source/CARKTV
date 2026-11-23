package com.car.taiwanktv

data class SongItem(
    val title: String,
    val artist: String = "",
    var videoId: String? = null,
    var candidateUrls: List<String> = emptyList(),
    var currentCandidateIndex: Int = 0
) {
    fun displayName(): String {
        return if (artist.isNotBlank()) "$title - $artist" else title
    }
}
