package com.abn3li.telemusic.repository

import android.util.Log
import com.abn3li.telemusic.data.remote.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** A language lyrics can be translated into: its code for the translator and its name in Settings. */
data class TranslationLanguage(val code: String, val name: String)

/**
 * Translates a song's lyric lines, one translation per line. The lines go out as one text, a line
 * per line, in batches, and come back the same way; a batch that doesn't come back with as many
 * lines is asked line by line instead, so a translation never lands under the wrong line. Each
 * song's answer is kept on the phone, under its lyrics, the language and the service - the same
 * lyrics are never translated twice, and lyrics that change get a new translation.
 */
class LyricsTranslator(private val cacheDir: File) {

    /**
     * Each distinct line of [lines] mapped to its translation into [language]. Lines already in
     * that language, or blank, are left out - nothing to show under them. Throws when the
     * translation couldn't be fetched (offline, refused).
     */
    suspend fun translate(songKey: String, lines: List<String>, language: String): Map<String, String> = withContext(Dispatchers.IO) {
        val distinct = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return@withContext emptyMap()
        val file = File(cacheDir, cacheName(songKey, distinct, language))
        readCache(file, distinct)?.let { return@withContext it }

        val translated = ArrayList<String>(distinct.size)
        for (batch in batches(distinct)) translated += translateBatch(batch, language)
        val result = distinct.indices
            .filter { translated[it].isNotBlank() && !sameText(translated[it], distinct[it]) }
            .associate { distinct[it] to translated[it] }
        writeCache(file, distinct, translated)
        result
    }

    private fun translateBatch(lines: List<String>, language: String): List<String> {
        val joined = request(lines.joinToString("\n"), language).split('\n')
        if (joined.size == lines.size) return joined.map { it.trim() }
        Log.w(TAG, "Batch came back with ${joined.size} lines for ${lines.size}; asking line by line")
        return lines.map { request(it, language).replace('\n', ' ').trim() }
    }

    private fun request(text: String, language: String): String {
        val url = TRANSLATE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", language)
            .addQueryParameter("dt", "t")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("q", text).build())
            .build()
        val body = NetworkModule.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Translation failed: HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        return translatedText(body)
    }

    private fun readCache(file: File, lines: List<String>): Map<String, String>? = runCatching {
        if (!file.isFile) return null
        val saved = JSONArray(file.readText())
        if (saved.length() != lines.size) return null
        lines.indices.mapNotNull { i ->
            saved.optString(i).takeIf { it.isNotBlank() && !sameText(it, lines[i]) }?.let { lines[i] to it }
        }.toMap()
    }.getOrNull()

    private fun writeCache(file: File, lines: List<String>, translated: List<String>) {
        runCatching {
            cacheDir.mkdirs()
            file.writeText(JSONArray(lines.indices.map { translated.getOrElse(it) { "" } }).toString())
        }.onFailure { Log.w(TAG, "Couldn't keep the translation: ${it.message}") }
    }

    companion object {
        private const val TAG = "LyricsTranslator"
        private const val TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
        private const val SERVICE = "gtx"
        // Characters per request: long songs go in a few requests rather than one huge one.
        private const val BATCH_CHARS = 3_500
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        val LANGUAGES = listOf(
            TranslationLanguage("ar", "Arabic"),
            TranslationLanguage("en", "English"),
            TranslationLanguage("ku", "Kurdish"),
            TranslationLanguage("fa", "Persian"),
            TranslationLanguage("tr", "Turkish"),
            TranslationLanguage("fr", "French"),
            TranslationLanguage("es", "Spanish"),
            TranslationLanguage("de", "German"),
            TranslationLanguage("ru", "Russian"),
            TranslationLanguage("hi", "Hindi"),
            TranslationLanguage("ja", "Japanese"),
            TranslationLanguage("ko", "Korean")
        )
        const val DEFAULT_LANGUAGE = "ar"

        fun languageName(code: String): String = LANGUAGES.firstOrNull { it.code == code }?.name ?: code

        /** The translated text in the service's answer: its pieces, joined back together. */
        internal fun translatedText(body: String): String {
            val pieces = JSONArray(body).optJSONArray(0) ?: return ""
            return (0 until pieces.length()).joinToString("") { pieces.optJSONArray(it)?.optString(0).orEmpty() }
        }

        /** [lines] split into requests of at most [BATCH_CHARS] characters, in order. */
        internal fun batches(lines: List<String>): List<List<String>> {
            val out = mutableListOf<MutableList<String>>()
            var size = 0
            for (line in lines) {
                if (out.isEmpty() || (size + line.length + 1 > BATCH_CHARS && out.last().isNotEmpty())) {
                    out += mutableListOf<String>()
                    size = 0
                }
                out.last() += line
                size += line.length + 1
            }
            return out
        }

        private fun sameText(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

        private fun cacheName(songKey: String, lines: List<String>, language: String): String {
            val digest = MessageDigest.getInstance("SHA-1")
                .digest("$SERVICE\n$language\n$songKey\n${lines.joinToString("\n")}".toByteArray())
            return digest.joinToString("") { "%02x".format(it) } + ".json"
        }
    }
}
