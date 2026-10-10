package com.jarvis.telefono.vps

/**
 * La logica del modulo VPS senza Android (provata sulla JVM in NucleoVpsTest): cosa si manda alla VPS,
 * come si riprende dopo una disconnessione, come si tengono le conferme. Il trasporto ([Canale]) e
 * l'archivio ([ArchivioLavori]) arrivano da fuori: in app OkHttp e SQLite, nelle prove dei finti.
 *
 * Ripresa (PROTOCOLLO-VPS.md §5): a ogni collegamento si chiede `job_lista`; per ogni lavoro che sulla VPS
 * ha più eventi di quelli salvati qui si manda `job_segui` con l'ultimo `n` salvato. Gli eventi già visti si
 * scartano (idempotente). I lavori nati senza rete partono al collegamento (`job_start` con lo stesso id:
 * se la VPS li conosce già non li rifà).
 */
class NucleoVps(
    private val archivio: ArchivioLavori,
    private val ora: () -> Long = { System.currentTimeMillis() },
    private val caso: () -> Long = { kotlin.random.Random.nextLong() },
) {
    interface Canale {
        /** true se il messaggio è partito (socket collegato e autenticato). */
        fun manda(testo: String): Boolean
        /** Chiede di collegarsi (se le regole lo permettono). */
        fun collega()
    }

    interface Ascoltatore {
        fun cambiato(id: String) {}
        fun conferma(c: ConfermaVps) {}
        fun confermaChiusa(lavoroId: String, azioneId: String, scelta: String) {}
        fun finito(l: LavoroLocale) {}
        fun errore(id: String, motivo: String) {}
        fun collegamento(collegato: Boolean) {}
    }

    var canale: Canale? = null
    private val ascoltatori = mutableListOf<Ascoltatore>()

    @Volatile
    var collegato = false
        private set

    /** Messaggi (conferme, annullamenti) da mandare appena torna il collegamento. */
    private val inAttesa = ArrayList<String>()

    /** Lavori per cui in questo collegamento è già partito job_segui (niente doppioni da job_lista). */
    private val seguiti = HashSet<String>()

    /**
     * 09/10 (Boss: «il resoconto arriva doppio»): i lavori scoperti da job_lista quando erano GIÀ finiti (partiti da
     * un'altra parte: l'app vecchia, una prova dal Mac, un registro ripulito). Si aggiungono e si seguono per il
     * dettaglio, ma la loro fine non si annuncia: era già stata detta, e ridirla rimetteva nel filo e nelle notifiche
     * ogni resoconto vecchio del Postino.
     */
    private val scopertiFiniti = HashSet<String>()

    fun ascolta(a: Ascoltatore): () -> Unit {
        synchronized(ascoltatori) { ascoltatori += a }
        return { synchronized(ascoltatori) { ascoltatori -= a } }
    }

    private fun avvisa(f: (Ascoltatore) -> Unit) {
        val copia = synchronized(ascoltatori) { ascoltatori.toList() }
        copia.forEach { runCatching { f(it) } }
    }

    // ─── dal telefono ──────────────────────────────────────────────────────────

    /** Un lavoro nuovo: salvato qui subito, mandato adesso o al prossimo collegamento. Restituisce l'id. */
    @Synchronized
    fun nuovoLavoro(agente: String, testo: String, modello: String = "sonnet", titolo: String? = null, origine: String = ""): String {
        val id = ProtocolloVps.nuovoId(ora(), caso())
        val l = LavoroLocale(
            id = id, agente = agente, titolo = titolo?.takeIf { it.isNotBlank() } ?: testo.lineSequence().first().take(80), testo = testo,
            stato = LavoroLocale.DA_MANDARE, creato = ora(), modello = modello, origine = origine,
        )
        archivio.salva(l)
        val partito = canale?.manda(ProtocolloVps.jobStart(id, agente, testo, modello, titolo = l.titolo)) == true
        if (partito) archivio.salva(l.copy(stato = LavoroLocale.INVIATO))
        else canale?.collega()
        avvisa { it.cambiato(id) }
        return id
    }

    /**
     * 0.3.0: un lavoro mandato da fuori con un job_start suo (la chat Postino a numeri, opzioni «modo: numeri»).
     * Si registra qui solo se è partito: così Lavori, cronologia, notifiche e conferme lo seguono come gli altri.
     */
    @Synchronized
    fun lavoroGiaMandato(id: String, agente: String, testo: String, titolo: String, origine: String = ""): Boolean {
        if (archivio.lavoro(id) != null) return true
        archivio.salva(LavoroLocale(id = id, agente = agente, titolo = titolo, testo = testo, stato = LavoroLocale.INVIATO, creato = ora(), origine = origine))
        avvisa { it.cambiato(id) }
        return true
    }

    /** La scelta di Boss su una conferma («invia» o «annulla»). false = non collegato: parte appena torna la rete. */
    @Synchronized
    fun scegli(lavoroId: String, azioneId: String, scelta: String): Boolean {
        val m = ProtocolloVps.conferma(lavoroId, azioneId, scelta)
        val l = archivio.lavoro(lavoroId)
        if (l?.conferma?.azioneId == azioneId) archivio.salva(l.copy(confermaMandata = if (scelta == ProtocolloVps.INVIA) ProtocolloVps.INVIA else ProtocolloVps.ANNULLA))
        avvisa { it.cambiato(lavoroId) }
        if (canale?.manda(m) == true) return true
        inAttesa += m
        canale?.collega()
        return false
    }

    @Synchronized
    fun annulla(id: String): Boolean {
        val l = archivio.lavoro(id) ?: return false
        if (l.stato == LavoroLocale.DA_MANDARE) {
            // non è mai partito: si chiude qui, senza disturbare la VPS
            archivio.salva(l.copy(stato = LavoroLocale.FINITO, esito = "annullato", riassunto = "Annullato prima di partire.", finito = ora()))
            avvisa { it.cambiato(id) }
            archivio.lavoro(id)?.let { f -> avvisa { it.finito(f) } }
            return true
        }
        val m = ProtocolloVps.jobCancel(id)
        if (canale?.manda(m) == true) return true
        inAttesa += m
        canale?.collega()
        return false
    }

    /** Ci sono cose per cui tenere aperto il collegamento? (lavori aperti, messaggi in attesa) */
    @Synchronized
    fun haLavoroAperto(): Boolean = inAttesa.isNotEmpty() || archivio.aperti().isNotEmpty()

    // ─── dal canale ────────────────────────────────────────────────────────────

    @Synchronized
    fun collegatoOra() {
        collegato = true
        seguiti.clear()
        val c = canale
        if (c != null) {
            c.manda(ProtocolloVps.jobLista(50))
            for (l in archivio.aperti()) {
                if (l.stato == LavoroLocale.DA_MANDARE) {
                    if (c.manda(ProtocolloVps.jobStart(l.id, l.agente, l.testo, l.modello, titolo = l.titolo))) archivio.salva(l.copy(stato = LavoroLocale.INVIATO))
                } else if (c.manda(ProtocolloVps.jobSegui(l.id, l.ultimoEvento))) {
                    // ripresa: gli eventi persi mentre il telefono era scollegato
                    seguiti += l.id
                }
            }
            val coda = ArrayList(inAttesa)
            inAttesa.clear()
            for (m in coda) if (!c.manda(m)) inAttesa += m
        }
        avvisa { it.collegamento(true) }
    }

    @Synchronized
    fun scollegato() {
        collegato = false
        avvisa { it.collegamento(false) }
    }

    @Synchronized
    fun ricevi(m: MessaggioVps) {
        when (m) {
            is MessaggioVps.Evento -> evento(m.evento)
            is MessaggioVps.Fine -> fine(m)
            is MessaggioVps.Lista -> lista(m)
            is MessaggioVps.Stato -> {
                val l = archivio.lavoro(m.id) ?: return
                if (m.ultimoEvento > l.ultimoEvento) canale?.manda(ProtocolloVps.jobSegui(m.id, l.ultimoEvento))
                if (l.stato == LavoroLocale.DA_MANDARE) archivio.salva(l.copy(stato = LavoroLocale.INVIATO))
            }
            is MessaggioVps.Errore -> {
                val l = archivio.lavoro(m.id)
                // un lavoro rifiutato alla partenza (id, testo, coda piena) si chiude qui con il motivo vero
                if (l != null && (l.stato == LavoroLocale.DA_MANDARE || l.stato == LavoroLocale.INVIATO) && l.ultimoEvento == 0) {
                    archivio.salva(l.copy(stato = LavoroLocale.FINITO, esito = "errore", riassunto = "La VPS non l'ha accettato: ${m.motivo}", finito = ora()))
                    archivio.lavoro(m.id)?.let { f -> avvisa { it.finito(f) } }
                }
                avvisa { it.errore(m.id, m.motivo) }
                avvisa { it.cambiato(m.id) }
            }
            is MessaggioVps.Collegato, is MessaggioVps.Altro -> {}
        }
    }

    private fun evento(e: EventoLavoro) {
        val l = archivio.lavoro(e.id) ?: return // un lavoro che non è nostro: lo aggiunge job_lista
        if (!archivio.aggiungiEvento(e)) return // già visto
        var nuovo = l.copy(ultimoEvento = maxOf(l.ultimoEvento, e.n))
        when (e.kind) {
            "comando", "testo" -> nuovo = nuovo.copy(ultimo = e.testo.lineSequence().firstOrNull().orEmpty().take(160))
            "stato" -> {
                val st = e.datiJson().optString("stato")
                nuovo = nuovo.copy(stato = when (st) {
                    "coda" -> LavoroLocale.CODA
                    "attesa" -> LavoroLocale.ATTESA
                    "avviato", "lavoro" -> if (nuovo.stato == LavoroLocale.FINITO) nuovo.stato else LavoroLocale.LAVORO
                    else -> nuovo.stato
                })
                e.confermaChiusa()?.let { (aid, scelta) ->
                    if (nuovo.conferma?.azioneId == aid) nuovo = nuovo.copy(conferma = null, confermaMandata = null)
                    avvisa { it.confermaChiusa(e.id, aid, scelta) }
                }
            }
            "conferma" -> e.conferma()?.let { c ->
                nuovo = nuovo.copy(conferma = c, confermaMandata = null, stato = LavoroLocale.ATTESA)
                archivio.salva(nuovo)
                // una conferma già scaduta (ripresa di eventi vecchi) non si ripropone a Boss
                if (c.scadeTs == 0L || c.scadeTs > ora()) avvisa { it.conferma(c) }
            }
            "risultato" -> nuovo = nuovo.copy(risultato = e.testo)
            // Lo stato del browser della VPS (aperto/chiuso e cosa fa) qui non arriva: il protocollo v1
            // (docs/PROTOCOLLO-VPS.md §3) non ha un messaggio per la finestra. Quando ci sarà, si legge qui e va a
            // SchedaDelega.da(…, browser). Finché manca, la scheda non mostra la riga «Browser».
        }
        archivio.salva(nuovo)
        avvisa { it.cambiato(e.id) }
    }

    private fun fine(m: MessaggioVps.Fine) {
        val l = archivio.lavoro(m.id) ?: return
        val nuovoEvento = archivio.aggiungiEvento(EventoLavoro(m.id, m.n, "fine", m.ts, m.riassunto, """{"esito":"${m.esito}"}"""))
        if (!nuovoEvento && l.stato == LavoroLocale.FINITO) return
        if (l.stato == LavoroLocale.FINITO && m.id in scopertiFiniti) {
            // la ripresa di un lavoro già finito quando l'abbiamo scoperto: si aggiorna, non si annuncia di nuovo
            archivio.salva(l.copy(esito = m.esito, riassunto = m.riassunto, ultimoEvento = maxOf(l.ultimoEvento, m.n)))
            avvisa { it.cambiato(m.id) }
            return
        }
        val f = l.copy(
            stato = LavoroLocale.FINITO, esito = m.esito, riassunto = m.riassunto, finito = if (m.ts > 0) m.ts else ora(),
            ultimoEvento = maxOf(l.ultimoEvento, m.n), conferma = null, confermaMandata = null,
        )
        archivio.salva(f)
        avvisa { it.cambiato(m.id) }
        avvisa { it.finito(f) }
    }

    private fun lista(m: MessaggioVps.Lista) {
        for (r in m.lavori) {
            val l = archivio.lavoro(r.id)
            if (l == null) {
                // un lavoro partito da un'altra parte (altro telefono, prova): si aggiunge e si segue
                archivio.salva(
                    LavoroLocale(
                        id = r.id, agente = r.agente, titolo = r.titolo, testo = r.testo, stato = statoDa(r.stato),
                        esito = r.esito, riassunto = r.riassunto, creato = r.creato, finito = r.finito, ultimo = r.ultimo,
                    ),
                )
                if (statoDa(r.stato) == LavoroLocale.FINITO) scopertiFiniti += r.id
                if (r.ultimoEvento > 0) canale?.manda(ProtocolloVps.jobSegui(r.id, 0))
                avvisa { it.cambiato(r.id) }
                continue
            }
            if (r.ultimoEvento > l.ultimoEvento && r.id !in seguiti) {
                if (canale?.manda(ProtocolloVps.jobSegui(r.id, l.ultimoEvento)) == true) seguiti += r.id
            }
            if (l.stato == LavoroLocale.INVIATO || l.stato == LavoroLocale.DA_MANDARE) {
                archivio.salva(l.copy(stato = statoDa(r.stato)))
                avvisa { it.cambiato(r.id) }
            }
        }
    }

    private fun statoDa(s: String) = when (s) {
        "coda" -> LavoroLocale.CODA
        "attesa" -> LavoroLocale.ATTESA
        "finito" -> LavoroLocale.FINITO
        else -> LavoroLocale.LAVORO
    }
}

/** Un lavoro come lo tiene il telefono. */
data class LavoroLocale(
    val id: String,
    val agente: String,
    val titolo: String,
    val testo: String,
    val stato: String,
    val esito: String? = null,
    val riassunto: String? = null,
    val risultato: String? = null,
    val creato: Long = 0,
    val finito: Long = 0,
    val ultimoEvento: Int = 0,
    val ultimo: String = "",
    val conferma: ConfermaVps? = null,
    /** La scelta già mandata per [conferma] (in attesa che la VPS la chiuda). */
    val confermaMandata: String? = null,
    val modello: String = "sonnet",
    /**
     * 09/10: da dove è partito il lavoro, con le stesse parole dei comandi («voce», «scritto», «dettato»,
     * «chat-<agente>», «schermata-vps»). Vuoto = non si sa (lavoro arrivato da job_lista, o di prima della 0.7.1).
     * Decide in quale chat compare la scheda della delega ([SchedaDelega.diJBoss]).
     */
    val origine: String = "",
) {
    val aperto: Boolean get() = stato != FINITO

    /**
     * 09/10: un lavoro della posta unica ([com.jarvis.telefono.postino.PostaCondivisa]: pagina del Postino e JBoss).
     * Le sue righe nel filo le scrive il Postino (risposta di JBoss, avviso delle mail da fare): niente scheda della
     * delega, niente «[VPS · Postino] Finito: …» e niente notifica in più, se no il resoconto si vede due volte.
     * Si riconosce dall'origine «chat-postino» o, se l'origine manca (job_lista, lavori di prima della 0.7.1),
     * dall'id che la posta unica dà ai suoi lavori («postino-…», [com.jarvis.telefono.postino.nuovoIdLavoro]).
     */
    val dellaPostaUnica: Boolean
        get() = agente == AGENTE_POSTINO && (origine == ORIGINE_POSTA || id.startsWith(PREFISSO_POSTA))

    companion object {
        const val AGENTE_POSTINO = "postino"
        const val ORIGINE_POSTA = "chat-postino"
        const val PREFISSO_POSTA = "postino-"
        const val DA_MANDARE = "da_mandare"
        const val INVIATO = "inviato"
        const val CODA = "coda"
        const val LAVORO = "lavoro"
        const val ATTESA = "attesa"
        const val FINITO = "finito"
    }
}

/** Dove il telefono tiene lavori ed eventi (in app: SQLite, [RegistroLavori]). */
interface ArchivioLavori {
    fun lavoro(id: String): LavoroLocale?
    fun salva(l: LavoroLocale)
    fun elenco(limite: Int = 100): List<LavoroLocale>
    fun aperti(): List<LavoroLocale>
    /** false se l'evento (id, n) c'era già. */
    fun aggiungiEvento(e: EventoLavoro): Boolean
    fun eventi(id: String, daN: Int = 0): List<EventoLavoro>
}

/** Archivio in memoria: per le prove e come ripiego se SQLite non si apre. */
class ArchivioInMemoria : ArchivioLavori {
    private val lavori = LinkedHashMap<String, LavoroLocale>()
    private val eventi = HashMap<String, java.util.TreeMap<Int, EventoLavoro>>()

    @Synchronized override fun lavoro(id: String) = lavori[id]
    @Synchronized override fun salva(l: LavoroLocale) { lavori[l.id] = l }
    @Synchronized override fun elenco(limite: Int) = lavori.values.sortedByDescending { it.creato }.take(limite)
    @Synchronized override fun aperti() = lavori.values.filter { it.aperto }
    @Synchronized override fun aggiungiEvento(e: EventoLavoro): Boolean {
        val m = eventi.getOrPut(e.id) { java.util.TreeMap() }
        if (m.containsKey(e.n)) return false
        m[e.n] = e
        return true
    }
    @Synchronized override fun eventi(id: String, daN: Int) = eventi[id]?.tailMap(daN, false)?.values?.toList().orEmpty()
}
