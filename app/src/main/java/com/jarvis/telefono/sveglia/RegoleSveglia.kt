package com.jarvis.telefono.sveglia

/**
 * La sveglia della VPS (JBoss 0.6.1, 2026-10-08), parte in Kotlin puro: si prova sulla JVM (SvegliaTest).
 *
 * La VPS, quando deve parlare col telefono e il telefono non è collegato, manda un messaggio FCM ad alta priorità,
 * solo dati: `{tipo: "sveglia", ts: <millisecondi della VPS>, v: 1}`. Il telefono apre il canale delle mani verso la VPS
 * per [FINESTRA_MS] (3 minuti), poi torna a riposo da solo (CanaleMani.programmaRiposo). Nessun ciclo, nessun wakelock.
 */
object RegoleSveglia {
    const val TIPO = "sveglia"

    /** Quanto resta aperto il canale dopo una sveglia: come il riposo delle mani dopo una frase. */
    const val FINESTRA_MS = 180_000L

    /**
     * Una sveglia arrivata troppo tardi (FCM la può tenere in coda se il telefono era senza rete) non serve più: la VPS
     * aspetta al massimo 15 secondi. Oltre [SCADENZA_MS] si ignora, così una raffica di sveglie vecchie non tiene il
     * telefono collegato per niente. Un orologio sfasato (ts nel futuro) non scarta niente.
     */
    const val SCADENZA_MS = 120_000L

    enum class Decisione { SVEGLIA, NON_E_SVEGLIA, SCADUTA }

    fun decidi(dati: Map<String, String>, adessoMs: Long): Decisione {
        if (dati["tipo"] != TIPO) return Decisione.NON_E_SVEGLIA
        val ts = dati["ts"]?.toLongOrNull() ?: return Decisione.SVEGLIA
        return if (adessoMs - ts > SCADENZA_MS) Decisione.SCADUTA else Decisione.SVEGLIA
    }

    /** Il token FCM è una stringa lunga senza spazi: quello che non gli somiglia non si manda. */
    fun tokenValido(t: String?): Boolean =
        t != null && t.length in 32..4096 && t.none { it.isWhitespace() || it == '"' || it == '\\' }

    /**
     * La finestra della sveglia: finché è aperta, gli strumenti che la VPS chiede fuori da una frase di Boss si
     * eseguono (è la VPS che ha svegliato il telefono per quello). Fuori dalla finestra si rifiutano come prima.
     */
    class Finestra(private val durataMs: Long = FINESTRA_MS) {
        @Volatile private var finoA = 0L

        fun apri(adessoMs: Long) { finoA = adessoMs + durataMs }
        fun chiudi() { finoA = 0L }
        fun aperta(adessoMs: Long): Boolean = adessoMs < finoA
        /** Uno strumento eseguito in finestra la allunga: la VPS sta ancora lavorando col telefono. */
        fun allunga(adessoMs: Long) { if (aperta(adessoMs)) finoA = adessoMs + durataMs }
    }
}
