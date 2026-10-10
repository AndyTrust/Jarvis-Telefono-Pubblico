package com.jarvis.telefono.voce

// La macchina a stati delle mani libere, senza microfono e senza Android.
// È la porta uno a uno di MotoreManiLibere (~/Jarvis/backtalk/backtalk/
// manilibere.py, 26/09/2026): stessi stati, stessi parametri, stesso ordine
// delle operazioni. Riceve fotogrammi da 30 ms (480 campioni int16) con
// l'istante e il flag «altoparlanti attivi»; restituisce null o un Evento.
//
// Stati:
//   «parola»  aspetta «JBOSS» («Hey Boss», «Hey JBoss») dal rilevatore;
//   «cattura» registra finché l'utente parla, chiude dopo fineParlatoS (1.2.4:
//             fineParlatoLungoS dopo parlatoLungoS di parlato) di
//             silenzio o a maxFraseS;
//   «muto»    altoparlanti attivi o coda d'eco: l'audio si butta.
//
// Due differenze dal computer, tutte e due nello stato «parola» e per la batteria:
// il Cancello di energia (se chiuso il fotogramma non passa né dal filtro né
// dal KWS) e il preroll (quando il cancello si apre il KWS riceve prima gli
// ultimi cancelloPrerollMs buttati). Sul computer il filtro gira su ogni
// fotogramma in ogni stato.

/** Il KWS. accetta() riceve un pezzo float -1..1 e dà la parola sentita (o null). */
interface RilevatoreParola {
    fun accetta(pcm: FloatArray): String?
    fun azzera()
}

/**
 * 1.2.4: quanti secondi di silenzio chiudono la frase, dati i secondi di parlato già sentiti.
 * Corta: [Opzioni.fineParlatoS]; da [Opzioni.parlatoLungoS] in su: [Opzioni.fineParlatoLungoS].
 */
fun finestraSilenzioS(opzioni: Opzioni, parlatoS: Double): Double =
    if (parlatoS >= opzioni.parlatoLungoS) maxOf(opzioni.fineParlatoS, opzioni.fineParlatoLungoS) else opzioni.fineParlatoS

/** Dice se un fotogramma da 30 ms è parlato. */
interface Vad {
    fun parlato(pcm: FloatArray): Boolean
    /** Dimentica lo stato interno (Silero ha una memoria; webrtcvad sul computer no). */
    fun azzera() {}
}

/** Filtro del rumore in streaming: l'uscita torna in fotogrammi da 480 (anche zero, con ritardo). */
interface Pulitore {
    fun pulisci(pcm: FloatArray): List<FloatArray>
    fun azzera()
}

/** L'impronta vocale dell'utente. somiglianza() riceve l'audio CRUDO. */
interface Verificatore {
    val pronta: Boolean
    fun somiglianza(crudo: FloatArray): Float?
}

sealed class Evento {
    data class Parola(val parola: String) : Evento()
    object NessunParlato : Evento()
    class Frase(
        val audio: FloatArray,      // filtrato: VAD e trascrizione
        val crudo: FloatArray,      // non filtrato: impronta
        val motivo: String,         // "silenzio", "limite" o "widget" (chiudiOra)
        val durataS: Double,
        val chiusaDopoSilenzioS: Double,
        val somiglianza: Float?,
        val accettata: Boolean,
        // 0.3.3: il microfono crudo PRIMA dell'inizio della cattura (gli ultimi preRollMs: la coda
        // della parola e l'eventuale inizio della frase detta di fila) e TUTTO il crudo dall'inizio
        // della cattura, compresi i primi ignoraInizialiMs che [audio] e [crudo] non hanno.
        val prima: FloatArray = FloatArray(0),
        val tutto: FloatArray = FloatArray(0),
    ) : Evento() {
        override fun toString() =
            "Frase(motivo=$motivo, durataS=$durataS, chiusaDopoSilenzioS=$chiusaDopoSilenzioS, " +
                "somiglianza=$somiglianza, accettata=$accettata, campioni=${audio.size})"
    }
}

class Motore(
    /** 0.6.1: si cambia da Impostazioni → Voce (fine frase, impronta) e vale dal prossimo ascolto. */
    @Volatile var opzioni: Opzioni,
    private val rilevatore: RilevatoreParola,
    private val vad: Vad,
    pulitore: Pulitore?,
    private val verificatore: Verificatore?,
    val cancello: Cancello = Cancello(opzioni),
) {
    enum class Stato { PAROLA, CATTURA, MUTO }

    // Sul computer il pulitore esiste solo con filtro_rumore acceso.
    private val pulitore: Pulitore? = pulitore?.takeIf { opzioni.filtroRumore }

    val parole: Set<String> = opzioni.parole.map { normalizza(it) }.toSet()

    var stato: Stato = Stato.PAROLA
        private set

    /** Quante volte la parola chiave ha aperto una cattura. */
    var attivazioni: Int = 0
        private set

    /**
     * Boss 09/10 («posso interromperlo e chiedere altro»): con il popup aperto, mentre JBoss parla,
     * il KWS resta in ascolto della parola («JBoss») anche col microfono «muto». Solo la parola:
     * niente cattura, niente VAD, l'audio non si tiene. Se scatta, [feed] dà [Evento.Parola] e resta
     * in «muto»: tocca al servizio zittire la voce e aprire la cattura (forzaCattura).
     * Spento di default (come sul computer). Il servizio lo spegne quando la risposta contiene la
     * parola stessa, per non interrompersi da solo con l'eco.
     */
    @Volatile
    var parolaDuranteVoce: Boolean = false

    /** Vero in cattura dopo il primo parlato vero: Boss sta dicendo la frase. Si legge da ogni thread. */
    @Volatile
    var utenteParla: Boolean = false
        private set

    /** Quante volte la parola ha interrotto JBoss mentre parlava. */
    var interruzioni: Int = 0
        private set

    private var fineAltoparlantiMs: Long = Long.MIN_VALUE / 2

    // --- cattura
    private val audio = ArrayList<FloatArray>()
    private val crudo = ArrayList<FloatArray>()
    private var inizioCatturaMs = 0L
    private var parlaDaMs: Long? = null      // istante del primo parlato vero
    private var ultimoParlatoMs = 0L
    private var corsa = 0                    // fotogrammi di parlato consecutivi

    // --- preroll del cancello (fotogrammi buttati mentre era chiuso)
    private val preroll = ArrayDeque<FloatArray>()
    private val maxPreroll = (opzioni.cancelloPrerollMs / Opzioni.FRAME_MS).toInt()

    // --- 0.3.3: lo storico crudo per la trascrizione (vedi Evento.Frase.prima / tutto).
    private val storico = ArrayDeque<FloatArray>()
    private val maxStorico = (opzioni.preRollMs / Opzioni.FRAME_MS).toInt()
    private var prima: FloatArray = FloatArray(0)
    private val tutto = ArrayList<FloatArray>()

    companion object {
        /** Come `" ".join(r.upper().split())` sul computer. */
        fun normalizza(s: String): String =
            s.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun azzeraCattura() {
        audio.clear()
        crudo.clear()
        tutto.clear()
        inizioCatturaMs = 0L
        parlaDaMs = null
        ultimoParlatoMs = 0L
        corsa = 0
        utenteParla = false
    }

    private fun ricominciaParola() {
        rilevatore.azzera()
        pulitore?.azzera()
        vad.azzera()
        preroll.clear()
        storico.clear()
        cancello.chiudi()
        stato = Stato.PAROLA
        azzeraCattura()
    }

    /** True se il microfono va considerato chiuso adesso (altoparlanti o coda d'eco). */
    fun muto(adessoMs: Long, altoparlanti: Boolean): Boolean {
        if (altoparlanti) {
            fineAltoparlantiMs = adessoMs
            return true
        }
        return adessoMs - fineAltoparlantiMs < opzioni.codaEcoMs
    }

    /**
     * Il widget tocca-e-tieni o il tasto «Ascolta» della notifica: apre la
     * cattura come se avesse sentito la parola chiave, senza aspettarla. Da lì
     * in poi è una cattura normale (primi ignoraInizialiMs buttati, chiusura
     * dopo fineParlatoS di silenzio, NessunParlato dopo attesaInizioS).
     *
     * La coda d'eco si dimentica: l'ha chiesto l'utente col dito, e chi lo chiama
     * ha già zittito Jarvis. Se però gli altoparlanti sono ancora accesi al
     * fotogramma dopo, il muto vince come sempre e la cattura muore.
     * Non conta come attivazione (quelle sono della parola chiave).
     * Va chiamato dallo stesso thread di [feed].
     */
    fun forzaCattura(adessoMs: Long) {
        fineAltoparlantiMs = Long.MIN_VALUE / 2
        rilevatore.azzera()
        vad.azzera()
        preroll.clear()
        cancello.chiudi()
        stato = Stato.CATTURA
        azzeraCattura()
        prima = istantanea()
        inizioCatturaMs = adessoMs
    }

    /** Lo storico crudo come un pezzo solo, e si svuota. */
    private fun istantanea(): FloatArray = concatena(storico.toList()).also { storico.clear() }

    /**
     * Il dito lascia il widget dopo un tocca-e-tieni lungo: la frase si chiude
     * adesso, senza aspettare fineParlatoS di silenzio. Non c'è sul computer (lì
     * il tasto chiude al rilascio in un altro ramo): è l'unica aggiunta, e
     * non cambia niente del resto della macchina.
     *
     * - In cattura con parlato vero: chiude come il silenzio, motivo «widget».
     * - In cattura senza parlato: torna ad aspettare la parola, NessunParlato.
     * - In «parola» o «muto»: non fa niente, null.
     * Va chiamato dallo stesso thread di [feed].
     */
    fun chiudiOra(adessoMs: Long): Evento? {
        if (stato != Stato.CATTURA) return null
        if (parlaDaMs == null) {
            ricominciaParola()
            return Evento.NessunParlato
        }
        return chiudi(adessoMs, "widget")
    }

    /** Un fotogramma da 30 ms (480 campioni int16). */
    fun feed(frame: ShortArray, adessoMs: Long, altoparlanti: Boolean): Evento? {
        if (muto(adessoMs, altoparlanti)) {
            // Microfono chiuso: niente KWS, niente cattura. Una cattura in
            // corso muore qui (Jarvis ha cominciato a parlare per altro).
            if (stato != Stato.MUTO) {
                stato = Stato.MUTO
                azzeraCattura()
            }
            if (parolaDuranteVoce) return parolaSopraLaVoce(frame)
            return null
        }
        if (stato == Stato.MUTO) ricominciaParola()
        val f = FloatArray(frame.size) { frame[it] / 32768.0f }
        if (stato == Stato.PAROLA) {
            if (maxStorico > 0) {
                storico.addLast(f)
                while (storico.size > maxStorico) storico.removeFirst()
            }
            return parola(f, adessoMs)
        }
        return cattura(f, adessoMs)
    }

    /** Solo il KWS, senza cancello né filtro: vedi [parolaDuranteVoce]. Lo stato resta MUTO. */
    private fun parolaSopraLaVoce(frame: ShortArray): Evento? {
        val f = FloatArray(frame.size) { frame[it] / 32768.0f }
        val r = rilevatore.accetta(f) ?: return null
        rilevatore.azzera()
        val parola = normalizza(r)
        if (parola !in parole) return null
        interruzioni++
        return Evento.Parola(parola)
    }

    /**
     * Torna ad aspettare la parola e dimentica la cattura in corso. Per la pausa e lo spegnimento
     * della voce: si chiama col microfono già fermo (nessun [feed] in corso).
     */
    fun azzera() {
        ricominciaParola()
        prima = FloatArray(0)
    }

    private fun parola(f: FloatArray, adessoMs: Long): Evento? {
        val daPassare: List<FloatArray>
        if (opzioni.cancelloAttivo) {
            if (!cancello.valuta(f, adessoMs)) {
                // Cancello chiuso: né filtro né KWS. Si tiene per il preroll.
                if (maxPreroll > 0) {
                    preroll.addLast(f)
                    while (preroll.size > maxPreroll) preroll.removeFirst()
                }
                return null
            }
            daPassare = if (preroll.isEmpty()) listOf(f) else (preroll + f).also { preroll.clear() }
        } else {
            daPassare = listOf(f)
        }
        for (fr in daPassare) {
            val pezzi = pulitore?.pulisci(fr) ?: listOf(fr)
            for (p in pezzi) {
                val r = rilevatore.accetta(p) ?: continue
                rilevatore.azzera()
                val parola = normalizza(r)
                if (parola in parole) {
                    attivazioni++
                    preroll.clear()
                    stato = Stato.CATTURA
                    azzeraCattura()
                    prima = istantanea()
                    inizioCatturaMs = adessoMs
                    return Evento.Parola(parola)
                }
                // Sul computer il resto dei pezzi era già nel flusso quando si
                // azzera: è perso anche lì.
                return null
            }
        }
        return null
    }

    private fun cattura(f: FloatArray, adessoMs: Long): Evento? {
        // Come sul computer il filtro gira anche sui fotogrammi che poi si buttano:
        // così quando la cattura comincia ha già il suo stato.
        val pezzi = pulitore?.pulisci(f) ?: listOf(f)
        val durataMs = adessoMs - inizioCatturaMs
        tutto.add(f)
        if (durataMs < opzioni.ignoraInizialiMs) return null
        crudo.add(f)
        for (p in pezzi) {
            audio.add(p)
            if (vad.parlato(p)) {
                corsa++
                // 3 fotogrammi di fila (90 ms) = parlato vero, non un clic
                if (corsa >= 3) {
                    if (parlaDaMs == null) {
                        parlaDaMs = adessoMs
                        utenteParla = true
                    }
                    ultimoParlatoMs = adessoMs
                }
            } else {
                corsa = 0
            }
        }
        if (parlaDaMs == null) {
            if (durataMs > opzioni.attesaInizioS * 1000) {
                ricominciaParola()
                return Evento.NessunParlato
            }
            return null
        }
        val silenzioMs = adessoMs - ultimoParlatoMs
        val fine = silenzioMs >= finestraSilenzioS(opzioni, (ultimoParlatoMs - (parlaDaMs ?: ultimoParlatoMs)) / 1000.0) * 1000
        if (fine || durataMs >= opzioni.maxFraseS * 1000) {
            return chiudi(adessoMs, if (fine) "silenzio" else "limite")
        }
        return null
    }

    private fun chiudi(adessoMs: Long, motivo: String): Evento.Frase {
        val a = concatena(audio)
        val c = if (crudo.isNotEmpty()) concatena(crudo) else a
        var somiglianza: Float? = null
        var accettata = true
        val v = verificatore
        if (v != null && v.pronta && c.size >= Opzioni.RATE / 2) {
            val s = v.somiglianza(c)
            if (s != null) {
                somiglianza = s
                accettata = s >= opzioni.sogliaImpronta
            }
        }
        val esito = Evento.Frase(
            audio = a,
            crudo = c,
            motivo = motivo,
            durataS = (adessoMs - inizioCatturaMs) / 1000.0,
            chiusaDopoSilenzioS = (adessoMs - ultimoParlatoMs) / 1000.0,
            somiglianza = somiglianza,
            accettata = accettata,
            prima = prima,
            tutto = concatena(tutto),
        )
        prima = FloatArray(0)
        ricominciaParola()
        return esito
    }

    private fun concatena(pezzi: List<FloatArray>): FloatArray {
        val out = FloatArray(pezzi.sumOf { it.size })
        var i = 0
        for (p in pezzi) {
            p.copyInto(out, i)
            i += p.size
        }
        return out
    }
}

/**
 * Rimette un flusso di campioni in blocchi di lunghezza fissa, tenendo il
 * resto per la volta dopo. È `Pulitore.__call__` del computer (uscita del GTCRN in
 * fotogrammi da 480) e serve anche al VAD Silero (finestre da 512).
 */
class Riblocca(private val lunghezza: Int) {
    private var buf = FloatArray(lunghezza * 4)
    private var n = 0

    fun aggiungi(campioni: FloatArray): List<FloatArray> {
        if (n + campioni.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + campioni.size))
        campioni.copyInto(buf, n)
        n += campioni.size
        if (n < lunghezza) return emptyList()
        val pezzi = ArrayList<FloatArray>(n / lunghezza)
        var i = 0
        while (n - i >= lunghezza) {
            pezzi.add(buf.copyOfRange(i, i + lunghezza))
            i += lunghezza
        }
        buf.copyInto(buf, 0, i, n)
        n -= i
        return pezzi
    }

    val inAttesa: Int get() = n

    fun azzera() {
        n = 0
    }
}
