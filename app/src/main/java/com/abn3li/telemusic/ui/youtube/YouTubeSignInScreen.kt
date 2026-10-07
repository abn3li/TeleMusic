package com.abn3li.telemusic.ui.youtube

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.youtube.YouTubeLoginPolicy
import com.abn3li.telemusic.ui.library.*
import com.abn3li.telemusic.ui.theme.ink
import com.abn3li.telemusic.ui.theme.paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/** Google renders its own login; only YouTube Music's finished session is read on confirmation. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeSignInScreen(onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as TgMusicApp
    val scope = rememberCoroutineScope()
    val currentOnDone by rememberUpdatedState(onDone)
    var browser by remember { mutableStateOf<WebView?>(null) }
    var musicPage by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }

    fun confirmSession() {
        val view = browser ?: return
        if (saving || !YouTubeLoginPolicy.isMusicPage(view.url)) return
        saving = true
        // The probe runs only on the exact HTTPS Music origin, never on a password page.
        // No JavascriptInterface is exposed to pages or subframes.
        view.evaluateJavascript("""
            (function() {
                if (location.origin !== 'https://music.youtube.com') return null;
                const config = window.ytcfg && window.ytcfg.data_ || window.yt && window.yt.config_ || {};
                return JSON.stringify({loggedIn: config.LOGGED_IN === true,
                    authUser: String(config.SESSION_INDEX || 0)});
            })();
        """.trimIndent()) { encoded ->
            if (!YouTubeLoginPolicy.isMusicPage(view.url)) { saving = false; return@evaluateJavascript }
            val page = runCatching {
                val json = JSONTokener(encoded).nextValue() as? String ?: return@runCatching null
                JSONObject(json)
            }.getOrNull()
            val cookies = CookieManager.getInstance().getCookie(YouTubeLoginPolicy.MUSIC_URL).orEmpty()
            if (page?.optBoolean("loggedIn") != true || YouTubeLoginPolicy.signingSecret(cookies) == null) {
                saving = false
                error = "Finish signing in on YouTube Music, then tap Done again."
                return@evaluateJavascript
            }
            CookieManager.getInstance().flush()
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) {
                        app.youtubeAccount.signIn(cookies, page.optString("authUser", "0"))
                    }
                    if (ok) currentOnDone() else error = "The YouTube Music session isn't ready yet."
                } catch (e: Exception) { error = "Couldn't save the session. Try again." }
                finally { saving = false }
            }
        }
    }

    if (pasteOpen) AppAlert(
        title = "Sign In with Cookies",
        message = "Sign in at music.youtube.com in your computer's browser. In developer tools (F12) > Network, select a request to music.youtube.com and copy its Cookie header here.",
        onDismiss = { if (!saving) pasteOpen = false },
        actions = listOf(
            AlertAction("Cancel", enabled = !saving) { pasteOpen = false; pasted = "" },
            AlertAction("Sign In", bold = true, enabled = pasted.isNotBlank() && !saving) {
                val cookies = pasted.trim().replace(Regex("""^Cookie:\s*""", RegexOption.IGNORE_CASE), "")
                saving = true
                scope.launch {
                    try {
                        val ok = withContext(Dispatchers.IO) { app.youtubeAccount.signIn(cookies) }
                        if (ok) { pasted = ""; pasteOpen = false; currentOnDone() }
                        else pasteError = "Copy the complete Cookie header from a signed-in YouTube Music request."
                    } catch (e: Exception) { pasteError = "Couldn't save the session. Try again." }
                    finally { saving = false }
                }
            }
        )
    ) {
        AlertTextField(pasted, { pasted = it; pasteError = null }, "Cookie header")
        pasteError?.let { AlertNote(it) }
    }

    BackHandler(enabled = canGoBack && !saving) { browser?.goBack() }
    Column(Modifier.fillMaxSize().background(paper)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cancel", color = AppAccent, fontSize = 17.sp,
                modifier = Modifier.clickable(enabled = !saving) { currentOnDone() })
            Text("YouTube Music", color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
            Text("Done", color = if (musicPage && !saving) AppAccent else ink.copy(alpha = 0.4f), fontSize = 17.sp,
                modifier = Modifier.clickable(enabled = musicPage && !saving) { confirmSession() })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Retry", color = AppAccent, modifier = Modifier.clickable(enabled = !saving) {
                blocked = false; error = null; musicPage = false
                browser?.loadUrl(YouTubeLoginPolicy.SIGN_IN_URL)
            })
            Text("Use cookies", color = AppAccent, modifier = Modifier.clickable(enabled = !saving) {
                pasteError = null; pasteOpen = true
            })
        }
        if (blocked) Text("Google blocked this in-app sign-in. Try Retry; if it still refuses, use cookies from your browser.",
            color = ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        error?.let { Text(it, color = ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        Box(Modifier.weight(1f)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // Retain the device's actual WebView identity, like the reference clients.
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.scheme == "https") return false
                            if (uri.scheme == "intent") {
                                // A YouTube app/store deep link cannot carry cookies back into TeleMusic.
                                // Keep the Music webpage open for session confirmation instead.
                                if (!YouTubeLoginPolicy.isMusicPage(view.url)) view.loadUrl(YouTubeLoginPolicy.MUSIC_URL)
                                return true
                            }
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            musicPage = YouTubeLoginPolicy.isMusicPage(url)
                            canGoBack = view.canGoBack()
                            if (YouTubeLoginPolicy.isGoogleRejection(url)) blocked = true
                        }
                    }
                    browser = this
                    loadUrl(YouTubeLoginPolicy.SIGN_IN_URL)
                }
            }, onRelease = {
                browser = null
                it.stopLoading()
                it.destroy()
            })
            if (saving) Box(Modifier.fillMaxSize().background(paper.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                CalmSpinner(color = AppAccent)
            }
        }
    }
}
