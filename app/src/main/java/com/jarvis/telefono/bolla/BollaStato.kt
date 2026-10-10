package com.jarvis.telefono.bolla

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jarvis.telefono.R
import com.jarvis.telefono.agenti.Avatar
import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.ui.AnimaAvatar
import com.jarvis.telefono.ui.Movimento
import com.jarvis.telefono.voce.AzioneVoce
import com.jarvis.telefono.voce.ModoVoce
import com.jarvis.telefono.voce.RegoleModo

/**
 * La bolla di stato (1.2.2): una pillola sopra qualunque app, che dice in parole che cosa sta
 * facendo Jarvis. Si sposta col dito (Boss 07/10: «deve essere flottante, perché a volte ti
 * metti sopra un nome e non capisco a chi stai scrivendo») e resta dove Boss la lascia. Fuori
 * dalla pillola i tocchi passano all'app sotto (FLAG_NOT_TOUCH_MODAL); le mani di Jarvis
 * toccano per nodi dell'accessibilità, quindi la bolla non le intralcia.
 *
 * Dove si disegna lo decide [finestra], a ogni comparsa:
 * - con l'accessibilità accesa è una finestra dell'accessibilità (TYPE_ACCESSIBILITY_OVERLAY),
 *   come il pannello della conferma: non serve il permesso «sopra le altre app»;
 * - senza, una finestra «sopra le altre app» (TYPE_APPLICATION_OVERLAY), se il permesso c'è;
 * - altrimenti niente bolla (resta la notifica).
 * Per questo non dipende dal tasto flottante: quello è un pulsante, questa è una spia.
 *
 * 0.2.0 (tema Claude): fondo delle schede, bordo del colore del momento, e a sinistra l'avatar
 * dell'agente che lavora ([TestiBolla.Riga.agente]) con l'alone che pulsa mentre lavora; senza
 * agente resta il pallino colorato. Compare scorrendo giù e accendendosi, sparisce al contrario
 * (niente animazioni se Boss le ha tolte dal sistema). L'alone si ferma quando la bolla sparisce.
 *
 * Boss 2026-10-09: compare solo quando JBoss opera FUORI dall'app ([consentita], letto da
 * [PrimoPiano] con [RegolePopup]); dentro l'app la voce va nella chat di JBoss. Sparisce al ritorno
 * dell'app davanti e appena l'operazione finisce.
 *
 * Tutto sul thread principale (chi la chiama da altri thread passa da [principale]).
 */
class BollaStato(
    private val finestra: () -> Pair<Context, Int>?,
    /** Dove Boss l'ha lasciata l'ultima volta (x, y in pixel dall'alto a sinistra), o null. */
    private val posizione: () -> Pair<Int, Int>? = { null },
    private val salvaPosizione: (Int, Int) -> Unit = { _, _ -> },
    /** 09/10: un tasto del popup della conversazione (interruttore, Pausa/Riprendi, Spegni, tocco sulle parole). */
    private val suAzione: (AzioneVoce) -> Unit = {},
    /** 09/10: Boss chiude il popup (✕). Il modo della voce resta quello scelto. */
    private val suChiudiPopup: () -> Unit = {},
    /**
     * Boss 09/10: «il popup solo quando l'app è chiusa». Falso = l'app è davanti: la bolla non si disegna (l'ultima
     * riga si ricorda, per [riprendi]) e quella già sullo schermo sparisce.
     */
    private val consentita: () -> Boolean = { true },
) {

    /**
     * Il popup della conversazione (Boss 09/10: «Jarvis rimane in ascolto con il popup attivo»). Sotto la
     * riga di stato:
     * ```
     * JBoss ascolta         [ ● acceso] [✕]
     * «...ultime parole sentite...»
     * [ Pausa ]        [ Spegni ]
     * ```
     * Finché c'è, la bolla non sparisce da sola: la chiude [conversazione] con null.
     */
    data class Pannello(val modo: ModoVoce, val ultime: String = "")

    companion object {
        private const val TAG = "JarvisBolla"
        /** Distanza dall'alto: sotto la barra di stato, sopra il pannello della conferma. */
        const val Y_DP = 34
    }

    private val principale = Handler(Looper.getMainLooper())
    private var vista: View? = null
    private var wm: WindowManager? = null
    private var titoloView: TextView? = null
    private var dettaglioView: TextView? = null
    private var puntino: GradientDrawable? = null
    private var puntinoView: View? = null
    private var frecciaView: View? = null
    private var avatarView: ImageView? = null
    private var jarvisView: ImageView? = null
    private var animaJarvis: AnimaAvatar? = null
    private var animaAgente: AnimaAvatar? = null
    private var sfondo: GradientDrawable? = null
    // Con il popup della conversazione aperto la bolla non si chiude da sola.
    private val chiudi = Runnable { if (pannello == null) nascondi() }

    // --- 09/10: il popup della conversazione
    private var pannello: Pannello? = null
    private var pannelloView: View? = null
    private var pannelloTitolo: TextView? = null
    private var interruttoreView: TextView? = null
    private var ultimeView: TextView? = null
    private var tastoSinistro: TextView? = null
    private var tastoDestro: TextView? = null

    /** Il popup aperto adesso (per i log e le prove). */
    val pannelloAperto: Pannello? get() = pannello

    /**
     * Apre o aggiorna il popup della conversazione con [p]; null lo chiude e la bolla torna quella di
     * prima (sparisce quando il giro è finito). Se la bolla non c'è, si apre con «Ti ascolto…».
     */
    fun conversazione(p: Pannello?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            principale.post { conversazione(p) }
            return
        }
        pannello = p
        if (p != null && vista == null) {
            mostra(ultima ?: TestiBolla.TI_ASCOLTO)
            return
        }
        applicaPannello()
        if (p == null && vista != null) {
            val t = ultima?.tono
            if (t == TestiBolla.Tono.FATTO || t == TestiBolla.Tono.ERRORE || t == TestiBolla.Tono.ASCOLTO) principale.postDelayed(chiudi, 1_500L)
        }
    }

    private fun applicaPannello() {
        val c = ctx ?: return
        val v = pannelloView ?: return
        val p = pannello
        if (p == null) {
            v.visibility = View.GONE
            return
        }
        v.visibility = View.VISIBLE
        pannelloTitolo?.text = c.getString(
            when (p.modo) {
                ModoVoce.ACCESO -> R.string.voce_popup_acceso_titolo
                ModoVoce.PAUSA -> R.string.voce_popup_pausa_titolo
                ModoVoce.SPENTO -> R.string.voce_popup_spento_titolo
            },
        )
        interruttoreView?.apply {
            text = c.getString(
                when (p.modo) {
                    ModoVoce.ACCESO -> R.string.voce_interruttore_acceso
                    ModoVoce.PAUSA -> R.string.voce_interruttore_pausa
                    ModoVoce.SPENTO -> R.string.voce_interruttore_spento
                },
            )
            val col = ContextCompat.getColor(
                c,
                when (p.modo) {
                    ModoVoce.ACCESO -> R.color.spia_verde
                    ModoVoce.PAUSA -> R.color.spia_giallo
                    ModoVoce.SPENTO -> R.color.spia_grigio
                },
            )
            setTextColor(col)
            (background as? GradientDrawable)?.setStroke(dp(1), col)
            descriviStato(this, text)
        }
        ultimeView?.text = when {
            !p.modo.ascolta -> c.getString(R.string.voce_popup_non_ascolto)
            p.ultime.isBlank() -> c.getString(R.string.voce_popup_parla_pure)
            else -> c.getString(R.string.voce_popup_ultime, TestiBolla.accorcia(p.ultime, 120))
        }
        val (sx, dx) = RegoleModo.tastiPopup(p.modo)
        tastoSinistro?.apply {
            text = c.getString(etichetta(sx))
            setOnClickListener { suAzione(sx) }
        }
        tastoDestro?.apply {
            if (dx == null) {
                visibility = View.INVISIBLE
                setOnClickListener(null)
            } else {
                visibility = View.VISIBLE
                text = c.getString(etichetta(dx))
                setOnClickListener { suAzione(dx) }
            }
        }
    }

    /** Per TalkBack: l'interruttore dice in che stato è. */
    private fun descriviStato(v: View, testo: CharSequence) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) v.stateDescription = testo
    }

    private fun etichetta(a: AzioneVoce): Int = when (a) {
        AzioneVoce.PAUSA -> R.string.voce_tasto_pausa
        AzioneVoce.RIPRENDI, AzioneVoce.INTERRUTTORE -> R.string.voce_tasto_riprendi
        AzioneVoce.SPEGNI -> R.string.voce_tasto_spegni
        AzioneVoce.ASCOLTA -> R.string.voce_tasto_ascolta
        AzioneVoce.ESCI -> R.string.voce_tasto_esci
    }

    /** L'ultima riga mostrata (per i log e le prove). */
    var ultima: TestiBolla.Riga? = null
        private set

    fun visibile(): Boolean = vista != null

    /** Mostra [riga]; con [chiudiDopoMs] sparisce da sola dopo quel tempo. */
    fun mostra(riga: TestiBolla.Riga, chiudiDopoMs: Long? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            principale.post { mostra(riga, chiudiDopoMs) }
            return
        }
        ultima = riga
        principale.removeCallbacks(chiudi)
        if (!consentita()) {
            // Dentro l'app niente popup: la domanda e la risposta stanno nella chat di JBoss.
            if (vista != null) nascondi()
            return
        }
        if (vista != null && finestraCambiata()) nascondi().also { ultima = riga }
        if (vista == null && !crea()) {
            Log.i(TAG, "bolla non disegnabile (né accessibilità né permesso sopra le altre app): ${riga.titolo}")
            return
        }
        titoloView?.text = TestiBolla.titolo(riga)
        dettaglioView?.apply {
            text = riga.dettaglio
            visibility = if (riga.dettaglio.isBlank()) View.GONE else View.VISIBLE
        }
        val (colore, bordo) = colori(riga.tono)
        puntino?.setColor(colore)
        sfondo?.setStroke(dp(2), bordo)
        mostraAgente(riga)
        applicaPannello()
        Log.i(TAG,"bolla: ${riga.tono.name.lowercase()} · ${TestiBolla.titolo(riga)}${if (riga.dettaglio.isNotBlank()) " · " + riga.dettaglio else ""}")
        if (chiudiDopoMs != null) principale.postDelayed(chiudi, chiudiDopoMs)
    }

    /**
     * Sparisce fra [ms] millisecondi, ma solo se mostra ancora [riga]: se nel frattempo Boss ha
     * detto di nuovo «Jarvis», la bolla nuova non si chiude per colpa di quella vecchia.
     */
    fun chiudiFra(ms: Long, riga: TestiBolla.Riga?) {
        principale.post {
            if (riga != null && ultima !== riga) return@post
            if (pannello != null) return@post // il popup della conversazione resta aperto
            principale.removeCallbacks(chiudi)
            principale.postDelayed(chiudi, ms)
        }
    }

    /**
     * L'app è andata in sottofondo mentre JBoss lavora (per esempio ha aperto WhatsApp): torna l'ultima riga, se
     * non è una riga finale. Sul thread principale.
     */
    fun riprendi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            principale.post { riprendi() }
            return
        }
        val r = ultima ?: return
        if (vista != null || r.tono == TestiBolla.Tono.FATTO || r.tono == TestiBolla.Tono.ERRORE) return
        mostra(r)
    }

    /** Mostra [dopo] fra [ms], solo se nel frattempo la bolla mostra ancora [prima]. */
    fun poi(ms: Long, prima: TestiBolla.Riga, dopo: TestiBolla.Riga) {
        principale.postDelayed({ if (ultima === prima) mostra(dopo) }, ms)
    }

    fun nascondi() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            principale.post { nascondi() }
            return
        }
        principale.removeCallbacks(chiudi)
        val v = vista ?: return
        vista = null
        val w = wm
        wm = null
        animaJarvis?.ferma(); animaAgente?.ferma()
        // La vista esce dalla bolla subito (vista = null): una bolla nuova si crea a parte, e
        // questa finisce di sparire da sola.
        Movimento.scompari(v, -dp(10).toFloat()) { runCatching { w?.removeView(v) } }
        Log.i(TAG, "bolla nascosta")
    }

    /**
     * Gli avatar (0.2.0, Boss 07/10): Jarvis quando agisce lui; con una delega «Jarvis → agente» e
     * l'avatar dell'agente. Chi lavora ha l'anello che gira, chi ascolta l'anello che pulsa; al
     * «Fatto» un luccichio breve. Bitmap animate con trasformazioni ([AnimaAvatar]).
     */
    private fun mostraAgente(riga: TestiBolla.Riga) {
        val c = ctx ?: return
        val img = avatarView ?: return
        jarvisView?.let { if (it.drawable == null) it.setImageBitmap(Avatar.subito(c, CatalogoAgenti.JARVIS)) }
        val modo = when (riga.tono) {
            TestiBolla.Tono.ASCOLTO -> AnimaAvatar.Modo.ASCOLTO
            TestiBolla.Tono.LAVORO, TestiBolla.Tono.ATTESA -> AnimaAvatar.Modo.LAVORO
            else -> null
        }
        val bmp = riga.agente?.let { Avatar.subito(c, it) }
        val chi = if (bmp == null) animaJarvis else animaAgente
        val altro = if (bmp == null) animaAgente else animaJarvis
        if (bmp == null) {
            (img.parent as? View)?.visibility = View.GONE
            frecciaView?.visibility = View.GONE
        } else {
            img.setImageBitmap(bmp)
            (img.parent as? View)?.visibility = View.VISIBLE
            frecciaView?.visibility = View.VISIBLE
            img.contentDescription = "agente ${riga.agente}"
        }
        altro?.ferma()
        if (modo != null) chi?.imposta(modo) else {
            chi?.ferma()
            if (riga.tono == TestiBolla.Tono.FATTO) chi?.luccica()
        }
    }

    /** Il dito sposta la pillola; al rilascio la posizione si ricorda. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun trascinabile(v: View, params: WindowManager.LayoutParams, w: WindowManager) {
        var dx = 0f; var dy = 0f; var x0 = 0; var y0 = 0; var mosso = false
        v.setOnTouchListener { vista, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; x0 = params.x; y0 = params.y; mosso = false
                    principale.removeCallbacks(chiudi) // mentre la tiene non sparisce
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val mx = (e.rawX - dx).toInt(); val my = (e.rawY - dy).toInt()
                    if (mosso || kotlin.math.abs(mx) > dp(6) || kotlin.math.abs(my) > dp(6)) {
                        mosso = true
                        params.x = (x0 + mx).coerceAtLeast(0)
                        params.y = (y0 + my).coerceAtLeast(0)
                        runCatching { w.updateViewLayout(vista, params) }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (mosso) {
                        salvaPosizione(params.x, params.y)
                        Log.i(TAG, "bolla spostata da Boss a (${params.x}, ${params.y})")
                    }
                    // Le bolle finali (fatto, errore) poi spariscono comunque.
                    val t = ultima?.tono
                    if (t == TestiBolla.Tono.FATTO || t == TestiBolla.Tono.ERRORE) principale.postDelayed(chiudi, 3_000L)
                    true
                }
                else -> false
            }
        }
    }

    /** Pallino e bordo per il momento: colori del tema (chiaro o scuro, values-night). */
    private fun colori(t: TestiBolla.Tono): Pair<Int, Int> {
        val c = ctx ?: return Color.GRAY to Color.GRAY
        fun col(id: Int) = ContextCompat.getColor(c, id)
        return when (t) {
            TestiBolla.Tono.ASCOLTO -> col(R.color.spia_verde) to col(R.color.spia_verde)
            TestiBolla.Tono.LAVORO -> col(R.color.jarvis_accento) to col(R.color.jarvis_accento)
            TestiBolla.Tono.ATTESA -> col(R.color.spia_giallo) to col(R.color.spia_giallo)
            TestiBolla.Tono.FATTO -> col(R.color.spia_verde) to col(R.color.spia_verde)
            TestiBolla.Tono.ERRORE -> col(R.color.spia_rosso) to col(R.color.spia_rosso)
        }
    }

    private var ctx: Context? = null
    private var lp: WindowManager.LayoutParams? = null

    /** Il servizio di accessibilità è ripartito (o si è spento): la finestra va rifatta. */
    private fun finestraCambiata(): Boolean = finestra()?.first !== ctx
    private fun dp(v: Int): Int {
        val c = ctx ?: return v
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
    }

    /** Il popup della conversazione (nascosto finché [conversazione] non lo apre). */
    private fun creaPannello(c: Context): View {
        fun col(id: Int) = ContextCompat.getColor(c, id)
        fun tasto(): TextView = TextView(c).apply {
            setTextColor(col(R.color.jarvis_testo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            minWidth = dp(96)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), col(R.color.jarvis_testo_tenue))
            }
        }
        val pan = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            minimumWidth = dp(280)
            setPadding(0, dp(8), 0, dp(2))
        }
        // Riga 1: «JBoss ascolta» ......... [● acceso] [✕]
        val testa = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titolo = TextView(c).apply {
            setTextColor(col(R.color.jarvis_testo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val interruttore = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            isClickable = true
            contentDescription = c.getString(R.string.voce_interruttore_descrizione)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), col(R.color.spia_verde)) }
            setOnClickListener { suAzione(AzioneVoce.INTERRUTTORE) }
        }
        val chiudiX = TextView(c).apply {
            text = "✕"
            setTextColor(col(R.color.jarvis_testo_tenue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            minWidth = dp(40); minHeight = dp(36)
            isClickable = true
            contentDescription = c.getString(R.string.voce_popup_chiudi)
            setOnClickListener { suChiudiPopup() }
        }
        testa.addView(titolo); testa.addView(interruttore); testa.addView(chiudiX)
        // Riga 2: le ultime parole sentite. Un tocco qui = «ti ascolto» (e interrompe JBoss se parla).
        val ultime = TextView(c).apply {
            setTextColor(col(R.color.jarvis_testo_tenue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(6), 0, dp(8))
            isClickable = true
            setOnClickListener { if (pannello?.modo?.ascolta == true) suAzione(AzioneVoce.ASCOLTA) }
        }
        // Riga 3: [Pausa|Riprendi]        [Spegni]
        val tasti = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sx = tasto()
        val spazio = View(c).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val dx = tasto()
        tasti.addView(sx); tasti.addView(spazio); tasti.addView(dx)
        pan.addView(testa); pan.addView(ultime); pan.addView(tasti)
        pannelloView = pan; pannelloTitolo = titolo; interruttoreView = interruttore
        ultimeView = ultime; tastoSinistro = sx; tastoDestro = dx
        return pan
    }

    private fun crea(): Boolean {
        val (c, tipo) = finestra() ?: return false
        ctx = c
        fun col(id: Int) = ContextCompat.getColor(c, id)
        val bg = GradientDrawable().apply {
            cornerRadius = c.resources.getDimension(R.dimen.raggio_pillola)
            setColor(col(R.color.jarvis_bolla_fondo))
            setStroke(dp(2), col(R.color.spia_verde))
        }
        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(R.color.spia_verde)) }
        // 09/10: la pillola è un contenitore verticale: sopra la riga di stato, sotto il popup della conversazione.
        val radice = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(16), dp(8))
            background = bg
            elevation = dp(3).toFloat()
        }
        val riga = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        radice.addView(riga)
        // A sinistra: Jarvis (il capo) sempre; con una delega «→» e l'avatar dell'agente con il suo alone.
        val lato = c.resources.getDimensionPixelSize(R.dimen.avatar_bolla)
        val cornice = FrameLayout(c).apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)) }
        val anelloJ = View(c).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER)
        }
        val jarvis = ImageView(c).apply {
            setImageBitmap(Avatar.subito(c, CatalogoAgenti.JARVIS))
            contentDescription = "JBoss"
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)
        }
        cornice.addView(anelloJ); cornice.addView(jarvis)
        riga.addView(cornice)
        val freccia = TextView(c).apply {
            text = "→"
            setTextColor(col(R.color.jarvis_testo_tenue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(4); marginEnd = dp(2) }
        }
        riga.addView(freccia)
        val sinistra = FrameLayout(c).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(lato + dp(8), lato + dp(8))
        }
        val alone = View(c).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(lato + dp(8), lato + dp(8), Gravity.CENTER)
        }
        val img = ImageView(c).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(lato, lato, Gravity.CENTER)
        }
        val pall = View(c).apply {
            background = dot
            layoutParams = FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER)
        }
        sinistra.addView(alone); sinistra.addView(img)
        riga.addView(sinistra)
        // Il pallino del momento resta, piccolo, fra gli avatar e il testo.
        riga.addView(pall.apply { layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginStart = dp(8); marginEnd = dp(10) } })
        val testi = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val t = TextView(c).apply {
            setTextColor(col(R.color.jarvis_testo))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val d = TextView(c).apply {
            setTextColor(col(R.color.jarvis_testo_tenue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        testi.addView(t)
        testi.addView(d)
        riga.addView(testi)
        val pan = creaPannello(c)
        radice.addView(pan)

        val salvata = posizione()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = salvata?.first ?: 0
            y = salvata?.second ?: dp(Y_DP)
            title = "JBoss: bolla di stato"
        }
        val w = c.getSystemService(WindowManager::class.java) ?: return false
        trascinabile(radice, params, w)
        return runCatching {
            w.addView(radice, params)
            vista = radice; wm = w; lp = params; titoloView = t; dettaglioView = d; puntino = dot; sfondo = bg
            puntinoView = pall; frecciaView = freccia; avatarView = img; jarvisView = jarvis
            animaJarvis = AnimaAvatar(jarvis, anelloJ); animaAgente = AnimaAvatar(img, alone)
            Movimento.compari(radice, -dp(12).toFloat())
            // Al primo giro, senza posizione salvata, in alto al centro (serve la larghezza vera).
            if (salvata == null) radice.post {
                params.x = ((c.resources.displayMetrics.widthPixels - radice.width) / 2).coerceAtLeast(0)
                runCatching { w.updateViewLayout(radice, params) }
            }
            true
        }.getOrElse {
            Log.w(TAG, "bolla non aggiunta: $it")
            false
        }
    }
}
