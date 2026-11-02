package com.fauzan.allchat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/** Satu "halaman" chat: nama tab, alamat web, domain yang boleh dibuka di dalam app, dan perlu tampilan desktop atau tidak. */
data class ChatService(
    val title: String,
    val url: String,
    val hosts: List<String>,
    val desktopMode: Boolean
)

class MainActivity : AppCompatActivity() {

    private val desktopUserAgent =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"

    // Urutan geser: Messenger -> Instagram -> WhatsApp
    private val services = listOf(
        ChatService(
            "Messenger", "https://www.facebook.com/messages/",
            listOf("facebook.com", "messenger.com", "fbcdn.net", "fb.com"), desktopMode = true
        ),
        ChatService(
            "Instagram", "https://www.instagram.com/direct/inbox/",
            listOf("instagram.com", "cdninstagram.com", "facebook.com"), desktopMode = false
        ),
        ChatService(
            "WhatsApp", "https://web.whatsapp.com/",
            listOf("whatsapp.com", "whatsapp.net"), desktopMode = true
        )
    )

    private lateinit var webViews: List<WebView>
    private lateinit var pager: ViewPager2
    private lateinit var progress: LinearProgressIndicator

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

        pager = findViewById(R.id.pager)
        progress = findViewById(R.id.progress)

        CookieManager.getInstance().setAcceptCookie(true)

        webViews = services.map { createWebView(it) }

        pager.adapter = PagerAdapter(webViews)
        pager.offscreenPageLimit = services.size // ketiganya tetap hidup, tidak reload saat digeser

        TabLayoutMediator(findViewById<TabLayout>(R.id.tabs), pager) { tab, pos ->
            tab.text = services[pos].title
        }.attach()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webViews[pager.currentItem]
                if (current.canGoBack()) current.goBack() else finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(service: ChatService): WebView {
        val web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (service.desktopMode) {
                userAgentString = desktopUserAgent
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host ?: return false
                val internal = service.hosts.any { host == it || host.endsWith(".$it") }
                if (internal) return false
                // Link luar (misal link berita di chat) dibuka di browser HP
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (view == webViews.getOrNull(pager.currentItem)) progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
                CookieManager.getInstance().flush() // simpan login
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view == webViews.getOrNull(pager.currentItem)) {
                    progress.setProgressCompat(newProgress, true)
                    progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }

            // Supaya bisa kirim foto/file dari galeri
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
                    Toast.makeText(this@MainActivity, "Tidak ada aplikasi pemilih file", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        web.loadUrl(service.url)
        return web
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    private class PagerAdapter(private val pages: List<WebView>) :
        RecyclerView.Adapter<PagerAdapter.Holder>() {

        class Holder(val container: ViewGroup) : RecyclerView.ViewHolder(container)

        override fun getItemViewType(position: Int) = position
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val frame = android.widget.FrameLayout(parent.context)
            frame.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return Holder(frame)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val web = pages[position]
            (web.parent as? ViewGroup)?.removeView(web)
            holder.container.removeAllViews()
            holder.container.addView(web)
        }
    }
}
