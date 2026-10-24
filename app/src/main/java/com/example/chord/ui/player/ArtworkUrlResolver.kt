package com.example.chord.ui.player

import android.net.Uri

/**
 * Requests a larger artwork variant when the source URL supports it.
 *
 * YouTube Music commonly returns googleusercontent URLs with a size suffix
 * such as `=w120-h120-l90-rj`. Those dimensions are replaced with a larger
 * square. Standard YouTube video thumbnails are upgraded to maxresdefault.
 * Unknown URL formats are returned unchanged rather than being corrupted.
 */
fun highResolutionArtworkUrl(url: String?): String? {
    val original = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    val youtubeVideoThumbnail = Regex(
        """^(https?://i\d*\.ytimg\.com/vi/[^/]+/)(?:default|mqdefault|hqdefault|sddefault|maxresdefault)\.(jpg|jpeg|webp)(.*)$""",
        RegexOption.IGNORE_CASE
    )
    youtubeVideoThumbnail.matchEntire(original)?.let { match ->
        return "${match.groupValues[1]}maxresdefault.${match.groupValues[2]}${match.groupValues[3]}"
    }

    // Google/YouTube Music artwork often carries its dimensions in the path,
    // after '=' rather than in query parameters.
    val sizeSuffix = Regex("""=w\d+-h\d+[^/?#]*""", RegexOption.IGNORE_CASE)
    if (sizeSuffix.containsMatchIn(original)) {
        return sizeSuffix.replace(original, "=w1200-h1200-l90-rj")
    }

    // Some endpoints use query parameters instead.
    return runCatching {
        val uri = Uri.parse(original)
        val query = uri.buildUpon().clearQuery()
        val params = uri.queryParameterNames
        params.forEach { key ->
            when (key.lowercase()) {
                "w", "width", "h", "height", "maxwidth", "maxheight" -> Unit
                else -> uri.getQueryParameters(key).forEach { value -> query.appendQueryParameter(key, value) }
            }
        }
        query
            .appendQueryParameter("w", "1200")
            .appendQueryParameter("h", "1200")
            .build()
            .toString()
    }.getOrDefault(original)
}
