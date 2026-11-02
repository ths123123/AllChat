package com.fauzan.allchat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar

/** Satu layanan chat. */
data class ChatService(
    val key: String,
    val title: String,
    val url: String,
    val hosts: List<String>,
    val colorRes: Int,
    val iconRes: Int,
    val navId: Int,
    val appPackage: String,
    /** null = tampilan HP biasa; angka = lebar halaman desktop yang dipaskan ke layar */
    val desktopWidth: Int?,
    val connectDesc: String,
    val connectNote: String
)

/** Satu halaman di dalam ViewPager: WebView + layar "Hubungkan" di atasnya. */
class Page(val service: ChatService, val root: FrameLayout, val web: WebView, val connect: View) {
    var progress = 100
}

class MainActivity : AppCompatActivity() {

    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"

    private val services = listOf(
        ChatService(
            key = "messenger", title = "Messenger",
            url = "https://www.facebook.com/messages/",
            hosts = listOf("facebook.com", "messenger.com", "fbcdn.net", "fb.com", "fbsbx.com"),
            colorRes = R.color.messenger, iconRes = R.drawable.ic_messenger, navId = R.id.nav_messenger,
            appPackage = "com.facebook.orca",
            desktopWidth = 760,
            connectDesc = "Baca dan balas chat Messenger langsung dari sini.",
            connectNote = "Masuk sekali saja dengan akun Facebook. Setelah itu langsung tersambung setiap kali dibuka."
        ),
        ChatService(
            key = "instagram", title = "Instagram",
            url = "https://www.instagram.com/direct/inbox/",
            hosts = listOf("instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net"),
            colorRes = R.color.instagram, iconRes = R.drawable.ic_instagram, navId = R.id.nav_instagram,
            appPackage = "com.instagram.android",
            desktopWidth = null,
            connectDesc = "Semua pesan langsung (DM) Instagram kamu di satu tempat.",
            connectNote = "Masuk sekali saja dengan akun Instagram (atau tombol \"Lanjutkan dengan Facebook\")."
        ),
        ChatService(
            key = "whatsapp", title = "WhatsApp",
            url = "https://web.whatsapp.com/",
            hosts = listOf("whatsapp.com", "whatsapp.net"),
            colorRes = R.color.whatsapp, iconRes = R.drawable.ic_whatsapp, navId = R.id.nav_whatsapp,
            appPackage = "com.whatsapp",
            desktopWidth = 900,
            connectDesc = "Tautkan WhatsApp kamu sebagai perangkat tambahan, tanpa logout dari HP.",
            connectNote = "Pilih \"Tautkan dengan nomor telepon\", masukkan nomormu, lalu ketik kode yang muncul di aplikasi WhatsApp."
        )
    )

    private lateinit var pages: List<Page>
    private lateinit var pager: ViewPager2
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var updater: Updater
    private val prefs by lazy { getSharedPreferences("allchat", MODE_PRIVATE) }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        progressBar = findViewById(R.id.progress)
        pager = findViewById(R.id.pager)
        bottomNav = findViewById(R.id.bottomNav)
        updater = Updater(this)

        CookieManager.getInstance().setAcceptCookie(true)

        pages = services.map { buildPage(it) }

        pager.adapter = PagerAdapter(pages)
        pager.offscreenPageLimit = pages.size

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                bottomNav.selectedItemId = services[position].navId
                applyTheme(position)
            }
        })
        bottomNav.setOnItemSelectedListener { item ->
            val index = services.indexOfFirst { it.navId == item.itemId }
            if (index >= 0 && pager.currentItem != index) pager.setCurrentItem(index, true)
            true
        }
        bottomNav.setOnItemReselectedListener { current().web.scrollTo(0, 0) }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reload -> current().web.reload()
                R.id.action_open_app -> openNativeApp(current().service)
                R.id.action_update -> updater.check(silent = false)
                R.id.action_disconnect -> confirmDisconnect(current())
            }
            true
        }

        applyTheme(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = current().web
                if (current().connect.visibility != View.VISIBLE && web.canGoBack()) web.goBack()
                else moveTaskToBack(true) // tetap hidup di latar, tidak perlu memuat ulang
            }
        })

        updater.check(silent = true)
    }

    private fun current() = pages[pager.currentItem]

    private fun isConnected(s: ChatService) = prefs.getBoolean("connected_${s.key}", false)

    // ---------- Tampilan ----------

    private fun applyTheme(position: Int) {
        val s = services[position]
        val color = ContextCompat.getColor(this, s.colorRes)
        val muted = ContextCompat.getColor(this, R.color.muted)
        toolbar.title = s.title
        toolbar.subtitle = "AllChat"
        toolbar.setTitleTextColor(color)

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val tint = ColorStateList(states, intArrayOf(color, muted))
        bottomNav.itemIconTintList = tint
        bottomNav.itemTextColor = tint
        bottomNav.itemActiveIndicatorColor =
            ColorStateList.valueOf(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))

        progressBar.setIndicatorColor(color)
        updateProgress()
    }

    private fun updateProgress() {
        val p = current()
        val loading = p.connect.visibility != View.VISIBLE && p.progress < 100
        progressBar.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        if (loading) progressBar.setProgressCompat(p.progress, true)
    }

    private fun buildPage(s: ChatService): Page {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val web = createWebView(s)
        val connect = LayoutInflater.from(this).inflate(R.layout.page_connect, root, false)
        val page = Page(s, root, web, connect)
        web.tag = page

        val color = ContextCompat.getColor(this, s.colorRes)
        connect.findViewById<View>(R.id.iconCircle).backgroundTintList = ColorStateList.valueOf(color)
        connect.findViewById<ImageView>(R.id.icon).setImageResource(s.iconRes)
        connect.findViewById<TextView>(R.id.title).text = "Hubungkan ${s.title}"
        connect.findViewById<TextView>(R.id.desc).text = s.connectDesc
        connect.findViewById<TextView>(R.id.note).text = s.connectNote
        connect.findViewById<MaterialButton>(R.id.connectButton).apply {
            backgroundTintList = ColorStateList.valueOf(color)
            setOnClickListener { connect(page) }
        }

        root.addView(web)
        root.addView(connect)

        if (isConnected(s)) {
            connect.visibility = View.GONE
            web.loadUrl(s.url)
        }
        return page
    }

    private fun connect(page: Page) {
        prefs.edit().putBoolean("connected_${page.service.key}", true).apply()
        page.connect.animate().alpha(0f).setDuration(200).withEndAction {
            page.connect.visibility = View.GONE
            page.connect.alpha = 1f
        }.start()
        page.web.loadUrl(page.service.url)
        updateProgress()

        if (page.service.key == "whatsapp") {
            Snackbar.make(
                page.root,
                "Pilih \"Tautkan dengan nomor telepon\", lalu masukkan kodenya di WhatsApp.",
                Snackbar.LENGTH_INDEFINITE
            ).setAction("Buka WhatsApp") { openNativeApp(page.service) }
                .setActionTextColor(ContextCompat.getColor(this, R.color.whatsapp))
                .show()
        }
    }

    private fun confirmDisconnect(page: Page) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Putuskan ${page.service.title}?")
            .setMessage("Kamu akan keluar dari ${page.service.title} di AllChat. Akun di aplikasi aslinya tidak terpengaruh.")
            .setPositiveButton("Putuskan") { _, _ -> disconnect(page) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun disconnect(page: Page) {
        val cm = CookieManager.getInstance()
        for (host in page.service.hosts) {
            for (url in listOf("https://$host", "https://www.$host", "https://web.$host")) {
                val cookies = cm.getCookie(url) ?: continue
                cookies.split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }.forEach { name ->
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$host")
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
            WebStorage.getInstance().deleteOrigin("https://$host")
            WebStorage.getInstance().deleteOrigin("https://www.$host")
            WebStorage.getInstance().deleteOrigin("https://web.$host")
        }
        cm.flush()
        prefs.edit().putBoolean("connected_${page.service.key}", false).apply()
        page.web.loadUrl("about:blank")
        page.web.clearHistory()
        page.connect.visibility = View.VISIBLE
        updateProgress()
    }

    private fun openNativeApp(s: ChatService) {
        val intent = packageManager.getLaunchIntentForPackage(s.appPackage)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "Aplikasi ${s.title} tidak terpasang di HP ini", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(service: ChatService): WebView {
        val web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.setBackgroundColor(Color.TRANSPARENT)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = 100 // abaikan ukuran huruf sistem yang bikin layout pecah
            if (service.desktopWidth != null) {
                userAgentString = desktopUserAgent
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            } else {
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                if (scheme != "http" && scheme != "https") {
                    // intent:, whatsapp:, mailto:, dll. → serahkan ke Android
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    return true
                }
                val host = uri.host ?: return false
                val internal = service.hosts.any { host == it || host.endsWith(".$it") }
                if (internal) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {}
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                fitDesktopPage(view, service)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                fitDesktopPage(view, service)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                fitDesktopPage(view, service)
                CookieManager.getInstance().flush()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                (view.tag as? Page)?.progress = newProgress
                if (::pager.isInitialized && view == current().web) updateProgress()
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    filePicker.launch(params.createIntent())
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
        return web
    }

    /**
     * Halaman versi desktop (Messenger, WhatsApp) dipaksa punya lebar tetap,
     * lalu WebView mengecilkannya agar pas satu layar — tidak ada bagian yang terpotong.
     */
    private fun fitDesktopPage(view: WebView, s: ChatService) {
        val w = s.desktopWidth ?: return
        val js = """
            (function(){
              var c='width=$w, user-scalable=yes';
              function apply(){
                var m=document.querySelector('meta[name=viewport]');
                if(!m){ if(!document.head) return; m=document.createElement('meta'); m.name='viewport'; document.head.appendChild(m); }
                if(m.content!==c) m.content=c;
              }
              apply();
              if(!window.__allchatFit){
                window.__allchatFit=true;
                new MutationObserver(apply).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    private class PagerAdapter(private val pages: List<Page>) :
        RecyclerView.Adapter<PagerAdapter.Holder>() {

        class Holder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

        override fun getItemViewType(position: Int) = position
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val frame = FrameLayout(parent.context)
            frame.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return Holder(frame)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val root = pages[position].root
            (root.parent as? ViewGroup)?.removeView(root)
            holder.container.removeAllViews()
            holder.container.addView(root)
        }
    }
}
