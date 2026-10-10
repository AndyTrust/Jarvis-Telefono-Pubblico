package com.jarvis.telefono

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.telefono.mani.NodoInfo
import com.jarvis.telefono.mani.PannelloConferma
import java.io.ByteArrayOutputStream

// Le mani di Jarvis sul telefono. Resta acceso finché l'utente non lo spegne da
// Impostazioni > Accessibilità: qui dentro eseguiamo solo comandi, non
// decidiamo mai da soli cosa fare — quello lo fa il ragionamento sulla server.
// 1.2.0: elementi numerati (come Voice Access), tocco sul nodo e non solo per
// coordinate, scorrimento, pressione lunga, HOME che funziona su Samsung,
// screenshot e il pannello della conferma d'invio. Tutto dal thread principale.
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        private const val MAX_ELEMENTI = 220

        var instance: JarvisAccessibilityService? = null
            private set
    }

    /** Gli elementi dell'ultima lettura numerata: «tocca 5» usa questi. */
    private var elementi: List<AccessibilityNodeInfo> = emptyList()
    private var pacchettoLettura: String? = null
    var pannello: PannelloConferma? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        pannello = PannelloConferma(this)
        // 1.2.0: le mani servono anche quando il servizio di Jarvis è morto (aggiornamento,
        // processo chiuso da Samsung): se Boss lo vuole acceso, lo si riaccende. Se l'ha spento
        // lui (Prefs.isAttivo falso) resta spento: è l'unico caso in cui Jarvis deve tacere.
        if (!JarvisService.isRunning && Prefs.isAttivo(this)) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java)) }
                .onFailure { Log.w(TAG, "servizio di Jarvis non riavviato: ${it.javaClass.simpleName}") }
        }
    }

    override fun onDestroy() {
        pannello?.nascondi()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ------------------------------------------------------------------ lettura

    /**
     * La radice della finestra dell'app in primo piano. rootInActiveWindow a volte è null
     * (Gmail in scrittura con la tastiera aperta, provato il 2026-10-07): allora si prende
     * dalla lista delle finestre la prima finestra d'applicazione, attiva o con il fuoco.
     */
    fun radice(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { r -> if (r.packageName?.toString() != packageName) return r }
        val app = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = app.firstOrNull { it.isActive } ?: app.firstOrNull { it.isFocused } ?: app.firstOrNull()
        return w?.root ?: rootInActiveWindow
    }

    /** Per il banco tecnico: le finestre che il servizio vede, con tipo e radice leggibile o no. */
    fun finestre(): String = runCatching { windows }.getOrDefault(emptyList()).joinToString("; ") {
        "t${it.type} att=${it.isActive} fuoco=${it.isFocused} ${it.title ?: ""} radice=${it.root?.packageName ?: "null"}"
    } + " | rootInActiveWindow=" + (rootInActiveWindow?.packageName ?: "null")

    fun pacchettoAttivo(): String? = radice()?.packageName?.toString()

    /** Tutti i nodi visibili della finestra attiva, in ordine di lettura. */
    fun nodiVisibili(limite: Int = 1500): List<AccessibilityNodeInfo> {
        val root = radice() ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        fun giro(n: AccessibilityNodeInfo, prof: Int) {
            if (out.size >= limite || prof > 45) return
            if (n.isVisibleToUser) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { giro(it, prof + 1) }
        }
        giro(root, 0)
        return out
    }

    fun info(n: AccessibilityNodeInfo): NodoInfo = NodoInfo(
        id = n.viewIdResourceName,
        testo = if (n.isPassword) null else n.text?.toString(),
        descrizione = n.contentDescription?.toString(),
        cliccabile = n.isClickable,
        abilitato = n.isEnabled,
    )

    /** L'etichetta di un nodo; se è un pulsante muto, il testo dei figli. */
    fun etichetta(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return "•••• (password)"
        val propria = n.text?.toString()?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: n.hintText()
        if (propria != null) return propria.replace('\n', ' ').take(120)
        if (!n.isClickable) return ""
        val figli = StringBuilder()
        fun giro(c: AccessibilityNodeInfo, prof: Int) {
            if (prof > 4 || figli.length > 60) return
            (c.text ?: c.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let { figli.append(it).append(' ') }
            for (i in 0 until c.childCount) c.getChild(i)?.let { giro(it, prof + 1) }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { giro(it, 1) }
        return figli.toString().trim().replace('\n', ' ').take(80)
    }

    private fun AccessibilityNodeInfo.hintText(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hintText?.toString()?.takeIf { it.isNotBlank() }?.let { "(suggerimento: $it)" } else null

    /**
     * Lo schermo come elenco numerato, sul modello di Voice Access: ogni elemento
     * toccabile, scrivibile o scorrevole ha un numero, anche i campi vuoti.
     */
    fun leggiSchermo(): String {
        val root = radice() ?: return "(schermo non leggibile in questo momento)"
        val pacchetto = root.packageName?.toString()
        val scelti = ArrayList<AccessibilityNodeInfo>()
        val righe = StringBuilder()
        for (n in nodiVisibili()) {
            if (scelti.size >= MAX_ELEMENTI) break
            val utile = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
            val et = etichetta(n)
            if (!utile && et.isEmpty()) continue
            scelti.add(n)
            val tipi = buildList {
                if (n.isEditable) add(if (n.isPassword) "campo password" else "campo")
                else if (n.isClickable) add("pulsante")
                if (n.isLongClickable) add("tieni premuto")
                if (n.isScrollable) add("scorre")
                if (n.isCheckable) add(if (n.isChecked) "spunta sì" else "spunta no")
                if (n.isFocused) add("selezionato")
                if (!n.isEnabled) add("disattivo")
            }
            val r = Rect().also { n.getBoundsInScreen(it) }
            val id = n.viewIdResourceName?.substringAfterLast(":id/")?.let { " #$it" } ?: ""
            val desc = n.contentDescription?.toString()?.takeIf { it.isNotBlank() && it != et }?.let { " «${it.take(60)}»" } ?: ""
            righe.append("[${scelti.size}] ").append(et.ifEmpty { "(senza testo)" }).append(desc).append(id)
            if (tipi.isNotEmpty()) righe.append(" (").append(tipi.joinToString(", ")).append(")")
            righe.append(" @(${r.centerX()},${r.centerY()})\n")
        }
        elementi = scelti
        pacchettoLettura = pacchetto
        val testa = "app: ${pacchetto ?: "?"} · ${scelti.size} elementi" +
            if (scelti.size >= MAX_ELEMENTI) " (troncato: scorri per vedere il resto)" else ""
        return if (scelti.isEmpty()) "$testa\n(nessun elemento visibile: forse un'interfaccia disegnata, prova screenshot)" else "$testa\n$righe"
    }

    /** L'elemento [n] dell'ultima lettura, aggiornato. Null se non c'è più. */
    fun elemento(n: Int): AccessibilityNodeInfo? {
        val nodo = elementi.getOrNull(n - 1) ?: return null
        return if (nodo.refresh()) nodo else null
    }

    fun letturaFattaSu(): String? = pacchettoLettura

    /** Il nodo più piccolo che contiene il punto (per sapere cosa tocca un tap per coordinate). */
    fun nodoAlPunto(x: Int, y: Int): AccessibilityNodeInfo? {
        var migliore: AccessibilityNodeInfo? = null
        var area = Long.MAX_VALUE
        val r = Rect()
        for (n in nodiVisibili()) {
            n.getBoundsInScreen(r)
            if (r.contains(x, y)) {
                val a = r.width().toLong() * r.height()
                if (a <= area) { area = a; migliore = n }
            }
        }
        return migliore
    }

    /** Il nodo e i suoi antenati fino al primo toccabile: è quello che riceve davvero il tocco. */
    fun catenaBersaglio(n: AccessibilityNodeInfo): List<NodoInfo> {
        val out = ArrayList<NodoInfo>()
        var c: AccessibilityNodeInfo? = n
        var passi = 0
        while (c != null && passi < 6) {
            out.add(info(c))
            if (c.isClickable) break
            c = c.parent
            passi++
        }
        // Un pulsante muto con un'icona e un testo figlio («Invia» dentro un FrameLayout).
        for (i in 0 until minOf(n.childCount, 4)) n.getChild(i)?.let { out.add(info(it)) }
        return out
    }

    // ------------------------------------------------------------------ azioni

    fun tap(x: Float, y: Float): Boolean = gesto(x, y, x, y, 80)

    fun gesto(x1: Float, y1: Float, x2: Float, y2: Float, durataMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durataMs.coerceIn(1, 5000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Clic sul nodo; se non è toccabile sale al primo genitore che lo è; altrimenti un tocco al centro. */
    fun clicca(n: AccessibilityNodeInfo, lungo: Boolean = false): Boolean {
        val azione = if (lungo) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var c: AccessibilityNodeInfo? = n
        var passi = 0
        while (c != null && passi < 6) {
            if ((if (lungo) c.isLongClickable else c.isClickable) && c.isEnabled) {
                if (c.performAction(azione)) return true
                break
            }
            c = c.parent
            passi++
        }
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        return gesto(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), if (lungo) 900 else 80)
    }

    fun typeText(text: String): Boolean = scrivi(null, text, aggiungi = false)

    /** Scrive in [n], o nel campo selezionato, o nel primo campo visibile. */
    fun scrivi(n: AccessibilityNodeInfo?, testo: String, aggiungi: Boolean): Boolean {
        val campo = n?.let { campoDa(it) }
            ?: radice()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: nodiVisibili().firstOrNull { it.isEditable }
            ?: return false
        campo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val prima = if (aggiungi && !campo.isShowingHintTextCompat()) campo.text?.toString().orEmpty() else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prima + testo)
        }
        if (campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Certi campi (Drive) accettano il testo solo dopo un tocco.
        campo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun AccessibilityNodeInfo.isShowingHintTextCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isShowingHintText

    /** Il campo scrivibile: il nodo stesso o un suo figlio. */
    private fun campoDa(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> campoDa(c)?.let { return it } }
        return null
    }

    /** Il testo dei campi scrivibili visibili: quello che partirebbe davvero con «Invia». */
    /** 0.7.0: tutti i testi visibili, in ordine (per il riepilogo di un acquisto). Mai le password. */
    fun testiDelloSchermo(): List<String> =
        nodiVisibili().filter { !it.isPassword }.mapNotNull { n ->
            (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() })?.trim()
        }.distinct()

    fun testiNeiCampi(): List<String> =
        nodiVisibili().filter { it.isEditable && !it.isPassword && !it.isShowingHintTextCompat() }
            .mapNotNull { it.text?.toString()?.takeIf { t -> t.isNotBlank() } }

    /** Invio della tastiera (cerca/vai), dall'API 30. */
    fun invioTastiera(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val campo = radice()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return campo.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Scorre [n] (o il primo elemento scorrevole); se nessuno accetta, uno strisciamento. */
    fun scorri(direzione: String, n: AccessibilityNodeInfo?): Boolean {
        val avanti = direzione in setOf("giu", "giù", "down", "destra", "right", "avanti")
        val bersaglio = n?.let { primoScorrevole(it) } ?: nodiVisibili().firstOrNull { it.isScrollable }
        if (bersaglio != null) {
            val ok = bersaglio.performAction(
                if (avanti) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
            if (ok) return true
        }
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        val dy = m.heightPixels * 0.3f
        val dx = m.widthPixels * 0.35f
        return when (direzione) {
            "destra", "right" -> gesto(cx + dx, cy, cx - dx, cy, 300)
            "sinistra", "left" -> gesto(cx - dx, cy, cx + dx, cy, 300)
            "su", "up", "indietro" -> gesto(cx, cy - dy, cx, cy + dy, 350)
            else -> gesto(cx, cy + dy, cx, cy - dy, 350)
        }
    }

    private fun primoScorrevole(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var passi = 0
        while (c != null && passi < 8) {
            if (c.isScrollable) return c
            c = c.parent
            passi++
        }
        return null
    }

    /**
     * HOME, BACK, RECENTS, NOTIFICHE, IMPOSTAZIONI_RAPIDE. Su One UI 8 HOME con
     * performGlobalAction torna false: ripiego con l'intent CATEGORY_HOME.
     */
    fun pressKey(name: String): Boolean {
        val action = when (name.uppercase()) {
            "HOME" -> GLOBAL_ACTION_HOME
            "BACK" -> GLOBAL_ACTION_BACK
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICHE", "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "IMPOSTAZIONI_RAPIDE", "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return false
        }
        if (performGlobalAction(action)) return true
        if (action == GLOBAL_ACTION_BACK) {
            // One UI 8 rifiuta anche BACK (provato il 2026-10-07): si tocca la freccia
            // «Indietro» dell'app, se c'è.
            val freccia = nodiVisibili().firstOrNull { n ->
                val d = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase() ?: return@firstOrNull false
                d in setOf("indietro", "torna indietro", "navigate up", "back", "chiudi", "close", "su")
            }
            // Tocco vero al centro della freccia: ACTION_CLICK sulla barra di WhatsApp apriva la
            // scheda del contatto invece di tornare indietro (provato il 2026-10-07).
            if (freccia != null) {
                val r = Rect().also { freccia.getBoundsInScreen(it) }
                if (!r.isEmpty) return tap(r.exactCenterX(), r.exactCenterY())
            }
            // 0.4.2 (prova del banco 2026-10-08: «torna indietro» dalla home di WhatsApp, nessuna freccia → errore):
            // il gesto «indietro» della navigazione a gesti, dal bordo sinistro verso il centro.
            val m = resources.displayMetrics
            return gesto(2f, m.heightPixels * 0.5f, m.widthPixels * 0.45f, m.heightPixels * 0.5f, 220)
        }
        if (action == GLOBAL_ACTION_HOME) {
            return runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
        return false
    }

    /** Apre un'app per nome (o per pacchetto) scegliendo con il catalogo. */
    fun openApp(appName: String): Boolean = AppTelefono.apri(this, appName) != null

    /** Il vecchio formato, tenuto per compatibilità: ora è la lettura numerata. */
    fun readScreen(): String = leggiSchermo()

    /** Le credenziali di un accesso: utente nel campo prima della password, password nel campo password. */
    fun compilaAccesso(utente: String?, password: String): String? {
        val campi = nodiVisibili().filter { it.isEditable }
        val pw = campi.firstOrNull { it.isPassword }
            ?: return "Non vedo nessun campo password sullo schermo. Da Android 16 un servizio di accessibilità che non è uno «strumento di accessibilità» " +
                "può non vedere i campi marcati sensibili: in quel caso usa l'autofill di sistema (Passbolt o Google) toccando il campo."
        if (!utente.isNullOrEmpty()) {
            val idx = campi.indexOf(pw)
            val campoUtente = campi.take(maxOf(idx, 0)).lastOrNull { !it.isPassword }
                ?: return "Non trovo il campo dell'utente prima della password."
            if (!scrivi(campoUtente, utente, aggiungi = false)) return "Il campo dell'utente non accetta il testo."
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password) }
        pw.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return if (pw.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) null
        else "Il campo password ha rifiutato il testo (Android lo protegge): usa l'autofill di sistema."
    }

    /** Screenshot ridotto in JPEG base64 (dall'API 30). [fine] riceve null e il motivo se non si può. */
    fun screenshot(larghezza: Int, fine: (String?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fine(null, "Screenshot possibile solo da Android 11")
            return
        }
        screenshot30(larghezza, fine)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun screenshot30(larghezza: Int, fine: (String?, String?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val b64 = runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?: error("bitmap vuota")
                    val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    val l = larghezza.coerceIn(240, 1080)
                    val h = (sw.height.toLong() * l / sw.width).toInt()
                    val piccola = Bitmap.createScaledBitmap(sw, l, h, true)
                    val out = ByteArrayOutputStream()
                    piccola.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }
                b64.onSuccess { fine(it, null) }.onFailure { fine(null, "Screenshot non convertito: ${it.javaClass.simpleName}") }
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot fallito: $errorCode")
                fine(null, "Screenshot rifiutato da Android (codice $errorCode; 3 = troppo ravvicinati, finestra protetta = nera)")
            }
        })
    }
}
