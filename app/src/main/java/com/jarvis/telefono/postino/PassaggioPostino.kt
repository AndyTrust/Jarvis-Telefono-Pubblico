package com.jarvis.telefono.postino

/**
 * Il passaggio della conversazione fra JBoss e il Postino (Boss, 2026-10-09: «quando dico "passa al Postino" la
 * conversazione passa al Postino; quando dico "torna a JBoss" torna a JBoss, e il passaggio si vede nel filo»).
 * Prima la frase finiva al cervello della VPS, che rispondeva «non posso passarti la conversazione».
 *
 * Decisione di Boss del 09/10: «passa al Postino» apre anche la PAGINA del Postino ([Esito.apriPagina]); «torna a
 * JBoss» la chiude e riporta alla chat di JBoss ([Esito.chiudiPagina]). La voce resta accesa nel passaggio
 * (l'ascolto continuo dipende dall'app davanti, [com.jarvis.telefono.bolla.RegolePopup]).
 *
 * Codice puro, provato sulla JVM. Lo stato è uno per tutta l'app ([conPostino]): con il Postino attivo, ogni frase
 * della chat di JBoss o della pagina del Postino (scritta o detta) va al Postino ([PostaCondivisa.perPostino]) finché
 * Boss non torna a JBoss. Dopo [FINESTRA_MS] senza frasi si torna a JBoss da soli, così una frase detta il giorno dopo
 * non finisce nella posta. Mentre la pagina del Postino è davanti ([paginaDavanti]) la finestra non scade: Boss sta
 * guardando la posta; conta da quando la lascia.
 */
class PassaggioPostino(
    /** La pagina del Postino è davanti (in app: [com.jarvis.telefono.bolla.PrimoPiano.paginaPostino]). */
    private val paginaDavanti: () -> Boolean = { false },
    private val ora: () -> Long = System::currentTimeMillis,
) {

    enum class Verso { AL_POSTINO, A_JBOSS }

    /**
     * Cosa fare dopo una frase di passaggio: la riga da scrivere nel filo, chi ha la conversazione adesso e la pagina
     * del Postino ([apriPagina]: si apre o torna davanti; [chiudiPagina]: si chiude e si torna alla chat di JBoss).
     */
    data class Esito(
        val testo: String,
        val conPostino: Boolean,
        val cambiato: Boolean,
        val apriPagina: Boolean = conPostino,
        val chiudiPagina: Boolean = !conPostino,
    )

    private var attivo = false
    private var ultimoUso = 0L

    /** Il Postino ha la conversazione adesso (e non è scaduta). */
    val conPostino: Boolean
        get() {
            if (attivo && paginaDavanti()) ultimoUso = ora()
            if (attivo && ora() - ultimoUso > FINESTRA_MS) attivo = false
            return attivo
        }

    /** Una frase detta o scritta mentre il Postino ha la conversazione (o la pagina si chiude): la finestra si allunga. */
    fun usato() { if (attivo) ultimoUso = ora() }

    /** Fra quanto scade la finestra (ms), o null se JBoss ha già la conversazione. Per il controllo a tempo dell'app. */
    fun scadeFra(): Long? = if (!conPostino) null else (FINESTRA_MS - (ora() - ultimoUso)).coerceAtLeast(0L) + 1

    /**
     * La frase [verso] di Boss. «torna a JBoss» o «JBoss» quando JBoss ha già la conversazione: null, la frase segue
     * il giro normale (la parola «JBoss» da sola non deve cambiare niente).
     */
    fun passa(verso: Verso): Esito? = when (verso) {
        Verso.AL_POSTINO -> {
            val prima = conPostino
            attivo = true
            ultimoUso = ora()
            Esito(if (prima) GIA_POSTINO else AL_POSTINO, conPostino = true, cambiato = !prima)
        }
        Verso.A_JBOSS -> if (!conPostino) null else {
            attivo = false
            Esito(A_JBOSS, conPostino = false, cambiato = true)
        }
    }

    /** Torna a JBoss senza frase (la pagina del Postino con «torna a JBoss», o chi azzera). */
    fun torna() { attivo = false }

    companion object {
        const val FINESTRA_MS = 15 * 60_000L

        const val AL_POSTINO = "Ora parli con il Postino. Puoi dire «leggi le mail», «avanti», «cancella questa», " +
            "«aggiorna la posta». Per tornare di' «torna a JBoss»."
        const val GIA_POSTINO = "Stai già parlando con il Postino. Per tornare di' «torna a JBoss»."
        const val A_JBOSS = "Ora parli con JBoss."

        private fun pulito(t: String): String = ComandiPostino.senzaAccenti(t.lowercase())
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("^\\s*(hey|ehi|ok|okay)\\b"), " ")
            .replace(Regex("^\\s*(j ?boss|jay ?boss|gei ?boss)\\s+(?=\\S)"), " ")
            .replace(Regex("\\b(per favore|grazie|adesso|ora|subito)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()

        /** «JBoss» come lo scrive Boss o come lo trascrive la voce («J Boss», «jay boss», «gei boss»). */
        private const val JBOSS = "(j ?boss|jay ?boss|gei ?boss|g ?boss)"
        private const val VERBI = "(passa|passami|passaci|vai|andiamo|portami|torna|tornare|ritorna|ritorniamo|torniamo|" +
            "fammi parlare|voglio parlare|parla|parliamo|mettimi|rimettimi|ridammi|dammi)"
        private const val LEGAMI = "( (a|al|alla|allo|con|col|coi|il|lo|la|da|dal|dalla|la chat del|la pagina del|il canale del|chat del|pagina del))*"

        private val AL = Regex("^postino$|^$VERBI$LEGAMI postino$")
        private val A = Regex("^$JBOSS$|^$VERBI$LEGAMI $JBOSS$|^(esci|esco) dal postino$|^basta (con il |col )?postino$")

        /** La frase → il verso del passaggio, o null (non è un passaggio). */
        fun capisci(frase: String): Verso? {
            val t = pulito(frase)
            if (t.isEmpty()) return null
            if (AL.matches(t)) return Verso.AL_POSTINO
            if (A.matches(t)) return Verso.A_JBOSS
            return null
        }
    }
}
