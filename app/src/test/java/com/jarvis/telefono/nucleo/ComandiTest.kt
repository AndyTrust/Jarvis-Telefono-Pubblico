package com.jarvis.telefono.nucleo

import com.jarvis.telefono.mani.NodoInfo
import com.jarvis.telefono.nucleo.RegistroComandi.Stato
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JBoss 0.7.0: la chat che esegue. Un messaggio scritto diventa un comando con il suo stato (in corso → fatto /
 * errore / alla VPS), una conferma lo mette «da confermare» con il box, un acquisto paga solo dopo il riepilogo.
 */
class ComandiTest {

    private var ora = 1_000_000L
    private fun registro() = RegistroComandi(orologio = { ora }, scadenzaMs = 60_000L)

    @Test
    fun messaggio_scritto_in_corso_poi_fatto() {
        val r = registro()
        val viste = mutableListOf<RegistroComandi.Istantanea>()
        r.osserva { viste += it }
        val id = r.inizia("  che ore sono  ", "scritto")
        val c = r.comando(id)!!
        assertEquals("che ore sono", c.testo)
        assertEquals("scritto", c.origine)
        assertEquals(Stato.IN_CORSO, c.stato)
        assertEquals(1, r.attivi())
        assertEquals("In corso: «che ore sono»", RegistroComandi.riga(r.istantanea(), ora))

        r.lavoro(id, "Apro l'orologio")
        ora += 4_000L
        assertEquals("In corso: Apro l'orologio · 4 s", RegistroComandi.riga(r.istantanea(), ora))

        r.chiudi(id, "ok", errore = false, dire = "Sono le 19 e 27.")
        assertEquals(Stato.FATTO, r.comando(id)!!.stato)
        assertEquals(0, r.attivi())
        assertEquals("Fatto: «che ore sono»", RegistroComandi.riga(r.istantanea(), ora))
        assertEquals(listOf(Stato.IN_CORSO, Stato.IN_CORSO, Stato.FATTO), viste.map { it.comando!!.stato })
    }

    @Test
    fun esiti_del_nucleo_diventano_stati() {
        assertEquals(Stato.FATTO, RegistroComandi.statoDaEsito("ok", false))
        assertEquals(Stato.ERRORE, RegistroComandi.statoDaEsito("ok", true))
        assertEquals(Stato.ERRORE, RegistroComandi.statoDaEsito("non capito", true))
        assertEquals(Stato.ERRORE, RegistroComandi.statoDaEsito("errore", true))
        assertEquals(Stato.ANNULLATO, RegistroComandi.statoDaEsito("annullato", false))
        assertEquals(Stato.ALLA_VPS, RegistroComandi.statoDaEsito("alla vps", false))
        assertEquals(Stato.ALLA_VPS, RegistroComandi.statoDaEsito("in coda", false))
        assertEquals(Stato.ERRORE, RegistroComandi.statoDaEsito("alla vps", true))
    }

    @Test
    fun errore_mostra_il_motivo_e_chiudere_due_volte_non_cambia_niente() {
        val r = registro()
        val id = r.inizia("fai una cosa strana", "scritto")
        r.chiudi(id, "non capito", errore = true, dire = "Non ho capito.")
        r.chiudi(id, "ok", errore = false, dire = "Fatto.")
        assertEquals(Stato.ERRORE, r.comando(id)!!.stato)
        assertEquals("Errore: Non ho capito.", RegistroComandi.riga(r.istantanea(), ora))
    }

    @Test
    fun conferma_mette_da_confermare_e_dopo_la_scelta_torna_in_corso() {
        val r = registro()
        val id = r.inizia("scrivi a Marco arrivo alle 8", "scritto")
        val k = RegistroComandi.Conferma("bozza:loc-1", RegistroComandi.TipoConferma.BOZZA, "Invio su WhatsApp a Marco", "Arrivo alle 8", "Invia")
        r.chiediConferma(k)
        assertEquals(Stato.DA_CONFERMARE, r.comando(id)!!.stato)
        assertEquals(k, r.confermaInAttesa())
        assertEquals("Da confermare: Invio su WhatsApp a Marco", RegistroComandi.riga(r.istantanea(), ora))
        assertEquals(1, r.attivi())

        // Una conferma che aspetta non scade mai da sola qui (la scadenza la decide il cancello: 2 minuti).
        ora += 10 * 60_000L
        assertEquals(Stato.DA_CONFERMARE, r.comando(id)!!.stato)

        r.confermaChiusa("altra")
        assertNotNull(r.confermaInAttesa())
        r.confermaChiusa("bozza:loc-1")
        assertNull(r.confermaInAttesa())
        assertEquals(Stato.IN_CORSO, r.comando(id)!!.stato)
        r.chiudi(id, "ok", false, "Mandato.")
        assertEquals(0, r.attivi())
    }

    @Test
    fun conferma_della_vps_senza_comando_tiene_vivo_il_servizio() {
        val r = registro()
        r.chiediConferma(RegistroComandi.Conferma("vps:L1:A1", RegistroComandi.TipoConferma.VPS, "Mail a Rossi", "", lavoroId = "L1", azioneId = "A1"))
        assertEquals(1, r.attivi())
        r.confermaChiusa("vps:L1:A1")
        assertEquals(0, r.attivi())
    }

    @Test
    fun comando_senza_risposta_scade() {
        val r = registro()
        val id = r.inizia("cerca il meteo", "scritto")
        ora += 61_000L
        assertEquals(0, r.attivi())
        assertEquals(Stato.ERRORE, r.comando(id)!!.stato)
    }

    // ---------------------------------------------------------------- regole di conferma

    @Test
    fun azioni_irreversibili_chiedono_conferma() {
        for (a in listOf("invia_bozza", "request_send_confirmation", "invio_tastiera", "INVIA_BOZZA")) {
            assertEquals(a, RegoleConferma.Livello.CONFERMA, RegoleConferma.perAzione(a))
        }
        for (a in listOf("open_app", "componi", "leggi_schermo", "cerca_google", "scrivi", "tocca", "scorri")) {
            assertEquals(a, RegoleConferma.Livello.LIBERA, RegoleConferma.perAzione(a))
        }
    }

    @Test
    fun acquisti_e_ordini_a_domicilio_sono_riconosciuti() {
        for (f in listOf(
            "comprami le batterie su Amazon", "acquista il biglietto", "ordinami una pizza a domicilio",
            "ordina il sushi su Deliveroo", "fai un ordine su Glovo", "paga la bolletta", "aggiungi al carrello le cuffie",
        )) assertEquals(f, RegoleConferma.Livello.ACQUISTO, RegoleConferma.perFrase(f))
        for (f in listOf(
            "apri Glovo", "ordina le mail per data", "che ore sono", "scrivi a Marco che arrivo",
            "controlla la busta paga di settembre", "cerca il meteo di domani",
        )) assertEquals(f, RegoleConferma.Livello.LIBERA, RegoleConferma.perFrase(f))
    }

    @Test
    fun pulsanti_di_pagamento_non_si_premono() {
        assertTrue(RegoleConferma.pulsanteDiAcquisto(NodoInfo(testo = "Conferma ordine")))
        assertTrue(RegoleConferma.pulsanteDiAcquisto(NodoInfo(descrizione = "Paga ora")))
        assertTrue(RegoleConferma.pulsanteDiAcquisto(NodoInfo(id = "com.shop:id/place_order_button")))
        assertTrue(RegoleConferma.pulsanteDiAcquisto(NodoInfo(testo = "Ordina ora")))
        // Carrello e cassa restano liberi: JBoss fa tutto il percorso, il sì serve solo sul pulsante finale.
        assertFalse(RegoleConferma.pulsanteDiAcquisto(NodoInfo(testo = "Aggiungi al carrello")))
        assertFalse(RegoleConferma.pulsanteDiAcquisto(NodoInfo(testo = "Vai alla cassa")))
        assertFalse(RegoleConferma.pulsanteDiAcquisto(NodoInfo(testo = "Invia")))
        assertFalse(RegoleConferma.pulsanteDiAcquisto(NodoInfo(id = "com.whatsapp:id/send")))
        assertFalse(RegoleConferma.pulsanteDiAcquisto(NodoInfo(testo = "Ordine del giorno")))
    }

    @Test
    fun riepilogo_acquisto_dallo_schermo() {
        val schermo = listOf(
            "Il tuo ordine", "Pizzeria Da Mario", "2 x Margherita", "€ 14,00", "1 x Coca-Cola", "€ 3,00",
            "Subtotale", "€ 17,00", "Consegna", "€ 2,50", "Totale", "€ 19,50",
            "Indirizzo di consegna", "Via Roma 10, Cagliari", "Pagamento", "Visa •••• 4242", "Ordina ora", "Menu", "Aiuto",
        )
        val r = RegoleConferma.riepilogoAcquisto(schermo, "Pizza da Mario")
        assertTrue(r, r.startsWith("Pizza da Mario\n\nTOTALE: Totale € 19,50"))
        for (pezzo in listOf("2 x Margherita", "€ 14,00", "Consegna", "Via Roma 10, Cagliari", "Visa •••• 4242")) {
            assertTrue(pezzo, r.contains(pezzo))
        }
        assertFalse(r.contains("Aiuto"))
        assertFalse(r.contains("Menu"))
    }

    @Test
    fun riepilogo_senza_dettagli_lo_dice() {
        val r = RegoleConferma.riepilogoAcquisto(listOf("Benvenuto", "Accedi"))
        assertTrue(r, r.contains("TOTALE: non leggibile"))
        assertTrue(r, r.contains("controlla l'app prima di pagare"))
    }

    // 09/10: «Fatto: «ciao, sei online?»» scritto a JBoss compariva anche nelle chat di Ricercatore e Social, e
    // riaprendo la chat di JBoss restava accanto a «Ancora niente con JBoss.».
    @Test
    fun lo_stato_di_un_comando_resta_nella_chat_che_lo_ha_mandato() {
        val r = registro()
        val apertaJboss = ora
        val id = r.inizia("ciao, sei online?", "scritto")
        r.chiudi(id, "ok", false, "Sì")
        fun riga(agente: String, da: Long) = RegistroComandi.riga(r.istantanea { RegistroComandi.diChat(it, agente, da) }, ora)
        assertEquals("Fatto: «ciao, sei online?»", riga(FiltroCronologia.JARVIS, apertaJboss))
        assertNull(riga("ricercatore", apertaJboss))
        assertNull(riga("social", apertaJboss))
        // Una frase scritta nella chat del Ricercatore resta sua, e non va in quella di JBoss.
        ora += 1_000
        r.inizia("cerca il meteo", FiltroCronologia.origineChat("ricercatore"))
        assertEquals("In corso: «cerca il meteo»", riga("ricercatore", apertaJboss))
        assertEquals("Fatto: «ciao, sei online?»", riga(FiltroCronologia.JARVIS, apertaJboss))
        assertNull(riga("social", apertaJboss))
    }

    @Test
    fun riaprendo_la_chat_lo_stato_di_prima_non_si_vede_senza_il_suo_messaggio() {
        val r = registro()
        val id = r.inizia("ciao, sei online?", "scritto")
        r.chiudi(id, "ok", false, "Sì")
        ora += 5_000
        val riaperta = ora
        assertNull(RegistroComandi.riga(r.istantanea { RegistroComandi.diChat(it, FiltroCronologia.JARVIS, riaperta) }, ora))
        ora += 1_000
        r.inizia("e adesso?", "voce")
        assertEquals("In corso: «e adesso?»", RegistroComandi.riga(r.istantanea { RegistroComandi.diChat(it, FiltroCronologia.JARVIS, riaperta) }, ora))
    }

    @Test
    fun la_conferma_in_attesa_resta_unica_anche_col_filtro() {
        val r = registro()
        r.inizia("manda la mail", "scritto")
        r.chiediConferma(RegistroComandi.Conferma("bozza:1", RegistroComandi.TipoConferma.BOZZA, "Mail a Marco", ""))
        assertNotNull(r.istantanea { RegistroComandi.diChat(it, "social", 0L) }.conferma)
    }
}
