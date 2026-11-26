package com.example.chord.ui.settings

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chord.ui.haptics.Haptic
import com.example.chord.ui.haptics.hapticClickable
import com.example.chord.ui.theme.ChordType

private val CardFill = Color.White.copy(alpha = 0.06f)
private val Dim = Color.White.copy(alpha = 0.60f)

/**
 * Building blocks for the Settings page. New setting = one row from here.
 *
 *   SettingsSection("Title") {
 *       SwitchRow("Name", "Hint", value) { onChange }
 *       ChoiceRow("Name", options, selected, { it.label }) { onSelect }
 *       ActionRow("Name", "Hint", "Button") { onClick }
 *       InfoRow("Name", "Value")
 *   }
 */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp).padding(top = 12.dp)) {
        Text(
            title.uppercase(), style = ChordType.Eyebrow, color = Dim,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardFill),
            content = content,
        )
    }
}

@Composable
private fun RowText(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = ChordType.CardTitle.copy(fontSize = 14.sp), color = Color.White)
        if (hint.isNotBlank()) {
            Text(hint, style = ChordType.CardSub.copy(fontSize = 11.sp, lineHeight = 14.sp), color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .hapticClickable(if (checked) Haptic.ToggleOff else Haptic.ToggleOn) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, hint, Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Color.White,
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f), uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
@OptIn(ExperimentalLayoutApi::class)

@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            style = ChordType.CardTitle.copy(fontSize = 14.sp),
            color = Color.White
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected

                Text(
                    text = label(option),
                    style = ChordType.Label,
                    color = if (isSelected) Color.Black else Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isSelected) Color.White
                            else Color.White.copy(alpha = 0.10f)
                        )
                        .hapticClickable(Haptic.Select) {
                            onSelect(option)
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
fun ActionRow(title: String, hint: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, hint, Modifier.weight(1f))
        Text(
            action, style = ChordType.Label, color = Color.Black,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                .hapticClickable(Haptic.Tap, onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp),
        )
    }
}

/**
 * Slider row. Shows title and live value on top, slider below.
 * [format] turns the value into text, e.g. { "%.0f dp".format(it) }.
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, style = ChordType.CardTitle.copy(fontSize = 14.sp),
                color = Color.White, modifier = Modifier.weight(1f),
            )
            Text(format(value), style = ChordType.CardSub, color = Dim)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.14f),
            ),
        )
    }
}

@Composable
fun InfoRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Title keeps its own width. Value takes the rest and wraps. (Before, a long value took the whole
        // row and squeezed the title to zero width, so its letters stacked.)
        Text(title, style = ChordType.CardTitle.copy(fontSize = 14.sp), color = Color.White, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = ChordType.CardSub, color = Dim, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}