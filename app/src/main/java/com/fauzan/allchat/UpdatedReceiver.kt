package com.fauzan.allchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/** Dipanggil Android setelah AllChat selesai diperbarui → buka lagi aplikasinya otomatis. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_JUST_UPDATED, true)

        // Izin "tampil di atas aplikasi lain" (dipakai Translate layar) membolehkan app membuka dirinya sendiri.
        if (Settings.canDrawOverlays(context)) {
            try {
                context.startActivity(open)
                return
            } catch (_: Exception) {
            }
        }

        // Tanpa izin itu: tampilkan notifikasi "ketuk untuk membuka"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("update") == null) {
            nm.createNotificationChannel(
                NotificationChannel("update", "Pembaruan", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pi = PendingIntent.getActivity(
            context, 3, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, "update")
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle("AllChat sudah diperbarui")
            .setContentText("Versi ${BuildConfig.VERSION_NAME} · ketuk untuk membuka")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { nm.notify(9, n) } catch (_: SecurityException) {}
    }
}
