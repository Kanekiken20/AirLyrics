package com.andsi.airlyrics.lyrics.providers

/**
 * Strips common streaming platform title/artist suffixes (e.g. "(feat. ...)", "[Remastered 2024]",
 * "- Single Version", "(Lossless)", "[Hi-Res]") so local fast caches and LRCLIB exact lookups hit
 * cleanly without slow sequential fallback searches.
 */
internal object LyricsMetadataNormalizer {
    private val PAREN_NOISE_REGEX = Regex(
        """\s*[\(\[]\s*(?:feat\.?|ft\.?|featuring|with|remaster(?:ed)?(?:\s+\d{4})?|live(?:\s+[^)\]]*)?|single\s+version|album\s+version|radio\s+edit|explicit|clean|deluxe(?:\s+edition)?|bonus\s+track|official\s+(?:audio|video|music\s+video|lyric\s+video)|audio|lossless|hi-?res|dolby\s+atmos|spatial\s+audio|mono|stereo)\b[^\)\]]*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val DASH_NOISE_REGEX = Regex(
        """\s+[-–—]\s+(?:remaster(?:ed)?(?:\s+\d{4})?|single\s+version|album\s+version|radio\s+edit|live(?:\s+.*)?|deluxe(?:\s+edition)?|bonus\s+track|explicit|clean|instrumental|acoustic|mono|stereo)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_SPLIT_REGEX = Regex("""\s*(?:,|&|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|×|x)\s*""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    fun cleanTitle(raw: String): String {
        var current = raw.trim()
        if (current.isEmpty()) return ""
        repeat(3) {
            val next = PAREN_NOISE_REGEX.replace(current, "")
            val strippedDash = DASH_NOISE_REGEX.replace(next, "")
            if (strippedDash == current) return@repeat
            current = strippedDash
        }
        return MULTI_SPACE_REGEX.replace(current, " ").trim()
    }

    fun cleanArtist(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val firstArtist = ARTIST_SPLIT_REGEX.split(trimmed, limit = 2).firstOrNull()?.trim().orEmpty()
        val candidate = if (firstArtist.isNotEmpty()) firstArtist else trimmed
        return MULTI_SPACE_REGEX.replace(candidate, " ").trim()
    }

    fun cacheKey(title: String, artist: String, durationMs: Long): String {
        val normTitle = cleanTitle(title).lowercase().ifEmpty { title.trim().lowercase() }
        val normArtist = cleanArtist(artist).lowercase().ifEmpty { artist.trim().lowercase() }
        val durationBucket = if (durationMs > 0L) ((durationMs + 2_000L) / 5_000L) else 0L
        return "$normTitle|$normArtist|$durationBucket"
    }
}
