package com.example.chord.ui.lyrics

data class WordToken(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

data class EnhancedLyricLine(
    val lineStartMs: Long,
    val words: List<WordToken>,
    val rawText: String
)

object WordLyricsParser {

    private val lineTimeRegex =
        Regex("""\[(\d{2,}):(\d{2})[.:](\d{2,3})\]""")

    private val wordTimeRegex =
        Regex("""<(\d{2,}):(\d{2})[.:](\d{2,3})>([^<]*)""")

    private fun timestamp(
        min: String,
        sec: String,
        fraction: String
    ): Long {
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }

        return min.toLong() * 60_000L +
                sec.toLong() * 1_000L +
                fractionMs
    }

    fun parse(lrcContent: String): List<EnhancedLyricLine> {
        val result = mutableListOf<EnhancedLyricLine>()

        for (line in lrcContent.lines()) {
            val lineMatches = lineTimeRegex.findAll(line).toList()
            if (lineMatches.isEmpty()) continue

            val wordMatches = wordTimeRegex.findAll(line).toList()

            for (lineMatch in lineMatches) {
                val lineStart = timestamp(
                    lineMatch.groupValues[1],
                    lineMatch.groupValues[2],
                    lineMatch.groupValues[3]
                )

                val words = wordMatches.mapNotNull { match ->
                    val text = match.groupValues[4]
                    if (text.isBlank()) return@mapNotNull null

                    WordToken(
                        word = text,
                        startMs = timestamp(
                            match.groupValues[1],
                            match.groupValues[2],
                            match.groupValues[3]
                        ),
                        endMs = 0L
                    )
                }.sortedBy { it.startMs }

                if (words.isNotEmpty()) {
                    val timedWords = words.mapIndexed { index, word ->
                        val nextStart = words.getOrNull(index + 1)?.startMs
                        word.copy(
                            endMs = maxOf(
                                word.startMs + 1,
                                nextStart ?: word.startMs + 500
                            )
                        )
                    }

                    result.add(
                        EnhancedLyricLine(
                            lineStartMs = lineStart,
                            words = timedWords,
                            rawText = timedWords.joinToString("") { it.word }
                        )
                    )
                } else {
                    val text = line
                        .replace(lineTimeRegex, "")
                        .trim()

                    if (text.isNotBlank()) {
                        result.add(
                            EnhancedLyricLine(
                                lineStartMs = lineStart,
                                words = listOf(
                                    WordToken(text, lineStart, lineStart + 3000)
                                ),
                                rawText = text
                            )
                        )
                    }
                }
            }
        }

        return result.sortedBy { it.lineStartMs }
    }
}