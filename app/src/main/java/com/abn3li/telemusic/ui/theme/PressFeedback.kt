package com.abn3li.telemusic.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

/** A neutral, bounded tint; only its alpha animates, with no expanding ripple or idle work. */
internal class SoftPressIndication(private val color: Color) : Indication {
    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
        val pressed by interactionSource.collectIsPressedAsState()
        val focused by interactionSource.collectIsFocusedAsState()
        val hovered by interactionSource.collectIsHoveredAsState()
        val alpha = animateFloatAsState(
            if (pressed || focused) 0.04f else if (hovered) 0.02f else 0f,
            tween(if (pressed) 60 else 140), label = "pressTint"
        )
        return remember(color, alpha) {
            object : IndicationInstance {
                override fun ContentDrawScope.drawIndication() {
                    drawContent()
                    if (alpha.value > 0f) drawRect(color.copy(alpha = alpha.value))
                }
            }
        }
    }
}

// Material buttons own a ripple internally instead of using LocalIndication. Share their
// interaction source with our overlay and turn off that internal ripple, keeping their semantics.
private object NoButtonRipple : RippleTheme {
    @Composable override fun defaultColor() = Color.Transparent
    @Composable override fun rippleAlpha() = RippleAlpha(0f, 0f, 0f, 0f)
}

@Composable
private fun buttonIndication(container: Color): Indication {
    val background = paper
    return remember(container, background) {
        SoftPressIndication(if (container.compositeOver(background).luminance() > 0.5f) Color.Black else Color.White)
    }
}

@Composable
internal fun SoftIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    CompositionLocalProvider(LocalRippleTheme provides NoButtonRipple) {
        IconButton(onClick, modifier.clip(CircleShape).indication(interaction, indication), enabled,
            interactionSource = interaction, content = content)
    }
}

@Composable
internal fun SoftButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(), content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = buttonIndication(colors.containerColor)
    CompositionLocalProvider(LocalRippleTheme provides NoButtonRipple) {
        Button(onClick, modifier.clip(ButtonDefaults.shape).indication(interaction, indication), enabled,
            colors = colors, interactionSource = interaction, content = content)
    }
}

@Composable
internal fun SoftTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = buttonIndication(colors.containerColor)
    CompositionLocalProvider(LocalRippleTheme provides NoButtonRipple) {
        FilledTonalButton(onClick, modifier.clip(ButtonDefaults.shape).indication(interaction, indication), enabled,
            colors = colors, interactionSource = interaction, content = content)
    }
}

@Composable
internal fun SoftTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    CompositionLocalProvider(LocalRippleTheme provides NoButtonRipple) {
        TextButton(onClick, modifier.clip(ButtonDefaults.textShape).indication(interaction, indication), enabled,
            interactionSource = interaction, content = content)
    }
}
