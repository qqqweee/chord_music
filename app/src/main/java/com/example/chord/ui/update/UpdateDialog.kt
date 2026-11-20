package com.example.chord.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.data.update.AppRelease
import com.example.chord.data.update.UpdatePhase
import com.example.chord.ui.components.pressScale
import com.example.chord.ui.theme.ChordTheme

/** "What's new" popup for an app update. Shown once per version (see AppUpdater). */
@Composable
fun UpdateDialog(
    release: AppRelease,
    phase: UpdatePhase,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
) {
    val downloading = phase is UpdatePhase.Downloading
    AlertDialog(
        onDismissRequest = onLater,
        containerColor = ChordTheme.sheet,
        shape = RoundedCornerShape(28.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("What's new", color = ChordTheme.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("Chord ${release.version}", color = ChordTheme.textDim, fontSize = 13.sp)
                }
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(50)).pressScale(onClick = onLater), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close", tint = ChordTheme.textDim)
                }
            }
        },
        text = {
            Column {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        release.notes.ifBlank { "Bug fixes and improvements." },
                        color = ChordTheme.text, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                }
                when (phase) {
                    is UpdatePhase.Downloading -> {
                        Spacer(Modifier.height(16.dp))
                        val bar = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50))
                        if (phase.progress >= 0f) LinearProgressIndicator(
                            progress = { phase.progress },
                            modifier = bar, color = ChordTheme.accent, trackColor = ChordTheme.surfaceHigh,
                        ) else LinearProgressIndicator(
                            modifier = bar, color = ChordTheme.accent, trackColor = ChordTheme.surfaceHigh,
                        )
                    }
                    is UpdatePhase.NeedsPermission -> Note("Allow Chord to install apps in the settings page that opened, go back, then tap Install.")
                    is UpdatePhase.Failed -> Note(phase.message)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            val label = when (phase) {
                is UpdatePhase.Downloading ->
                    if (phase.progress >= 0f) "Downloading ${(phase.progress * 100).toInt()}%" else "Downloading"
                is UpdatePhase.Ready, is UpdatePhase.NeedsPermission -> "Install"
                is UpdatePhase.Failed -> "Retry"
                is UpdatePhase.Idle -> "Update now"
            }
            Text(
                label, color = ChordTheme.onAccent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (downloading) ChordTheme.accent.copy(alpha = 0.5f) else ChordTheme.accent)
                    .pressScale(onClick = { if (!downloading) onUpdate() })
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        },
        dismissButton = {
            Text(
                if (downloading) "Cancel" else "Later",
                color = ChordTheme.textDim, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).pressScale(onClick = onLater)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        },
    )
}

@Composable
private fun Note(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = ChordTheme.textDim, fontSize = 12.sp, lineHeight = 16.sp)
}