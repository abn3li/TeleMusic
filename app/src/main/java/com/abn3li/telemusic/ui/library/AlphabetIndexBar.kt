package com.abn3li.telemusic.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp

/** A–Z then "#" for anything that doesn't start with a Latin letter. */
internal val IndexLetters: List<String> = ('A'..'Z').map { it.toString() } + "#"

/** The index letter a title/artist/album files under: its first letter or digit, "#" if that
 * isn't A–Z (digits, symbols, non-Latin scripts) - same grouping as Apple Music. */
internal fun indexLetterOf(text: String): String {
    val c = text.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else "#"
}

private val BubbleSize = 62.dp
private val BubbleGap = 14.dp
private val StripWidth = 20.dp

/**
 * Apple Music's thin letter strip on the right edge. Touching or dragging over it calls [onLetter]
 * once per letter change (with a haptic tick) and shows a letter bubble beside the finger.
 *
 * Costs nothing while idle: the bubble isn't composed at all until a finger is down, and while
 * dragging it moves via graphicsLayer (draw-phase only, no recomposition) - the text itself only
 * recomposes when the letter actually changes.
 */
@Composable
internal fun AlphabetIndexBar(letters: List<String>, onLetter: (String) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val latestOnLetter by rememberUpdatedState(onLetter)
    var activeLetter by remember { mutableStateOf<String?>(null) }
    var shownLetter by remember { mutableStateOf("") }
    val fingerY = remember { mutableFloatStateOf(0f) }
    val stripTop = remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxHeight()) {
        // Each letter gets up to 14dp; on short screens the strip shrinks to fit instead of clipping.
        val rowHeight = min(14.dp, maxHeight / letters.size)
        val density = LocalDensity.current
        val fontSize = with(density) { (rowHeight * 0.8f).toSp() }
        val bubblePx = with(density) { BubbleSize.toPx() }
        val shiftX = with(density) { -(StripWidth + BubbleGap).toPx() }
        val maxY = with(density) { maxHeight.toPx() } - bubblePx

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .width(StripWidth)
                .onPlaced { stripTop.floatValue = it.positionInParent().y }
                .clip(RoundedCornerShape(10.dp))
                .background(if (activeLetter != null) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                .pointerInput(letters) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun select(y: Float) {
                            fingerY.floatValue = y
                            val letter = letters[(y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
                            if (letter != activeLetter) {
                                activeLetter = letter
                                shownLetter = letter
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestOnLetter(letter)
                            }
                        }
                        select(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            select(change.position.y)
                        }
                        activeLetter = null
                    }
                }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                Text(
                    letter,
                    color = if (letter == activeLetter) Color.White else AppAccent,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(rowHeight)
                )
            }
        }

        // The letter bubble, to the left of the strip at finger height.
        AnimatedVisibility(
            visible = activeLetter != null,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.7f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .graphicsLayer {
                    translationX = shiftX
                    translationY = (stripTop.floatValue + fingerY.floatValue - bubblePx / 2f).coerceIn(0f, maxY.coerceAtLeast(0f))
                }
        ) {
            Box(
                Modifier
                    .size(BubbleSize)
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .background(Color(0xF22C2C2E), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(shownLetter, color = AppAccent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
