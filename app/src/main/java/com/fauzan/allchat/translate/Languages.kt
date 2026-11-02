package com.fauzan.allchat.translate

import android.content.Context

/** Bahasa yang didukung Translate Layar (kode ISO). */
object Languages {
    const val KOREAN = "ko"
    const val INDONESIAN = "id"
    const val ENGLISH = "en"
    const val SPANISH = "es"

    val all = listOf(
        KOREAN to "Korea",
        INDONESIAN to "Indonesia",
        ENGLISH to "Inggris",
        SPANISH to "Spanyol"
    )

    private fun prefs(c: Context) = c.getSharedPreferences("allchat", Context.MODE_PRIVATE)

    fun source(c: Context): String = prefs(c).getString("tr_src", KOREAN)!!
    fun target(c: Context): String = prefs(c).getString("tr_dst", INDONESIAN)!!
    fun setSource(c: Context, code: String) = prefs(c).edit().putString("tr_src", code).apply()
    fun setTarget(c: Context, code: String) = prefs(c).edit().putString("tr_dst", code).apply()
    fun label(code: String) = all.firstOrNull { it.first == code }?.second ?: code
}
