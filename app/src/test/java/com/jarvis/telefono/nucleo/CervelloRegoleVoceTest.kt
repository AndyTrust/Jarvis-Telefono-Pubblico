package com.jarvis.telefono.nucleo

import com.jarvis.telefono.mani.CercaContatti.Contatto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.3 (Boss 07/10 sera: «non funziona, funziona malissimo»): il cervello a regole sulle frasi come
 * escono davvero dalla trascrizione. Quattro gruppi:
 *  - BUONE: 60 forme naturali con varianti (devono fare la cosa giusta);
 *  - STORPIATE: 20 frasi rovinate dalla trascrizione, comprese quelle dei log del 07/10;
 *  - NEUTRE: 20 frasi che NON devono far scattare niente;
 *  - PERICOLOSE: 20 quasi-comandi che non devono mai mandare niente né inventare numeri o indirizzi.
 * Rubrica finta, nessun dato vero.
 */
class CervelloRegoleVoceTest {

    private val rubrica = listOf(
        Contatto("Marco Rossi", listOf("+39 333 1234567"), listOf("marco.rossi@example.com")),
        Contatto("Giulia Verdi", listOf("335 111 2233"), listOf("giulia@example.com")),
        Contatto("Mamma", listOf("3409998887"), emptyList()),
        Contatto("Paolo", listOf("3471234000"), emptyList()),
        Contatto("Luca Neri", listOf("3331111111", "3332222222"), emptyList()),
    )
    private val config = ConfigPersonale(appMail = "", accountMail = "boss@example.com", chatSeStesso = "PROVA BOSS (Tu)", whatsappMe = "")
    // Martedì 7 ottobre 2026, 21:36.
    private val ctx = Contesto(rubrica, config, ora = 21, minuti = 36, giorno = 7, mese = 10, anno = 2026, giornoSettimana = 2)
    private val c = CervelloRegole()
    private fun p(f: String) = c.interpreta(f, ctx)

    /** Cosa ha deciso, in una riga: «dire:…» per le risposte senza azioni, altrimenti le azioni e il campo chiave. */
    private fun esito(f: String): String {
        val pi = p(f)
        if (!pi.capito) return "NON CAPITO"
        if (pi.azioni.isEmpty()) return "dire:" + pi.dire
        return pi.azioni.joinToString("+") { a ->
            a.action + when (a.action) {
                "apri_app" -> "=" + a["nome"]
                "key" -> "=" + a["name"]
                "scorri" -> "=" + a["direzione"]
                "componi" -> "=" + a["tipo"] + listOfNotNull(a["numero"], a["dove"]).joinToString("") { ":$it" }
                "cerca_google" -> "=" + a["domanda"]
                "cerca_in_app" -> "=" + a["app"] + ":" + a["testo"]
                else -> ""
            }
        }
    }

    private val ORA = "dire:Sono le 21 e 36."
    private val DATA = "dire:Oggi è martedì 7 ottobre 2026."

    // ------------------------------------------------------------ 60 buone

    private val buone = linkedMapOf(
        "che ore sono" to ORA, "Che ore sono?" to ORA, "che ora è" to ORA, "Che ora è?" to ORA,
        "che ora sono" to ORA, "Che ora sono adesso?" to ORA, "che ore sono adesso" to ORA,
        "mi dici che ore sono" to ORA, "sai che ore sono?" to ORA, "dimmi l'ora" to ORA,
        "mi dici l'ora per favore" to ORA, "JBoss, che ore sono?" to ORA, "Hey Boss che ore sono" to ORA,
        "ehm che ore sono" to ORA, "senti, che ora è?" to ORA,
        "che giorno è oggi" to DATA, "Che giorno è?" to DATA, "che data è oggi" to DATA,
        "quanti ne abbiamo oggi" to DATA, "oggi che giorno è" to DATA,
        "apri WhatsApp" to "apri_app=WhatsApp", "Apri whatsapp." to "apri_app=WhatsApp",
        "apri la fotocamera" to "apri_app=fotocamera", "lancia Spotify" to "apri_app=Spotify",
        "avvia YouTube" to "apri_app=YouTube", "vai su Instagram" to "apri_app=Instagram",
        "apri le impostazioni" to "apri_app=impostazioni", "aprì WhatsApp" to "apri_app=WhatsApp",
        "apri Gmail adesso" to "apri_app=Gmail",
        "chiama la mamma" to "componi=chiama:3409998887+invia_bozza", "telefona a Paolo" to "componi=chiama:3471234000+invia_bozza",
        "chiama il 333 1234567" to "componi=chiama:3331234567+invia_bozza",
        "manda un WhatsApp a Marco Rossi che arrivo tra dieci minuti" to "componi=whatsapp:+39 333 1234567+invia_bozza",
        "scrivi a Giulia che sono in ritardo" to "componi=whatsapp:335 111 2233+invia_bozza",
        "manda un SMS a Paolo: ok ricevuto" to "componi=sms:3471234000+invia_bozza",
        "cerca su Google ristoranti a Cagliari" to "cerca_google=ristoranti a Cagliari",
        "cerca il meteo di domani" to "cerca_google=il meteo di domani",
        "cerca su YouTube musica rilassante" to "componi=youtube",
        "metti Vasco Rossi su Spotify" to "cerca_in_app=Spotify:Vasco Rossi",
        "portami a casa" to "componi=naviga:casa",
        "come arrivo all'aeroporto di Olbia" to "componi=naviga:aeroporto di Olbia",
        "naviga verso via Roma 10" to "componi=naviga:via Roma 10",
        "voglio andare a Cagliari" to "componi=naviga:Cagliari",
        "dove si trova il Colosseo" to "componi=mappe:Colosseo",
        "torna indietro" to "key=BACK", "indietro" to "key=BACK", "vai alla home" to "key=HOME",
        "torna alla schermata principale" to "key=HOME", "scorri giù" to "scorri=giu", "scorri in su" to "scorri=su",
        "mostra le app recenti" to "key=RECENTS", "apri le notifiche" to "key=NOTIFICHE",
        "leggi le ultime notifiche" to "key=NOTIFICHE",
        "accendi la torcia" to "key=IMPOSTAZIONI_RAPIDE", "attiva il wifi" to "key=IMPOSTAZIONI_RAPIDE",
        "spegni il bluetooth" to "key=IMPOSTAZIONI_RAPIDE",
        "imposta una sveglia alle sette" to "componi=sveglia", "metti un timer di cinque minuti" to "componi=timer",
        "svegliami domani alle 7" to "componi=sveglia",
        "alza il volume" to "dire:Il volume non lo so ancora regolare: usa i tasti laterali del telefono.",
    )

    @Test fun `60 frasi buone`() {
        assertEquals(60, buone.size)
        val sbagliate = buone.filter { (f, atteso) -> esito(f) != atteso }.map { (f, atteso) -> "«$f»: atteso $atteso, avuto ${esito(f)}" }
        assertTrue(sbagliate.joinToString("\n"), sbagliate.isEmpty())
    }

    // ------------------------------------------------------------ 20 storpiate

    private val storpiate = linkedMapOf(
        // dai log del 07/10 (Boss ha detto «che ore sono» / «che ore sono adesso»)
        "Pior sono." to "ora", "Piori sono adesso." to "ora", "Chiaro sono adesso." to "ora",
        "Che ora sono adesso?" to "ora", "che ora sono" to "ora",
        // altre storpiature tipiche
        "Ore sono." to "ora", "Kior sono" to "ora", "che ore son" to "ora", "...che ore sono?" to "ora",
        "Boss che ore sono" to "ora", "torna in dietro" to "key=BACK", "va alla home" to "key=HOME",
        "scorre giù" to "scorri=giu", "che giorni è oggi" to "data",
        "Apri whats app" to "apri_app=WhatsApp", "apri you tube" to "apri_app=YouTube",
        "manda un wazzap a Paolo, arrivo" to "componi=whatsapp:3471234000+invia_bozza",
        "invia un uozzap a Paolo: ok" to "componi=whatsapp:3471234000+invia_bozza",
        "Cerca su Google, meteo Olbia." to "cerca_google=meteo Olbia",
        "Hey Jarvis aprimi spotify" to "apri_app=spotify",
        "come arrivo all aeroporto di Olbia" to "componi=naviga:aeroporto di Olbia",
    )

    @Test fun `20 frasi storpiate dalla trascrizione`() {
        assertEquals(21, storpiate.size)
        val sbagliate = storpiate.filter { (f, atteso) ->
            val e = esito(f)
            when (atteso) {
                "ora" -> !(e.startsWith("dire:") && e.endsWith("Sono le 21 e 36."))
                "data" -> !(e.startsWith("dire:") && e.endsWith("Oggi è martedì 7 ottobre 2026."))
                else -> e != atteso
            }
        }.map { (f, atteso) -> "«$f»: atteso $atteso, avuto ${esito(f)}" }
        assertTrue(sbagliate.joinToString("\n"), sbagliate.isEmpty())
    }

    @Test fun `la somiglianza dice cosa ha capito`() {
        assertEquals("Ho capito «che ore sono». Sono le 21 e 36.", p("Pior sono.").dire)
    }

    // ------------------------------------------------------------ 20 neutre: non scatta niente

    private val neutre = listOf(
        "come sono", "dove sono", "chi sono", "cosa sono", "che cosa sono", "ci sono",
        "Chi ha resciato?", "e", "ok", "grazie", "va bene", "ciao", "che bello",
        "sono stanco", "domani piove", "ho fame", "non lo so", "buonanotte", "che fai", "dove siamo",
    )

    @Test fun `20 frasi neutre non fanno scattare niente`() {
        assertEquals(20, neutre.size)
        val scattate = neutre.filter { p(it).capito }.map { "«$it» → ${esito(it)}" }
        assertTrue(scattate.joinToString("\n"), scattate.isEmpty())
    }

    @Test fun `non capito dice cosa ha sentito`() {
        val pi = p("Chi ha resciato?")
        assertFalse(pi.capito)
        assertTrue(pi.dire, pi.dire.startsWith("Ho sentito «Chi ha resciato»"))
        assertTrue(pi.dire, "apri WhatsApp" in pi.dire)
    }

    // ------------------------------------------------------------ 20 pericolose: mai invii, mai numeri inventati

    private val pericolose = listOf(
        "manda", "manda un WhatsApp", "manda un messaggio", "scrivi una mail", "manda un SMS",
        "scrivi a", "manda un WhatsApp a Ermenegildo: ciao", "chiama Ermenegildo", "chiama",
        "manda un WhatsApp a Luca: arrivo", "manda un SMS al 12: ciao", "scrivi una mail a Paolo: ciao",
        "manda un WhatsApp a Marco", "manda un WhatsApp a me: ciao", "invia", "annulla",
        "telefona", "manda un messaggio a tutti: ciao", "scrivi una mail a mario punto rossi: ciao", "chiama il 33",
    )

    @Test fun `20 quasi comandi pericolosi non mandano niente`() {
        assertEquals(20, pericolose.size)
        val noti = setOf("3409998887", "3471234000", "+39 333 1234567", "335 111 2233", "3331111111", "3332222222")
        val male = pericolose.mapNotNull { f ->
            val pi = p(f)
            val numeri = pi.azioni.mapNotNull { it["numero"]?.toString() }
            val inviaSenzaComponi = pi.azioni.any { it.action == "invia_bozza" } && pi.azioni.none { it.action == "componi" }
            when {
                pi.azioni.any { it.action == "invia_bozza" } -> "«$f» prepara un invio: ${esito(f)}"
                numeri.any { it !in noti } -> "«$f» usa un numero inventato: $numeri"
                inviaSenzaComponi -> "«$f» invio senza bozza"
                else -> null
            }
        }
        assertTrue(male.joinToString("\n"), male.isEmpty())
    }

    @Test fun `manda senza destinatario chiede a chi`() = assertTrue(p("manda un WhatsApp").dire.startsWith("A chi"))
    @Test fun `chiama senza nome chiede chi`() = assertTrue(p("chiama").dire.startsWith("Chi chiamo"))
    @Test fun `invia senza bozza lo dice`() = assertTrue(p("invia").dire.contains("nessun messaggio"))

    // ------------------------------------------------------------ pezzi

    @Test fun `chiave`() {
        assertEquals("che ora e", CervelloRegole.chiave("Che ora è, adesso?"))
        assertEquals("apri whatsapp", CervelloRegole.chiave("Apri WhatsApp, grazie."))
    }

    @Test fun `fonetica`() {
        assertEquals(CervelloRegole.fonetica("che ore"), CervelloRegole.fonetica("ke ore"))
        assertEquals("kiaro", CervelloRegole.fonetica("chiaro"))
        assertTrue(CervelloRegole.somiglianza(CervelloRegole.fonetica("pior sono"), CervelloRegole.fonetica("che ore sono")) >= 0.6)
    }

    @Test fun `ogni invio vero passa dalla bozza`() {
        for (f in listOf("manda un WhatsApp a Paolo: arrivo", "manda un SMS a Paolo: ok", "scrivi una mail a Giulia: ciao")) {
            val a = p(f).azioni.map { it.action }
            assertEquals(f, listOf("componi", "invia_bozza"), a)
        }
    }
}
