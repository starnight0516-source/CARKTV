package com.car.taiwanktv

enum class AutoPlayMode(val label: String) {
    MIXED("公播:混合"),
    MANDARIN("公播:純國語"),
    TAIWANESE("公播:純台語"),
    KKBOX_ALL("公播:KK綜合"),
    KKBOX_ZH("公播:KK華語"),
    KKBOX_TW("公播:KK台語")
}

data class SongItem(
    val title: String,
    val artist: String = "",
    var videoId: String? = null,
    val corpMvUrl: String? = null,
    val lang: String = "MIX", // "ZH" = 國語, "TW" = 台語, "MIX" = 混合/未指定
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
