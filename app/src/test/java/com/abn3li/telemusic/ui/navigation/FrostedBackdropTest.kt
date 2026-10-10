package com.abn3li.telemusic.ui.navigation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Android drawing on the JVM: correctness checks, not a phone GPU benchmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrostedBackdropTest {
    @Test fun cachedSurfaceStillShowsNewPageContent() {
        val source = NativeFrostedSource()
        val surface = NativeFrostedSurface()
        recordPage(source, Color.Red)
        val first = render(surface, source)
        recordPage(source, Color.Blue)
        val second = render(surface, source)
        assertColour(Color.Red, first)
        assertColour(Color.Blue, second)
    }

    @Test fun movingSampleUsesTheNewPartOfThePage() {
        val source = NativeFrostedSource()
        recordPage(source, Color.Red, right = Color.Blue)
        val surface = NativeFrostedSurface()
        assertColour(Color.Red, render(surface, source, Offset(0f, 0f)))
        assertColour(Color.Blue, render(surface, source, Offset(200f, 0f)))
    }

    @Test fun changingPageDoesNotKeepThePreviousPagesImage() {
        val first = NativeFrostedSource()
        val second = NativeFrostedSource()
        recordPage(first, Color.Red)
        recordPage(second, Color.Green)
        val surface = NativeFrostedSurface()
        assertColour(Color.Red, render(surface, first))
        assertColour(Color.Green, render(surface, second))
    }

    @Test fun discardedOrResizedSurfaceCanBeDrawnAgain() {
        val source = NativeFrostedSource()
        recordPage(source, Color.Blue)
        val surface = NativeFrostedSurface()
        render(surface, source)
        surface.node.discardDisplayList()
        assertColour(Color.Blue, render(surface, source, width = 140, density = 2f))
        assertTrue(surface.node.hasDisplayList())
    }

    @Test fun backgroundColourChangesOutsideThePageAreNotStale() {
        val source = NativeFrostedSource()
        val surface = NativeFrostedSurface()
        recordPage(source, Color.Red, background = Color.Green)
        assertColour(Color.Green, render(surface, source, Offset(500f, 0f)))
        recordPage(source, Color.Red, background = Color.Blue)
        assertColour(Color.Blue, render(surface, source, Offset(500f, 0f)))
    }

    @Test fun departingPageCannotUnregisterTheNewPage() {
        val host = FrostedBackdropHost()
        val old = FrostedBackdrop()
        val current = FrostedBackdrop()
        host.attach(old)
        host.attach(current)
        host.detach(old)
        assertSame(current, host.active)
        host.detach(current)
        assertNull(host.active)
    }

    @Test fun returningFromPageRestoresTheUnderlyingBackdrop() {
        val host = FrostedBackdropHost()
        val home = FrostedBackdrop()
        val playlist = FrostedBackdrop()
        host.attach(home)
        host.attach(playlist)
        host.detach(playlist)
        assertSame(home, host.active)
        host.attach(home)
        host.detach(home)
        assertNull(host.active)
    }

    private fun recordPage(source: NativeFrostedSource, colour: Color, right: Color = colour, background: Color = colour) {
        val root = RenderNode("Test page")
        root.setPosition(0, 0, 400, 200)
        val recording = root.beginRecording()
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(recording), Size(400f, 200f)) {
                val content = object : ContentDrawScope, DrawScope by this {
                    override fun drawContent() {
                        drawRect(colour)
                        drawRect(right, Offset(200f, 0f), Size(200f, 200f))
                    }
                }
                source.record(content, background)
            }
        } finally { root.endRecording() }
    }

    private fun render(surface: NativeFrostedSurface, source: NativeFrostedSource,
        position: Offset = Offset.Zero, width: Int = 100, density: Float = 1f): Bitmap {
        val root = RenderNode("Test surface")
        root.setPosition(0, 0, width, 100)
        val recording = root.beginRecording()
        try {
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(recording), Size(width.toFloat(), 100f)) {
                surface.draw(this, source, position)
            }
        } finally { root.endRecording() }
        // Android's native display-list rasterizer is used by Robolectric for this API.
        return renderNodeBitmap(root, width, 100)
    }

    private fun assertColour(expected: Color, actual: Bitmap) {
        val pixel = actual.getPixel(actual.width / 2, actual.height / 2)
        actual.recycle()
        assertEquals(expected.red * 255, android.graphics.Color.red(pixel).toFloat(), 3f)
        assertEquals(expected.green * 255, android.graphics.Color.green(pixel).toFloat(), 3f)
        assertEquals(expected.blue * 255, android.graphics.Color.blue(pixel).toFloat(), 3f)
    }
}

internal fun renderNodeBitmap(root: RenderNode, width: Int, height: Int): Bitmap {
    val method = HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
        RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    method.isAccessible = true
    val hardware = method.invoke(null, root, width, height) as Bitmap
    return hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
}
