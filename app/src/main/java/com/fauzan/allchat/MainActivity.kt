package com.fauzan.allchat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.TransitionManager
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator

/** Satu layanan chat. */
data class ChatService(
    val key: String,
    val title: String,
    val url: String,
    val hosts: List<String>,
    val colorRes: Int,
    val iconRes: Int,
    val appPackage: String,
    /** null = tampilan HP; angka = lebar halaman desktop yang dipaskan ke layar */
    val desktopWidth: Int?,
    val connectDesc: String
)

/** Satu halaman: WebView + layar "Hubungkan" di atasnya. */
class Page(val service: ChatService, val root: FrameLayout, val web: WebView, val connect: View) {
    var progress = 100
    var loaded = false
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
            colorRes = R.color.messenger, iconRes = R.drawable.ic_messenger,
            appPackage = "com.facebook.orca",
            desktopWidth = 760,
            connectDesc = "Masuk sekali dengan akun Facebook,\nselanjutnya langsung terbuka."
        ),
        ChatService(
            key = "instagram", title = "Instagram",
            url = "https://www.instagram.com/direct/inbox/",
            hosts = listOf("instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net"),
            colorRes = R.color.instagram, iconRes = R.drawable.ic_instagram,
            appPackage = "com.instagram.android",
            desktopWidth = null,
            connectDesc = "Masuk sekali dengan akun Instagram,\nselanjutnya langsung terbuka."
        )
    )

    private lateinit var pages: List<Page>
    private lateinit var pager: ViewPager2
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var bar: LinearLayout
    private lateinit var tabs: List<Triple<LinearLayout, ImageView, TextView>>
    private lateinit var updater: Updater
    private val handler = Handler(Looper.getMainLooper())
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
        setupSystemBars()

        pager = findViewById(R.id.pager)
        progressBar = findViewById(R.id.progress)
        bar = findViewById(R.id.bar)
        tabs = listOf(
            Triple(findViewById(R.id.tabMessenger), findViewById(R.id.iconMessenger), findViewById(R.id.labelMessenger)),
            Triple(findViewById(R.id.tabInstagram), findViewById(R.id.iconInstagram), findViewById(R.id.labelInstagram))
        )
        updater = Updater(this)

        CookieManager.getInstance().setAcceptCookie(true)
        pages = services.map { buildPage(it) }

        pager.adapter = PagerAdapter(pages)
        pager.offscreenPageLimit = pages.size

        val start = prefs.getInt("last_tab", 0).coerceIn(0, pages.size - 1)
        pager.setCurrentItem(start, false)
        selectTab(start, animate = false)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectTab(position, animate = true)
                prefs.edit().putInt("last_tab", position).apply()
                ensureLoaded(pages[position])
            }
        })
        tabs.forEachIndexed { i, t ->
            t.first.setOnClickListener {
                if (pager.currentItem == i) pages[i].web.scrollTo(0, 0) else pager.setCurrentItem(i, true)
            }
        }
        findViewById<View>(R.id.moreButton).setOnClickListener { showMenu() }

        // Cepat: muat dulu tab yang sedang dibuka, tab lain menyusul sebentar kemudian
        ensureLoaded(pages[start])
        handler.postDelayed({ pages.forEach { ensureLoaded(it) } }, 1500)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val p = current()
                if (p.connect.visibility != View.VISIBLE && p.web.canGoBack()) p.web.goBack()
                else moveTaskToBack(true) // tetap hidup di latar → dibuka lagi langsung tampil
            }
        })

        handler.postDelayed({ updater.check(silent = true) }, 4000)
    }

    private fun current() = pages[pager.currentItem]
    private fun isConnected(s: ChatService) = prefs.getBoolean("connected_${s.key}", false)

    private fun setupSystemBars() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    // ---------- Tab bawah ----------

    private fun selectTab(position: Int, animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(bar)
        val muted = ContextCompat.getColor(this, R.color.muted)
        tabs.forEachIndexed { i, (box, icon, label) ->
            val color = ContextCompat.getColor(this, services[i].colorRes)
            val selected = i == position
            box.backgroundTintList = ColorStateList.valueOf(
                if (selected) Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
                else Color.TRANSPARENT
            )
            icon.imageTintList = ColorStateList.valueOf(if (selected) color else muted)
            label.setTextColor(color)
            label.visibility = if (selected) View.VISIBLE else View.GONE
        }
        progressBar.setIndicatorColor(ContextCompat.getColor(this, services[position].colorRes))
        updateProgress()
    }

    private fun updateProgress() {
        if (!::pager.isInitialized) return
        val p = current()
        val loading = p.connect.visibility != View.VISIBLE && p.loaded && p.progress < 100
        progressBar.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        if (loading) progressBar.setProgressCompat(p.progress, true)
    }

    // ---------- Menu ----------

    private fun showMenu() {
        val p = current()
        val sheet = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_menu, null)
        v.findViewById<TextView>(R.id.sheetTitle).text = "${p.service.title}  ·  AllChat v${BuildConfig.VERSION_NAME}"
        v.findViewById<View>(R.id.rowReload).setOnClickListener { sheet.dismiss(); p.web.reload() }
        v.findViewById<View>(R.id.rowOpenApp).setOnClickListener { sheet.dismiss(); openNativeApp(p.service) }
        v.findViewById<View>(R.id.rowUpdate).setOnClickListener { sheet.dismiss(); updater.check(silent = false) }
        v.findViewById<View>(R.id.rowDisconnect).apply {
            visibility = if (isConnected(p.service)) View.VISIBLE else View.GONE
            setOnClickListener { sheet.dismiss(); confirmDisconnect(p) }
        }
        sheet.setContentView(v)
        sheet.show()
    }

    // ---------- Halaman ----------

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
        connect.findViewById<TextView>(R.id.title).text = s.title
        connect.findViewById<TextView>(R.id.desc).text = s.connectDesc
        connect.findViewById<MaterialButton>(R.id.connectButton).apply {
            backgroundTintList = ColorStateList.valueOf(color)
            setOnClickListener { connect(page) }
        }

        root.addView(web)
        root.addView(connect)
        connect.visibility = if (isConnected(s)) View.GONE else View.VISIBLE
        return page
    }

    private fun ensureLoaded(page: Page) {
        if (page.loaded || !isConnected(page.service)) return
        page.loaded = true
        page.web.loadUrl(page.service.url)
    }

    private fun connect(page: Page) {
        prefs.edit().putBoolean("connected_${page.service.key}", true).apply()
        ensureLoaded(page)
        page.connect.animate().alpha(0f).setDuration(180).withEndAction {
            page.connect.visibility = View.GONE
            page.connect.alpha = 1f
            updateProgress()
        }.start()
    }

    private fun confirmDisconnect(page: Page) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Keluar dari ${page.service.title}?")
            .setMessage("Hanya di AllChat. Akun di aplikasi aslinya tidak terpengaruh.")
            .setPositiveButton("Keluar") { _, _ -> disconnect(page) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun disconnect(page: Page) {
        val cm = CookieManager.getInstance()
        // Messenger & Instagram berbagi beberapa domain Meta; hapus milik layanan ini saja
        val own = if (page.service.key == "instagram") listOf("instagram.com") else listOf("facebook.com", "messenger.com")
        for (host in own) {
            for (url in listOf("https://$host", "https://www.$host")) {
                val cookies = cm.getCookie(url) ?: continue
                cookies.split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }.forEach { name ->
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$host")
                    cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
            WebStorage.getInstance().deleteOrigin("https://$host")
            WebStorage.getInstance().deleteOrigin("https://www.$host")
        }
        cm.flush()
        prefs.edit().putBoolean("connected_${page.service.key}", false).apply()
        page.loaded = false
        page.web.loadUrl("about:blank")
        page.web.clearHistory()
        page.connect.visibility = View.VISIBLE
        updateProgress()
    }

    private fun openNativeApp(s: ChatService) {
        val intent = packageManager.getLaunchIntentForPackage(s.appPackage)
        if (intent != null) startActivity(intent)
        else Toast.makeText(this, "Aplikasi ${s.title} tidak terpasang", Toast.LENGTH_SHORT).show()
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(service: ChatService): WebView {
        val web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.bg))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true   // video tidak autoplay → lebih ringan & hemat kuota
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT       // pakai cache → buka ulang lebih cepat
            offscreenPreRaster = true                  // geser antar tab terasa mulus
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = true
            if (service.desktopWidth != null) {
                userAgentString = desktopUserAgent
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            } else {
                setSupportZoom(false)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: return false
                if (scheme != "http" && scheme != "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    return true
                }
                val host = uri.host ?: return false
                if (service.hosts.any { host == it || host.endsWith(".$it") }) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) {}
                return true
            }

            override fun onPageCommitVisible(view: WebView, url: String?) = fitDesktopPage(view, service)

            override fun onPageFinished(view: WebView, url: String?) {
                fitDesktopPage(view, service)
                CookieManager.getInstance().flush()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                (view.tag as? Page)?.progress = newProgress
                if (newProgress > 10) fitDesktopPage(view, service)
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

    /** Halaman desktop Messenger dipaskan ke lebar layar, tidak ada yang terpotong. */
    private fun fitDesktopPage(view: WebView, s: ChatService) {
        val w = s.desktopWidth ?: return
        val js = """
            (function(){
              if(window.__allchatFit) return;
              var c='width=$w, user-scalable=yes';
              function apply(){
                var m=document.querySelector('meta[name=viewport]');
                if(!m){ if(!document.head) return; m=document.createElement('meta'); m.name='viewport'; document.head.appendChild(m); }
                if(m.content!==c) m.content=c;
              }
              if(!document.head) return;
              window.__allchatFit=true;
              apply();
              new MutationObserver(apply).observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});
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
