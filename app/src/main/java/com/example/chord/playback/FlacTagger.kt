package com.example.chord.playback

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Writes title, artist and cover art into a FLAC file.
 * Keeps STREAMINFO and other blocks. Replaces old VORBIS_COMMENT, PICTURE, PADDING.
 * Audio frames are copied as is. Returns false on any problem; caller keeps the original.
 */
object FlacTagger {
    fun run(src: File, dst: File, title: String, artist: String, cover: ByteArray?, coverMime: String): Boolean =
        try {
            tag(src, dst, title, artist, cover, coverMime); true
        } catch (_: Exception) {
            dst.delete(); false
        }

    private fun tag(src: File, dst: File, title: String, artist: String, cover: ByteArray?, coverMime: String) {
        DataInputStream(src.inputStream().buffered()).use { input ->
            val magic = ByteArray(4).also { input.readFully(it) }
            check(String(magic, Charsets.US_ASCII) == "fLaC") { "Not FLAC" }

            val kept = ArrayList<Pair<Int, ByteArray>>()
            while (true) {
                val h = input.readUnsignedByte()
                val last = h and 0x80 != 0
                val type = h and 0x7F
                val len = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                val data = ByteArray(len).also { input.readFully(it) }
                if (type != 1 && type != 4 && type != 6) kept += type to data
                if (last) break
            }
            check(kept.firstOrNull()?.first == 0) { "No STREAMINFO" }

            kept += 4 to vorbis(title, artist)
            if (cover != null && cover.isNotEmpty()) kept += 6 to picture(cover, coverMime)

            BufferedOutputStream(FileOutputStream(dst)).use { out ->
                out.write(magic)
                kept.forEachIndexed { i, (type, data) ->
                    check(data.size < (1 shl 24)) { "Block too big" }
                    out.write(type or if (i == kept.lastIndex) 0x80 else 0)
                    out.write(data.size shr 16); out.write(data.size shr 8); out.write(data.size)
                    out.write(data)
                }
                input.copyTo(out as java.io.OutputStream)
            }
        }
    }

    private fun vorbis(title: String, artist: String): ByteArray {
        val o = ByteArrayOutputStream()
        fun le32(v: Int) { o.write(v); o.write(v shr 8); o.write(v shr 16); o.write(v shr 24) }
        fun str(s: String) { val b = s.toByteArray(Charsets.UTF_8); le32(b.size); o.write(b) }
        str("Chord")
        val fields = buildList {
            if (title.isNotBlank()) add("TITLE=$title")
            if (artist.isNotBlank()) add("ARTIST=$artist")
        }
        le32(fields.size)
        fields.forEach(::str)
        return o.toByteArray()
    }

    private fun picture(cover: ByteArray, mime: String): ByteArray {
        val o = ByteArrayOutputStream()
        fun be32(v: Int) { o.write(v shr 24); o.write(v shr 16); o.write(v shr 8); o.write(v) }
        val m = mime.toByteArray(Charsets.US_ASCII)
        be32(3)                 // front cover
        be32(m.size); o.write(m)
        be32(0)                 // no description
        be32(0); be32(0); be32(0); be32(0) // width, height, depth, colors: unknown
        be32(cover.size); o.write(cover)
        return o.toByteArray()
    }
}