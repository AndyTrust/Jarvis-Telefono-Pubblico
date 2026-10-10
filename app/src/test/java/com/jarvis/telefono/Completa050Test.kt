package com.jarvis.telefono

import com.jarvis.telefono.cassaforte.AccessoSito
import com.jarvis.telefono.cassaforte.AccountMail
import com.jarvis.telefono.cassaforte.AllineaCaselle
import com.jarvis.telefono.cassaforte.CasellaVps
import com.jarvis.telefono.cassaforte.ProtocolloAccount
import com.jarvis.telefono.cassaforte.RispostaAccount
import com.jarvis.telefono.cassaforte.ServerPosta
import com.jarvis.telefono.cassaforte.TipoMail
import com.jarvis.telefono.cassaforte.Voce
import com.jarvis.telefono.mani.CancelloInvio
import com.jarvis.telefono.mani.ComponiIntent
import com.jarvis.telefono.mani.Orologio
import com.jarvis.telefono.mani.ProfiliApp
import com.jarvis.telefono.nucleo.Complessita
import com.jarvis.telefono.vps.Instradamento
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le correzioni e i pezzi nuovi della 0.5.0 (2026-10-08, jboss-completa). */
class Completa050Test {

    // ---------------------------------------------------------------- apri resta sul telefono

    @Test fun apriInstagramELinkedInRestanoSulTelefono() {
        for (f in listOf("apri Instagram", "apri LinkedIn", "apri Facebook", "avvia X", "lancia Instagram")) {
            val e = Instradamento.decidi(f, moduloAcceso = true, rete = true)
            assertTrue("$f: $e", e is Instradamento.Esito.Locale)
        }
        // Un lavoro social vero va ancora alla VPS.
        assertTrue(Instradamento.decidi("scrivi un post su LinkedIn sul volo di domani", moduloAcceso = true, rete = true) is Instradamento.Esito.Vps)
        assertTrue(Instradamento.decidi("sulla VPS apri il terminale", moduloAcceso = true, rete = true) is Instradamento.Esito.Vps)
        assertTrue(Instradamento.decidi("controlla se ho notifiche nuove di Instagram o LinkedIn", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
        assertTrue(Instradamento.decidi("chi mi ha scritto su Instagram", moduloAcceso = true, rete = true) is Instradamento.Esito.Locale)
    }

    @Test fun geminiArrivaAncheDentroGoogle() {
        assertEquals(listOf(ComponiIntent.GOOGLE_APP), PhoneActionExecutor.PRIMO_PIANO_ANCHE[ComponiIntent.GEMINI])
    }

    // ---------------------------------------------------------------- sveglia e timer

    @Test fun numeriInParole() {
        assertEquals(7, Orologio.numero("sette"))
        assertEquals(23, Orologio.numero("ventitre"))
        assertEquals(21, Orologio.numero("ventuno"))
        assertEquals(38, Orologio.numero("trentotto"))
        assertEquals(45, Orologio.numero("quarantacinque"))
        assertEquals(1, Orologio.numero("un"))
        assertNull(Orologio.numero("mattina"))
    }

    @Test fun sveglie() {
        assertEquals(Orologio.Sveglia(6, 45), Orologio.sveglia("metti una sveglia alle 6 e 45"))
        assertEquals(Orologio.Sveglia(6, 45), Orologio.sveglia("metti una sveglia alle 6 45"))
        assertEquals(Orologio.Sveglia(7, 30), Orologio.sveglia("svegliami alle sette e mezza"))
        assertEquals(Orologio.Sveglia(7, 15), Orologio.sveglia("sveglia alle 7 e un quarto"))
        assertEquals(Orologio.Sveglia(6, 45), Orologio.sveglia("sveglia alle 7 meno un quarto"))
        assertEquals(Orologio.Sveglia(18, 30), Orologio.sveglia("imposta una sveglia alle 18 30"))
        assertEquals(Orologio.Sveglia(19, 0), Orologio.sveglia("metti la sveglia alle 7 di sera"))
        assertEquals(Orologio.Sveglia(8, 0), Orologio.sveglia("metti la sveglia domani alle otto"))
        assertEquals(Orologio.Sveglia(1, 0), Orologio.sveglia("sveglia all una"))
        assertNull(Orologio.sveglia("metti una sveglia"))
        assertNull(Orologio.sveglia("metti un timer di 5 minuti"))
        assertNull(Orologio.sveglia("sveglia alle 25"))
    }

    @Test fun timer() {
        assertEquals(300, Orologio.timerSecondi("metti un timer di 5 minuti"))
        assertEquals(300, Orologio.timerSecondi("metti un timer di cinque minuti"))
        assertEquals(90, Orologio.timerSecondi("timer di 90 secondi"))
        assertEquals(5400, Orologio.timerSecondi("metti un timer di un ora e mezza"))
        assertEquals(1800, Orologio.timerSecondi("timer di mezz ora"))
        assertEquals(150, Orologio.timerSecondi("timer di 2 minuti e mezzo"))
        assertEquals(3900, Orologio.timerSecondi("timer di 1 ora e 5 minuti"))
        assertNull(Orologio.timerSecondi("metti un timer"))
        assertEquals("un'ora e 30 minuti", Orologio.durataInParole(5400))
    }

    @Test fun intentSvegliaETimerSenzaSchermata() {
        val s = ComponiIntent.sveglia(6, 45)
        assertEquals(ComponiIntent.SET_ALARM, s.azione)
        assertEquals(6, s.extraInteri[ComponiIntent.EXTRA_HOUR])
        assertEquals(45, s.extraInteri[ComponiIntent.EXTRA_MINUTES])
        assertEquals(true, s.extraSiNo[ComponiIntent.EXTRA_SKIP_UI])
        val t = ComponiIntent.timer(300)
        assertEquals(ComponiIntent.SET_TIMER, t.azione)
        assertEquals(300, t.extraInteri[ComponiIntent.EXTRA_LENGTH])
    }

    @Test fun svegliaConOraNonVaAllaVps() {
        assertTrue(Complessita.complessa("metti una sveglia alle 6 e 45"))
        assertFalse(Complessita.complessaSenzaSveglia("metti una sveglia alle 6 e 45"))
        assertTrue(Complessita.complessaSenzaSveglia("metti una sveglia alle 6 e poi dimmi che tempo fa"))
        assertTrue(Complessita.complessa("cerca su Google chi ha vinto l'ultima partita del Cagliari e dimmelo"))
    }

    // ---------------------------------------------------------------- chiamate con conferma

    @Test fun chiamaSiConfermaAVoce() {
        assertEquals(CancelloInvio.Esito.INVIA, CancelloInvio.interpreta("chiama"))
        assertEquals(CancelloInvio.Esito.INVIA, CancelloInvio.interpreta("sì chiamalo"))
        assertEquals(CancelloInvio.Esito.ANNULLA, CancelloInvio.interpreta("annulla"))
        assertNotNull(ProfiliApp.di("com.samsung.android.dialer"))
    }

    // ---------------------------------------------------------------- caselle del Postino nel telefono

    private fun lista(): RispostaAccount.Lista {
        val o = JSONObject("""{"type":"account_lista","caselle":[
            {"id":"lavoro-booking","indirizzo":"booking@azienda-esempio.example","azienda":"Azienda Esempio","tipo":"","imap":["imap.hostinger.com",993],"smtp":["smtp.hostinger.com",465],"password":true,"da_app":false},
            {"id":"gmail-boss","indirizzo":"gmail-utente@example.com","azienda":"Personale","imap":["imap.gmail.com",993],"smtp":["smtp.gmail.com",465],"password":true,"da_app":false},
            {"id":"pec","indirizzo":"pec-utente@example.org","azienda":"PEC","imap":["imaps.sicurezzapostale.it",993],"smtp":["smtps.sicurezzapostale.it",465],"password":true,"da_app":false},
            {"id":"negozio","indirizzo":"utente@example.com","azienda":"Negozio Esempio","imap":["mail.example.com",993],"password":true,"da_app":false}
        ],"cervello":{}}""")
        return ProtocolloAccount.leggi(o) as RispostaAccount.Lista
    }

    @Test fun laListaLeggeServerEAzienda() {
        val l = lista()
        assertEquals(4, l.caselle.size)
        assertEquals(ServerPosta("imap.hostinger.com", 993), l.caselle[0].imap)
        assertEquals("Azienda Esempio", l.caselle[0].azienda)
    }

    @Test fun leCaselleDellaVpsEntranoSenzaPassword() {
        val samsung = AccountMail("samsung", "Samsung Email", "utente@example.com", tipo = TipoMail.SAMSUNG)
        val nuove = AllineaCaselle.daSalvare(listOf(samsung), lista().caselle)
        assertEquals(4, nuove.size)
        assertTrue(nuove.all { it.password.isEmpty() && it.suVps })
        assertEquals(TipoMail.HOSTINGER, nuove.single { it.indirizzo.startsWith("booking@") }.tipo)
        assertEquals(TipoMail.GMAIL, nuove.single { it.indirizzo == "gmail-utente@example.com" }.tipo)
        assertEquals(TipoMail.PEC, nuove.single { it.indirizzo.endsWith("example.org") }.tipo)
        // Seconda volta: niente di nuovo.
        assertEquals(0, AllineaCaselle.daSalvare(nuove + samsung, lista().caselle).size)
    }

    @Test fun unaCasellaGiaNelTelefonoPrendeSoloIlSegno() {
        val mia = AccountMail("negozio", "Negozio", "utente@example.com", password = "x", tipo = TipoMail.GENERICO)
        val nuove = AllineaCaselle.daSalvare(listOf(mia), lista().caselle.filter { it.id == "negozio" })
        assertEquals(1, nuove.size)
        assertEquals("negozio", nuove[0].id)
        assertTrue(nuove[0].suVps)
        assertEquals("x", nuove[0].password)
    }

    // ---------------------------------------------------------------- accessi siti

    @Test fun accessoSitoSoloEmailEPassword() {
        assertNull(AccessoSito.valida("https://esempio.it/login", "boss@esempio.it", "segreta"))
        assertNotNull(AccessoSito.valida("https://esempio.it", "boss@esempio.it", ""))
        assertNotNull(AccessoSito.valida("https://esempio.it", "boss@esempio.it", "x", "accedi con Google"))
        assertNotNull(AccessoSito.valida("non un sito", "u", "p"))
        assertEquals("sito-esempio.it", AccessoSito.idPer("https://www.esempio.it/login"))
    }

    @Test fun accessoSitoNonMostraLaPasswordNeiLog() {
        val a = AccessoSito("sito-x.it", "x.it", "https://x.it", "boss@x.it", "segretissima", "")
        assertFalse(a.toString().contains("segretissima"))
        val back = Voce.daJson(a.json()) as AccessoSito
        assertEquals(a, back)
    }

    // ---------------------------------------------------------------- YouTube e siti

    @Test fun youtubeESiti() {
        val y = ComponiIntent.youtube("tutorial Kotlin coroutines")
        assertEquals("https://www.youtube.com/results?search_query=tutorial%20Kotlin%20coroutines", y.uri)
        assertEquals(listOf(ComponiIntent.YOUTUBE), y.pacchetti)
        assertTrue(ComponiIntent.sembraSito("ilsole24ore.com"))
        assertTrue(ComponiIntent.sembraSito("https://www.ryanair.com/it"))
        assertFalse(ComponiIntent.sembraSito("WhatsApp"))
        assertFalse(ComponiIntent.sembraSito("la calcolatrice"))
        val r = com.jarvis.telefono.nucleo.CervelloRegole()
        val ctx = com.jarvis.telefono.nucleo.Contesto(contatti = emptyList())
        val p1 = r.interpreta("apri il sito ilsole24ore.com", ctx)
        assertEquals("link", p1.azioni.single()["tipo"])
        val p2 = r.interpreta("cerca su YouTube tutorial Kotlin coroutines", ctx)
        assertEquals("youtube", p2.azioni.single()["tipo"])
        assertEquals("apri_app", r.interpreta("apri WhatsApp", ctx).azioni.single().action)
    }

    @Test fun paginaGoogleInCaricamento() {
        assertTrue(com.jarvis.telefono.mani.ParoleInvio.paginaInCaricamento(listOf("Cagliari CA", "Elaborazione in corso…")))
        assertFalse(com.jarvis.telefono.mani.ParoleInvio.paginaInCaricamento(listOf("Overview dell'AI", "Domani a Cagliari")))
    }
}
