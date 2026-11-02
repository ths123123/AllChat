package com.fauzan.allchat.translate

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.fauzan.allchat.R
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

/**
 * Gelembung mengambang untuk menerjemahkan teks di layar.
 * Ketuk gelembung → tangkap layar → baca teks (ML Kit) → terjemahkan (offline) → tampilkan di atas teks asli.
 */
class ScreenTranslateService : Service() {

    companion object {
        const val ACTION_START = "com.fauzan.allchat.translate.START"
        const val ACTION_STOP = "com.fauzan.allchat.translate.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "translate"
        private const val NOTIF_ID = 7

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var bgThread: HandlerThread? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val frameLock = Any()
    private var latestFrame: Image? = null

    private var recognizer: TextRecognizer? = null
    private var srcLang = Languages.KOREAN
    private var dstLang = Languages.INDONESIAN

    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: FrameLayout? = null
    private var busy = false

    private var screenW = 0
    private var screenH = 0

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (projection != null) return START_NOT_STICKY // sudah berjalan

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            projection = null
        }
        val proj = projection
        if (proj == null) {
            toast("Tidak bisa memulai rekam layar")
            stopSelf()
            return START_NOT_STICKY
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)

        wm = getSystemService(WindowManager::class.java)
        setupCapture(proj)
        setupMlKit()
        showBubble()
        running = true
        return START_NOT_STICKY
    }

    // ---------- Notifikasi (wajib untuk rekam layar) ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Translate layar", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, TranslateSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_translate)
            .setContentTitle("Translate layar aktif")
            .setContentText("${Languages.label(Languages.source(this))} → ${Languages.label(Languages.target(this))} · ketuk gelembung untuk menerjemahkan")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(0, "Hentikan", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- Tangkap layar ----------

    @SuppressLint("WrongConstant")
    private fun setupCapture(proj: MediaProjection) {
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        screenW = size.x
        screenH = size.y
        val dpi = resources.displayMetrics.densityDpi

        val thread = HandlerThread("allchat-capture").also { it.start() }
        bgThread = thread
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) synchronized(frameLock) {
                latestFrame?.close()
                latestFrame = img
            }
        }, Handler(thread.looper))
        reader = r
        virtualDisplay = proj.createVirtualDisplay(
            "AllChatTranslate", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    private fun grabBitmap(): Bitmap? = synchronized(frameLock) {
        val img = latestFrame ?: return null
        try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val w = img.width
            val h = img.height
            val padded = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            if (padded.width == w) padded else Bitmap.createBitmap(padded, 0, 0, w, h)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- ML Kit ----------

    private fun setupMlKit() {
        srcLang = Languages.source(this)
        dstLang = Languages.target(this)
        recognizer = if (srcLang == Languages.KOREAN) {
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        } else {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun translateScreen() {
        if (busy) return
        busy = true
        removeOverlay()
        bubble?.visibility = View.INVISIBLE   // supaya gelembung tidak ikut terbaca

        main.postDelayed({
            val bmp = grabBitmap()
            bubble?.visibility = View.VISIBLE
            setLoading(true)
            val rec = recognizer
            if (bmp == null || rec == null) {
                finish("Layar belum siap, coba ketuk lagi")
                return@postDelayed
            }
            rec.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text -> translateBlocks(text) }
                .addOnFailureListener { finish("Gagal membaca teks. Pastikan Google Play Services aktif.") }
        }, 200)
    }

    private fun translateBlocks(text: Text) {
        val blocks = text.textBlocks.filter { it.boundingBox != null && it.text.isNotBlank() }
        if (blocks.isEmpty()) {
            finish("Tidak ada teks yang terbaca di layar")
            return
        }
        val sources = blocks.map { b -> b.lines.joinToString(" ") { it.text } }
        val boxes = blocks.map { it.boundingBox!! }
        val src = srcLang
        val dst = dstLang
        Thread {
            val translated = OnlineTranslator.translateAll(sources, src, dst)
            main.post {
                val anyOk = translated.any { it != null }
                if (!anyOk) {
                    finish("Gagal menerjemahkan. Periksa koneksi internet.")
                    return@post
                }
                showOverlay(boxes, translated.mapIndexed { i, t -> t ?: sources[i] })
                finish(null)
            }
        }.start()
    }

    private fun finish(message: String?) {
        busy = false
        setLoading(false)
        if (message != null) toast(message)
    }

    // ---------- Gelembung ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val size = dp(54)
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6111418"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
            elevation = dp(6).toFloat()
            contentDescription = "Terjemahkan layar"
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - dp(6)
            y = screenH / 3
        }

        v.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y
                        touchX = e.rawX; touchY = e.rawY
                        moved = false
                        view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - touchX).toInt()
                        val dy = (e.rawY - touchY).toInt()
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = startX + dx
                            lp.y = (startY + dy).coerceIn(0, screenH - size)
                            wm.updateViewLayout(view, lp)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        if (!moved && e.action == MotionEvent.ACTION_UP) {
                            if (overlay != null) removeOverlay() else translateScreen()
                        } else if (moved) {
                            // menempel ke tepi kiri/kanan
                            lp.x = if (lp.x + size / 2 < screenW / 2) dp(6) else screenW - size - dp(6)
                            wm.updateViewLayout(view, lp)
                        }
                    }
                }
                return true
            }
        })

        wm.addView(v, lp)
        bubble = v
        bubbleParams = lp
    }

    private fun setLoading(loading: Boolean) {
        val b = bubble ?: return
        b.animate().cancel()
        if (loading) {
            b.alpha = 0.75f
            b.animate().rotationBy(3600f).setDuration(10000).setInterpolator(LinearInterpolator()).start()
        } else {
            b.alpha = 1f
            b.rotation = 0f
        }
    }

    // ---------- Hasil terjemahan ----------

    private fun showOverlay(boxes: List<Rect>, texts: List<String>) {
        removeOverlay()
        val root = FrameLayout(this)
        root.setOnClickListener { removeOverlay() }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, lp)
        overlay = root

        root.post {
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            boxes.forEachIndexed { i, box ->
                val w = max(box.width() + dp(8), dp(120)).coerceAtMost(screenW - dp(8))
                val tv = TextView(this).apply {
                    text = texts[i]
                    setTextColor(Color.parseColor("#111418"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setLineSpacing(0f, 1.08f)
                    val ph = dp(8)
                    val pv = dp(5)
                    setPadding(ph, pv, ph, pv)
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#F7FFFFFF"))
                        setStroke(dp(1), Color.parseColor("#22000000"))
                    }
                    elevation = dp(3).toFloat()
                    setOnClickListener { removeOverlay() }
                }
                val params = FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT)
                val left = box.centerX() - w / 2 - origin[0]
                params.leftMargin = left.coerceIn(dp(4), screenW - w - dp(4))
                params.topMargin = (box.top - dp(4) - origin[1]).coerceAtLeast(0)
                root.addView(tv, params)
                tv.alpha = 0f
                tv.animate().alpha(1f).setDuration(150).start()
            }
            // Gelembung tetap paling atas
            val b = bubble
            val bp = bubbleParams
            if (b != null && bp != null) {
                try { wm.removeView(b); wm.addView(b, bp) } catch (_: Exception) {}
            }
        }
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    // ---------- Selesai ----------

    override fun onDestroy() {
        running = false
        if (::wm.isInitialized) {
            removeOverlay()
            bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        bubble = null
        virtualDisplay?.release()
        synchronized(frameLock) { try { latestFrame?.close() } catch (_: Exception) {}; latestFrame = null }
        reader?.close()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        recognizer?.close()
        bgThread?.quitSafely()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
