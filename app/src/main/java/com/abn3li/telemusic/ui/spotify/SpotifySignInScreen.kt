package com.abn3li.telemusic.ui.spotify

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.spotify.SpotifyWebCookies
import com.abn3li.telemusic.ui.library.AlertAction
import com.abn3li.telemusic.ui.library.AlertTextField
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.AppAlert
import com.abn3li.telemusic.ui.theme.ink
import com.abn3li.telemusic.ui.theme.paper

private const val LOGIN_URL = "https://accounts.spotify.com/en/login"

/**
 * Spotify's own login page, inside the app. Once you're signed in, Spotify has set its session
 * cookie (`sp_dc`), which is handed to [com.abn3li.telemusic.data.spotify.SpotifyAccount] and the
 * page closes. The cookie can also be pasted, for accounts that sign in with Google, which
 * doesn't allow signing in inside an app.
 */
@Composable
fun SpotifySignInScreen(onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val currentOnDone by rememberUpdatedState(onDone)
    var browser by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    // A cookie Spotify turned down isn't tried again: the page reports it on every load, and
    // trying it each time made the page flash between "Signing in" and the error.
    var refused by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Stays on this page until Spotify has accepted the sign-in, then opens your library.
    fun finishWith(cookie: String) {
        if (done || cookie == refused) return
        done = true
        CookieManager.getInstance().flush()
        scope.launch {
            try {
                app.spotifyAccount.signInWithWebCookie(cookie)
                app.spotifyAccount.requestOpenLibrary()
                currentOnDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                done = false
                refused = cookie
                failed = e.message ?: "Spotify didn't accept the sign-in"
            }
        }
    }

    BackHandler { if (browser?.canGoBack() == true) browser?.goBack() else currentOnDone() }

    Column(Modifier.fillMaxSize().background(paper).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
            Text("Cancel", color = AppAccent, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.CenterStart).clickable { currentOnDone() })
            Text("Sign In to Spotify", color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center))
            Text("Paste", color = AppAccent, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.CenterEnd).clickable { pasting = true })
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    // Fill the space, not fit the page: a web view sized to its content tells the page
                    // its screen is 0 tall, and Spotify sizes its sign-in form by the screen.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    val cookies = CookieManager.getInstance()
                    cookies.setAcceptCookie(true)
                    // The login page loads its form and its checks from other Spotify sites,
                    // which need their own cookies.
                    cookies.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            loading = newProgress < 100
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            SpotifyWebCookies.session()?.let(::finishWith)
                        }
                        // A single-page sign-in can finish without loading a new page.
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            SpotifyWebCookies.session()?.let(::finishWith)
                        }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) failed = error.description?.toString() ?: "Couldn't reach Spotify"
                        }
                        // A crashed page must not take the app down with it.
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            failed = "The page stopped - try again"
                            return true
                        }
                    }
                    browser = this
                    // A fresh login every time: a session left in the web view (an earlier sign-in,
                    // one signed out of) would otherwise be taken as this one.
                    SpotifyWebCookies.clear { loadUrl(LOGIN_URL) }
                }
            }, onRelease = {
                browser = null
                it.stopLoading()
                it.destroy()
            })
            if (done && failed == null) Column(
                Modifier.fillMaxSize().background(paper),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = AppAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                Text("Signing in…", color = ink.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp))
            } else if (loading && failed == null) CircularProgressIndicator(
                color = AppAccent, strokeWidth = 2.5.dp,
                modifier = Modifier.align(Alignment.Center).size(28.dp)
            )
            failed?.let { reason ->
                Column(
                    Modifier.fillMaxSize().background(paper).padding(32.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(reason, color = ink.copy(alpha = 0.7f), fontSize = 15.sp)
                    Text("Try Again", color = AppAccent, fontSize = 17.sp,
                        modifier = Modifier.padding(top = 12.dp).clickable {
                            failed = null
                            // Starts over: the turned-down session goes, so a new sign-in can set its own.
                            SpotifyWebCookies.clear { refused = null; browser?.loadUrl(LOGIN_URL) }
                        })
                }
            }
        }
    }

    if (pasting) AppAlert(
        title = "Paste Sign-In Cookie",
        message = "In a browser where you're signed in to open.spotify.com, copy the value of the sp_dc cookie and paste it here. It works like a password: keep it to yourself.",
        onDismiss = { pasting = false },
        actions = listOf(
            AlertAction("Cancel") { pasting = false },
            AlertAction("Sign In", bold = true, enabled = cookieValue(pasted) != null) {
                pasting = false
                cookieValue(pasted)?.let(::finishWith)
            }
        )
    ) {
        AlertTextField(pasted, { pasted = it }, "sp_dc")
    }
}

/** The cookie's value from what was pasted: the value alone, or `sp_dc=value`. */
private fun cookieValue(text: String): String? = text.trim()
    .substringAfter("sp_dc=", text.trim())
    .substringBefore(';')
    .trim()
    .takeIf { it.length >= 40 && it.none(Char::isWhitespace) }
