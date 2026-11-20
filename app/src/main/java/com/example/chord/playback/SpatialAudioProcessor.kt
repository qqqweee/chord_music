package com.example.chord.playback

import android.content.Context
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.tanh

enum class SpatialMode(val label: String) {
    Off("Off"),
    /** Wider stage. Safe for speakers and headphones. */
    Wide("Wide"),
    /** Headphone 3D: crossfeed (head shadow + ear delay) + small room. Sound moves out of your head. */
    Surround("3D"),
    /** Bigger hall. More room, more air. */
    Concert("Concert"),
}

/** Live values. AppSettings writes them. The audio thread reads them. */
object SpatialParams {
    @Volatile var mode: SpatialMode = SpatialMode.Off
    /** 0..1 */
    @Volatile var strength: Float = 0.6f
}

/** How sure we are the phone has Dolby / a system spatializer. */
enum class SpatialSupport { None, Probable, Yes }

/**
 * What this phone can do by itself (Dolby Atmos / system spatializer).
 *
 * Old check asked Spatializer.isAvailable. That is true only while a compatible output
 * is connected right now, and Android 12L+ only. Dolby Atmos on Samsung, Xiaomi, Motorola,
 * OnePlus, Lenovo and others is an OEM audio effect and never shows in Spatializer, so
 * Settings said "Not on this phone". Now four checks, any one is enough:
 *   1. Spatializer capability (API 32+). Capability, not current route.
 *   2. Audio effect list has a Dolby effect.
 *   3. A Dolby app or service is installed.
 *   4. Dolby system property or vendor feature is present.
 */
object DeviceSpatial {
    private val DOLBY_EFFECT_TYPES = setOf(
        "9d4921da-8225-4f29-aefa-39537a04bcaa",   // Dolby Audio Processing type
        "46d279d9-9be7-453d-9d7c-ef937f6a1abf",   // Dolby DAX3 type
        "6ab06da4-c516-4611-8166-452799218539",   // Dolby DAP implementation
    )
    private val DOLBY_PACKAGES = listOf(
        "com.dolby.daxappui", "com.dolby.daxservice", "com.dolby.dax", "com.dolby.atmos",
        "com.dolby.ds", "com.dolby.daxappui2",
    )

    fun support(context: Context): SpatialSupport {
        if (systemSpatializer(context)) return SpatialSupport.Yes
        if (dolbyEffect()) return SpatialSupport.Yes
        if (dolbyInstalled(context) || dolbyProperty()) return SpatialSupport.Probable
        return SpatialSupport.None
    }

    /** True when the Atmos switch is worth showing. Probable counts: we show it and let the system decide. */
    fun available(context: Context): Boolean = support(context) != SpatialSupport.None

    private fun systemSpatializer(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 32) return false
        return runCatching {
            val sp = context.getSystemService(AudioManager::class.java).spatializer
            // Capability only. Do not require isAvailable: that depends on the connected output.
            sp.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
        }.getOrDefault(false)
    }

    private fun dolbyEffect(): Boolean = runCatching {
        android.media.audiofx.AudioEffect.queryEffects().orEmpty().any { d ->
            d.type.toString().lowercase() in DOLBY_EFFECT_TYPES ||
                d.uuid.toString().lowercase() in DOLBY_EFFECT_TYPES ||
                d.name.contains("dolby", ignoreCase = true) ||
                d.implementor.contains("dolby", ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun dolbyInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return DOLBY_PACKAGES.any { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        } || runCatching {
            pm.systemAvailableFeatures.any { it.name?.contains("dolby", ignoreCase = true) == true }
        }.getOrDefault(false)
    }

    private fun dolbyProperty(): Boolean = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        listOf("ro.vendor.dolby.dax.version", "vendor.audio.dolby.ds2.enabled", "ro.vendor.audio.dolby.dax.support", "persist.vendor.dolby.loglevel")
            .any { (get.invoke(null, it, "") as String).isNotBlank() }
    }.getOrDefault(false)
}

/**
 * Real DSP, not a label. Runs on the PCM before it hits the speaker.
 *
 *   Wide      mid/side: side level goes up
 *   3D        wider side + crossfeed: each ear also hears the other channel,
 *             late by ~0.3 ms and with the highs cut (head shadow) + 3 early room echoes
 *   Concert   like 3D, longer and louder room
 *
 * 16-bit stereo only. Anything else passes untouched. Settings change fades in, no clicks.
 */
@UnstableApi
class SpatialAudioProcessor : BaseAudioProcessor() {

    private val bufL = FloatArray(SIZE)
    private val bufR = FloatArray(SIZE)
    private var w = 0
    private var sr = 44_100
    private var lpL = 0f
    private var lpR = 0f
    private var lpK = 0.1f
    private var amount = 0f   // smoothed 0..1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET   // inactive: audio skips this stage
        }
        sr = inputAudioFormat.sampleRate
        lpK = (1.0 - exp(-2.0 * PI * HEAD_SHADOW_HZ / sr)).toFloat()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / 4
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 4)

        val mode = SpatialParams.mode
        val target = if (mode == SpatialMode.Off) 0f else SpatialParams.strength.coerceIn(0f, 1f)

        // Nothing to do and nothing fading: copy and leave.
        if (target == 0f && amount < 0.001f) {
            amount = 0f
            out.put(inputBuffer)
            out.flip()
            return
        }

        val itd = (sr * 0.00030).toInt().coerceAtLeast(1)
        val roomScale = if (mode == SpatialMode.Concert) 1.8f else 1f
        val d1 = (sr * 0.0113 * roomScale).toInt().coerceIn(1, MASK)
        val d2 = (sr * 0.0197 * roomScale).toInt().coerceIn(1, MASK)
        val d3 = (sr * 0.0293 * roomScale).toInt().coerceIn(1, MASK)
        // Mode of the moment. While fading out to Off we keep the last shape.
        val shape = if (mode == SpatialMode.Off) lastMode else mode.also { lastMode = it }

        repeat(frames) {
            amount += (target - amount) * SMOOTH
            val a = amount
            val l = inputBuffer.short / 32768f
            val r = inputBuffer.short / 32768f
            bufL[w] = l
            bufR[w] = r

            val widen: Float
            val cross: Float
            val room: Float
            when (shape) {
                SpatialMode.Wide -> { widen = 1f + 1.1f * a; cross = 0f; room = 0f }
                SpatialMode.Surround -> { widen = 1f + 0.7f * a; cross = 0.32f * a; room = 0.20f * a }
                SpatialMode.Concert -> { widen = 1f + 0.5f * a; cross = 0.24f * a; room = 0.42f * a }
                SpatialMode.Off -> { widen = 1f; cross = 0f; room = 0f }
            }

            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f * widen
            var ol = mid + side
            var or = mid - side

            if (cross > 0f) {
                lpL += lpK * (bufR[(w - itd) and MASK] - lpL)
                lpR += lpK * (bufL[(w - itd) and MASK] - lpR)
                ol += cross * lpL
                or += cross * lpR
            }
            if (room > 0f) {
                val i1 = (w - d1) and MASK
                val i2 = (w - d2) and MASK
                val i3 = (w - d3) and MASK
                ol += room * (0.50f * bufR[i1] + 0.35f * bufL[i2] + 0.25f * bufR[i3])
                or += room * (0.50f * bufL[i1] + 0.35f * bufR[i2] + 0.25f * bufL[i3])
            }

            val norm = 1f / (1f + cross * 0.8f + room * 1.1f)
            out.putShort(toShort(ol * norm))
            out.putShort(toShort(or * norm))
            w = (w + 1) and MASK
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    private var lastMode: SpatialMode = SpatialMode.Wide

    private fun toShort(x: Float): Short {
        val ax = abs(x)
        // Soft knee above 0.9 so loud songs do not crack.
        val y = if (ax <= 0.9f) x else (if (x > 0) 1 else -1) * (0.9f + 0.1f * tanh((ax - 0.9f) / 0.1f))
        return (y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
    }

    override fun onFlush() = clear()
    override fun onReset() = clear()

    private fun clear() {
        bufL.fill(0f); bufR.fill(0f)
        w = 0; lpL = 0f; lpR = 0f
    }

    private companion object {
        const val SIZE = 8192
        const val MASK = SIZE - 1
        const val HEAD_SHADOW_HZ = 700.0
        const val SMOOTH = 0.0004f   // ~55 ms to settle at 44.1 kHz
    }
}
