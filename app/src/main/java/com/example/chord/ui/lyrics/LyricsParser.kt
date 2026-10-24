package com.example.chord.ui.lyrics

data class LyricLine(
    val startTimeMs: Long,
    val text: String
)

object LyricsParser {
    // Matches LRC format: [mm:ss.xx] or [mm:ss.xxx]
    private val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")

    fun parse(lrc: String): List<LyricLine> {
        return lrc.lines().mapNotNull { line ->
            val match = regex.matchEntire(line.trim())
            if (match != null) {
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val millisecondsText = match.groupValues[3]

                // Handle 2-digit (centiseconds) vs 3-digit (milliseconds)
                val milliseconds = if (millisecondsText.length == 2) {
                    millisecondsText.toLong() * 10
                } else {
                    millisecondsText.toLong()
                }

                val timeMs = (minutes * 60 * 1000) + (seconds * 1000) + milliseconds
                val text = match.groupValues[4].trim()

                LyricLine(timeMs, text)
            } else {
                null
            }
        }.sortedBy { it.startTimeMs }
    }
}