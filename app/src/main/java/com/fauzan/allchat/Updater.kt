package com.fauzan.allchat

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cek rilis terbaru di GitHub. Kalau ada versi lebih baru, tawarkan "Perbarui",
 * unduh APK di dalam aplikasi, lalu buka penginstal Android.
 */
class Updater(private val activity: Activity) {

    private val latestApi = "https://api.github.com/repos/ths123123/AllChat/releases/latest"

    fun check(silent: Boolean) {
        Thread {
            try {
                val json = JSONObject(httpGetText(latestApi))
                val tag = json.optString("tag_name")               // contoh: v1.0.7
                val latest = tag.substringAfterLast('.').toIntOrNull() ?: 0
                val notes = json.optString("body").trim()
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                        }
                    }
                }
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    if (latest > BuildConfig.VERSION_CODE && apkUrl != null) {
                        askToUpdate(tag, notes, apkUrl)
                    } else if (!silent) {
                        toast("Sudah versi terbaru (v${BuildConfig.VERSION_NAME})")
                    }
                }
            } catch (e: Exception) {
                if (!silent) activity.runOnUiThread { toast("Gagal cek pembaruan. Periksa internet.") }
            }
        }.start()
    }

    private fun askToUpdate(tag: String, notes: String, apkUrl: String) {
        val message = buildString {
            append("Versi baru $tag tersedia.\nVersi kamu: v${BuildConfig.VERSION_NAME}")
            if (notes.isNotEmpty()) append("\n\nPerubahan:\n").append(notes.take(400))
            append("\n\nLogin kamu tetap tersimpan.")
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Pembaruan tersedia")
            .setMessage(message)
            .setPositiveButton("Perbarui") { _, _ -> download(apkUrl) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun download(apkUrl: String) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val label = TextView(activity).apply { text = "Mengunduh… 0%" }
        val bar = LinearProgressIndicator(activity).apply { max = 100 }
        box.addView(label)
        box.addView(bar)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Memperbarui AllChat")
            .setView(box)
            .setCancelable(false)
            .show()

        Thread {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "AllChat.apk")
                val conn = open(apkUrl)
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    activity.runOnUiThread {
                                        bar.setProgressCompat(pct, true)
                                        label.text = "Mengunduh… $pct%"
                                    }
                                }
                            }
                        }
                    }
                }
                activity.runOnUiThread {
                    dialog.dismiss()
                    install(out)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dialog.dismiss()
                    toast("Unduhan gagal, coba lagi nanti.")
                }
            }
        }.start()
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "AllChat-Android")
            val code = c.responseCode
            if (code in 300..399) {
                current = c.getHeaderField("Location") ?: throw IllegalStateException("redirect")
                c.disconnect()
            } else {
                if (code != 200) throw IllegalStateException("HTTP $code")
                return c
            }
        }
        throw IllegalStateException("too many redirects")
    }

    private fun httpGetText(url: String): String {
        val c = open(url)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
