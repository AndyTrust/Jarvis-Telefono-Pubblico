package com.jarvis.telefono.ui

import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.agenti.Competenze
import com.jarvis.telefono.voce.Stato

/**
 * Il chip sotto Jarvis (0.2.0, Boss 07/10): un agente compare solo mentre opera («Mani: Apro
 * Spotify…») e poi sparisce. Jarvis da solo non ha chip: è già lì, grande. Funzione pura (ChipTest).
 */
object ChipLavoro {

    /**
     * L'agente da mostrare nel chip a fianco di Jarvis, o null = nessun chip. Il Postino è un
     * titolare (sempre in Home): quando lavora lui non c'è chip, si accende il suo segno «in corso».
     */
    fun agente(s: Stato): String? = s.agenteAlLavoro
        ?.takeIf { CatalogoAgenti.predefinito(it) != null && it != CatalogoAgenti.POSTINO }

    /** Il Postino sta lavorando adesso? (il punto «in corso» sulla sua scheda in Home) */
    fun postinoAlLavoro(s: Stato): Boolean = s.agenteAlLavoro == CatalogoAgenti.POSTINO

    /** «Mani: Apro Spotify…», o «Mani: al lavoro…» se non si sa ancora l'azione. */
    fun testo(s: Stato): String? {
        val id = agente(s) ?: return null
        val cosa = s.lavoroInCorso?.takeIf { it.isNotBlank() } ?: "al lavoro…"
        return "${Competenze.nomeCorto(id)}: $cosa"
    }

    /**
     * 0.3.0: un lavoro aperto sulla VPS (agente, ultimo passo). Il chip c'è se l'agente ha un avatar e non è il
     * Postino (titolare: si accende il suo «in corso sulla VPS»). Il lavoro locale ha la precedenza.
     */
    fun agenteVps(s: Stato, agenteVps: String?): String? =
        if (agente(s) != null) null else agenteVps?.takeIf { CatalogoAgenti.predefinito(it) != null && it != CatalogoAgenti.POSTINO }

    /** «Ricercatore: sulla VPS · Cerco le fonti…» */
    fun testoVps(agenteVps: String, ultimo: String?): String =
        "${Competenze.nomeCorto(agenteVps)}: sulla VPS${ultimo?.takeIf { it.isNotBlank() }?.let { " · ${it.take(60)}" } ?: "…"}"
}
