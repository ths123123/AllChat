package com.fauzan.allchat.translate

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

/** Bahasa yang didukung Translate Layar. */
object Languages {
    val all = listOf(
        TranslateLanguage.KOREAN to "Korea",
        TranslateLanguage.INDONESIAN to "Indonesia",
        TranslateLanguage.ENGLISH to "Inggris",
        TranslateLanguage.SPANISH to "Spanyol"
    )

    private fun prefs(c: Context) = c.getSharedPreferences("allchat", Context.MODE_PRIVATE)

    fun source(c: Context): String = prefs(c).getString("tr_src", TranslateLanguage.KOREAN)!!
    fun target(c: Context): String = prefs(c).getString("tr_dst", TranslateLanguage.INDONESIAN)!!
    fun setSource(c: Context, code: String) = prefs(c).edit().putString("tr_src", code).apply()
    fun setTarget(c: Context, code: String) = prefs(c).edit().putString("tr_dst", code).apply()
    fun label(code: String) = all.firstOrNull { it.first == code }?.second ?: code
}
