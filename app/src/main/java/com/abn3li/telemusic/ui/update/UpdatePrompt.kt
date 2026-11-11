package com.abn3li.telemusic.ui.update

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abn3li.telemusic.R
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.update.UpdateCheckResult
import com.abn3li.telemusic.data.update.UpdateChecker
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.theme.LocalPalette
import com.abn3li.telemusic.ui.theme.ink
import kotlinx.coroutines.delay

private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
private const val SHOW_AFTER_START_MS = 1_800L

/**
 * Looks for a newer release once when the app opens - at most once a day - and shows
 * [UpdateDialog] over the home screen when there is one. Nothing runs after that.
 */
@Composable
fun StartupUpdatePrompt() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var update by remember { mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null) }
    val current = remember { installedVersion(context) }

    LaunchedEffect(Unit) {
        val app = context.applicationContext as TgMusicApp
        if (app.needsOnboarding()) return@LaunchedEffect
        val prefs = context.getSharedPreferences("update_prompt", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) return@LaunchedEffect
        val result = UpdateChecker().check(current)
        if (result is UpdateCheckResult.Error) return@LaunchedEffect
        prefs.edit().putLong("last_check", now).apply()
        if (result is UpdateCheckResult.UpdateAvailable) {
            // Let the loading screen finish first, so the card lands on the home screen.
            val sinceStart = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
            if (sinceStart < SHOW_AFTER_START_MS) delay(SHOW_AFTER_START_MS - sinceStart)
            update = result
        }
    }

    update?.let { found ->
        UpdateDialog(
            update = found,
            currentVersion = current,
            onUpdate = { uriHandler.openUri(found.releaseUrl); update = null },
            onLater = { update = null }
        )
    }
}

internal fun installedVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"

/** The update card: the app's icon, the new version, its notes (scrolling when long) and two buttons that never move. */
@Composable
fun UpdateDialog(
    update: UpdateCheckResult.UpdateAvailable,
    currentVersion: String,
    onUpdate: () -> Unit,
    onLater: () -> Unit
) {
    val palette = LocalPalette.current
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    val notes = remember(update.notes) { parseReleaseNotes(update.notes) }
    val shape = RoundedCornerShape(18.dp)

    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(300.dp)
                .heightIn(max = maxHeight)
                .clip(shape)
                .background(palette.alert)
                .border(0.5.dp, ink.copy(alpha = 0.12f), shape)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // The drawable keeps the adaptive-icon margin (artwork is the middle 2/3).
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_tile), contentDescription = null, modifier = Modifier.requiredSize(96.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("New Version Available", color = ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Version ${update.version} · You have $currentVersion",
                    color = ink.copy(alpha = 0.55f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            if (notes.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(ink.copy(alpha = 0.08f)))
                val scroll = rememberScrollState()
                val fadeColor = palette.alert
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .drawWithContent {
                            drawContent()
                            if (scroll.canScrollForward) {
                                val fade = 28.dp.toPx()
                                drawRect(
                                    Brush.verticalGradient(listOf(Color.Transparent, fadeColor), startY = size.height - fade, endY = size.height),
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - fade)
                                )
                            }
                        }
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (notes.first() !is NoteLine.Heading) NoteHeading("WHAT'S NEW")
                    notes.forEach { line ->
                        when (line) {
                            is NoteLine.Heading -> NoteHeading(line.text.uppercase())
                            is NoteLine.Bullet -> Row {
                                Text("•", color = AppAccent, fontSize = 13.5.sp, lineHeight = 20.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(line.text, color = ink.copy(alpha = 0.85f), fontSize = 13.5.sp, lineHeight = 20.sp)
                            }
                            is NoteLine.Text -> Text(line.text, color = ink.copy(alpha = 0.85f), fontSize = 13.5.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).background(AppAccent).clickable(onClick = onUpdate),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Download Update", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onLater),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Later", color = ink.copy(alpha = 0.7f), fontSize = 14.5.sp)
                }
            }
        }
    }
}

@Composable
private fun NoteHeading(text: String) {
    Text(
        text,
        color = ink.copy(alpha = 0.45f),
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 3.dp)
    )
}

private sealed interface NoteLine {
    val text: String
    data class Heading(override val text: String) : NoteLine
    data class Bullet(override val text: String) : NoteLine
    data class Text(override val text: String) : NoteLine
}

/** GitHub release notes are Markdown: "-"/"*" lines become bullets, "#" lines small headings, and the marks are dropped. */
private fun parseReleaseNotes(notes: String?): List<NoteLine> {
    if (notes.isNullOrBlank()) return emptyList()
    return notes.lines().mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() || line.matches(Regex("^[-*_]{3,}$")) -> null
            line.startsWith("#") -> NoteLine.Heading(cleanMarkdown(line.trimStart('#')))
            line.matches(Regex("^([-*+]|\\d+[.)])\\s+.*")) -> NoteLine.Bullet(cleanMarkdown(line.replaceFirst(Regex("^([-*+]|\\d+[.)])\\s+"), "")))
            else -> NoteLine.Text(cleanMarkdown(line))
        }
    }.filter { it.text.isNotBlank() }
}

private fun cleanMarkdown(text: String): String = text
    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace("**", "").replace("__", "").replace("`", "")
    .trim()
