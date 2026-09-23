package com.abn3li.telemusic.ui.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iOS-style grouped lists (Settings, Sync, YouTube).
internal val GroupCardColor = Color(0xFF1C1C1E)
internal val GroupLabelColor = Color(0xFF8E8D93)
internal val DestructiveRed = Color(0xFFFF453A)

/** Small grey caption above a group. */
@Composable
internal fun GroupHeader(text: String) {
    Text(
        text.uppercase(),
        color = GroupLabelColor,
        fontSize = 13.sp,
        lineHeight = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 7.dp)
    )
}

/** Small grey note under a group. */
@Composable
internal fun GroupFooter(text: String, color: Color = GroupLabelColor) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 7.dp)
    )
}

/** Rounded dark card holding a group of rows. */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GroupCardColor),
        content = content
    )
}

@Composable
internal fun GroupDivider(start: Dp = 15.dp) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(0.5.dp)
            .background(Color.White.copy(alpha = 0.12f))
    )
}

/** Coloured rounded-square icon, like iOS Settings. */
@Composable
internal fun GroupIcon(icon: ImageVector, background: Color) {
    Box(
        Modifier.size(29.dp).clip(RoundedCornerShape(7.dp)).background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * One row: optional icon, title, optional dimmed description, trailing content (a chevron,
 * value, switch...). Tappable when [onClick] is set.
 */
@Composable
internal fun GroupRow(
    title: String,
    desc: String? = null,
    icon: (@Composable () -> Unit)? = null,
    titleColor: Color = Color.White,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 15.dp, vertical = 11.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 16.5.sp, lineHeight = 20.5.sp)
            if (desc != null) {
                Text(desc, color = Color.White.copy(alpha = 0.5f), fontSize = 13.2.sp, lineHeight = 16.2.sp)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(15.dp))
            trailing()
        }
    }
}

/** Grey value text followed by a chevron. */
@Composable
internal fun GroupValue(text: String?, showChevron: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (text != null) {
            Text(
                text,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = if (showChevron) 8.dp else 0.dp)
            )
        }
        if (showChevron) ChevronIcon()
    }
}

/** iOS-style toggle. */
@Composable
internal fun GroupSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val haptics = LocalHapticFeedback.current
    val track by animateColorAsState(if (checked) AppAccent else Color(0xFF39393D), label = "switchTrack")
    val thumbOffset by animateDpAsState(
        if (checked) 20.dp else 0.dp,
        spring(dampingRatio = 0.65f, stiffness = 500f),
        label = "switchThumb"
    )
    Box(
        Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** Inline text field row inside a [GroupCard]. */
@Composable
internal fun GroupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 15.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label != null) {
            Text(label, color = Color.White, fontSize = 16.5.sp, modifier = Modifier.width(92.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, color = Color.White.copy(alpha = 0.3f), fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                enabled = enabled,
                textStyle = TextStyle(color = Color.White, fontSize = 16.5.sp),
                cursorBrush = SolidColor(AppAccent),
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/** A tappable action in accent (or [color]) text, e.g. "Apply", "Log Out". */
@Composable
internal fun GroupActionRow(
    label: String,
    color: Color = AppAccent,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 11.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = color, fontSize = 16.5.sp, modifier = Modifier.weight(1f))
        if (loading) CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
}

/** Pill chip used for filters (Sync chat types, YouTube genres). */
@Composable
internal fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) AppAccent else GroupCardColor, label = "pillBg")
    Text(
        label,
        color = if (selected) Color.White else Color.White.copy(alpha = 0.75f),
        fontSize = 15.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** A choice inside an expanded picker (cache limit, DNS resolver): label, optional detail, and a
 * checkmark on the selected one. */
@Composable
internal fun GroupOption(
    label: String,
    selected: Boolean,
    startPadding: Int,
    detail: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = startPadding.dp, end = 15.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 15.5.sp)
            if (detail != null) Text(detail, color = Color.White.copy(alpha = 0.45f), fontSize = 12.5.sp)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = AppAccent, modifier = Modifier.size(20.dp))
        }
    }
}
