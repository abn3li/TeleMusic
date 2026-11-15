package com.abn3li.telemusic.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

/** A neutral, bounded tint; only its alpha animates, with no expanding ripple or idle work. */
internal class SoftPressIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = SoftPressNode(interactionSource, color)
    override fun equals(other: Any?) = other is SoftPressIndication && other.color == color
    override fun hashCode() = color.hashCode()
}

private class SoftPressNode(private val source: InteractionSource, private val color: Color) :
    Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            var focuses = 0
            var hovers = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focuses++
                    is FocusInteraction.Unfocus -> focuses = (focuses - 1).coerceAtLeast(0)
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers = (hovers - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val pressed = presses > 0
                val target = if (pressed || focuses > 0) 0.04f else if (hovers > 0) 0.02f else 0f
                launch { alpha.animateTo(target, tween(if (pressed) 60 else 140)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) drawRect(color.copy(alpha = a))
    }
}

// Material buttons own a ripple internally instead of using LocalIndication. Share their
// interaction source with our overlay and turn off that internal ripple (a null ripple
// configuration), keeping their semantics.
@Composable
private fun buttonIndication(container: Color): Indication {
    val background = paper
    return remember(container, background) {
        SoftPressIndication(if (container.compositeOver(background).luminance() > 0.5f) Color.Black else Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoftIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        IconButton(onClick, modifier.clip(CircleShape).indication(interaction, indication), enabled,
            interactionSource = interaction, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoftButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(), content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = buttonIndication(colors.containerColor)
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Button(onClick, modifier.clip(ButtonDefaults.shape).indication(interaction, indication), enabled,
            colors = colors, interactionSource = interaction, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoftTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(), content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = buttonIndication(colors.containerColor)
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        FilledTonalButton(onClick, modifier.clip(ButtonDefaults.shape).indication(interaction, indication), enabled,
            colors = colors, interactionSource = interaction, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoftTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val indication = LocalIndication.current
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        TextButton(onClick, modifier.clip(ButtonDefaults.textShape).indication(interaction, indication), enabled,
            interactionSource = interaction, content = content)
    }
}
