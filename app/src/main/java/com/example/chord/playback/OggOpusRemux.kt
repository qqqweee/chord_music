package com.example.chord.playback

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * WebM/Opus -> Ogg/Opus, with cover art. Android Files shows .webm as video with no thumbnail.
 * Ogg Opus is plain audio and can carry cover art, so Files shows the art.
 */
object OggOpusRemux {
    private const val TAG = "OggOpusRemux"

    fun run(src: File, dst: File, title: String, artist: String, cover: ByteArray?, coverMime: String): Boolean =
        try {
            remux(src, dst, title, artist, cover, coverMime); true
        } catch (e: Exception) {
            Log.w(TAG, "Remux failed", e); dst.delete(); false
        }

    private fun remux(src: File, dst: File, title: String, artist: String, cover: ByteArray?, coverMime: String) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.absolutePath)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME) == "audio/opus") { ex.selectTrack(i); fmt = f; break }
            }
            requireNotNull(fmt) { "No opus track" }

            val csd = fmt.getByteBuffer("csd-0")
            val raw = csd?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
            val head = if (raw != null && raw.size >= 19 && String(raw, 0, 8, Charsets.US_ASCII) == "OpusHead") raw
            else buildHead(if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2)

            FileOutputStream(dst).use { fos ->
                val w = OggWriter(fos.buffered(64 * 1024), System.nanoTime().toInt())
                w.packet(head, 0L, flush = true)
                w.packet(tags(title, artist, cover, coverMime), 0L, flush = true)

                val buf = ByteBuffer.allocate(512 * 1024)
                var granule = 0L
                while (true) {
                    buf.clear()
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) break
                    val pkt = ByteArray(n)
                    buf.position(0)
                    buf.get(pkt, 0, n)
                    granule += samples(pkt)
                    w.packet(pkt, granule, flush = false)
                    ex.advance()
                }
                w.finish(granule)
            }
        } finally {
            ex.release()
        }
    }

    private fun buildHead(channels: Int): ByteArray {
        val b = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("OpusHead".toByteArray(Charsets.US_ASCII)); b.put(1); b.put(channels.toByte())
        b.putShort(3840); b.putInt(48000); b.putShort(0); b.put(0)
        return b.array()
    }

    /** Samples at 48 kHz in one Opus packet, from its TOC byte. */
    private fun samples(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val cfg = toc shr 3
        val frame = when {
            cfg < 12 -> intArrayOf(480, 960, 1920, 2880)[cfg % 4]
            cfg < 16 -> intArrayOf(480, 960)[cfg % 2]
            else -> intArrayOf(120, 240, 480, 960)[cfg % 4]
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size > 1) p[1].toInt() and 0x3F else 1
        }
        return frame * count
    }

    private fun le32(o: ByteArrayOutputStream, v: Int) { for (i in 0..3) o.write((v shr (8 * i)) and 0xFF) }
    private fun be32(o: ByteArrayOutputStream, v: Int) { for (i in 3 downTo 0) o.write((v shr (8 * i)) and 0xFF) }

    private fun tags(title: String, artist: String, cover: ByteArray?, coverMime: String): ByteArray {
        val comments = ArrayList<String>()
        if (title.isNotBlank()) comments += "TITLE=$title"
        if (artist.isNotBlank()) comments += "ARTIST=$artist"
        if (cover != null) {
            val pic = ByteArrayOutputStream()
            val mime = coverMime.toByteArray(Charsets.US_ASCII)
            be32(pic, 3); be32(pic, mime.size); pic.write(mime); be32(pic, 0)
            be32(pic, 0); be32(pic, 0); be32(pic, 0); be32(pic, 0)
            be32(pic, cover.size); pic.write(cover)
            comments += "METADATA_BLOCK_PICTURE=" + Base64.encodeToString(pic.toByteArray(), Base64.NO_WRAP)
        }
        val o = ByteArrayOutputStream()
        o.write("OpusTags".toByteArray(Charsets.US_ASCII))
        val vendor = "Chord".toByteArray()
        le32(o, vendor.size); o.write(vendor)
        le32(o, comments.size)
        comments.forEach { val b = it.toByteArray(Charsets.UTF_8); le32(o, b.size); o.write(b) }
        return o.toByteArray()
    }

    private class OggWriter(private val out: OutputStream, private val serial: Int) {
        private val segs = ArrayList<Int>()
        private val body = ByteArrayOutputStream()
        private var seq = 0
        private var bos = true
        private var continued = false

        fun packet(data: ByteArray, granule: Long, flush: Boolean) {
            val count = data.size / 255 + 1
            var off = 0
            for (i in 0 until count) {
                val len = if (i < count - 1) 255 else data.size % 255
                if (segs.size == 255) page(-1L, eos = false, mid = true)
                segs.add(len); body.write(data, off, len); off += len
            }
            if (flush || segs.size >= 50) page(granule, eos = false, mid = false)
        }

        fun finish(granule: Long) {
            page(granule, eos = true, mid = false)
            out.flush()
        }

        private fun page(granule: Long, eos: Boolean, mid: Boolean) {
            val h = ByteBuffer.allocate(27 + segs.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("OggS".toByteArray(Charsets.US_ASCII)); h.put(0)
            h.put(((if (continued) 1 else 0) or (if (bos) 2 else 0) or (if (eos) 4 else 0)).toByte())
            h.putLong(granule); h.putInt(serial); h.putInt(seq++); h.putInt(0)
            h.put(segs.size.toByte())
            segs.forEach { h.put(it.toByte()) }
            val header = h.array()
            val payload = body.toByteArray()
            var crc = 0
            for (b in header) crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            for (b in payload) crc = (crc shl 8) xor TABLE[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
            ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
            out.write(header); out.write(payload)
            segs.clear(); body.reset()
            bos = false; continued = mid
        }

        companion object {
            private val TABLE = IntArray(256).also { t ->
                for (i in 0 until 256) {
                    var r = i shl 24
                    repeat(8) { r = if (r and Int.MIN_VALUE != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
                    t[i] = r
                }
            }
        }
    }
}
