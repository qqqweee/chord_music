package com.example.chord.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * The only place that writes a quality label for a streamed song.
 *
 * A resolver only CLAIMS a format ("FLAC · Monochrome"). The label shown to the person comes from
 * what the player actually opened: the audio track the extractor found in the bytes.
 * A claimed FLAC that arrives as something else is shown as what it is.
 *
 * It also tells [ProviderHealth] the provider really played, which clears its failure strikes.
 */
@UnstableApi
class PlaybackQualityReporter(private val player: Player) : Player.Listener {

    override fun onTracksChanged(tracks: Tracks) {
        val item = player.currentMediaItem ?: return
        if (item.localConfiguration?.uri?.scheme != "chord") return
        val format = tracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
            ?: return
        report(item.mediaId, format)
    }

    private fun report(videoId: String, format: Format) {
        val decoded = StreamInfoStore.family(format.sampleMimeType) ?: StreamInfoStore.family(format.codecs)
        val stream = StreamResolver.currentStream(videoId)
        val claimed = stream?.let { StreamInfoStore.family(it.mimeType) ?: StreamInfoStore.family(it.quality) }
        val bits = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT -> 32
            else -> null
        }
        val sameAsClaim = decoded != null && decoded == claimed
        val label = when {
            // Lossless confirmed by the decoder side: rate (and depth when known) come from the file.
            decoded == "flac" || decoded == "alac" -> {
                val core = StreamInfoStore.label(
                    codecs = null, mime = format.sampleMimeType,
                    bitrate = null,
                    sampleRate = format.sampleRate.takeIf { it > 0 },
                    bitDepth = bits,
                    channels = format.channelCount.takeIf { it > 0 },
                )
                val from = if (sameAsClaim) stream?.quality?.split(" · ")?.lastOrNull()
                    ?.takeIf { it.isNotBlank() && !it.equals(core.substringBefore(" · "), true) } else null
                listOfNotNull(core, from).joinToString(" · ")
            }
            // Lossy, and the resolver said the same codec: its label has the bitrate, keep it.
            sameAsClaim && stream != null -> stream.quality
            // Lossy: report what the player sees. Never "FLAC".
            else -> StreamInfoStore.label(
                format.codecs, format.sampleMimeType, format.averageBitrate.takeIf { it > 0 },
                format.sampleRate.takeIf { it > 0 }, bits, format.channelCount.takeIf { it > 0 },
            )
        }
        // Label = codec only ("Opus · 160 kbps ..."). The "no lossless match" reason is shown in the toast, not here.
        StreamInfoStore.put(videoId, label)
        stream?.provider?.takeIf { it.isNotBlank() }?.let { ProviderHealth.markOk(it) }
    }
}