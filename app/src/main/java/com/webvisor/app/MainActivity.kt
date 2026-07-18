package com.webvisor.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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

        setupStatusBar()
        setupWebView()
        setupFullScreenOnScroll()
        setupDownloads()

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        applyDarkModePreferences()
        handleIncomingIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // La Activity está marcada con android:configChanges="...|uiMode" para
        // no reiniciarse (perdería la página cargada) cuando el sistema pasa
        // de claro a oscuro o viceversa. Por eso el ajuste se hace a mano acá.
        applyDarkModePreferences(newConfig)
    }

    private fun isSystemInDarkMode(config: Configuration = resources.configuration): Boolean {
        val nightModeFlags = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Sincroniza la app con el modo claro/oscuro del sistema:
     * - Color de los íconos de la barra de estado (oscuros sobre fondo claro,
     *   claros sobre fondo oscuro).
     * - Le pide al WebView que oscurezca automáticamente las páginas que no
     *   tengan su propio modo oscuro (si el dispositivo lo soporta).
     */
    private fun applyDarkModePreferences(config: Configuration = resources.configuration) {
        val isDark = isSystemInDarkMode(config)

        WindowInsetsControllerCompat(window, binding.root).isAppearanceLightStatusBars = !isDark

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, isDark)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * La barra de estado y la de navegación reservan su propio espacio
     * (nada se dibuja detrás): la web se ve como el contenido normal de una
     * app, no "por debajo" de la hora/batería. El color de ambas barras se
     * sincroniza con el fondo de cada página (ver syncStatusBarColorWithPage).
     */
    private fun setupStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    /**
     * Lee el color de fondo real de la página cargada (el de <body>, o si
     * ese es transparente, el de <html>) y lo aplica a la barra de estado y
     * a la de navegación, para que se vean como una continuación de la web
     * en vez de una franja de color fijo ajena a la página.
     */
    private fun syncStatusBarColorWithPage() {
        val script = """
            (function() {
                var bodyColor = window.getComputedStyle(document.body).backgroundColor;
                if (!bodyColor || bodyColor === 'rgba(0, 0, 0, 0)' || bodyColor === 'transparent') {
                    return window.getComputedStyle(document.documentElement).backgroundColor;
                }
                return bodyColor;
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(script) { rawResult ->
            val color = parseCssColor(rawResult) ?: return@evaluateJavascript
            applyBarsColor(color)
        }
    }

    /** Aplica un color a ambas barras del sistema y ajusta el color de sus íconos. */
    private fun applyBarsColor(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color

        val lightIcons = isColorLight(color)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.isAppearanceLightStatusBars = lightIcons
        controller.isAppearanceLightNavigationBars = lightIcons
    }

    /**
     * Convierte el resultado de evaluateJavascript (un string con formato
     * CSS, ej. "rgb(18, 18, 18)" o "rgba(18, 18, 18, 1)", entre comillas
     * dobles por venir de JSON) a un color de Android. Devuelve null si no
     * se pudo interpretar o si el fondo es completamente transparente.
     */
    private fun parseCssColor(rawResult: String?): Int? {
        if (rawResult.isNullOrBlank() || rawResult == "null") return null

        val unquoted = rawResult.trim().removeSurrounding("\"")
        val numbers = Regex("[\\d.]+").findAll(unquoted).map { it.value }.toList()
        if (numbers.size < 3) return null

        return try {
            val r = numbers[0].toFloat().toInt().coerceIn(0, 255)
            val g = numbers[1].toFloat().toInt().coerceIn(0, 255)
            val b = numbers[2].toFloat().toInt().coerceIn(0, 255)
            val alpha = if (numbers.size >= 4) numbers[3].toFloat() else 1f
            if (alpha <= 0f) return null
            Color.rgb(r, g, b)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** true si el color es "claro" (conviene usar íconos oscuros encima). */
    private fun isColorLight(color: Int): Boolean {
        val luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return luminance > 0.5
    }

    /**
     * Oculta la barra de navegación inferior (modo inmersivo) para que la
     * web ocupe toda la pantalla como una app. La barra de estado (reloj,
     * batería, señal) NO se toca: queda siempre visible.
     */
    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    /** Vuelve a mostrar la barra de navegación inferior. */
    private fun showSystemBars() {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }

    /**
     * Al empezar a scrollear la web, la barra de navegación inferior
     * desaparece para dejar la página en pantalla completa, como si fuera
     * una app nativa. El reloj y la batería (barra de estado) siguen
     * visibles todo el tiempo. Si el usuario vuelve al principio de la
     * página, la barra de navegación reaparece.
     */
    private fun setupFullScreenOnScroll() {
        binding.webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > 0) {
                hideSystemBars()
            } else {
                showSystemBars()
            }
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
                syncStatusBarColorWithPage()
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

    /**
     * Registra un DownloadListener en el WebView: cualquier archivo que la
     * página quiera descargar (PDF, imágenes, adjuntos, etc.) se manda al
     * DownloadManager del sistema, que se encarga de bajarlo a la carpeta
     * "Descargas" del dispositivo y mostrar el progreso/notificación.
     */
    private fun setupDownloads() {
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(
                        mimeType.takeIf { it.isNotBlank() }
                            ?: MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(
                                    MimeTypeMap.getFileExtensionFromUrl(url)
                                )
                            ?: "application/octet-stream"
                    )
                    if (!cookies.isNullOrEmpty()) {
                        addRequestHeader("cookie", cookies)
                    }
                    addRequestHeader("User-Agent", userAgent)
                    setDescription(getString(R.string.app_name))
                    setTitle(fileName)
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }

                val downloadManager =
                    getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)

                Toast.makeText(
                    this,
                    getString(R.string.downloading_toast, fileName),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.download_error),
                    Toast.LENGTH_SHORT
                ).show()
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
            applyBarsColor(ContextCompat.getColor(this, R.color.sage_green))
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
