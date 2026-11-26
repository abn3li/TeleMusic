package com.abn3li.telemusic.ui.sync

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.abn3li.telemusic.TgMusicApp
import com.abn3li.telemusic.data.telegram.TelegramAuthState
import com.abn3li.telemusic.ui.library.DestructiveRed
import com.abn3li.telemusic.ui.library.GroupActionRow
import com.abn3li.telemusic.ui.library.GroupCard
import com.abn3li.telemusic.ui.library.GroupDivider
import com.abn3li.telemusic.ui.library.GroupFooter
import com.abn3li.telemusic.ui.library.GroupHeader
import com.abn3li.telemusic.ui.library.GroupTextField
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay

/** The login code, where it was sent, and another way to get it when Telegram offers one. */
@Composable
internal fun CodeStep(viewModel: SyncViewModel, state: TelegramAuthState.WaitingForCode, onChangeNumber: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var isResending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A code sent again starts its own wait.
    var waitSeconds by remember(state) { mutableIntStateOf(state.secondsUntilResend()) }
    LaunchedEffect(state) {
        isResending = false
        while (waitSeconds > 0) {
            delay(1_000)
            waitSeconds = state.secondsUntilResend()
        }
    }
    GroupHeader("Login Code")
    GroupCard {
        GroupTextField(value = code, onValueChange = { code = it; error = null }, placeholder = "Code",
            // Some codes are a word or a phrase.
            keyboardType = if (state.digits) KeyboardType.Number else KeyboardType.Text, enabled = !isSubmitting)
        GroupDivider()
        GroupActionRow("Verify", enabled = code.isNotBlank(), loading = isSubmitting) {
            isSubmitting = true
            error = null
            viewModel.submitCode(code.trim()) {
                error = it
                isSubmitting = false
            }
        }
    }
    error?.let { GroupFooter(it, color = DestructiveRed) }
    GroupFooter("${state.phoneNumber.ifBlank { "Code" }}: ${state.delivery}")

    GroupHeader("No code?")
    GroupCard {
        state.link?.let { link ->
            GroupActionRow("Open Fragment") {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    .onFailure { error = "Couldn't open $link" }
            }
            GroupDivider()
        }
        if (state.nextDelivery != null) {
            GroupActionRow(if (waitSeconds > 0) "Resend in ${waitSeconds}s" else "Resend Code", enabled = waitSeconds == 0, loading = isResending) {
                isResending = true
                error = null
                viewModel.resendCode {
                    error = it
                    isResending = false
                }
            }
            GroupDivider()
        }
        GroupActionRow("Change Number", enabled = !isSubmitting, onClick = onChangeNumber)
        GroupDivider()
        QrAction(viewModel) { error = it }
    }
    GroupFooter(
        if (state.nextDelivery != null) "Next way: ${state.nextDelivery}"
        else "Telegram doesn't offer another way to send this code. If it was sent inside Telegram, sign in to the Telegram app with this number first."
    )
}

/** Sign in without a code: shown on the phone-number and code steps. */
@Composable
internal fun QrAction(viewModel: SyncViewModel, enabled: Boolean = true, onError: (String) -> Unit) {
    var loading by remember { mutableStateOf(false) }
    GroupActionRow("Sign In with QR Code", enabled = enabled, loading = loading) {
        loading = true
        viewModel.requestQr { loading = false; onError(it) }
    }
}

/** The QR another signed-in phone scans. Telegram makes a new one every half minute while this
 * waits, so leaving the page (or the app) ends the wait. */
@Composable
internal fun QrStep(link: String) {
    val context = LocalContext.current
    val app = context.applicationContext as TgMusicApp
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) app.restartTelegramSignIn() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            app.restartTelegramSignIn()
        }
    }
    val matrix = remember(link) {
        runCatching { QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0)) }.getOrNull()
    }
    GroupHeader("QR Code")
    GroupCard {
        Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
            // Always dark on white: cameras need it in the dark theme too.
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(18.dp)) {
                Canvas(Modifier.size(220.dp).semantics { contentDescription = "Telegram sign-in QR code" }) {
                    val modules = matrix ?: return@Canvas
                    val side = size.width / modules.width
                    for (y in 0 until modules.height) for (x in 0 until modules.width) {
                        // A hair wider than its square, so no light lines show between them.
                        if (modules.get(x, y)) drawRect(Color.Black, Offset(x * side, y * side), Size(side + 0.5f, side + 0.5f))
                    }
                }
            }
        }
        GroupDivider()
        GroupActionRow("Use Phone Number") { app.restartTelegramSignIn() }
    }
    GroupFooter("On another phone: Telegram, Settings, Devices, Link Desktop Device, then point it here. This phone can't scan its own screen.")
}

/** Telegram asks this account for an email before it signs in. */
@Composable
internal fun EmailStep(viewModel: SyncViewModel, onChangeNumber: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GroupHeader("Email")
    GroupCard {
        GroupTextField(value = email, onValueChange = { email = it; error = null }, placeholder = "name@example.com",
            keyboardType = KeyboardType.Email, enabled = !isSubmitting)
        GroupDivider()
        GroupActionRow("Continue", enabled = "@" in email, loading = isSubmitting) {
            isSubmitting = true
            error = null
            viewModel.submitEmail(email.trim()) {
                error = it
                isSubmitting = false
            }
        }
        GroupDivider()
        GroupActionRow("Change Number", enabled = !isSubmitting, onClick = onChangeNumber)
    }
    error?.let { GroupFooter(it, color = DestructiveRed) }
        ?: GroupFooter("Telegram asks for an email to sign in to this account. It becomes your Telegram login email.")
}

@Composable
internal fun EmailCodeStep(viewModel: SyncViewModel, address: String, onChangeNumber: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GroupHeader("Email Code")
    GroupCard {
        GroupTextField(value = code, onValueChange = { code = it; error = null }, placeholder = "Code",
            keyboardType = KeyboardType.Number, enabled = !isSubmitting)
        GroupDivider()
        GroupActionRow("Verify", enabled = code.isNotBlank(), loading = isSubmitting) {
            isSubmitting = true
            error = null
            viewModel.submitEmailCode(code.trim()) {
                error = it
                isSubmitting = false
            }
        }
        GroupDivider()
        GroupActionRow("Change Number", enabled = !isSubmitting, onClick = onChangeNumber)
    }
    error?.let { GroupFooter(it, color = DestructiveRed) } ?: GroupFooter(
        "Sent to ${address.ifBlank { "your Telegram login email" }}. Check Spam too. If you can't open this email, reset it from the Telegram app.")
}

/** A step only the Telegram app can do: says so, instead of waiting without a word. */
@Composable
internal fun UnsupportedStep(message: String, onChangeNumber: () -> Unit) {
    GroupHeader("Telegram")
    GroupCard { GroupActionRow("Change Number", onClick = onChangeNumber) }
    GroupFooter(message, color = DestructiveRed)
}
