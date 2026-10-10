package com.jarvis.telefono.nucleo

/**
 * I comandi della chat di JBoss (0.7.0, 2026-10-08): ogni frase di Boss, scritta o detta, diventa un comando con uno
 * stato che la Home mostra in una riga (in corso, da confermare, fatto, errore) e, se serve il sì di Boss, un box con
 * Conferma e Annulla. Il comando va avanti anche con l'app in secondo piano (ServizioComando lo tiene vivo finché
 * [attivi] > 0).
 *
 * Kotlin puro e thread-safe: gli ascoltatori sono chiamati sul thread di chi cambia lo stato (Android li porta sul
 * thread principale). Provato in ComandiTest.
 */
class RegistroComandi(
    private val orologio: () -> Long = System::currentTimeMillis,
    /** Un comando aperto da più di tanto si chiude come «scaduto» (rete morta, risposta mai arrivata). */
    private val scadenzaMs: Long = SCADENZA_MS,
) {
    enum class Stato { IN_CORSO, DA_CONFERMARE, FATTO, ERRORE, ANNULLATO, ALLA_VPS }

    data class Comando(
        val id: Long,
        val testo: String,
        val origine: String,
        val stato: Stato,
        val inizioMs: Long,
        val dettaglio: String = "",
        val fineMs: Long = 0L,
    ) {
        val aperto: Boolean get() = stato == Stato.IN_CORSO || stato == Stato.DA_CONFERMARE
    }

    /** POSTA (09/10): una cancellazione del Postino, chiesta sul telefono prima di mandarla alla VPS. */
    enum class TipoConferma { BOZZA, VPS, POSTA }

    /** Il sì che serve a Boss: [chiave] identifica chi aspetta («bozza:<id>», «vps:<lavoro>:<azione>»). */
    data class Conferma(
        val chiave: String,
        val tipo: TipoConferma,
        val titolo: String,
        val corpo: String,
        val etichettaSi: String = "Conferma",
        val lavoroId: String = "",
        val azioneId: String = "",
    )

    data class Istantanea(val comando: Comando?, val conferma: Conferma?, val attivi: Int)

    private val lock = Any()
    private var prossimoId = 1L
    private val comandi = LinkedHashMap<Long, Comando>()
    private var conferma: Conferma? = null
    private val ascoltatori = mutableListOf<(Istantanea) -> Unit>()

    fun osserva(f: (Istantanea) -> Unit): () -> Unit {
        synchronized(lock) { ascoltatori += f }
        return { synchronized(lock) { ascoltatori -= f } }
    }

    /** Una frase nuova di Boss: comando «in corso». */
    fun inizia(testo: String, origine: String): Long {
        val id = synchronized(lock) {
            pulisci()
            val id = prossimoId++
            comandi[id] = Comando(id, testo.trim(), origine, Stato.IN_CORSO, orologio())
            // Ne restano pochi: la riga mostra solo l'ultimo.
            while (comandi.size > MAX_COMANDI) comandi.remove(comandi.keys.first())
            id
        }
        avvisa()
        return id
    }

    /** Cosa sta facendo adesso («Apro WhatsApp…», «La VPS lavora…»). */
    fun lavoro(id: Long, dettaglio: String) = cambia(id) { if (it.aperto) it.copy(dettaglio = dettaglio) else it }

    /** Il comando aperto più recente (per la bolla della VPS che non conosce l'id). */
    fun ultimoAperto(): Long? = synchronized(lock) { comandi.values.lastOrNull { it.aperto }?.id }

    /** Chiude il comando con l'esito del nucleo («ok», «errore», «non capito», «annullato», «in corso» della VPS…). */
    fun chiudi(id: Long, esito: String, errore: Boolean, dire: String = "") =
        cambia(id) { if (!it.aperto) it else it.copy(stato = statoDaEsito(esito, errore), dettaglio = dire.trim(), fineMs = orologio()) }

    /** Serve il sì di Boss: il comando aperto più recente passa a «da confermare». Una sola conferma alla volta. */
    fun chiediConferma(c: Conferma) {
        synchronized(lock) {
            conferma = c
            comandi.values.lastOrNull { it.aperto }?.let { comandi[it.id] = it.copy(stato = Stato.DA_CONFERMARE) }
        }
        avvisa()
    }

    /** La conferma [chiave] è chiusa (sì, no o scaduta): il comando torna «in corso» finché il nucleo non lo chiude. */
    fun confermaChiusa(chiave: String) {
        synchronized(lock) {
            if (conferma?.chiave != chiave) return
            conferma = null
            comandi.values.filter { it.stato == Stato.DA_CONFERMARE }.forEach { comandi[it.id] = it.copy(stato = Stato.IN_CORSO) }
        }
        avvisa()
    }

    fun confermaInAttesa(): Conferma? = synchronized(lock) { conferma }

    fun attivi(): Int = synchronized(lock) { pulisci(); conta() }

    fun istantanea(): Istantanea = synchronized(lock) {
        pulisci()
        Istantanea(comandi.values.lastOrNull(), conferma, conta())
    }

    /**
     * 09/10: come [istantanea], ma il comando mostrato è l'ultimo che passa [filtro] (la chat di un agente vede solo i
     * suoi). La conferma in attesa resta quella unica dell'app: le regole del sì non cambiano.
     */
    fun istantanea(filtro: (Comando) -> Boolean): Istantanea = synchronized(lock) {
        pulisci()
        Istantanea(comandi.values.lastOrNull(filtro), conferma, conta())
    }

    fun comando(id: Long): Comando? = synchronized(lock) { comandi[id] }

    private fun cambia(id: Long, f: (Comando) -> Comando) {
        val cambiato = synchronized(lock) {
            val c = comandi[id] ?: return
            val n = f(c)
            if (n == c) false else { comandi[id] = n; true }
        }
        if (cambiato) avvisa()
    }

    /** Sotto lock: i comandi aperti, più la conferma se aspetta senza un comando (una conferma della VPS). */
    private fun conta(): Int = comandi.values.count { it.aperto } + if (conferma != null && comandi.values.none { it.stato == Stato.DA_CONFERMARE }) 1 else 0

    /** Sotto lock. */
    private fun pulisci() {
        val ora = orologio()
        for (c in comandi.values.toList()) {
            if (c.aperto && c.stato != Stato.DA_CONFERMARE && ora - c.inizioMs > scadenzaMs) {
                comandi[c.id] = c.copy(stato = Stato.ERRORE, dettaglio = "Nessuna risposta in tempo.", fineMs = ora)
            }
        }
    }

    private fun avvisa() {
        val (copia, ist) = synchronized(lock) {
            ascoltatori.toList() to Istantanea(comandi.values.lastOrNull(), conferma, conta())
        }
        copia.forEach { runCatching { it(ist) } }
    }

    companion object {
        const val SCADENZA_MS = 10 * 60_000L
        private const val MAX_COMANDI = 20

        /** L'esito scritto dal nucleo in cronologia → lo stato della riga. */
        fun statoDaEsito(esito: String, errore: Boolean): Stato = when (esito.trim().lowercase()) {
            "annullato" -> Stato.ANNULLATO
            "in corso", "in coda", "alla vps" -> if (errore) Stato.ERRORE else Stato.ALLA_VPS
            "non capito", "errore", "scaduto" -> Stato.ERRORE
            else -> if (errore) Stato.ERRORE else Stato.FATTO
        }

        /**
         * 09/10 (Boss: «Fatto: «ciao, sei online?»» compariva anche nelle chat di Ricercatore e Social, e riaprendo la
         * chat di JBoss restava lo stato di una richiesta di prima senza il suo messaggio): un comando appartiene alla
         * chat di [agente] solo se è partito da lì e da quando la chat è aperta ([daMs]).
         * JBoss ([FiltroCronologia.JARVIS]): tutto quello che non viene dalla chat di un altro agente (scritto in Home o
         * nella sua chat, voce, dettato). Un altro agente: solo le frasi con origine «chat-<agente>».
         */
        fun diChat(c: Comando, agente: String, daMs: Long): Boolean {
            if (c.inizioMs < daMs) return false
            return if (agente == FiltroCronologia.JARVIS) !c.origine.startsWith(PREFISSO_CHAT)
            else c.origine == FiltroCronologia.origineChat(agente)
        }

        private const val PREFISSO_CHAT = "chat-"

        /** La riga di stato in chat. null = niente da mostrare. */
        fun riga(i: Istantanea, adessoMs: Long = System.currentTimeMillis()): String? {
            val c = i.comando
            val k = i.conferma
            if (k != null) return "Da confermare: ${k.titolo}"
            if (c == null) return null
            val frase = "«" + c.testo.take(40) + (if (c.testo.length > 40) "…" else "") + "»"
            return when (c.stato) {
                Stato.IN_CORSO -> "In corso: " + c.dettaglio.ifBlank { frase } + secondi(adessoMs - c.inizioMs)
                Stato.DA_CONFERMARE -> "Da confermare: $frase"
                Stato.FATTO -> "Fatto: $frase"
                Stato.ERRORE -> "Errore: " + c.dettaglio.ifBlank { frase }.take(80)
                Stato.ANNULLATO -> "Annullato: $frase"
                Stato.ALLA_VPS -> "Alla VPS: $frase, ti avviso quando ha finito"
            }
        }

        private fun secondi(ms: Long): String = if (ms >= 3_000L) " · ${ms / 1000} s" else ""
    }
}

/** Il registro unico dell'app (la Home, il nucleo, le mani e il servizio guardano questo). */
object Comandi {
    val registro = RegistroComandi()
}
