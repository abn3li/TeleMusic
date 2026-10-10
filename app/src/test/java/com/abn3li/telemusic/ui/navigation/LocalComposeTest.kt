package com.abn3li.telemusic.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/** A JVM-only host: no test Activity or manifest entry is added to either APK variant. */
abstract class LocalComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    protected val activity: ComponentActivity get() = controller.get()

    @Before fun createLocalActivity() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @After fun destroyLocalActivity() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        for (index in 0 until content.childCount) {
            (content.getChildAt(index) as? ComposeView)?.disposeComposition()
        }
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    protected fun setContent(content: @Composable () -> Unit) {
        activity.setContent(content = content)
        compose.waitForIdle()
    }
}
