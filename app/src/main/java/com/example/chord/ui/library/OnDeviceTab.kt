package com.example.chord.ui.library

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.chord.data.home.HomeItem
import com.example.chord.data.home.HomeItemType
import com.example.chord.data.local.LocalMusicRepository
import com.example.chord.data.model.Song
import com.example.chord.ui.components.MediaRow
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordTheme

/** Music files on the phone. Asks permission once, then lists and plays them. Works with no internet. */
@Composable
fun OnDeviceTab(
    nowPlayingId: String?,
    isPlaying: Boolean,
    onPlayQueue: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, LocalMusicRepository.permission) == PackageManager.PERMISSION_GRANTED)
    }
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(granted) { if (granted) songs = LocalMusicRepository.load(context) }

    val list = songs
    when {
        !granted -> Column(
            modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Play music from your phone", color = ChordTheme.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("No internet needed. We only read your audio files.", color = ChordTheme.textDim, fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp))
            Text(
                "Allow access", color = ChordTheme.bg, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 18.dp).clip(RoundedCornerShape(50)).background(ChordTheme.text)
                    .hapticClickable(Haptic.Confirm) { ask.launch(LocalMusicRepository.permission) }
                    .padding(horizontal = 26.dp, vertical = 11.dp),
            )
        }
        list == null -> Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator(color = ChordTheme.text, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        list.isEmpty() -> Text("No music files found on this phone.", color = ChordTheme.textDim, fontSize = 15.sp,
            modifier = modifier.fillMaxWidth().padding(32.dp))
        else -> LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
            item(key = "actions") {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Play all", Icons.Rounded.PlayArrow, true) { onPlayQueue(list, 0) }
                    Pill("Shuffle", Icons.Rounded.Shuffle, false) { onPlayQueue(list.shuffled(), 0) }
                    Spacer(Modifier.weight(1f))
                    Text("${list.size} songs", color = ChordTheme.textDim, fontSize = 12.sp)
                }
            }
            itemsIndexed(list, key = { _, s -> s.videoId }) { i, s ->
                MediaRow(
                    item = HomeItem(s.title, s.details, s.thumbnailUrl, s.videoId, null, HomeItemType.Song),
                    active = s.videoId == nowPlayingId,
                    playing = isPlaying,
                    onClick = { onPlayQueue(list, i) },
                    modifier = Modifier.padding(horizontal = 20.dp),
                    onMoreFromArtist = null,
                )
            }
        }
    }
}

@Composable
private fun Pill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, filled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (filled) ChordTheme.text else ChordTheme.surfaceHigh)
            .hapticClickable(Haptic.Confirm, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (filled) ChordTheme.bg else ChordTheme.text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(5.dp))
        Text(label, color = if (filled) ChordTheme.bg else ChordTheme.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
