package com.example.chord.ui.stats.story

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.chord.R
import com.example.chord.data.stats.Recap
import com.example.chord.data.stats.RecapPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * One poster with the whole story on it: minutes, #1 artist, top artists, top tracks.
 * Same look as the story (cream paper, coral marker, ink type). 1080 x 1920.
 */
object RecapShare {
    private const val W = 1080
    private const val H = 1920
    private const val PAD = 80f

    private const val CREAM = 0xFFEFE8D8.toInt()
    private const val INK = 0xFF1A1916.toInt()
    private const val CORAL = 0xFFC4472E.toInt()

    suspend fun share(ctx: Context, r: Recap, p: RecapPeriod) {
        try {
            val file = withContext(Dispatchers.IO) { render(ctx, r, p) }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "My ${p.noun} in sound, on Chord")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share your ${p.noun}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ChordRecap", "summary share failed: ${e.message}", e)
            android.widget.Toast.makeText(ctx, "Could not share your recap", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun render(ctx: Context, r: Recap, p: RecapPeriod): File {
        val artistImg = r.topArtists.firstOrNull()?.thumb?.let { cover(ctx, it) }

        val bold = Typeface.create(ResourcesCompat.getFont(ctx, R.font.sf_pro_display_bold) ?: Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC)
        val semi = ResourcesCompat.getFont(ctx, R.font.sf_pro_display_semibold) ?: Typeface.DEFAULT_BOLD
        val med = ResourcesCompat.getFont(ctx, R.font.sf_pro_display_medium) ?: Typeface.DEFAULT

        fun tp(size: Float, color: Int, tf: Typeface, spacing: Float = 0f) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = tf; letterSpacing = spacing }

        fun fit(t: String, paint: TextPaint, maxW: Float, min: Float = 24f): String {
            while (paint.measureText(t) > maxW && paint.textSize > min) paint.textSize -= 2f
            return t
        }

        fun cut(t: String, paint: TextPaint, maxW: Float) = TextUtils.ellipsize(t, paint, maxW, TextUtils.TruncateAt.END).toString()

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Paper + grain.
        c.drawColor(CREAM)
        val rnd = Random(7)
        val grain = Paint().apply { color = 0x1A000000; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND }
        repeat(1400) { c.drawPoint(rnd.nextFloat() * W, rnd.nextFloat() * H, grain) }

        // Bullseye, bottom right.
        for (i in 6 downTo 1) {
            c.drawCircle(W + 20f, H + 20f, 60f * i, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (i % 2 == 0) INK else CREAM })
        }

        // Brand.
        c.drawCircle(PAD + 14f, 118f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CORAL })
        c.drawText("Chord", PAD + 44f, 134f, tp(46f, INK, bold, -0.02f))

        // Heading, second line on a coral marker strip.
        c.drawText("MY ${p.word}", PAD, 330f, tp(160f, INK, bold, -0.04f))
        val strip = tp(160f, CREAM, bold, -0.04f)
        val w2 = strip.measureText("IN SOUND")
        c.save(); c.rotate(-1.5f, PAD, 480f)
        c.drawRect(PAD - 20f, 365f, PAD + w2 + 30f, 505f, Paint().apply { color = CORAL })
        c.drawText("IN SOUND", PAD, 480f, strip)
        c.restore()

        // Minutes.
        val mins = String.format(Locale.US, "%,d", r.totalMs / 60_000L)
        c.drawText("$mins minutes", PAD, 680f, tp(88f, INK, bold, -0.03f).also { fit("$mins minutes", it, W - 2 * PAD) })
        val hours = r.totalMs / 3_600_000L
        val sub = if (hours >= 1) "About $hours hours of music · ${r.songCount} different songs" else "${r.songCount} different songs"
        c.drawText(sub, PAD, 740f, tp(38f, 0xCC1A1916.toInt(), med))

        // #1 artist: round photo + name.
        val top = r.topArtists.firstOrNull()
        val cx = PAD + 150f
        val cy = 960f
        c.drawCircle(cx, cy, 158f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK })
        c.save(); c.clipPath(Path().apply { addCircle(cx, cy, 150f, Path.Direction.CW) })
        if (artistImg != null) {
            val side = minOf(artistImg.width, artistImg.height)
            val src = Rect((artistImg.width - side) / 2, (artistImg.height - side) / 2, (artistImg.width + side) / 2, (artistImg.height + side) / 2)
            c.drawBitmap(artistImg, src, RectF(cx - 150f, cy - 150f, cx + 150f, cy + 150f), Paint(Paint.FILTER_BITMAP_FLAG))
        } else c.drawRect(cx - 150f, cy - 150f, cx + 150f, cy + 150f, Paint().apply { color = CORAL })
        c.restore()
        val tx = PAD + 360f
        val tw = W - tx - PAD
        c.drawText("TOP ARTIST", tx, 900f, tp(30f, CORAL, semi, 0.14f))
        val nm = tp(78f, INK, bold, -0.03f)
        c.drawText(fit((top?.name ?: "Nothing yet").uppercase(), nm, tw, 36f).let { cut(it, nm, tw) }, tx, 985f, nm)
        c.drawText(if (top != null) "${String.format(Locale.US, "%,d", top.ms / 60_000L)} minutes together" else "", tx, 1040f, tp(34f, 0xCC1A1916.toInt(), med))

        // Two columns: top artists, top tracks.
        val colW = (W - 2 * PAD - 50f) / 2f
        val left = PAD
        val right = PAD + colW + 50f
        val listTop = 1210f

        c.drawText("TOP ARTISTS", left, listTop, tp(30f, CORAL, semi, 0.14f))
        c.drawText("TOP TRACKS", right, listTop, tp(30f, CORAL, semi, 0.14f))

        val rowH = 92f
        r.topArtists.take(5).forEachIndexed { i, a ->
            val y = listTop + 70f + i * rowH
            c.drawText("${i + 1}", left, y, tp(44f, INK, bold))
            val n = tp(36f, INK, semi)
            c.drawText(cut(a.name, n, colW - 60f), left + 60f, y, n)
        }
        r.topSongs.take(5).forEachIndexed { i, s ->
            val y = listTop + 70f + i * rowH
            c.drawText("${i + 1}", right, y, tp(44f, INK, bold))
            val t = tp(34f, INK, semi)
            val a = tp(27f, 0xB31A1916.toInt(), med)
            c.drawText(cut(s.title, t, colW - 60f), right + 60f, y - 12f, t)
            c.drawText(cut(s.artist, a, colW - 60f), right + 60f, y + 22f, a)
        }

        c.drawText("chord · my ${p.noun} in sound", PAD, H - 90f, tp(34f, INK, semi))

        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        return File(dir, "chord_recap_summary.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private suspend fun cover(ctx: Context, url: String): Bitmap? = runCatching {
        val req = ImageRequest.Builder(ctx).data(url).size(600, 600).allowHardware(false).build()
        (ctx.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap()
    }.getOrNull()
}
