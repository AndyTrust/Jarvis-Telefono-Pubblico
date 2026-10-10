package com.jarvis.telefono.collegamento

import java.text.Normalizer

/**
 * Le frasi del Collegamento Jarvis (0.6.0). Kotlin puro, provato in ComandiCollegamentoTest. Il nucleo le guarda
 * prima dell'instradamento: sono comandi del telefono, non lavori per la VPS.
 */
object ComandiCollegamento {

    sealed class Comando {
        /** La webapp Jarvis (il Command Center); [filo] = una conversazione delle notifiche. */
        data class Webapp(val filo: String? = null) : Comando()
        /** Apre la pagina di un contesto seguito (CRM di lavoro, Patrimonio) nel browser. */
        data class ApriContesto(val id: String) : Comando()
        object AllineaMemoria : Comando()
        object CosaSaiDiMe : Comando()
        object Stato : Comando()
        object GiroNotifiche : Comando()
        /** integra-jarvis: «cerca aggiornamenti» (era il pulsante dell'app 1.2.4). */
        object CercaAggiornamenti : Comando()
    }

    private fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ").trim()
        .replace(Regex("^(hey |ehi |ok )?(j ?boss |boss )"), "")
        .replace(Regex("^(per favore |puoi |mi puoi |potresti )"), "")

    private val APRI = "(apri|aprimi|apra|mostrami|fammi vedere|vai (su|sulla|nella|nel|al|alla))"

    private val WEBAPP = Regex("^$APRI (la |il )?(webapp|web app|app di jarvis|chat di jarvis|jarvis|command center|centro comandi|sito di jarvis|sito jarvis|pannello di jarvis)$")
    private val POSTINO = Regex("^$APRI (i |il )?(report|filo|chat) (del |di )?postino$")
    private val CRM = Regex("^$APRI (il |la )?(mio )?(crm|gestionale)( di lavoro| dei clienti)?$")
    private val PATRIMONIO = Regex("^$APRI (il |la )?(mio )?(pagina (del |di )?)?patrimonio$")
    private val ALLINEA = Regex("^(allinea|sincronizza|riallinea|aggiorna) (la |le )?memori[ae]( con jarvis| condivisa)?$")
    private val COSA_SAI = Regex("^(cosa|che cosa|che) (sai|ricordi|conosci) (di me|delle mie abitudini|dei miei gusti)$|^che memoria hai di me$")
    private val STATO = Regex("^(come sta |stato del |com e il )?collegamento (con |a )?jarvis$|^sei collegato a jarvis$")
    private val AGGIORNAMENTI = Regex("^(cerca|controlla|guarda se ci sono|ci sono) (gli |degli )?aggiornamenti( di jboss| dell app| per jboss)?$|^(c e|esiste) una (nuova versione|versione nuova) di jboss$|^aggiorna (jboss|l app)$")
    private val GIRO = Regex("^(controlla|guarda|ci sono) (i |le )?(report|notifiche|avvisi) (di jarvis|del command center|del postino)( nuovi| nuove)?$")

    fun riconosci(frase: String): Comando? {
        val f = norm(frase)
        return when {
            WEBAPP.matches(f) -> Comando.Webapp()
            POSTINO.matches(f) -> Comando.Webapp("postino")
            CRM.matches(f) -> Comando.ApriContesto("crm")
            PATRIMONIO.matches(f) -> Comando.ApriContesto("patrimonio")
            ALLINEA.matches(f) -> Comando.AllineaMemoria
            COSA_SAI.matches(f) -> Comando.CosaSaiDiMe
            STATO.matches(f) -> Comando.Stato
            GIRO.matches(f) -> Comando.GiroNotifiche
            AGGIORNAMENTI.matches(f) -> Comando.CercaAggiornamenti
            else -> null
        }
    }
}
