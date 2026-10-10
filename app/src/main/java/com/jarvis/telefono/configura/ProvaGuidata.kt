package com.jarvis.telefono.configura

import com.jarvis.telefono.nucleo.Cronologia

/**
 * Il passo 5, la prova guidata: tre frasi da dire a JBoss. Si capisce dalla cronologia (le frasi di Boss e le
 * risposte), senza toccare la voce. Puro, provato in PassiTest.
 */
object ProvaGuidata {
    enum class Prova(val frase: String, val spiega: String) {
        ORA("«Hey Boss, che ore sono»", "JBoss ti ascolta e risponde con l'ora."),
        APP("«apri Spotify»", "JBoss apre l'app (o un'altra che hai, per esempio «apri Impostazioni»)."),
        BOZZA("«manda un WhatsApp a me con scritto prova»", "JBoss prepara la bozza e ti chiede conferma: tocca Annulla."),
    }

    enum class Fase { ATTESA, ASCOLTATA, FATTA }

    private fun riconosce(p: Prova, testo: String): Boolean {
        val t = testo.lowercase()
        return when (p) {
            Prova.ORA -> "ore" in t || "che ora" in t || "orario" in t
            Prova.APP -> Regex("\\bapri\\b").containsMatchIn(t)
            Prova.BOZZA -> "whatsapp" in t || "messaggio" in t
        }
    }

    /** Per ogni prova: ATTESA, ASCOLTATA (Boss l'ha detta) o FATTA (JBoss ha risposto dopo). [dal] = apertura del passo. */
    fun fasi(voci: List<Cronologia.Voce>, dal: Long): Map<Prova, Fase> {
        val dopo = voci.filter { it.quando >= dal }.sortedBy { it.quando }
        return Prova.values().associateWith { p ->
            val i = dopo.indexOfFirst { it.chi == Cronologia.BOSS && riconosce(p, it.testo) }
            when {
                i < 0 -> Fase.ATTESA
                dopo.drop(i + 1).takeWhile { it.chi != Cronologia.BOSS }.any { it.chi == Cronologia.JARVIS } -> Fase.FATTA
                else -> Fase.ASCOLTATA
            }
        }
    }

    fun tutteFatte(f: Map<Prova, Fase>): Boolean = f.values.all { it == Fase.FATTA }

    /**
     * 2026-10-10: la riga «JBoss in ascolto» è verde solo se JBoss è acceso, il microfono è concesso E la voce non è in
     * pausa o spenta dalla notifica. Prima bastava «acceso»: con la voce in pausa la riga diceva «JBoss ti sente» e il
     * microfono era chiuso.
     */
    fun ascoltoAcceso(attivo: Boolean, microfono: Boolean, modo: com.jarvis.telefono.voce.ModoVoce): Boolean =
        attivo && microfono && modo.ascolta
}
