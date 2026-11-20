package com.example.chord.ui.player

import android.net.Uri

private val YOUTUBE_THUMBNAIL_REGEX = Regex(
    """^(https?://i\d*\.ytimg\.com/vi/[^/]+/)(?:default|mqdefault|hqdefault|sddefault|hq720|maxresdefault)\.(jpg|jpeg|webp)(\?.*)?$""",
    RegexOption.IGNORE_CASE
)

private val GOOGLE_SIZE_SUFFIX_REGEX = Regex(
    """=(?:w\d+-h\d+|s\d+)[^/?#]*""",
    RegexOption.IGNORE_CASE
)

/**
 * Requests a higher-resolution artwork variant when the URL supports it.
 * Unknown URL formats are returned unchanged.
 */
internal fun highResolutionArtworkUrl(url: String?): String? {
    val original = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    // 1. YouTube Video Thumbnails: Upgrade hqdefault (4:3 letterboxed) to hq720 (true 16:9 without 404 risks)
    YOUTUBE_THUMBNAIL_REGEX.matchEntire(original)?.let { match ->
        return "${match.groupValues[1]}hq720.${match.groupValues[2]}${match.groupValues[3]}"
    }

    // 2. YouTube Music / Google CDN artwork (=w120-h120-l90-rj or =s540)
    if (GOOGLE_SIZE_SUFFIX_REGEX.containsMatchIn(original)) {
        return GOOGLE_SIZE_SUFFIX_REGEX.replace(original, "=w1600-h1600-l100-rj")
    }

    // 3. Generic query-param sizing for Google content domains
    if (!original.contains("googleusercontent.com") && !original.contains("ggpht.com")) {
        return original
    }

    return runCatching {
        val uri = Uri.parse(original)
        val builder = uri.buildUpon().clearQuery()

        uri.queryParameterNames.forEach { key ->
            if (key.lowercase() !in setOf("w", "width", "h", "height", "maxwidth", "maxheight", "s", "size")) {
                uri.getQueryParameters(key).forEach { value ->
                    builder.appendQueryParameter(key, value)
                }
            }
        }

        builder
            .appendQueryParameter("w", "1600")
            .appendQueryParameter("h", "1600")
            .build()
            .toString()
    }.getOrDefault(original)
}

/**
 * Small-slot artwork (mini player, queue rows). Asks the CDN for a small image so it
 * loads fast. Big 1600px files are only for the full player.
 */
internal fun thumbArtworkUrl(url: String?, px: Int = 400): String? {
    val original = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (GOOGLE_SIZE_SUFFIX_REGEX.containsMatchIn(original)) {
        return GOOGLE_SIZE_SUFFIX_REGEX.replace(original, "=w$px-h$px-l90-rj")
    }
    return original
}

/**
 * Best quality first, original URL last. If a big size 404s, the next one is tried,
 * so artwork never goes blank.
 */
internal fun artworkUrlCandidates(url: String?): List<String> {
    val original = url?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    val best = highResolutionArtworkUrl(original) ?: original
    return listOf(best, original).distinct()
}
