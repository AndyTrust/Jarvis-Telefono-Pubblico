package com.jarvis.telefono.ui

import com.jarvis.telefono.agenti.CatalogoAgenti

/**
 * La barra di scrittura degli agenti (2026-10-10): campo + Parla + Chiama + Invia, uguale in tutte le chat
 * (layout barra_agente). Qui le parti senza Android, provate in BarraAgenteTest: il testo guida di ogni agente e la
 * chiamata a mani libere. JBoss è l'app; Jarvis è la webapp sul computer.
 */
object TestiBarra {

    /** Il testo guida del campo: «Scrivi a <agente>: …» con esempi del suo mestiere. */
    fun suggerimento(agente: String): String = when (agente) {
        CatalogoAgenti.JARVIS -> "Scrivi a JBoss: «che ore sono», «apri WhatsApp»…"
        CatalogoAgenti.POSTINO -> "Scrivi al Postino: «invia la 1», «cestina la 2»…"
        CatalogoAgenti.RICERCATORE -> "Scrivi al Ricercatore: «cerca notizie su…», «confronta i prezzi di…»"
        CatalogoAgenti.SOCIAL -> "Scrivi a Social: «prepara un post su…», «idee per LinkedIn»…"
        CatalogoAgenti.MANI -> "Scrivi a Mani: «apri le impostazioni», «accendi la torcia»…"
        CatalogoAgenti.SCRITTORE -> "Scrivi allo Scrittore: «scrivi una mail di auguri», «accorcia questo testo»…"
        else -> "Scrivi all'agente…"
    }

    /** Il nome con l'articolo, per i messaggi («Chiamata con il Postino»). */
    fun conNome(agente: String): String = when (agente) {
        CatalogoAgenti.JARVIS -> "JBoss"
        CatalogoAgenti.POSTINO -> "il Postino"
        CatalogoAgenti.RICERCATORE -> "il Ricercatore"
        CatalogoAgenti.SOCIAL -> "Social"
        CatalogoAgenti.MANI -> "Mani"
        CatalogoAgenti.SCRITTORE -> "lo Scrittore"
        else -> "l'agente"
    }

    fun chiamataAperta(agente: String) =
        "Chiamata con ${conNome(agente)}: parla pure. Di' «basta» o tocca di nuovo il telefono per chiudere."
    fun chiamataChiusa(agente: String) = "Chiamata con ${conNome(agente)} chiusa."
    const val SILENZIO = "Non ti sento più: chiudo la chiamata."
    const val MICROFONO = "Serve il permesso del microfono: Impostazioni → Permessi."
    const val VOCE_ASSENTE = "Questo telefono non ha il riconoscimento vocale di Google. Scarica la voce senza rete " +
        "in Impostazioni → Voce (circa 415 MB, una volta), oppure scrivi."
    const val DETTATO_FALLITO = "Il microfono non parte adesso: riprova tra un attimo o scrivi."
    const val ASCOLTO = "Ti ascolto… parla adesso (tocca il microfono per fermare)"
    const val VUOTO = "Scrivi qualcosa nel campo, poi tocca la freccia."

    /** Il motivo di un dettato non partito (DettatoNativo.motivo) → la frase per l'utente. */
    fun motivoDettato(motivo: String): String = when (motivo) {
        "microfono_negato" -> MICROFONO
        "whisper_assente" -> VOCE_ASSENTE
        else -> DETTATO_FALLITO
    }
}

/**
 * La chiamata a mani libere con un agente: ascolta una frase, la manda, aspetta la risposta, riascolta. Finisce con
 * «basta» (o simili), con un secondo tocco sul telefono, o dopo [SILENZI_MAX] ascolti di fila senza parole.
 * Nessun invio parte da qui: le frasi vanno all'agente come se le avessi scritte, e ogni invio vero chiede Invia.
 */
class ChiamataAgente {

    enum class Stato { SPENTA, ASCOLTO, ATTESA_RISPOSTA }

    sealed class Passo {
        /** Apri il microfono (dettato). */
        object Ascolta : Passo()
        /** Manda questa frase all'agente, poi aspetta la risposta. */
        data class Manda(val testo: String) : Passo()
        /** Chiudi la chiamata, con il motivo da mostrare. */
        data class Chiudi(val perche: String) : Passo()
        /** Niente da fare. */
        object Niente : Passo()
    }

    var stato = Stato.SPENTA
        private set
    private var silenzi = 0

    val aperta: Boolean get() = stato != Stato.SPENTA

    fun avvia(): Passo {
        stato = Stato.ASCOLTO
        silenzi = 0
        return Passo.Ascolta
    }

    /** Il tocco sul telefono durante la chiamata, o l'uscita dalla pagina. */
    fun ferma(): Passo {
        if (stato == Stato.SPENTA) return Passo.Niente
        stato = Stato.SPENTA
        return Passo.Chiudi("")
    }

    /** È finito un dettato: [testo] vuoto = nessuna parola. [guasto] = il microfono non è partito. */
    fun dettato(testo: String, guasto: Boolean = false): Passo {
        if (stato != Stato.ASCOLTO) return Passo.Niente
        if (guasto) { stato = Stato.SPENTA; return Passo.Chiudi("guasto") }
        val t = testo.trim()
        if (t.isEmpty()) {
            silenzi++
            if (silenzi >= SILENZI_MAX) { stato = Stato.SPENTA; return Passo.Chiudi(TestiBarra.SILENZIO) }
            return Passo.Ascolta
        }
        silenzi = 0
        if (FINE.matches(normalizza(t))) { stato = Stato.SPENTA; return Passo.Chiudi("") }
        stato = Stato.ATTESA_RISPOSTA
        return Passo.Manda(t)
    }

    /** L'agente ha risposto (comando chiuso e voce finita): si riascolta. */
    fun rispostaArrivata(): Passo {
        if (stato != Stato.ATTESA_RISPOSTA) return Passo.Niente
        stato = Stato.ASCOLTO
        return Passo.Ascolta
    }

    companion object {
        const val SILENZI_MAX = 2
        private val FINE = Regex("^(basta|fine|chiudi|chiudi la chiamata|riattacca|stop|ciao|grazie basta|ok basta)( grazie)?$")
        private fun normalizza(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
