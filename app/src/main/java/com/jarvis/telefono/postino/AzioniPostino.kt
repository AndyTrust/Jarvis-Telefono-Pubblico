package com.jarvis.telefono.postino

/**
 * Le azioni rapide del Postino (0.6.6, layout approvato da Boss il 2026-10-08). Codice puro, provato
 * sulla JVM: quale azione per quale swipe, quali chiedono la conferma, quali comandi partono e in che
 * ordine, il riassunto in due righe.
 *
 * - swipe a SINISTRA = Elimina (nel Cestino): SEMPRE con conferma sul telefono, anche per una mail sola;
 *   poi la VPS chiede la sua (postino_numeri.py: ogni cestino/spam chiede conferma).
 * - swipe a DESTRA = Archivia: subito, senza conferma, annullabile per [ATTESA_ANNULLA_MS]: il comando
 *   parte solo allo scadere, quindi «Annulla» lascia la mail dov'è (niente da spostare indietro).
 * - nella mail aperta: Rispondi (bozza di JBoss, parte solo con Invia…), Spam (con conferma),
 *   Fatto (segna letta, poi archivia), Dopo (resta in arrivo), Elimina (con conferma).
 */
object AzioniPostino {

    enum class Azione(val nome: String) {
        ELIMINA("Elimina"), ARCHIVIA("Archivia"), RISPONDI("Rispondi"), SPAM("Spam"), FATTO("Fatto"), DOPO("Dopo"),
    }

    enum class Swipe { SINISTRA, DESTRA }

    /** Il tempo per «Annulla» dopo uno swipe di archivio, prima che il comando parta per la VPS. */
    const val ATTESA_ANNULLA_MS = 5_000L

    fun perSwipe(s: Swipe): Azione = when (s) {
        Swipe.SINISTRA -> Azione.ELIMINA
        Swipe.DESTRA -> Azione.ARCHIVIA
    }

    /** Elimina e Spam chiedono SEMPRE la conferma sul telefono, qualunque sia il numero di mail. */
    fun chiedeConferma(a: Azione): Boolean = a == Azione.ELIMINA || a == Azione.SPAM

    /** L'archivio da swipe si può annullare; le altre azioni no (o passano da una conferma, o da Invia). */
    fun annullabile(a: Azione): Boolean = a == Azione.ARCHIVIA

    /** Le azioni che vogliono la cartella di destinazione. */
    fun vuoleCartella(a: Azione): Boolean = a == Azione.ARCHIVIA || a == Azione.FATTO

    /**
     * I comandi per la VPS, in ordine: ognuno è un lavoro, il successivo parte solo dopo che il
     * precedente è finito bene. Fatto = prima «letta» (la VPS la verifica in arrivo), poi «sposta»:
     * nello stesso lavoro la verifica di «letta» non troverebbe più la mail in arrivo.
     * Rispondi non manda niente da qui: apre la bozza. Senza cartella Archivia e Fatto tornano vuoti
     * (il telefono chiede la cartella, non la inventa).
     */
    fun comandi(a: Azione, numeri: List<Int>, cartella: String? = null): List<String> {
        if (numeri.isEmpty()) return emptyList()
        val nn = ComandiPostino.elencoCorto(numeri)
        return when (a) {
            Azione.ELIMINA -> listOf("cestina $nn")
            Azione.SPAM -> listOf("spam $nn")
            Azione.DOPO -> listOf("tieni $nn")
            Azione.ARCHIVIA -> if (cartella.isNullOrBlank()) emptyList() else listOf("sposta $nn in $cartella")
            Azione.FATTO -> if (cartella.isNullOrBlank()) emptyList() else listOf("letta $nn", "sposta $nn in $cartella")
            Azione.RISPONDI -> emptyList()
        }
    }

    /** Più archivi in attesa (swipe in fila) → un comando solo: «sposta 3, 7 in Rumore; sposta 5 in GitHub». */
    fun comandoArchivi(perNumero: Map<Int, String>): String? {
        if (perNumero.isEmpty()) return null
        return perNumero.entries.groupBy({ it.value }, { it.key }).entries
            .sortedBy { it.value.min() }
            .joinToString("; ") { (c, nn) -> "sposta ${ComandiPostino.elencoCorto(nn)} in $c" }
    }

    /**
     * La cartella per archiviare SUBITO: la scelta di Boss, poi quella della scheda, poi la proposta
     * del resoconto; vale solo se è una cartella vera della casella. null = va chiesta.
     */
    fun cartellaArchivio(scelta: String?, scheda: String?, proposta: String?, vere: List<String>): String? =
        listOf(scelta, scheda, proposta).firstOrNull { !it.isNullOrBlank() && it in vere }

    /** La domanda della conferma sul telefono. */
    fun domanda(a: Azione, numeri: List<Int>): String {
        val chi = if (numeri.size == 1) "la mail ${numeri[0]}" else "${numeri.size} mail (${ComandiPostino.elencoCorto(numeri)})"
        return when (a) {
            Azione.ELIMINA -> "Elimino $chi? Va nel Cestino (recuperabile da lì)."
            Azione.SPAM -> "Segno $chi come spam? Va nella cartella Spam."
            else -> "${a.nome} $chi?"
        }
    }

    /** Il riassunto in due righe, prima di scegliere: le prime frasi, al massimo [massimo] caratteri. */
    fun riassuntoBreve(testo: String?, massimo: Int = 160): String {
        val t = testo.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (t.length <= massimo) return t
        val frasi = Regex("[^.!?]+[.!?]+").findAll(t).map { it.value.trim() }.toList()
        val presa = StringBuilder()
        for (f in frasi) {
            if (presa.length + f.length + 1 > massimo) break
            if (presa.isNotEmpty()) presa.append(' ')
            presa.append(f)
        }
        if (presa.isNotEmpty()) return presa.toString()
        val taglio = t.take(massimo - 1)
        val spazio = taglio.lastIndexOf(' ').takeIf { it > massimo / 2 } ?: taglio.length
        return taglio.substring(0, spazio).trimEnd(',', ';', ':', ' ') + "…"
    }

    /**
     * Gli archivi da swipe in attesa: si vedono già tolti dalla lista, partono allo scadere,
     * «Annulla» li toglie senza mandare niente. Il tempo arriva da fuori (provato sulla JVM).
     */
    class CodaAnnullabile(private val ora: () -> Long = System::currentTimeMillis, private val attesa: Long = ATTESA_ANNULLA_MS) {
        private val inAttesa = linkedMapOf<Int, Pair<String, Long>>()     // numero → (cartella, scadenza)

        fun metti(numero: Int, cartella: String) { inAttesa[numero] = cartella to ora() + attesa }

        /** true se c'era ed è stata tolta prima di partire. */
        fun annulla(numero: Int): Boolean = inAttesa.remove(numero) != null

        fun contiene(numero: Int) = numero in inAttesa

        fun numeri(): Set<Int> = inAttesa.keys.toSet()

        /** Quelle scadute (o tutte con [tutte]): escono dalla coda e tornano numero → cartella. */
        fun scadute(tutte: Boolean = false): Map<Int, String> {
            val adesso = ora()
            val via = inAttesa.filter { tutte || it.value.second <= adesso }.mapValues { it.value.first }
            via.keys.forEach { inAttesa.remove(it) }
            return via
        }

        /** Fra quanti ms scade la prossima (null se la coda è vuota). */
        fun prossimaFra(): Long? = inAttesa.values.minOfOrNull { it.second }?.let { (it - ora()).coerceAtLeast(0) }
    }
}
