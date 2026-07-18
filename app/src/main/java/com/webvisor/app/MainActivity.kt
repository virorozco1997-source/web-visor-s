package com.webvisor.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.webvisor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Host del primer sitio cargado; referencia para DomainPolicy en modo estricto. */
    private var originalHost: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeTransparentStatusBar()
        setupWebView()

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Barra de estado invisible/transparente para maximizar el espacio
     * de la web. El contenido se dibuja también detrás de la status bar.
     */
    private fun setupEdgeToEdgeTransparentStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val insetsController = WindowInsetsControllerCompat(window, binding.root)
        insetsController.isAppearanceLightStatusBars = false

        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            // No forzamos padding: dejamos que el WebView llegue hasta el borde.
            view.onApplyWindowInsets(insets)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString + " WebVisor/1.0"

        // Compartir discreto: mantener presionado la franja inferior de la
        // pantalla (últimos ~56dp) comparte el link actual. Se limita a esa
        // franja a propósito para no interferir con la selección de texto
        // nativa del WebView en el resto del contenido.
        val shareStripHeightPx = (56 * resources.displayMetrics.density).toInt()
        val shareGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                shareCurrentUrl()
            }
        })
        webView.setOnTouchListener { view, event ->
            if (event.y >= view.height - shareStripHeightPx) {
                shareGestureDetector.onTouchEvent(event)
            }
            // Devolvemos false siempre para que el WebView reciba el evento
            // con normalidad (scroll, zoom, selección de texto, etc.).
            false
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.swipeRefresh.isRefreshing = true
                binding.emptyState.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                // No se ignoran errores SSL silenciosamente: se cancela la carga.
                handler.cancel()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()

                // Esquemas que no son web (intent://, tel:, mailto:, whatsapp:, market://, etc.)
                // se delegan a la app nativa correspondiente del sistema.
                if (scheme != "http" && scheme != "https") {
                    return openWithExternalApp(uri)
                }

                // Regla de Dominio Flexible: por defecto se permite navegar
                // libremente (YouTube, pagos, login, etc. incluidos). Ver DomainPolicy.
                return if (DomainPolicy.isNavigationAllowed(uri.toString(), originalHost)) {
                    false // false = "no lo intercepto", que lo cargue el propio WebView
                } else {
                    openWithExternalApp(uri)
                }
            }
        }
    }

    private fun openWithExternalApp(uri: Uri): Boolean {
        return try {
            val externalIntent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(externalIntent)
            true
        } catch (e: ActivityNotFoundException) {
            // No hay app instalada que maneje ese esquema; evitamos crash.
            true
        }
    }

    /**
     * Comparte la URL actualmente cargada usando el selector nativo de
     * Android (WhatsApp, Email, copiar al portapapeles, etc.). Se activa
     * con un long-press en la franja inferior de la pantalla; como única
     * confirmación visible se usa una vibración breve, sin ícono ni botón.
     */
    private fun shareCurrentUrl() {
        val url = binding.webView.url
        if (url.isNullOrEmpty()) return

        binding.webView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    /**
     * Extrae la URL de un Deep Link (QR escaneado, link compartido desde
     * WhatsApp/Email/Galería, etc.) y la carga en el WebView.
     */
    private fun handleIncomingIntent(intent: Intent) {
        val data: Uri? = intent.data

        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            originalHost = data.host
            binding.emptyState.visibility = View.GONE
            binding.webView.loadUrl(data.toString())
        } else if (binding.webView.url.isNullOrEmpty()) {
            // La app se abrió desde el ícono, sin ningún enlace: mostramos
            // una pantalla de espera en lugar de una web en blanco.
            binding.emptyState.visibility = View.VISIBLE
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
