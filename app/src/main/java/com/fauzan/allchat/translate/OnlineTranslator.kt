package com.fauzan.allchat.translate

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Terjemahan online, tanpa unduh bahasa.
 * Utama: layanan Google Translate gratis (endpoint publik tanpa kunci).
 * Cadangan: MyMemory (API gratis) kalau yang utama gagal / dibatasi.
 */
object OnlineTranslator {

    private val pool = Executors.newFixedThreadPool(4)

    /** Menerjemahkan beberapa teks sekaligus (paralel). Dipanggil dari thread latar. */
    fun translateAll(texts: List<String>, src: String, dst: String): List<String?> {
        val futures = texts.map { t -> pool.submit(Callable { translate(t, src, dst) }) }
        return futures.map { f -> try { f.get() } catch (e: Exception) { null } }
    }

    fun translate(text: String, src: String, dst: String): String? {
        return try {
            google(text, src, dst)
        } catch (e: Exception) {
            try { myMemory(text, src, dst) } catch (e2: Exception) { null }
        }
    }

    private fun google(text: String, src: String, dst: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t" +
            "&sl=$src&tl=$dst&q=" + URLEncoder.encode(text, "UTF-8")
        val arr = JSONArray(get(url))
        val parts = arr.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val seg = parts.optJSONArray(i) ?: continue
            sb.append(seg.optString(0))
        }
        val out = sb.toString().trim()
        if (out.isEmpty()) throw IllegalStateException("empty")
        return out
    }

    private fun myMemory(text: String, src: String, dst: String): String {
        val url = "https://api.mymemory.translated.net/get?langpair=$src%7C$dst&q=" +
            URLEncoder.encode(text.take(480), "UTF-8")
        val out = JSONObject(get(url)).getJSONObject("responseData").optString("translatedText").trim()
        if (out.isEmpty()) throw IllegalStateException("empty")
        return out
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AllChat")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
