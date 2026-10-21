package com.abn3li.telemusic.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private class MenuRequest(
    val owner: Any,
    val locals: CompositionLocalContext,
    val dismiss: () -> Unit,
    val content: @Composable () -> Unit
)

private class MenuHostState {
    var request by mutableStateOf<MenuRequest?>(null)
    fun release(owner: Any) { if (request?.owner === owner) request = null }
}

private val LocalMenuHost = staticCompositionLocalOf<MenuHostState?> { null }

/** Long-press menus draw over the app inside its own window. A Popup window had to be built on
 * the first long-press (a slow first open), and while one exists Compose checks where it is on
 * screen every frame - ~120 wakeups a second with no menu up. */
@Composable
internal fun ContextMenuHost(content: @Composable () -> Unit) {
    val state = remember { MenuHostState() }
    CompositionLocalProvider(LocalMenuHost provides state) {
        content()
        MenuLayer(state)
    }
}

@Composable
private fun MenuLayer(state: MenuHostState) {
    val request = state.request ?: return
    BackHandler { request.dismiss() }
    // The menu covers the page: put away a keyboard left up by a search field under it.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(request.owner) { keyboard?.hide() }
    Box(Modifier.fillMaxSize()) {
        key(request.owner) {
            // Keep the originating page's palette, editing actions and player insets.
            CompositionLocalProvider(request.locals) { request.content() }
        }
    }
}

@Composable
internal fun ContextMenuLayer(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val state = LocalMenuHost.current
    if (state == null) {
        Popup(popupPositionProvider = WindowOriginPosition, onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true), content = content)
        return
    }
    val owner = remember { Any() }
    val locals = currentCompositionLocalContext
    val latestContent by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    SideEffect {
        state.request = MenuRequest(owner, locals, { latestDismiss() }, { latestContent() })
    }
    DisposableEffect(state, owner) { onDispose { state.release(owner) } }
}
