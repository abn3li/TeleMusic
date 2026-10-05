package com.abn3li.telemusic.ui.settings

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.theme.LocalPalette

/** Legal documents are loaded only while this sheet is open, from the APK. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicensesSheet(onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val asset = "file:///android_asset/licenses/${if (palette.isLight) "index" else "dark"}.html"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.background,
        contentColor = palette.ink
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Licenses", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Done", Modifier.clickable(onClick = onDismiss).padding(start = 16.dp, bottom = 8.dp), color = AppAccent, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(color = palette.hairline)
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.url.scheme == "https") {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                }
                                return true
                            }
                        }
                        tag = asset
                        loadUrl(asset)
                    }
                },
                update = { view ->
                    if (view.tag != asset) {
                        view.tag = asset
                        view.loadUrl(asset)
                    }
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                }
            )
        }
    }
}
