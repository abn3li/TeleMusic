package com.abn3li.telemusic.ui.nowplaying

/**
 * One synced lyric line. [words], when the lyrics have word timing (enhanced LRC, e.g. from
 * BiniLyrics), say how many of [text]'s characters are sung by when: at [WordMark.timeMs] the
 * first [WordMark.chars] characters are done. Sorted, both columns never going backwards.
 * Empty for plain line-synced lyrics, whose sweep is estimated from the line's length instead.
 */
data class LyricLine(val timeMs: Long, val text: String, val words: List<WordMark> = emptyList())

data class WordMark(val timeMs: Long, val chars: Int)

private val LINE_STAMP = Regex("""^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val WORD_STAMP = Regex("""<(\d{1,3}):(\d{2})[.:](\d{1,3})>""")

private fun millis(minutes: String, seconds: String, fraction: String?): Long =
    minutes.toLong() * 60_000 + seconds.toLong() * 1000 + (fraction ?: "").padEnd(3, '0').take(3).toLong()

/**
 * LRC, line-synced or with word timing (`[00:27.39]<00:27.39>I <00:27.54>been<00:27.70>`). A line
 * with several leading times (`[00:12.00][01:30.00]chorus`) is repeated at each. Tag lines like
 * `[ar:Artist]` are skipped.
 */
fun parseLrc(lrc: String): List<LyricLine> {
    val result = mutableListOf<LyricLine>()
    for (raw in lrc.lineSequence()) {
        var rest = raw.trim()
        val times = mutableListOf<Long>()
        while (true) {
            val match = LINE_STAMP.find(rest) ?: break
            val (min, sec, fraction) = match.destructured
            times += millis(min, sec, fraction.ifEmpty { null })
            rest = rest.substring(match.range.last + 1)
        }
        if (times.isEmpty()) continue
        val (text, words) = parseWords(rest)
        times.forEach { result += LyricLine(it, text, words) }
    }
    return result.sortedBy { it.timeMs }
}

private fun parseWords(body: String): Pair<String, List<WordMark>> {
    if (!body.contains('<')) return body.trim() to emptyList()
    val text = StringBuilder()
    val raw = mutableListOf<WordMark>()
    var last = 0
    for (match in WORD_STAMP.findAll(body)) {
        text.append(body, last, match.range.first)
        val (min, sec, fraction) = match.destructured
        raw += WordMark(millis(min, sec, fraction), text.length)
        last = match.range.last + 1
    }
    text.append(body, last, body.length)
    val full = text.toString()
    val lead = full.length - full.trimStart().length
    val trimmed = full.trim()
    // Kept in step with the trimmed text, and never going backwards: background vocals can start
    // before the main words end, but the sweep only moves forward.
    val words = mutableListOf<WordMark>()
    for (mark in raw) {
        val chars = (mark.chars - lead).coerceIn(0, trimmed.length)
        val previous = words.lastOrNull()
        val time = if (previous != null) maxOf(mark.timeMs, previous.timeMs) else mark.timeMs
        if (previous != null && chars < previous.chars) continue
        if (previous != null && previous.chars == chars && previous.timeMs == time) continue
        words += WordMark(time, chars)
    }
    return trimmed to words
}
