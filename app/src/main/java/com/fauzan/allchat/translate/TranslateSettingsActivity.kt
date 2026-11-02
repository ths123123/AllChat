package com.fauzan.allchat.translate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.fauzan.allchat.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class TranslateSettingsActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var startButton: MaterialButton
    private var waitingForOverlay = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, ScreenTranslateService::class.java)
                .setAction(ScreenTranslateService.ACTION_START)
                .putExtra(ScreenTranslateService.EXTRA_CODE, result.resultCode)
                .putExtra(ScreenTranslateService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Gelembung aktif. Buka bacaanmu lalu ketuk gelembungnya.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            status.text = "Izin rekam layar dibatalkan. Izin ini dibutuhkan agar teks di layar bisa dibaca."
        }
        refresh()
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_translate)

        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        status = findViewById(R.id.status)
        startButton = findViewById(R.id.startButton)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        fillChips(findViewById(R.id.srcGroup), Languages.source(this)) { Languages.setSource(this, it); onLanguageChanged() }
        fillChips(findViewById(R.id.dstGroup), Languages.target(this)) { Languages.setTarget(this, it); onLanguageChanged() }

        startButton.setOnClickListener {
            if (ScreenTranslateService.running) {
                startService(Intent(this, ScreenTranslateService::class.java).setAction(ScreenTranslateService.ACTION_STOP))
                startButton.postDelayed({ refresh() }, 300)
            } else {
                start()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Kembali dari layar izin "tampil di atas aplikasi lain" → lanjutkan otomatis
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            start()
        }
    }

    private fun fillChips(group: ChipGroup, selected: String, onPick: (String) -> Unit) {
        for ((code, label) in Languages.all) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = label
                tag = code
                isCheckable = true
                isCheckedIconVisible = false
                textSize = 15f
            }
            group.addView(chip)
            if (code == selected) chip.isChecked = true
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            val chip = ids.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            onPick(chip.tag as String)
        }
    }

    private fun onLanguageChanged() {
        status.text = if (ScreenTranslateService.running)
            "Bahasa baru dipakai setelah kamu Hentikan lalu Mulai lagi."
        else ""
    }

    private fun refresh() {
        val running = ScreenTranslateService.running
        startButton.text = if (running) "Hentikan" else "Mulai"
        startButton.isEnabled = true
        if (running && status.text.isNullOrEmpty()) {
            status.text = "Aktif: ${Languages.label(Languages.source(this))} → ${Languages.label(Languages.target(this))}"
        }
    }

    private fun start() {
        val src = Languages.source(this)
        val dst = Languages.target(this)
        if (src == dst) {
            status.text = "Pilih bahasa asal dan tujuan yang berbeda."
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true
            status.text = "Aktifkan \"Tampilkan di atas aplikasi lain\" untuk AllChat, lalu kembali ke sini."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        status.text = ""
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
