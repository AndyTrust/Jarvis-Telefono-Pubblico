package com.jarvis.telefono.collegamento

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.telefono.BuildConfig
import com.jarvis.telefono.DettatoNativo
import com.jarvis.telefono.JarvisService
import com.jarvis.telefono.MainActivity
import com.jarvis.telefono.Permessi
import com.jarvis.telefono.R

/**
 * La webapp Jarvis dentro JBoss: il sito del Command Center della VPS, lo stesso che Boss usa dal Mac e dal PC
 * Windows (chat, lavagna, stato, missioni, Patrimonio). Dalla 0.6.0 è un modulo di JBoss; da integra-jarvis
 * (2026-10-08) ha TUTTO quello che aveva la WebActivity dell'app `com.jarvis.app` 1.2.4:
 *
 * - ponte `window.JarvisApp` (stesso nome: il sito lo usa in ponte.js, dettato.js, chiamata.js, mobile.js):
 *   dettato nativo nella chat (Google o Whisper, testo parziale), voce del sito (`speechSynthesis` → TTS del telefono),
 *   stato, salva e dimentica l'accesso, riprova, «Voce e telefono» (= la Home di JBoss);
 * - accesso salvato nella cassaforte di JBoss e UN accesso automatico per processo ([AccessoSalvato]);
 * - microfono concesso solo all'host del sito e solo col permesso Android; finestre alert/confirm/prompt col titolo
 *   «JBoss»; pagina offline con «Riprova»; ripresa dopo la morte del motore della WebView; schermo intero nelle aree
 *   sicure con il tasto ⋮ sul bordo destro (il sito gli lascia la fascia quando l'user agent ha `JarvisApp/`).
 *
 * Sicurezza (regole pure in [AccessoWeb], provate sulla JVM): solo https sull'host di base; il resto fuori (http,
 * https, mailto, tel) o bloccato; niente file e content, niente contenuti misti, cookie di terze parti spenti, Safe
 * Browsing acceso, debug della WebView solo nelle build di debug; il ponte risponde solo a pagine dell'host o alla
 * pagina offline. Utente e password mai nei log.
 *
 * Un microfono solo: il dettato del sito passa da [DettatoNativo], che mette in pausa l'ascolto di «Hey Boss» e lo
 * riprende alla fine. Le risposte della chat del sito NON le dice JBoss (Arbitro: origine «sito»): le legge il sito
 * solo se Boss usa «Chiama Jarvis», con la voce del telefono.
 */
class WebJarvisActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_FILO = "filo"
        const val EXTRA_PAGINA = "pagina"
        /** L'id del messaggio del Command Center di una notifica: la webapp ci va sopra. */
        const val EXTRA_FID = "fid"
        private const val TAG = "JarvisWebapp"
        /** Per processo: se l'accesso automatico fallisce, non si riprova finché l'app non riparte. */
        private val tentativo = TentativoAutomatico()
    }

    private lateinit var web: WebView
    private var base: String = ""
    private lateinit var accesso: AccessoSalvato
    private lateinit var dettato: DettatoNativo
    private lateinit var voceWeb: VoceWeb

    /** Aggiornati sul principale, letti dal thread del ponte JS. */
    @Volatile private var ponteAmmesso = false
    @Volatile private var paginaOffline = false
    @Volatile private var suPaginaAccesso = false
    private var ponteAttaccato = false
    private val ponte by lazy { Ponte() }

    /** Utente e password scritti a mano: si propongono da salvare solo se l'accesso riesce. Solo in memoria. */
    private var candidato: Pair<String, String>? = null
    private var accessoAutomaticoInviato = false
    private var eraSuAccesso = false
    private var ultimoUrlBuono: String? = null
    /** Notifica da raggiungere quando la pagina del sito è pronta (fid, filo). */
    private var notificaDaAprire: Pair<String, String?>? = null

    private var richiestaMicrofono: PermissionRequest? = null
    private var dettatoInAttesaDelPermesso = false

    private val permessoMicrofono = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concesso ->
        richiestaMicrofono?.let { r -> if (concesso) r.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else r.deny() }
        richiestaMicrofono = null
        if (dettatoInAttesaDelPermesso) {
            dettatoInAttesaDelPermesso = false
            dettato.avvia() // con il permesso negato consegna "" e stato() dice «microfono_negato»
        }
        if (concesso) MainActivity.riavviaServizioSeServe(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        base = CollegamentoJarvis.baseWeb(this).orEmpty()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val radice = FrameLayout(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.jarvis_fondo)) }
        if (!AccessoWeb.indirizzoBaseValido(base)) {
            radice.addView(TextView(this).apply {
                text = "Manca l'indirizzo della webapp Jarvis: mettilo in configurazione («web_url») o abbina la VPS con il QR."
                setTextColor(ContextCompat.getColor(context, R.color.jarvis_testo))
                setPadding(dp(24), dp(24), dp(24), dp(24))
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setContentView(radice)
            return
        }

        accesso = AccessoSalvato(this, base)
        voceWeb = VoceWeb(this) { js -> runOnUiThread { if (ponteAmmesso && !isDestroyed && ::web.isInitialized) web.evaluateJavascript(js, null) } }
        dettato = DettatoNativo(this, parziale = { testo -> consegnaParziale(testo) }) { testo -> consegnaDettato(testo) }

        web = WebView(this)
        radice.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        radice.addView(creaTastoMenu(), FrameLayout.LayoutParams(dp(34), dp(52), Gravity.END or Gravity.CENTER_VERTICAL))
        // Barre di sistema, ritaglio della fotocamera e tastiera: la pagina sta dentro l'area sicura.
        ViewCompat.setOnApplyWindowInsetsListener(radice) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(radice)
        configuraWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!paginaOffline && web.canGoBack()) { web.goBack(); return }
                // La webapp è un pezzo di JBoss: se è sola nel task (aperta da notifica o voce) «indietro» porta a JBoss.
                if (isTaskRoot) startActivity(Intent(this@WebJarvisActivity, MainActivity::class.java))
                finish()
            }
        })

        if (!CollegamentoJarvis.acceso(this)) {
            Toast.makeText(this, "Collegamento Jarvis spento: la webapp si apre, ma JBoss non riceve notifiche né parla con Jarvis.", Toast.LENGTH_LONG).show()
        }
        val ripristinata = savedInstanceState?.let { web.restoreState(it) } != null
        if (!ripristinata || intent.hasExtra(EXTRA_FILO) || intent.hasExtra(EXTRA_PAGINA)) carica(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configuraWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            safeBrowsingEnabled = true
            // Come in Chrome su ogni telefono: il <meta viewport> del sito vale e il testo non segue i caratteri di sistema.
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            userAgentString = AccessoWeb.userAgent(userAgentString ?: WebSettings.getDefaultUserAgent(this@WebJarvisActivity), BuildConfig.VERSION_NAME)
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, false) }
        web.webViewClient = Cliente()
        web.webChromeClient = Cromo()
        web.setBackgroundColor(ContextCompat.getColor(this, R.color.jarvis_fondo))
    }

    private fun creaTastoMenu(): View = TextView(this).apply {
        text = "⋮"
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        alpha = 0.55f
        contentDescription = "Menu di JBoss"
        background = GradientDrawable().apply {
            cornerRadii = floatArrayOf(dp(12).toFloat(), dp(12).toFloat(), 0f, 0f, 0f, 0f, dp(12).toFloat(), dp(12).toFloat())
            color = ColorStateList.valueOf(Color.parseColor("#66000000"))
        }
        setOnClickListener { v -> apriMenu(v) }
    }

    private fun apriMenu(ancora: View) {
        PopupMenu(this, ancora).apply {
            menu.add(0, 1, 0, "Ricarica")
            menu.add(0, 2, 1, "Voce e telefono (JBoss)")
            menu.add(0, 3, 2, "Dimentica l'accesso")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> ricarica()
                    2 -> apriJBoss()
                    3 -> confermaDimenticaAccesso()
                }
                true
            }
            show()
        }
    }

    // ─── navigazione ────────────────────────────────────────────────────────────

    private fun carica(i: Intent?) {
        notificaDaAprire = i?.getStringExtra(EXTRA_FID)?.takeIf { it.isNotBlank() }?.let { it to i.getStringExtra(EXTRA_FILO) }
        caricaUrl(AccessoWeb.indirizzo(base, i?.getStringExtra(EXTRA_FILO), i?.getStringExtra(EXTRA_PAGINA)))
    }

    /** Carica [url] dell'host di base (il ponte va attaccato PRIMA del caricamento per valere su quella pagina). */
    private fun caricaUrl(url: String) {
        if (!AccessoWeb.eHostBase(url, base)) return
        attaccaPonte(true)
        web.loadUrl(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::web.isInitialized) return
        val filo = intent.getStringExtra(EXTRA_FILO)?.filter { it.isLetterOrDigit() || it == '-' }
        val fid = intent.getStringExtra(EXTRA_FID)?.takeIf { it.isNotBlank() }
        if (fid != null && ponteAmmesso && !paginaOffline && !suPaginaAccesso) {
            web.evaluateJavascript(AccessoWeb.scriptVaiANotifica(fid, filo), null)
        } else if (!filo.isNullOrBlank() && ponteAmmesso && !paginaOffline) {
            web.evaluateJavascript("window.CCNotifiche&&window.CCNotifiche.apri('$filo');location.hash='#chat';", null)
        } else carica(intent)
    }

    private fun ricarica() { if (paginaOffline) caricaUrl(ultimoUrlBuono ?: base) else web.reload() }

    private fun mostraOffline() {
        if (paginaOffline) return
        val html = runCatching { assets.open("webapp/offline.html").bufferedReader().use { it.readText() } }.getOrNull()
        if (html == null) {
            Toast.makeText(this, "La webapp di Jarvis non risponde", Toast.LENGTH_LONG).show()
            return
        }
        paginaOffline = true
        ponteAmmesso = true
        attaccaPonte(true)
        // Senza indirizzo di base: la pagina locale ha un'origine opaca e non vede cookie né dati del sito.
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /** Aggiunge o toglie window.JarvisApp; vale dal prossimo caricamento. */
    private fun attaccaPonte(si: Boolean) {
        if (si && !ponteAttaccato) {
            web.addJavascriptInterface(ponte, "JarvisApp")
            ponteAttaccato = true
        } else if (!si && ponteAttaccato) {
            web.removeJavascriptInterface("JarvisApp")
            ponteAttaccato = false
        }
    }

    private fun apriFuori(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Nessuna app per aprire questo indirizzo.", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Cliente : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString()
            if (AccessoWeb.eHostBase(url, base)) {
                if (request.isForMainFrame) attaccaPonte(true)
                return false
            }
            if (request.isForMainFrame && AccessoWeb.daAprireFuori(url) && request.url != null) apriFuori(request.url)
            else Log.i(TAG, "navigazione bloccata (${request.url?.scheme})")
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            // about:blank in cima è solo la pagina offline: la navigazione verso about: è bloccata.
            paginaOffline = url == "about:blank"
            val buono = AccessoWeb.eHostBase(url, base)
            ponteAmmesso = buono || paginaOffline
            suPaginaAccesso = AccessoWeb.ePaginaAccesso(url, base)
            if (buono && !paginaOffline) {
                view.evaluateJavascript(VoceWeb.POLYFILL, null)
                view.evaluateJavascript(AccessoWeb.SCRIPT_RICONOSCIMENTO, null)
            }
            if (!buono && !paginaOffline) {
                // Non dovrebbe mai succedere (shouldOverrideUrlLoading lo impedisce): seconda rete.
                attaccaPonte(false)
                view.stopLoading()
                Log.w(TAG, "pagina fuori dall'host di base fermata")
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!AccessoWeb.eHostBase(url, base)) return
            ultimoUrlBuono = url
            view.evaluateJavascript(VoceWeb.POLYFILL, null)
            view.evaluateJavascript(AccessoWeb.SCRIPT_RICONOSCIMENTO, null)
            CookieManager.getInstance().flush()
            dopoInvio(url)
            val paginaAccesso = AccessoWeb.ePaginaAccesso(url, base)
            if (eraSuAccesso && !paginaAccesso) view.clearHistory() // indietro non riporta alla pagina di accesso
            eraSuAccesso = paginaAccesso
            Log.i(TAG, "pagina caricata (${if (paginaAccesso) "accesso" else "sito"})")
            if (paginaAccesso) suAccesso(view)
            else notificaDaAprire?.let { (fid, filo) ->
                notificaDaAprire = null
                Log.i(TAG, "vado alla notifica del Command Center")
                view.evaluateJavascript(AccessoWeb.scriptVaiANotifica(fid, filo), null)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                Log.i(TAG, "pagina non caricata: errore ${error.errorCode}")
                mostraOffline()
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && AccessoWeb.eRipiego(response.statusCode) && !AccessoWeb.ePaginaAccesso(request.url?.toString(), base)) {
                Log.i(TAG, "pagina non caricata: HTTP ${response.statusCode}")
                mostraOffline()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Il motore della pagina è morto: si ricrea la schermata invece di chiudere JBoss.
            Log.w(TAG, "motore della WebView terminato (crash=${detail.didCrash()})")
            recreate()
            return true
        }
    }

    private inner class Cromo : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val voluto = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!voluto || !AccessoWeb.eHostBase(request.origin?.toString(), base)) {
                    request.deny()
                    return@runOnUiThread
                }
                if (Permessi.microfono(this@WebJarvisActivity)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    richiestaMicrofono?.deny()
                    richiestaMicrofono = request
                    permessoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            finestraJs(url, message, result, conAnnulla = false)

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean =
            finestraJs(url, message, result, conAnnulla = true)

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (!AccessoWeb.eHostBase(url, base) || isFinishing) return false
            val campo = EditText(this@WebJarvisActivity).apply { setText(defaultValue.orEmpty()); setSingleLine() }
            val cornice = FrameLayout(this@WebJarvisActivity).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo) }
            AlertDialog.Builder(this@WebJarvisActivity)
                .setTitle("JBoss")
                .setMessage(message.orEmpty())
                .setView(cornice)
                .setPositiveButton("OK") { _, _ -> result.confirm(campo.text.toString()) }
                .setNegativeButton("Annulla") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        private fun finestraJs(url: String?, message: String?, result: JsResult, conAnnulla: Boolean): Boolean {
            if (!AccessoWeb.eHostBase(url, base) || isFinishing) return false
            AlertDialog.Builder(this@WebJarvisActivity)
                .setTitle("JBoss")
                .setMessage(message.orEmpty())
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .apply { if (conAnnulla) setNegativeButton("Annulla") { _, _ -> result.cancel() } }
                .setOnCancelListener { if (conAnnulla) result.cancel() else result.confirm() }
                .show()
            return true
        }
    }

    // ─── accesso salvato (nella cassaforte) ─────────────────────────────────────

    private fun suAccesso(view: WebView) {
        if (accessoAutomaticoInviato) {
            // Siamo tornati sulla pagina di accesso dopo l'invio automatico: non è riuscito.
            accessoAutomaticoInviato = false
            Toast.makeText(this, "Accesso automatico non riuscito: scrivi utente e password", Toast.LENGTH_LONG).show()
        }
        val salvate = accesso.leggi()
        when (AccessoWeb.decidi(true, salvate != null, tentativo.libero, accesso.rifiutato() || !accesso.disponibile)) {
            AccessoWeb.Azione.COMPILA_E_INVIA -> if (salvate != null && tentativo.prendi()) {
                accessoAutomaticoInviato = true
                view.evaluateJavascript(AccessoWeb.scriptCompila(salvate.first, salvate.second)) { esito ->
                    if (esito?.contains("inviato") != true) accessoAutomaticoInviato = false
                }
            }
            AccessoWeb.Azione.ASCOLTA_INVIO -> view.evaluateJavascript(AccessoWeb.scriptAscoltaInvio(), null)
            AccessoWeb.Azione.NIENTE -> {}
        }
    }

    /** Dopo un invio scritto a mano: riuscito → si propone di salvare; di nuovo l'accesso → si butta. */
    private fun dopoInvio(url: String?) {
        val c = candidato ?: return
        when (AccessoWeb.dopoInvio(url, base)) {
            AccessoWeb.Candidato.SCARTA -> candidato = null
            AccessoWeb.Candidato.ASPETTA -> {}
            AccessoWeb.Candidato.PROPONI -> { candidato = null; proponiSalvataggio(c) }
        }
    }

    private fun proponiSalvataggio(c: Pair<String, String>) {
        if (!accesso.disponibile || isFinishing) return
        val salvate = accesso.leggi()
        if (salvate == c) return
        AlertDialog.Builder(this)
            .setTitle("JBoss")
            .setMessage(if (salvate == null) "Salvare utente e password della webapp nella cassaforte di JBoss?" else "Aggiornare utente e password salvati con quelli appena usati?")
            .setPositiveButton("Sì") { _, _ ->
                val ok = accesso.salva(c.first, c.second)
                Toast.makeText(this, if (ok) "Salvati nella cassaforte, cifrati" else "Non sono riuscito a salvarli: la cassaforte non risponde", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("No") { _, _ -> if (salvate == null) accesso.segnaRifiutato() }
            .show()
    }

    private fun confermaDimenticaAccesso() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("JBoss")
            .setMessage("Dimenticare utente e password salvati? Al prossimo accesso li dovrai riscrivere.")
            .setPositiveButton("Dimentica") { _, _ -> dimenticaAccesso() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun dimenticaAccesso() {
        accesso.dimentica()
        candidato = null
        Toast.makeText(this, "Utente e password dimenticati. La sessione aperta resta finché non premi Esci.", Toast.LENGTH_LONG).show()
    }

    private fun apriJBoss() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ─── dettato nella chat del sito ────────────────────────────────────────────

    private fun avviaDettato() {
        if (!Permessi.microfono(this)) {
            dettatoInAttesaDelPermesso = true
            permessoMicrofono.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        dettato.avvia()
    }

    private fun consegnaDettato(testo: String) {
        if (isDestroyed || !::web.isInitialized) return
        web.evaluateJavascript(AccessoWeb.scriptDettato(testo), null)
    }

    private fun consegnaParziale(testo: String) {
        if (isDestroyed || !::web.isInitialized || !ponteAmmesso || paginaOffline) return
        web.evaluateJavascript(AccessoWeb.scriptParziale(testo), null)
    }

    private fun statoJson(): String = AccessoWeb.statoJson(
        BuildConfig.VERSION_NAME, dettato.fase.name.lowercase(), dettato.motivo, dettato.motore,
        dettato.googlePresente(), dettato.whisperPresente(), Permessi.microfono(this), JarvisService.instance != null,
        accesso.haCredenziali(),
    )

    /**
     * window.JarvisApp. I metodi arrivano su un thread del WebView: si controlla che la pagina sia dell'host di base
     * (o la pagina offline) e si passa al thread principale. Solo String, boolean.
     */
    private inner class Ponte {
        private fun ok(): Boolean = ponteAmmesso && !isDestroyed

        @JavascriptInterface
        fun avviaDettato(): Boolean {
            if (!ok() || paginaOffline) return false
            runOnUiThread { this@WebJarvisActivity.avviaDettato() }
            return true
        }

        @JavascriptInterface
        fun fermaDettato() { if (ok()) runOnUiThread { dettato.ferma() } }

        @JavascriptInterface
        fun parla(id: String?, testo: String?, rate: Float) {
            if (!ok() || paginaOffline || id.isNullOrEmpty() || testo == null || id.length > 40) return
            runOnUiThread { voceWeb.parla(id, testo.take(20000), rate) }
        }

        @JavascriptInterface
        fun taci() { if (ok()) runOnUiThread { voceWeb.taci() } }

        @JavascriptInterface
        fun staParlando(): Boolean = ok() && voceWeb.staParlando()

        @JavascriptInterface
        fun stato(): String = if (ok()) statoJson() else "{}"

        @JavascriptInterface
        fun salvaAccesso(utente: String?, password: String?) {
            if (!ok() || !suPaginaAccesso || utente.isNullOrEmpty() || password.isNullOrEmpty()) return
            if (utente.length > 128 || password.length > 1024) return
            runOnUiThread { candidato = utente to password }
        }

        @JavascriptInterface
        fun dimenticaAccesso() { if (ok() && !paginaOffline) runOnUiThread { this@WebJarvisActivity.dimenticaAccesso() } }

        @JavascriptInterface
        fun riprova() { if (ok()) runOnUiThread { caricaUrl(ultimoUrlBuono ?: base) } }

        @JavascriptInterface
        fun apriVoceETelefono() { if (ok()) runOnUiThread { apriJBoss() } }
    }

    // ─── ciclo di vita ──────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (::web.isInitialized) { CookieManager.getInstance().flush(); web.onPause() }
        super.onPause()
    }

    override fun onStop() {
        // Fuori dallo schermo il dettato si chiude: il microfono torna a «Hey Boss».
        if (::dettato.isInitialized) dettato.annulla()
        if (::voceWeb.isInitialized) voceWeb.taci()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        if (::voceWeb.isInitialized) voceWeb.rilascia()
        if (::dettato.isInitialized) dettato.rilascia()
        richiestaMicrofono?.deny()
        richiestaMicrofono = null
        if (::web.isInitialized) { (web.parent as? ViewGroup)?.removeView(web); web.destroy() }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
