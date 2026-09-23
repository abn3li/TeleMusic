package com.abn3li.telemusic.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// iOS-style centered alert, used for every confirmation/input dialog in the app.
internal val AlertColor = Color(0xFF252527)
private val AlertHairline = Color.White.copy(alpha = 0.14f)

internal class AlertAction(
    val label: String,
    val bold: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val loading: Boolean = false,
    val onClick: () -> Unit
)

/**
 * Title, optional message and [content] (text fields, notes), then the [actions]: side by side
 * when there are two, stacked otherwise - like UIAlertController.
 */
@Composable
internal fun AppAlert(
    title: String,
    onDismiss: () -> Unit,
    actions: List<AlertAction>,
    message: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(290.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AlertColor)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineHeight = 22.sp)
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
                }
                if (content != null) {
                    Spacer(Modifier.height(14.dp))
                    content()
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(AlertHairline))
            if (actions.size == 2) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    AlertButton(actions[0], Modifier.weight(1f))
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(AlertHairline))
                    AlertButton(actions[1], Modifier.weight(1f))
                }
            } else {
                actions.forEachIndexed { index, action ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(AlertHairline))
                    AlertButton(action, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AlertButton(action: AlertAction, modifier: Modifier) {
    val color = if (action.destructive) DestructiveRed else AppAccent
    Box(
        modifier
            .height(46.dp)
            .clickable(enabled = action.enabled && !action.loading, onClick = action.onClick)
            .alpha(if (action.enabled) 1f else 0.35f),
        contentAlignment = Alignment.Center
    ) {
        if (action.loading) {
            CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            Text(
                action.label,
                color = color,
                fontSize = 17.sp,
                fontWeight = if (action.bold) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

/** Compact text field for inside an [AppAlert]. */
@Composable
internal fun AlertTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1C))
            .then(if (isError) Modifier.background(DestructiveRed.copy(alpha = 0.12f)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Color.White.copy(alpha = 0.3f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(AppAccent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Small note under alert fields (errors, hints). */
@Composable
internal fun AlertNote(text: String, color: Color = Color.White.copy(alpha = 0.6f)) {
    Text(text, color = color, fontSize = 12.5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}
