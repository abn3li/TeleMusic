package com.abn3li.telemusic.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Saves what happened when the app dies, so a phone without a computer can still send it.
 * Java and Kotlin crashes are caught as they happen. Native crashes and freezes can't be caught
 * from inside, so Android's own record of them is read on the next start. Nothing here runs
 * until the app dies, apart from that one look at the record on start.
 */
internal object CrashReporter {
    private const val DIR = "crash"
    private const val REPORT = "telemusic-crash-log.txt"
    private const val PENDING = "pending"
    private const val PREFS = "crash_reporter"
    private const val LAST_EXIT = "last_exit"
    private const val CRASH_PROCESS = ":crash"
    private const val MAX_TRACE_BYTES = 512 * 1024
    private const val LOG_LINES = "1500"
    private const val LOG_WAIT_MS = 2_000L

    fun reportFile(context: Context) = File(File(context.cacheDir, DIR), REPORT)

    /** [CrashActivity]'s own process, which must not start the app's players and database. */
    fun isCrashProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readText().trimEnd('\u0000').endsWith(CRASH_PROCESS)
    }.getOrDefault(false)

    fun install(app: Application) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                // The crash first: if the log can't be read, this much is already saved.
                val report = header(app, "Crash") + "Thread: ${thread.name}\n\n${error.stackTraceToString()}\n"
                save(app, report)
                reportFile(app).appendText(logSection(Process.myPid()))
                showPending(app)
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
        // A native crash or a freeze from the last run, if Android recorded a new one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) thread(name = "last-exit") {
            runCatching { readLastExit(app) }
            showPending(app)
        }
    }

    /** Opens the report if one is waiting - a crash in the background can't open it itself. */
    fun showPending(context: Context) {
        if (!File(File(context.cacheDir, DIR), PENDING).exists()) return
        runCatching {
            context.startActivity(Intent(context, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun clearPending(context: Context) {
        File(File(context.cacheDir, DIR), PENDING).delete()
    }

    private fun save(context: Context, report: String) {
        val file = reportFile(context)
        file.parentFile?.mkdirs()
        file.writeText(report)
        File(file.parentFile, PENDING).createNewFile()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLastExit(context: Context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(LAST_EXIT, 0L)
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8).firstOrNull {
            it.timestamp > seen && (it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR)
        } ?: return
        prefs.edit().putLong(LAST_EXIT, exit.timestamp).apply()
        val freeze = exit.reason == ApplicationExitInfo.REASON_ANR
        val report = buildString {
            append(header(context, if (freeze) "Freeze (app not responding)" else "Native crash"))
            append("Happened: ${timeText(exit.timestamp)}\n")
            append("Process: ${exit.processName}, status ${exit.status}\n")
            append("Details: ${exit.description.orEmpty()}\n\n")
            append(traceText(exit, freeze))
            append(logSection(exit.pid))
        }
        save(context, report)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun traceText(exit: ApplicationExitInfo, freeze: Boolean): String {
        val bytes = exit.traceInputStream?.use { it.readNBytesCompat(MAX_TRACE_BYTES) } ?: return ""
        // A freeze's trace is text. A native crash's is a binary record, but the parts worth
        // reading in it (the library, function and abort message) are plain strings inside.
        val text = String(bytes, Charsets.ISO_8859_1)
        return if (freeze) "$text\n" else Regex("[\\x20-\\x7e]{6,}").findAll(text).joinToString("\n") { it.value } + "\n"
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = read(buffer, total, limit - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    private fun header(context: Context, kind: String): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return "TeleMusic $kind\n" +
            "Saved: ${timeText(System.currentTimeMillis())}\n" +
            "App: ${info?.versionName} (${info?.let(PackageInfoCompat::getLongVersionCode)})\n" +
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
            "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n\n"
    }

    private fun timeText(millis: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun logSection(pid: Int): String {
        val log = recentLog(pid)
        return if (log.isBlank()) "" else "\n--- App log ---\n$log"
    }

    /** The app may read its own log lines without any permission. Time-limited: a crashing app
     * has to end, and a report without its log is still worth having. */
    private fun recentLog(pid: Int): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOG_LINES, "--pid=$pid")
            .redirectErrorStream(true).start()
        var text = ""
        val reader = thread { text = process.inputStream.bufferedReader().readText() }
        reader.join(LOG_WAIT_MS)
        process.destroy()
        text
    }.getOrDefault("")
}
