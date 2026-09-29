package com.abn3li.telemusic.ui.navigation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The bottom bar's own icon family: one 24-unit grid, a 1.9 rounded stroke, round caps and
 * joins, so the four read as a set. Drawn in black and tinted by the bar (Icon's tint), so
 * the same vector serves the grey and accent states. Home alone has a filled selected form.
 */
internal object NavIcons {

    // Rounded house: soft roof joins, arched doorway notched out of the base.
    private const val HOME =
        "M4 10.2L12 3.9L20 10.2V18.4A2.1 2.1 0 0 1 17.9 20.5H14.6V15.6A2.6 2.6 0 0 0 9.4 15.6V20.5H6.1A2.1 2.1 0 0 1 4 18.4Z"

    // A round lens and a short diagonal handle.
    private const val SEARCH =
        "M10.6 4.2A6.4 6.4 0 1 1 10.6 17A6.4 6.4 0 1 1 10.6 4.2Z M15.3 15.3L20 20"

    // Two arcs of one circle, each ending in a rounded chevron - the second is the first turned
    // half a turn, so the pair balances around an open centre.
    private const val SYNC =
        "M5.24 10.19A7 7 0 0 1 18.58 9.61 M19.67 6.6L18.58 9.61L15.81 8.01 " +
            "M18.76 13.81A7 7 0 0 1 5.42 14.39 M4.33 17.4L5.42 14.39L8.19 15.99"

    // Two track spines and a flagged note: a music collection, not a single note.
    private const val LIBRARY_STROKES = "M4.3 7V19.2 M8.2 5V19.2 M15.4 17V5.3L20.4 4.1V7.3"
    private const val LIBRARY_NOTE_HEAD = "M13 14.4A2.6 2.6 0 1 1 13 19.6A2.6 2.6 0 1 1 13 14.4Z"

    private const val STROKE = 1.9f

    val Home: ImageVector by lazy { icon("NavHome") { stroked(HOME) } }
    val HomeFilled: ImageVector by lazy { icon("NavHomeFilled") { stroked(HOME, filled = true) } }
    val Search: ImageVector by lazy { icon("NavSearch") { stroked(SEARCH) } }
    val Sync: ImageVector by lazy { icon("NavSync") { stroked(SYNC) } }
    val Library: ImageVector by lazy {
        icon("NavLibrary") {
            stroked(LIBRARY_STROKES)
            filled(LIBRARY_NOTE_HEAD)
        }
    }

    private inline fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    private fun ImageVector.Builder.stroked(path: String, filled: Boolean = false) {
        addPath(
            pathData = addPathNodes(path),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }

    private fun ImageVector.Builder.filled(path: String) {
        addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
    }
}
