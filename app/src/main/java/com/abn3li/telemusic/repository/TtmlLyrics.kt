package com.abn3li.telemusic.repository

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns TTML lyrics (Apple-Music-style, as BiniLyrics serves them) into what the rest of the app
 * already stores: plain text, and LRC for the synced version. Word timing is kept as enhanced
 * LRC - `[mm:ss.xxx]<mm:ss.xxx>word <mm:ss.xxx>word<mm:ss.xxx>` - where each `<time>` is when
 * the text after it starts, and a tag right after a word marks when it ends (written only where
 * a pause follows, and after the last word). Plain LRC readers just see the line's time.
 *
 * Only the body is read: the head carries transliterations and translations timed like the
 * lyrics themselves. Background vocals (a span with ttm:role="x-bg") stay on their line, after
 * the main words, like "(Back to let you know)".
 */
internal object TtmlLyrics {

    class Converted(val plain: String, val synced: String?)

    // A line ending this long before the next one gets an empty line at its end, which shows
    // the countdown dots over the gap instead of leaving the last line lit through it.
    private const val GAP_MS = 4_000L
    // Shorter pauses between words than this don't get their own end tag.
    private const val WORD_PAUSE_MS = 60L

    private sealed interface Piece {
        class Word(val text: String, val beginMs: Long, val endMs: Long?) : Piece
        class Gap(val text: String) : Piece
    }

    private class Line(val beginMs: Long?, val endMs: Long?, val pieces: List<Piece>) {
        val text: String get() = pieces.joinToString("") {
            when (it) {
                is Piece.Word -> it.text
                is Piece.Gap -> it.text
            }
        }.replace(SPACES, " ").trim()
    }

    private const val TTML_METADATA = "http://www.w3.org/ns/ttml#metadata"
    private val SPACES = Regex("""\s+""")

    fun convert(ttml: String): Converted? {
        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Lyrics never need a DTD; refusing them keeps a hostile file from fetching or
                // expanding anything.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        } catch (e: Exception) {
            return null
        }
        val body = document.getElementsByTagNameNS("*", "body").item(0) as? Element ?: return null
        val paragraphs = body.getElementsByTagNameNS("*", "p")
        val lines = (0 until paragraphs.length).mapNotNull { index ->
            val p = paragraphs.item(index) as Element
            val pieces = mutableListOf<Piece>()
            collect(p, pieces)
            Line(parseTime(p.getAttribute("begin")), parseTime(p.getAttribute("end")), mergeGaps(pieces))
                .takeIf { it.text.isNotEmpty() }
        }
        if (lines.isEmpty()) return null

        val plain = lines.joinToString("\n") { it.text }
        val timed = lines.filter { it.beginMs != null }.sortedBy { it.beginMs }
        // Untimed ("timing: None") TTML is plain lyrics only.
        if (timed.size < lines.size / 2 || timed.isEmpty()) return Converted(plain, null)

        val synced = buildString {
            timed.forEachIndexed { index, line ->
                append('[').append(stamp(line.beginMs!!)).append(']')
                appendWords(line)
                append('\n')
                val next = timed.getOrNull(index + 1)?.beginMs
                val end = line.endMs
                if (next != null && end != null && next - end >= GAP_MS) {
                    append('[').append(stamp(end)).append("]\n")
                }
            }
        }.trimEnd()
        return Converted(plain, synced)
    }

    private fun StringBuilder.appendWords(line: Line) {
        val words = line.pieces.filterIsInstance<Piece.Word>()
        if (words.isEmpty()) {
            append(line.text)
            return
        }
        // Leading spaces would only be trimmed off again by the reader.
        val pieces = line.pieces.dropWhile { it is Piece.Gap }
        pieces.forEachIndexed { index, piece ->
            when (piece) {
                is Piece.Gap -> append(piece.text.replace(SPACES, " "))
                is Piece.Word -> {
                    append('<').append(stamp(piece.beginMs)).append('>').append(piece.text)
                    val nextWord = pieces.drop(index + 1).firstOrNull { it is Piece.Word } as Piece.Word?
                    val end = piece.endMs
                    if (end != null && (nextWord == null || nextWord.beginMs - end >= WORD_PAUSE_MS)) {
                        append('<').append(stamp(end)).append('>')
                    }
                }
            }
        }
    }

    /** Words (timed spans) and the text between them, in order, from [element]'s children. */
    private fun collect(element: Element, into: MutableList<Piece>) {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.nodeValue?.let { into += Piece.Gap(it) }
                Node.ELEMENT_NODE -> {
                    val child = node as Element
                    when {
                        child.localName == "br" -> into += Piece.Gap(" ")
                        // A timed span holding only text is one word (or syllable).
                        child.hasAttribute("begin") && !hasElementChildren(child) -> {
                            val begin = parseTime(child.getAttribute("begin"))
                            val text = child.textContent.orEmpty()
                            if (begin == null) into += Piece.Gap(text)
                            else into += Piece.Word(text, begin, parseTime(child.getAttribute("end")))
                        }
                        else -> {
                            // Background vocals: set apart from the main words by a space.
                            if (child.getAttributeNS(TTML_METADATA, "role") == "x-bg") {
                                into += Piece.Gap(" ")
                            }
                            collect(child, into)
                        }
                    }
                }
            }
        }
    }

    /** Runs of text between words become one, with runs of spaces cut to one. */
    private fun mergeGaps(pieces: List<Piece>): List<Piece> {
        val merged = mutableListOf<Piece>()
        for (piece in pieces) {
            val previous = merged.lastOrNull()
            if (piece is Piece.Gap && previous is Piece.Gap) {
                merged[merged.lastIndex] = Piece.Gap(previous.text + piece.text)
            } else {
                merged += piece
            }
        }
        return merged.map { if (it is Piece.Gap) Piece.Gap(it.text.replace(SPACES, " ")) else it }
    }

    private fun hasElementChildren(element: Element): Boolean {
        val children = element.childNodes
        return (0 until children.length).any { children.item(it).nodeType == Node.ELEMENT_NODE }
    }

    /** TTML clock times: "27.395", "1:07.512", "1:02:03.4", or with an "s" suffix. */
    internal fun parseTime(value: String?): Long? {
        val text = value?.trim()?.removeSuffix("s")?.takeIf { it.isNotEmpty() } ?: return null
        val parts = text.split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) {
            val number = part.toDoubleOrNull() ?: return null
            seconds = seconds * 60 + number
        }
        return (seconds * 1000).toLong().takeIf { it >= 0 }
    }

    /** mm:ss.xxx - minutes can pass 99 in a very long track, which LRC readers accept. */
    private fun stamp(ms: Long): String {
        val minutes = ms / 60_000
        val seconds = (ms / 1000) % 60
        val millis = ms % 1000
        // Locale.ROOT: an Arabic locale would otherwise write Arabic-Indic digits.
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis)
    }
}
