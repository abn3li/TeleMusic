package com.abn3li.telemusic.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.abn3li.telemusic.R
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.ui.library.AppAccent
import com.abn3li.telemusic.ui.library.GroupCardColor
import com.abn3li.telemusic.ui.library.GroupLabelColor
import com.abn3li.telemusic.ui.library.LibraryTileColor
import com.abn3li.telemusic.ui.settings.readableFolderName

private const val STEP_WELCOME = 0
private const val STEP_FEATURES = 1
private const val STEP_NOTIFICATIONS = 2
private const val STEP_DOWNLOADS = 3

private val Hairline = Color.White.copy(alpha = 0.12f)
private val OnGreen = Color(0xFF30D158)
private val MixDaily = Color(0xFF72243E)

/**
 * The hello screens shown once, on the very first start: Welcome, what the app does,
 * notifications (asked here with a reason, instead of a bare pop-up at launch) and where
 * downloads go. Telegram isn't set up here - that stays in the Sync tab for whoever uses it.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    // Android 12 and older allow notifications without asking, and a granted one needs no page.
    val needsNotifications = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
    // The pages this phone shows, in order; back and next just move along this list.
    val steps = remember {
        listOfNotNull(STEP_WELCOME, STEP_FEATURES, STEP_NOTIFICATIONS.takeIf { needsNotifications }, STEP_DOWNLOADS)
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val next = { index = (index + 1).coerceAtMost(steps.lastIndex) }
    val back = { index = (index - 1).coerceAtLeast(0) }

    fun finish() {
        app.settingsStore.onboardingDone = true
        onFinished()
    }

    BackHandler(enabled = index > 0, onBack = back)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (steps[index]) {
            STEP_WELCOME -> WelcomeStep(onNext = next)
            STEP_FEATURES -> FeaturesStep(onBack = back, onNext = next)
            STEP_NOTIFICATIONS -> NotificationsStep(onBack = back, onNext = next)
            else -> DownloadsStep(onBack = back, onDone = ::finish)
        }
    }
}

// ---- Steps ----

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset((-90).dp, (-110).dp)
                .size(340.dp)
                .background(Brush.radialGradient(listOf(AppAccent.copy(alpha = 0.34f), Color.Transparent)))
        )
        StepLayout(
            onBack = null,
            bottom = {
                PrimaryButton("Get Started", onNext)
                Text(
                    "Telegram needs a quick setup. You can do it any time in the Sync tab.",
                    color = GroupLabelColor,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                )
            }
        ) {
            AppIcon()
            Spacer(Modifier.height(18.dp))
            StepTitle("Welcome to TeleMusic", "Your music from Telegram, YouTube and Spotify, together in one player.")
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GroupCardColor)) {
                SourceRow(Icons.AutoMirrored.Rounded.Send, "Telegram", "Play songs from your channels and chats")
                RowDivider()
                SourceRow(Icons.Rounded.Subscriptions, "YouTube Music", "Stream or download any song")
                RowDivider()
                SourceRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Spotify playlists", "Bring your playlists and Liked Songs")
                RowDivider()
                SourceRow(Icons.Rounded.Folder, "On this phone", "Add your own music files")
            }
        }
    }
}

@Composable
private fun FeaturesStep(onBack: () -> Unit, onNext: () -> Unit) {
    StepLayout(onBack = onBack, bottom = { PrimaryButton("Continue", onNext) }) {
        StepTitle("Everything in one player", "Made to feel good every time you press play.")
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureRow(
                Triple(Icons.Rounded.Lyrics, "Synced lyrics", "Sing along, line by line"),
                Triple(Icons.Rounded.Download, "Play offline", "Download songs and whole playlists")
            )
            FeatureRow(
                Triple(Icons.Rounded.AutoAwesome, "Made for You", "Daily mixes from what you play"),
                Triple(Icons.Rounded.DirectionsCar, "In the car", "Android Auto, Bluetooth and lock screen")
            )
            FeatureRow(
                Triple(Icons.Rounded.Widgets, "Widgets", "Control music from your home screen"),
                Triple(Icons.Rounded.Lock, "Private", "No sign-up. Your music stays on your phone")
            )
        }
    }
}

@Composable
private fun NotificationsStep(onBack: () -> Unit, onNext: () -> Unit) {
    var allowed by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A "Don't allow" just moves on - asking again here would only nag.
        if (granted) allowed = true else onNext()
    }
    StepLayout(
        onBack = onBack,
        bottom = {
            if (allowed) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = OnGreen, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Notifications are on", color = OnGreen, fontSize = 15.sp)
                }
                PrimaryButton("Continue", onNext)
            } else {
                // Only shown on Android 13+, where notifications need asking (see needsNotifications).
                PrimaryButton("Allow Notifications") { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                LinkButton("Not now", onNext)
            }
        }
    ) {
        Spacer(Modifier.height(12.dp))
        Box {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(width = 320.dp, height = 220.dp)
                    .background(Brush.radialGradient(listOf(AppAccent.copy(alpha = 0.26f), Color.Transparent)))
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerNotificationPreview()
                DownloadNotificationPreview()
            }
        }
        Spacer(Modifier.height(34.dp))
        StepTitle(
            "Turn on notifications",
            "See what's playing, control your music from the lock screen, and follow downloads, Telegram syncs and Spotify imports."
        )
    }
}

@Composable
private fun DownloadsStep(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val settings = app.settingsStore
    // Starts on what's already chosen, if anything; else on a folder.
    var useFolder by rememberSaveable { mutableStateOf(!(settings.downloadLocationChosen && settings.downloadFolderUri == null)) }
    var folder by rememberSaveable { mutableStateOf(settings.downloadFolderUri) }
    var finishAfterPick by rememberSaveable { mutableStateOf(false) }

    // A folder picked here but not kept gives back the access Android granted for it.
    fun releaseIfUnsaved(uri: String?) {
        if (uri == null || uri == settings.downloadFolderUri) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    fun save() {
        if (useFolder) {
            settings.downloadFolderUri = folder ?: return
        } else {
            releaseIfUnsaved(folder)
            settings.downloadFolderUri = null
        }
        settings.downloadLocationChosen = true
        onDone()
    }

    fun decideLater() {
        releaseIfUnsaved(folder)
        onDone()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            if (treeUri.toString() != folder) releaseIfUnsaved(folder)
            folder = treeUri.toString()
            if (finishAfterPick) save()
        }
        finishAfterPick = false
    }

    StepLayout(
        onBack = onBack,
        bottom = {
            PrimaryButton("Start Listening") {
                if (useFolder && folder == null) {
                    finishAfterPick = true
                    folderPicker.launch(null)
                } else {
                    save()
                }
            }
            // Leaves the choice open: the first download asks, like before.
            LinkButton("Decide later", ::decideLater)
        }
    ) {
        StepTitle("Where should downloads go?", "Songs you download play without internet. You can change this later in Settings.")
        Spacer(Modifier.height(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceCard(
                icon = Icons.Rounded.Folder,
                title = "A folder on your phone",
                desc = "Your file manager and other music apps can see the songs",
                selected = useFolder,
                onClick = {
                    useFolder = true
                    if (folder == null) folderPicker.launch(null)
                }
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF2C2C2E))
                        .clickable { folderPicker.launch(null) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = GroupLabelColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        folder?.let { readableFolderName(Uri.parse(it)) } ?: "No folder chosen yet",
                        color = if (folder != null) Color.White else GroupLabelColor,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (folder != null) "Change" else "Choose", color = AppAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            ChoiceCard(
                icon = Icons.Rounded.Lock,
                title = "Inside TeleMusic",
                desc = "Private to the app, removed if you uninstall it",
                selected = !useFolder,
                onClick = { useFolder = false }
            )
        }
        Text(
            "No storage permission needed: TeleMusic can only use the folder you pick.",
            color = GroupLabelColor,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp)
        )
    }
}

// ---- Building blocks ----

/** A step's page: optional back arrow, scrolling content, and buttons pinned to the bottom. */
@Composable
private fun StepLayout(
    onBack: (() -> Unit)?,
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp)) {
        if (onBack != null) {
            Box(
                Modifier.offset(x = (-10).dp).size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, contentDescription = "Back", tint = AppAccent, modifier = Modifier.size(22.dp))
            }
        } else {
            Spacer(Modifier.height(44.dp))
        }
        // Each page fits on one screen and stays still; it only scrolls on a phone too short for it.
        val scroll = rememberScrollState()
        Column(
            Modifier.weight(1f).verticalScroll(scroll, enabled = scroll.maxValue > 0).padding(top = 8.dp, bottom = 16.dp),
            content = content
        )
        bottom()
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Text(title, color = Color.White, fontSize = 35.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
    Text(subtitle, color = GroupLabelColor, fontSize = 17.sp, lineHeight = 23.sp)
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppAccent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LinkButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp).height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = AppAccent, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** The launcher icon's own artwork. Its drawable carries the adaptive-icon margin (the artwork
 * is the middle 2/3), so it's drawn larger than its slot to line the artwork up with the text. */
@Composable
private fun AppIcon() {
    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(114.dp)
        )
    }
}

/** A white rounded tile with a pink icon, like the Home screen's shortcut tiles. */
@Composable
private fun IconTile(icon: ImageVector, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(LibraryTileColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = AppAccent, modifier = Modifier.size(size * 0.54f))
    }
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, desc: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = GroupLabelColor, fontSize = 13.5.sp)
        }
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 71.dp).height(0.5.dp).background(Hairline))
}

@Composable
private fun FeatureRow(left: Triple<ImageVector, String, String>, right: Triple<ImageVector, String, String>) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FeatureTile(left, Modifier.weight(1f))
        FeatureTile(right, Modifier.weight(1f))
    }
}

@Composable
private fun FeatureTile(feature: Triple<ImageVector, String, String>, modifier: Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .heightIn(min = 124.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GroupCardColor)
            .padding(14.dp)
    ) {
        IconTile(feature.first, size = 38.dp)
        Spacer(Modifier.height(10.dp))
        Text(feature.second, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(feature.third, color = GroupLabelColor, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    extra: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GroupCardColor)
            .border(BorderStroke(2.dp, if (selected) AppAccent else Color.Transparent), shape)
            .clickable(onClick = onClick)
            .padding(start = 13.dp, end = 15.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
                Text(desc, color = GroupLabelColor, fontSize = 13.5.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            if (selected) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(AppAccent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(17.dp))
                }
            } else {
                Box(Modifier.size(22.dp).border(2.dp, Color(0xFF48484A), CircleShape))
            }
        }
        if (selected && extra != null) extra()
    }
}

/** What the playback notification looks like - a picture, nothing in it is tappable. */
@Composable
private fun PlayerNotificationPreview() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GroupCardColor)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(27.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("TeleMusic · now", color = GroupLabelColor, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(MixDaily), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("Song title", color = Color.White, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
                Text("Artist", color = GroupLabelColor, fontSize = 14.sp)
            }
            Icon(Icons.Rounded.SkipPrevious, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.SkipNext, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        ProgressBar(0.38f, Color.White)
    }
}

@Composable
private fun DownloadNotificationPreview() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(GroupCardColor.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(Icons.Rounded.Download, size = 36.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("Downloading 12 of 40 songs", color = Color.White, fontSize = 14.5.sp)
            Spacer(Modifier.height(6.dp))
            ProgressBar(0.3f, AppAccent)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
    }
}
