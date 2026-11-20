package com.example.chord.ui.stats

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.chord.data.stats.ListenStats
import com.example.chord.data.stats.formatListenTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Draws a 1080x1350 poster of your stats and opens the share sheet. */
object StatsShare {
    private const val W = 1080
    private const val H = 1350
    private const val PAD = 80f

    suspend fun share(context: Context, stats: ListenStats) {
        val file = withContext(Dispatchers.IO) { render(context, stats) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share your stats").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun render(ctx: Context, s: ListenStats): File {
        val song = s.topSong?.thumb?.let { cover(ctx, it) }
        val artist = s.topArtist?.thumb?.let { cover(ctx, it) }
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // Background: deep gradient + soft glow.
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W * 0.7f, H.toFloat(), 0xFF2B2160.toInt(), 0xFF0B0B10.toInt(), Shader.TileMode.CLAMP)
        })
        c.drawCircle(W * 0.92f, H * 0.06f, 620f, Paint().apply {
            shader = RadialGradient(W * 0.92f, H * 0.06f, 620f, 0x40B7A4FF, 0x00B7A4FF, Shader.TileMode.CLAMP)
        })

        fun text(size: Float, color: Int, spacing: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = medium; textSize = size; this.color = color; letterSpacing = spacing
        }

        c.drawText("CHORD  ·  MY LISTENING", PAD, 140f, text(30f, 0x99FFFFFF.toInt(), 0.16f))

        // Big number, shrunk to fit.
        val big = text(160f, 0xFFFFFFFF.toInt(), -0.03f)
        val total = formatListenTime(s.totalMs)
        while (big.measureText(total) > W - 2 * PAD && big.textSize > 60f) big.textSize -= 4f
        c.drawText(total, PAD, 360f, big)
        c.drawText("listened all time", PAD, 418f, text(36f, 0x99FFFFFF.toInt()))

        // Three small stats.
        val cols = listOf(
            "Today" to formatListenTime(s.todayMs),
            "Last 7 days" to formatListenTime(s.weekMs),
            "Streak" to "${s.streakDays} day" + if (s.streakDays == 1) "" else "s",
        )
        val colW = (W - 2 * PAD) / 3f
        cols.forEachIndexed { i, (label, value) ->
            val x = PAD + i * colW
            c.drawText(value, x, 560f, text(50f, 0xFFFFFFFF.toInt()))
            c.drawText(label, x, 606f, text(30f, 0x80FFFFFF.toInt()))
        }

        // Two covers.
        val size = 440f
        val top = 700f
        val items = listOf(
            Triple("TOP SONG", s.topSong?.let { it.title to "${it.artist.ifBlank { "Unknown" }} · ${formatListenTime(it.ms)}" }, song),
            Triple("TOP ARTIST", s.topArtist?.let { it.name to formatListenTime(it.ms) }, artist),
        )
        items.forEachIndexed { i, (label, info, img) ->
            val x = PAD + i * (size + 40f)
            val rect = RectF(x, top, x + size, top + size)
            val clip = Path().apply { addRoundRect(rect, 48f, 48f, Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            if (img != null) {
                val side = minOf(img.width, img.height)
                val src = Rect((img.width - side) / 2, (img.height - side) / 2, (img.width + side) / 2, (img.height + side) / 2)
                c.drawBitmap(img, src, rect, Paint(Paint.FILTER_BITMAP_FLAG))
            } else c.drawRect(rect, Paint().apply { color = 0x22FFFFFF })
            c.drawRect(rect, Paint().apply {
                shader = LinearGradient(0f, top, 0f, top + size, 0x59000000, 0x00000000, Shader.TileMode.CLAMP)
            })
            c.restore()
            c.drawText(label, x + 28f, top + 56f, text(26f, 0xCCFFFFFF.toInt(), 0.14f))
            val name = text(42f, 0xFFFFFFFF.toInt())
            val sub = text(32f, 0x99FFFFFF.toInt())
            c.drawText(TextUtils.ellipsize(info?.first ?: "Nothing yet", name, size, TextUtils.TruncateAt.END).toString(), x, top + size + 64f, name)
            c.drawText(TextUtils.ellipsize(info?.second ?: "", sub, size, TextUtils.TruncateAt.END).toString(), x, top + size + 112f, sub)
        }

        val foot = text(30f, 0x66FFFFFF.toInt(), 0.2f)
        c.drawText("chord", (W - foot.measureText("chord")) / 2f, 1290f, foot)

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        return File(dir, "chord_stats.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private suspend fun cover(ctx: Context, url: String): Bitmap? = runCatching {
        val req = ImageRequest.Builder(ctx).data(url).size(600, 600).allowHardware(false).build()
        (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
    }.getOrNull()
}
