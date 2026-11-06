package com.abn3li.telemusic.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

private const val MAX_SHOWN_CHARS = 20_000
private const val MAX_COPIED_CHARS = 60_000
private val Accent = Color.rgb(0xE6, 0x43, 0x66)

/** Provider of the log file. It lives in the crash process, so sharing never starts the app. */
class CrashLogProvider : FileProvider()

/**
 * Shows a crash and lets the person send it. It runs in its own process and uses plain Android
 * views, with nothing from the app, so it still opens when the app can't.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.clearPending(this)
        val file = CrashReporter.reportFile(this)
        val report = runCatching { file.readText() }.getOrDefault("No log was saved.")

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        root.addView(TextView(this).apply {
            text = "TeleMusic crashed"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Send this log to the developer so it can be fixed."
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 15f
            setPadding(0, dp(6), 0, dp(14))
        })
        root.addView(ScrollView(this).apply {
            addView(TextView(this@CrashActivity).apply {
                text = report.take(MAX_SHOWN_CHARS)
                setTextColor(Color.argb(190, 255, 255, 255))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(if (filled) Accent else Color.rgb(0x2C, 0x2C, 0x2E))
            setOnClickListener(View.OnClickListener { onClick() })
        }
        val share = button("Share log", filled = true) {
            val uri = FileProvider.getUriForFile(this, "$packageName.crashlog", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "TeleMusic crash log")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share log"))
        }
        val copy = button("Copy", filled = false) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("TeleMusic crash log", report.take(MAX_COPIED_CHARS)))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        buttons.addView(share, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        buttons.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        setContentView(root)
    }
}
