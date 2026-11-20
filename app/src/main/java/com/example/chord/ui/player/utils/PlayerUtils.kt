package com.example.chord.ui.player.utils

import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatPlayerTime(timeMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(timeMs).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", minutes / 60, minutes % 60, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

fun highResolutionArtworkUrl(url: String?): String? {
    if (url == null) return null
    return url.replace(Regex("=w\\d+-h\\d+.*"), "=w1200-h1200-l90-rj").replace("hqdefault.jpg", "maxresdefault.jpg")
}

fun activeLyricAt(raw: String?, positionMs: Long): String? {
    if (raw.isNullOrBlank()) return null

    val wordTimestamp = Regex("""<(\d+):(\d+(?:\.\d+)?)>""")
    val lineTimestamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)\]""")

    data class TimedWord(val timeMs: Long, val text: String)
    data class LyricLine(val startMs: Long, val words: List<TimedWord>, val plainText: String)

    fun timestampMs(match: MatchResult): Long =
        (match.groupValues[1].toLongOrNull() ?: 0L) * 60_000L + ((match.groupValues[2].toDoubleOrNull() ?: 0.0) * 1000.0).toLong()

    val lines = raw.lineSequence().mapNotNull { sourceLine ->
        val lineMatch = lineTimestamp.find(sourceLine)
        val lineStart = lineMatch?.let(::timestampMs) ?: 0L
        val content = if (lineMatch != null) sourceLine.substring(lineMatch.range.last + 1) else sourceLine
        val matches = wordTimestamp.findAll(content).toList()

        if (matches.isNotEmpty()) {
            val timedWords = matches.mapIndexedNotNull { index, match ->
                val end = matches.getOrNull(index + 1)?.range?.first ?: content.length
                val word = content.substring(match.range.last + 1, end).trim()
                if (word.isBlank()) null else TimedWord(timestampMs(match), word)
            }.sortedBy { it.timeMs }
            if (timedWords.isEmpty()) null else LyricLine(if (lineMatch != null) lineStart else timedWords.first().timeMs, timedWords, timedWords.joinToString(" ") { it.text })
        } else {
            val clean = content.replace(Regex("""\[\d+:\d+(?:\.\d+)?]"""), "").replace(Regex("""<\d+:\d+(?:\.\d+)?>"""), "").trim()
            if (clean.isBlank()) null else LyricLine(lineStart, emptyList(), clean)
        }
    }.sortedBy { it.startMs }.toList()

    if (lines.isEmpty()) return null
    val currentLine = lines.lastOrNull { it.startMs <= positionMs } ?: return lines.first().words.firstOrNull()?.text ?: lines.first().plainText
    if (currentLine.words.isEmpty()) return currentLine.plainText
    val reachedWords = currentLine.words.filter { it.timeMs <= positionMs }
    return if (reachedWords.isNotEmpty()) reachedWords.joinToString(" ") { it.text } else currentLine.words.first().text
}