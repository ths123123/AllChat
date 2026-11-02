package com.fauzan.allchat

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update otomatis:
 * 1. cek rilis terbaru di GitHub,
 * 2. unduh APK di latar,
 * 3. pasang lewat PackageInstaller tanpa tombol "Install" (Android 12+),
 * 4. AllChat terbuka lagi sendiri (lihat UpdatedReceiver).
 */
class Updater(
    private val activity: Activity,
    /** Dipanggil di UI thread: null = tidak sedang update, 0..100 = progres unduhan. */
    private val onProgress: (Int?) -> Unit
) {

    private val latestApi = "https://api.github.com/repos/ths123123/AllChat/releases/latest"

    @Volatile
    private var working = false

    fun check(silent: Boolean) {
        if (working) {
            if (!silent) toast("Pembaruan sedang diunduh…")
            return
        }
        Thread {
            try {
                val json = JSONObject(httpGetText(latestApi))
                val tag = json.optString("tag_name")               // contoh: v1.0.7
                val latest = tag.substringAfterLast('.').toIntOrNull() ?: 0
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) apkUrl = a.optString("browser_download_url")
                    }
                }
                if (latest > BuildConfig.VERSION_CODE && apkUrl != null) {
                    ui { toast("Memperbarui AllChat ke $tag…") }
                    downloadAndInstall(apkUrl)
                } else if (!silent) {
                    ui { toast("Sudah versi terbaru (v${BuildConfig.VERSION_NAME})") }
                }
            } catch (e: Exception) {
                if (!silent) ui { toast("Gagal cek pembaruan. Periksa internet.") }
            }
        }.start()
    }

    /** Berjalan di thread latar. */
    private fun downloadAndInstall(apkUrl: String) {
        working = true
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
                                ui { onProgress(pct) }
                            }
                        }
                    }
                }
            }
            ui { onProgress(100) }
            install(out)
        } catch (e: Exception) {
            ui {
                onProgress(null)
                toast("Unduhan pembaruan gagal, dicoba lagi nanti.")
            }
        } finally {
            working = false
        }
    }

    private fun install(apk: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(activity.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            // Pasang tanpa konfirmasi (berlaku setelah AllChat pernah memperbarui dirinya sekali)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("AllChat.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val status = PendingIntent.getBroadcast(
                activity, sessionId,
                Intent(activity, InstallReceiver::class.java),
                flags
            )
            session.commit(status.intentSender)
        }
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

    private fun httpGetText(url: String): String =
        open(url).inputStream.bufferedReader().use { it.readText() }

    private fun ui(block: () -> Unit) = activity.runOnUiThread {
        if (!activity.isFinishing) block()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
