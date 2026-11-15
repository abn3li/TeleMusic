package com.abn3li.telemusic.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * What the app was doing just before a crash: where you were (screen, player, dock) and the last
 * few things that happened. Written only when something happens, never on a timer; read only by
 * [CrashReporter] when the app dies.
 */
internal object CrashTrail {
    private const val SIZE = 40
    private val times = LongArray(SIZE)
    private val events = arrayOfNulls<String>(SIZE)
    private var next = 0
    private var count = 0
    private val now = ConcurrentHashMap<String, String>()

    /** Something that just happened. */
    fun note(event: String) = synchronized(this) {
        times[next] = System.currentTimeMillis()
        events[next] = event
        next = (next + 1) % SIZE
        if (count < SIZE) count++
    }

    /** Where things stand now; the change itself is noted too. */
    fun set(key: String, value: String) {
        if (now.put(key, value) != value) note("$key: $value")
    }

    fun text(): String = synchronized(this) {
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        buildString {
            append("--- What was happening ---\n")
            if (now.isNotEmpty()) {
                append("Now: ")
                append(now.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" })
                append('\n')
            }
            append("Last steps (oldest first):\n")
            val start = (next - count + SIZE) % SIZE
            for (i in 0 until count) {
                val at = (start + i) % SIZE
                append(format.format(Date(times[at]))).append("  ").append(events[at]).append('\n')
            }
            append('\n')
        }
    }
}
