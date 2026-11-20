package com.example.chord.ui.lyrics

data class WordToken(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    /** False for syllables that belong to the same word ("Fi" + "toor"), so they render joined. */
    val trailingSpace: Boolean = true,
)

data class EnhancedLyricLine(
    val lineStartMs: Long,
    val words: List<WordToken>,
    val rawText: String,
    val lineEndMs: Long = words.lastOrNull()?.endMs ?: lineStartMs,
    /** True when the file carried real per-word timestamps, false when timing was synthesized. */
    val isWordSynced: Boolean = false,
)

/**
 * Parses both lyric flavours:
 *  - Enhanced LRC:  [00:12.34]<00:12.34>Hello <00:12.80>world <00:13.40>
 *  - Standard LRC:  [00:12.34]Hello world   (word timing is synthesized across the line)
 *
 * Fixes over the previous version:
 *  - trailing "end" timestamps (<mm:ss.xx> with no text) now close the last word instead of being dropped
 *  - syllables without a space between them stay joined
 *  - several words inside one tag are split and timed proportionally
 *  - the last word / last line end at the next line's start instead of a fixed guess
 *  - standard LRC gets a smooth word sweep instead of the whole line flipping at once
 *  - blank "[00:30.00]" lines are used as end-of-line markers
 *  - 1-3 digit minutes/fractions and missing fractions are accepted
 */
object WordLyricsParser {

    private const val TIME = """(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?"""
    private val lineTag = Regex("""\[$TIME\]""")
    private val wordTag = Regex("""<$TIME>""")
    private val whitespace = Regex("""\s+""")

    private class Piece(val word: String, val start: Long, var end: Long?, var space: Boolean)
    private class Raw(val start: Long, val text: String, val pieces: List<Piece>?)

    private fun ms(match: MatchResult): Long {
        val g = match.groupValues
        val frac = g[3]
        val fractionMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100L
            2 -> frac.toLong() * 10L
            else -> frac.take(3).toLong()
        }
        return g[1].toLong() * 60_000L + g[2].toLong() * 1_000L + fractionMs
    }

    private fun wordPieces(content: String, lineStart: Long?): List<Piece> {
        val matches = wordTag.findAll(content).toList()
        if (matches.isEmpty()) return emptyList()

        data class Seg(val text: String, val start: Long)

        val segs = ArrayList<Seg>()
        val prefix = content.substring(0, matches.first().range.first)
        if (prefix.isNotBlank()) segs += Seg(prefix, lineStart ?: ms(matches.first()))
        matches.forEachIndexed { i, m ->
            val end = matches.getOrNull(i + 1)?.range?.first ?: content.length
            segs += Seg(content.substring(m.range.last + 1, end), ms(m))
        }

        val pieces = ArrayList<Piece>()
        segs.forEachIndexed { i, seg ->
            val segEnd = segs.getOrNull(i + 1)?.start
            if (seg.text.isBlank()) {
                // Empty tag = end marker of the previous word (already used as its segEnd).
                if (seg.text.isNotEmpty()) pieces.lastOrNull()?.space = true
                return@forEachIndexed
            }
            if (seg.text.first().isWhitespace()) pieces.lastOrNull()?.space = true
            val trailing = seg.text.last().isWhitespace()
            val parts = seg.text.trim().split(whitespace)

            if (parts.size == 1) {
                pieces += Piece(parts[0], seg.start, segEnd, trailing)
            } else {
                val total = parts.sumOf { it.length }.coerceAtLeast(1)
                val spanEnd = segEnd ?: (seg.start + (total * 80L + 300L).coerceIn(400L, 2500L))
                val span = (spanEnd - seg.start).coerceAtLeast(parts.size.toLong())
                var cum = 0
                parts.forEachIndexed { j, part ->
                    val s = seg.start + span * cum / total
                    cum += part.length
                    val e = seg.start + span * cum / total
                    val isLast = j == parts.lastIndex
                    pieces += Piece(
                        word = part,
                        start = s,
                        end = if (isLast && segEnd == null) null else e,
                        space = !isLast || trailing,
                    )
                }
            }
        }
        return pieces
    }

    private fun fallbackEnd(p: Piece, nextStart: Long?): Long {
        val estimate = p.start + (p.word.length * 80L + 300L).coerceIn(400L, 2500L)
        return if (nextStart != null && nextStart > p.start) minOf(estimate, nextStart) else estimate
    }

    fun parse(lrcContent: String): List<EnhancedLyricLine> {
        val raws = ArrayList<Raw>()

        for (source in lrcContent.lineSequence()) {
            val line = source.trim()
            if (line.isEmpty()) continue

            val tags = lineTag.findAll(line).toList()
            val hasWords = wordTag.containsMatchIn(line)
            if (tags.isEmpty() && !hasWords) continue // metadata ([ar:..]) or untimed text

            val content = line.replace(lineTag, "").trimStart()

            if (hasWords) {
                val start = tags.firstOrNull()?.let { ms(it) }
                val pieces = wordPieces(content, start)
                if (pieces.isEmpty()) continue
                raws += Raw(minOf(start ?: pieces.first().start, pieces.first().start), "", pieces)
            } else {
                val text = content.trim()
                tags.forEach { raws += Raw(ms(it), text, null) } // blank text = end marker
            }
        }

        val sorted = raws.sortedBy { it.start }
        val out = ArrayList<EnhancedLyricLine>()

        for (i in sorted.indices) {
            val raw = sorted[i]
            val nextStart = (i + 1 until sorted.size)
                .firstOrNull { sorted[it].start > raw.start }
                ?.let { sorted[it].start }

            val pieces = raw.pieces
            if (pieces != null) {
                for (k in pieces.indices) {
                    val p = pieces[k]
                    if (p.end == null) {
                        p.end = pieces.getOrNull(k + 1)?.start ?: fallbackEnd(p, nextStart)
                    }
                }
                val tokens = pieces.map {
                    WordToken(it.word, it.start, maxOf(it.end ?: it.start, it.start + 1L), it.space)
                }
                out += EnhancedLyricLine(
                    lineStartMs = raw.start,
                    words = tokens,
                    rawText = joinWords(tokens),
                    lineEndMs = tokens.last().endMs,
                    isWordSynced = true,
                )
            } else if (raw.text.isNotBlank()) {
                val parts = raw.text.split(whitespace).filter { it.isNotEmpty() }
                val gap = nextStart?.minus(raw.start)
                val cap = (raw.text.length * 90L + 800L).coerceIn(1200L, 8000L)
                val duration = (if (gap != null && gap in 1L..8000L) gap else cap).coerceAtLeast(300L)
                val total = parts.sumOf { it.length }.coerceAtLeast(1)
                var cum = 0
                val tokens = parts.mapIndexed { j, part ->
                    val s = raw.start + duration * cum / total
                    cum += part.length
                    val e = raw.start + duration * cum / total
                    WordToken(part, s, maxOf(e, s + 1L), j < parts.lastIndex)
                }
                out += EnhancedLyricLine(
                    lineStartMs = raw.start,
                    words = tokens,
                    rawText = raw.text,
                    lineEndMs = raw.start + duration,
                    isWordSynced = false,
                )
            }
        }
        return out
    }
}

internal fun joinWords(words: List<WordToken>): String = buildString {
    words.forEachIndexed { i, w ->
        append(w.word)
        if (w.trailingSpace && i < words.lastIndex) append(' ')
    }
}

/**
 * Text for the one-line lyric preview on the player screen: the whole line that is being sung right now.
 * Before the first line it shows that first line; after the last line it keeps the last one.
 */
fun List<EnhancedLyricLine>.previewAt(positionMs: Long): String? {
    if (isEmpty()) return null
    val idx = indexOfLast { it.lineStartMs <= positionMs }
    return this[idx.coerceAtLeast(0)].rawText.takeIf { it.isNotBlank() }
}