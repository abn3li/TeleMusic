package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.ink

/** A few short choices side by side, one of them picked. */
@Composable
internal fun <T> SegmentedChoice(options: List<Pair<T, String>>, selected: T, onChange: (T) -> Unit, modifier: Modifier = Modifier) {
    val chosen = if (LocalPalette.current.isLight) Color.White else Color(0xFF48484B)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(ink.copy(alpha = 0.08f)).padding(2.dp)) {
        options.forEach { (value, label) ->
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                    .background(if (value == selected) chosen else Color.Transparent)
                    .semantics { this.selected = value == selected }
                    .clickable(role = Role.RadioButton) { onChange(value) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        }
    }
}

/** Where a new playlist goes: the YouTube account, or this phone. */
@Composable
internal fun PlaylistPlaceChoice(youtube: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) =
    SegmentedChoice(listOf(true to "YouTube", false to "On this phone"), youtube, onChange, modifier)
