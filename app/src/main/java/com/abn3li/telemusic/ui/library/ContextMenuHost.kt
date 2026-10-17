package com.abn3li.telemusic.ui.library

import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

private class MenuWindowRequest(
    val owner: Any,
    val locals: CompositionLocalContext,
    val dismiss: () -> Unit,
    val content: @Composable () -> Unit
)

private class MenuWindowState {
    var request by mutableStateOf<MenuWindowRequest?>(null)
    fun release(owner: Any) { if (request?.owner === owner) request = null }
}

private val LocalMenuWindow = staticCompositionLocalOf<MenuWindowState?> { null }

/** Keep one native window; hidden windows neither intercept input nor retain menu content. */
@Composable
internal fun ContextMenuHost(content: @Composable () -> Unit) {
    val state = remember { MenuWindowState() }
    CompositionLocalProvider(LocalMenuWindow provides state) {
        content()
        SharedMenuWindow(state)
    }
}

@Composable
private fun SharedMenuWindow(state: MenuWindowState) {
    val request = state.request
    val active = request != null
    Popup(popupPositionProvider = WindowOriginPosition,
        onDismissRequest = { state.request?.dismiss?.invoke() },
        properties = PopupProperties(focusable = active, dismissOnBackPress = active,
            dismissOnClickOutside = active, excludeFromSystemGesture = active)) {
        val view = LocalView.current
        val focus = LocalFocusManager.current
        val window = LocalWindowInfo.current
        val input = LocalTextInputService.current
        val keyboard = LocalSoftwareKeyboardController.current
        val toolbar = LocalTextToolbar.current
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(view, active) {
            while (!view.isAttachedToWindow) withFrameNanos { }
            val root = generateSequence(view as View?) { it.parent as? View }
                .firstOrNull { it.layoutParams is WindowManager.LayoutParams } ?: return@LaunchedEffect
            val params = root.layoutParams as WindowManager.LayoutParams
            params.alpha = if (active) 1f else 0f
            params.flags = if (active) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else (params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            view.context.getSystemService(WindowManager::class.java).updateViewLayout(root, params)
        }
        Box(Modifier.fillMaxSize()) {
            if (request != null) key(request.owner) {
                // Preserve the originating page's palette, editing actions and player insets.
                CompositionLocalProvider(request.locals) {
                    // Page-specific locals move with the content; native input still belongs
                    // to the popup's Compose view rather than the originating activity view.
                    CompositionLocalProvider(LocalView provides view, LocalFocusManager provides focus,
                        LocalWindowInfo provides window, LocalTextInputService provides input,
                        LocalSoftwareKeyboardController provides keyboard, LocalTextToolbar provides toolbar,
                        LocalInputModeManager provides inputMode) { request.content() }
                }
            }
        }
    }
}

@Composable
internal fun ContextMenuWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val state = LocalMenuWindow.current
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
        state.request = MenuWindowRequest(owner, locals, { latestDismiss() }, { latestContent() })
    }
    DisposableEffect(state, owner) { onDispose { state.release(owner) } }
}
