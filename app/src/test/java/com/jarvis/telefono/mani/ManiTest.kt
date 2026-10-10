package com.jarvis.telefono.mani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

/** Le mani 1.2.0: composizione degli intent, cancello d'invio, catalogo app, bersagli. */
class ComponiIntentTest {

    @Test
    fun `numero italiano diventa internazionale per WhatsApp`() {
        assertEquals("393331234567", ComponiIntent.numeroInternazionale("333 123 4567"))
        assertEquals("393331234567", ComponiIntent.numeroInternazionale("+39 333-123-4567"))
        assertEquals("393331234567", ComponiIntent.numeroInternazionale("0039 3331234567"))
        assertEquals("393331234567", ComponiIntent.numeroInternazionale("393331234567"))
        assertEquals("41791234567", ComponiIntent.numeroInternazionale("+41 79 123 45 67"))
        assertNull(ComponiIntent.numeroInternazionale("12"))
    }

    @Test
    fun `whatsapp a me stesso con il numero della configurazione`() {
        // Numero di esempio neutro, come lo passa il cervello: «+» e cifre.
        val p = ComponiIntent.whatsapp("+390000000001", "prova bozza")
        assertEquals(ComponiIntent.VIEW, p.azione)
        assertEquals("https://wa.me/390000000001?text=prova%20bozza", p.uri)
        assertEquals("com.whatsapp", p.pacchetti.first())
        assertEquals("https://wa.me/393330000001?text=prova%20Jarvis%20Telefono", ComponiIntent.whatsapp("393330000001", "prova Jarvis Telefono").uri)
        assertEquals("https://wa.me/393330000001?text=%C3%A8%20l%27ora%3A%2018%2C30", ComponiIntent.whatsapp("3330000001", "è l'ora: 18,30").uri)
    }

    @Test
    fun `whatsapp apre wa_me con il testo codificato e preferisce l'app normale`() {
        val p = ComponiIntent.whatsapp("3331234567", "Ci vediamo alle 8 & poi?")
        assertEquals(ComponiIntent.VIEW, p.azione)
        assertEquals("https://wa.me/393331234567?text=Ci%20vediamo%20alle%208%20%26%20poi%3F", p.uri)
        assertEquals(listOf("com.whatsapp", "com.whatsapp.w4b"), p.pacchetti)
        assertFalse("la descrizione non porta il testo", p.descrizione.contains("vediamo"))
        assertEquals("com.whatsapp.w4b", ComponiIntent.whatsapp("3331234567", "x", business = true).pacchetti.first())
    }

    @Test
    fun `mail con destinatario oggetto e corpo, negli extra e nel mailto`() {
        val p = ComponiIntent.mail(listOf("prova@esempio.it"), "Cena", "Ciao,\nalle 20?")
        assertEquals(ComponiIntent.SENDTO, p.azione)
        assertEquals("mailto:prova@esempio.it?subject=Cena&body=Ciao%2C%0Aalle%2020%3F", p.uri)
        assertEquals(listOf("prova@esempio.it"), p.extraListe[ComponiIntent.EXTRA_EMAIL])
        assertEquals("Cena", p.extraTesti[ComponiIntent.EXTRA_SUBJECT])
        assertEquals("Ciao,\nalle 20?", p.extraTesti[ComponiIntent.EXTRA_TEXT])
        assertTrue("senza pacchetto = app predefinita", p.pacchetti.isEmpty())
        assertEquals(listOf(ComponiIntent.GMAIL), ComponiIntent.mail(listOf("a@b.it"), "", "", pacchetto = ComponiIntent.GMAIL).pacchetti)
    }

    @Test
    fun `samsung email riceve il corpo una volta sola`() {
        val p = ComponiIntent.mail(listOf("boss@example.com"), "Prova", "prova bozza", pacchetto = ComponiIntent.SAMSUNG_EMAIL)
        assertEquals(listOf(ComponiIntent.SAMSUNG_EMAIL), p.pacchetti)
        assertEquals("mailto:boss@example.com?subject=Prova&body=prova%20bozza", p.uri)
        assertNull(p.extraTesti[ComponiIntent.EXTRA_TEXT])
        assertEquals("Prova", p.extraTesti[ComponiIntent.EXTRA_SUBJECT])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mail senza chiocciola rifiutata`() {
        ComponiIntent.mail(listOf("marco"), "x", "y")
    }

    @Test
    fun `sms telefono link mappe google`() {
        assertEquals("smsto:+390000000001", ComponiIntent.sms("+39 000 000 0001", "ciao").uri)
        assertEquals("ciao", ComponiIntent.sms("3331234567", "ciao").extraTesti["sms_body"])
        assertEquals("tel:%2B390000000001", ComponiIntent.chiama("+39 000 000 0001").uri)
        assertEquals(ComponiIntent.DIAL, ComponiIntent.chiama("3331234567").azione)
        assertEquals("https://example.com", ComponiIntent.link("example.com").uri)
        assertEquals("geo:0,0?q=Piazza%20Navona%2C%20Roma", ComponiIntent.mappe("Piazza Navona, Roma").uri)
        assertEquals("google.navigation:q=Olbia", ComponiIntent.mappe("Olbia", naviga = true).uri)
        val g = ComponiIntent.cercaGoogle("meteo Olbia domani")
        assertEquals("https://www.google.com/search?q=meteo%20Olbia%20domani", g.uri)
        assertEquals(ComponiIntent.GOOGLE_APP, g.pacchetti.first())
        assertEquals(ComponiIntent.CHROME, ComponiIntent.cercaGoogle("x", nelBrowser = true).pacchetti.first())
    }

    @Test
    fun `telegram con utente apre la chat, senza apre la condivisione`() {
        assertEquals("https://t.me/marco", ComponiIntent.telegram("@marco", "ciao").uri)
        val s = ComponiIntent.telegram(null, "ciao")
        assertEquals(ComponiIntent.SEND, s.azione)
        assertEquals("ciao", s.extraTesti[ComponiIntent.EXTRA_TEXT])
    }

    @Test
    fun `calendario legge le date e mette un'ora di durata`() {
        val roma = ZoneId.of("Europe/Rome")
        val p = ComponiIntent.calendario("Cena", "2026-10-08 20:00", zona = roma)
        val inizio = p.extraNumeri.getValue("beginTime")
        assertEquals(1791482400000L, inizio) // 2026-10-08 18:00 UTC
        assertEquals(inizio + 3_600_000L, p.extraNumeri["endTime"])
        val g = ComponiIntent.calendario("Ferie", "2026-10-08", zona = roma)
        assertEquals(1L, g.extraNumeri["allDay"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `data illeggibile rifiutata`() {
        ComponiIntent.calendario("x", "domani sera")
    }
}

class CancelloInvioTest {

    private var ora = 1_000_000L
    private val cancello = CancelloInvio(orologio = { ora }, durataPermessoMs = 60_000L)

    private fun richiesta(id: String) =
        CancelloInvio.Richiesta(id, "WhatsApp", "Marco", "ciao", "com.whatsapp", toccaDaSolo = true, creataMs = ora)

    @Test
    fun `le parole di Boss`() {
        val si = listOf("invia", "Invia!", "sì invia", "ok", "Jarvis, invia", "hey jarvis manda", "va bene", "procedi", "confermo")
        val no = listOf("no", "annulla", "non inviare", "no grazie", "lascia stare", "ferma", "aspetta")
        val altro = listOf("sì ma cambia l'ora in nove", "scrivi invece che arrivo tardi", "", "non lo so", "va")
        si.forEach { assertEquals(it, CancelloInvio.Esito.INVIA, CancelloInvio.interpreta(it)) }
        no.forEach { assertEquals(it, CancelloInvio.Esito.ANNULLA, CancelloInvio.interpreta(it)) }
        altro.forEach { assertEquals(it, CancelloInvio.Esito.ALTRO, CancelloInvio.interpreta(it)) }
    }

    @Test
    fun `una sola richiesta sospesa, la nuova restituisce la vecchia`() {
        assertNull(cancello.proponi(richiesta("a")))
        assertEquals("a", cancello.proponi(richiesta("b"))?.id)
        assertNull("id sbagliato non chiude", cancello.chiudi("a"))
        assertEquals("b", cancello.chiudi("b")?.id)
        assertNull(cancello.sospesa())
    }

    @Test
    fun `il permesso vale una volta e scade`() {
        assertFalse(cancello.consumaPermesso())
        cancello.concediPermesso()
        assertTrue(cancello.consumaPermesso())
        assertFalse("una volta sola", cancello.consumaPermesso())
        cancello.concediPermesso()
        ora += 61_000L
        assertFalse("scaduto", cancello.consumaPermesso())
    }

    @Test
    fun `l'interruttore azzera tutto`() {
        cancello.proponi(richiesta("a"))
        cancello.concediPermesso()
        assertEquals("a", cancello.azzera()?.id)
        assertFalse(cancello.permessoValido())
        assertNull(cancello.sospesa())
    }

    @Test
    fun `annulla toglie la bozza e rifiuta le azioni di quella richiesta`() {
        // 1.2.4: «Annulla» fa quello che faceva «Ferma» (Boss 07/10).
        cancello.proponi(richiesta("a"))
        cancello.concediPermesso()
        assertFalse(cancello.richiestaBloccata())
        assertEquals("la bozza torna indietro per la risposta", "a", cancello.annullaRichiesta(120_000L)?.id)
        assertNull(cancello.sospesa())
        assertFalse("niente permessi rimasti", cancello.permessoValido())
        assertTrue("le azioni di questa richiesta si rifiutano", cancello.richiestaBloccata())
        ora += 60_000L
        assertTrue(cancello.richiestaBloccata())
        cancello.nuovaRichiesta()
        assertFalse("la richiesta dopo riparte subito", cancello.richiestaBloccata())
    }

    @Test
    fun `il blocco dopo annulla scade da solo e vale anche senza bozza`() {
        assertNull("niente bozza: niente da rispondere", cancello.annullaRichiesta(120_000L))
        assertTrue(cancello.richiestaBloccata())
        ora += 120_000L
        assertFalse("dopo 2 minuti Jarvis riparte anche senza una richiesta nuova", cancello.richiestaBloccata())
    }

    @Test
    fun `la conferma ha solo Invia e Annulla, niente Ferma`() {
        assertEquals(listOf("Annulla", "Invia"), CancelloInvio.SCELTE)
        // Il pannello e la notifica sono codice Android: si guarda il sorgente.
        val radice = listOf(File("src/main/java/com/jarvis/telefono"), File("app/src/main/java/com/jarvis/telefono")).first { it.isDirectory }
        val pannello = File(radice, "mani/PannelloConferma.kt").readText()
        val esecutore = File(radice, "PhoneActionExecutor.kt").readText()
        val ricevitore = File(radice, "ConfirmActionReceiver.kt").readText()
        assertFalse("pulsante Ferma nel pannello", pannello.contains("pulsante(\"Ferma\""))
        assertFalse("onFerma nel pannello", pannello.contains("onFerma"))
        assertEquals("due pulsanti nel pannello", 2, Regex("riga\\.addView\\(pulsante\\(").findAll(pannello).count())
        val azioni = Regex("\\.addAction\\(0, \"([^\"]+)\"").findAll(esecutore).map { it.groupValues[1] }.toList()
        assertEquals("azioni della notifica", listOf("Invia", "Annulla"), azioni)
        assertFalse("azione Ferma nella notifica", esecutore.contains("ACTION_FERMA"))
        assertFalse("ramo Ferma nel ricevitore", ricevitore.contains("ACTION_FERMA"))
    }

    @Test
    fun `riconosce i pulsanti d'invio e non quelli simili`() {
        assertTrue(ParoleInvio.eInvio(NodoInfo(id = "com.whatsapp:id/send")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(descrizione = "Invia")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(testo = "Send message")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(testo = "Invia SMS")))
        assertFalse(ParoleInvio.eInvio(NodoInfo(testo = "Inviato")))
        assertFalse(ParoleInvio.eInvio(NodoInfo(testo = "Posta in arrivo")))
        assertFalse(ParoleInvio.eInvio(NodoInfo(descrizione = "Allega")))
        assertFalse(ParoleInvio.eInvio(NodoInfo(id = "com.whatsapp:id/entry", testo = "invia la foto domani a Marco e poi chiamami")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(testo = "Posta")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(testo = "Pubblica")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(descrizione = "Conferma pagamento")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(testo = "Paga")))
        assertTrue(ParoleInvio.eInvio(NodoInfo(id = "com.twitter.android:id/tweet_button")))
        assertFalse(ParoleInvio.eInvio(NodoInfo(testo = "Pagamenti")))
        assertFalse(ParoleInvio.eInvio(NodoInfo(testo = "Posta in arrivo")))
        assertFalse(ParoleInvio.eInvio(NodoInfo(testo = "Post recenti")))
    }

    @Test
    fun `trova il pulsante d'invio prima per id poi per descrizione`() {
        val nodi = listOf(
            NodoInfo(testo = "Invia"),
            NodoInfo(descrizione = "Invia", id = "x:id/altro"),
            NodoInfo(id = "com.google.android.gm:id/send"),
        )
        assertEquals(2, ParoleInvio.trovaPulsante(nodi))
        assertEquals(1, ParoleInvio.trovaPulsante(nodi.take(2)))
        assertEquals(-1, ParoleInvio.trovaPulsante(listOf(NodoInfo(testo = "Allega"))))
        assertEquals(-1, ParoleInvio.trovaPulsante(listOf(NodoInfo(id = "a:id/send", abilitato = false))))
    }
}

class CatalogoAppTest {
    private val app = listOf(
        CatalogoApp.App("WhatsApp", "com.whatsapp"),
        CatalogoApp.App("WhatsApp Business", "com.whatsapp.w4b"),
        CatalogoApp.App("Gmail", "com.google.android.gm"),
        CatalogoApp.App("Email", "com.samsung.android.email.provider"),
        CatalogoApp.App("YouTube Music", "com.google.android.apps.youtube.music"),
        CatalogoApp.App("YouTube", "com.google.android.youtube"),
        CatalogoApp.App("Intesa Sanpaolo Mobile", "com.latuabancaperandroid"),
        CatalogoApp.App("Fineco", "com.fineco.it"),
        CatalogoApp.App("Booking.com", "com.booking"),
        CatalogoApp.App("Ryanair", "com.ryanair.cheapflights"),
    )

    @Test
    fun `nomi noti vanno al pacchetto giusto`() {
        assertEquals("com.whatsapp", CatalogoApp.cerca("whatsapp", app).first().pacchetto)
        assertEquals("com.whatsapp", CatalogoApp.cerca("WhatsApp", app).first().pacchetto)
        // Senza predefinita nessuna marca ha la precedenza: Gmail prima di Samsung Email (CatalogoApp.POSTA).
        assertEquals("com.google.android.gm", CatalogoApp.cerca("posta", app).first().pacchetto)
        assertEquals("com.samsung.android.email.provider",
            CatalogoApp.cerca("posta", app, postaPredefinita = "com.samsung.android.email.provider").first().pacchetto)
        assertEquals("com.google.android.youtube", CatalogoApp.cerca("youtube", app).first().pacchetto)
    }

    @Test
    fun `samsung email con l'etichetta E-mail di One UI si apre con tutti i nomi se e la predefinita`() {
        val s24 = app.map { if (it.pacchetto == "com.samsung.android.email.provider") it.copy(nome = "E-mail") else it }
        val pred = "com.samsung.android.email.provider"
        for (n in listOf("posta", "mail", "email", "e-mail", "E-mail")) {
            assertEquals(n, pred, CatalogoApp.cerca(n, s24, postaPredefinita = pred).first().pacchetto)
        }
        for (n in listOf("samsung email", "Samsung Email", "samsung mail")) {
            assertEquals(n, pred, CatalogoApp.cerca(n, s24).first().pacchetto)
        }
        assertEquals("com.google.android.gm", CatalogoApp.cerca("gmail", s24).first().pacchetto)
    }

    @Test
    fun `nomi detti male o parziali si trovano lo stesso`() {
        assertEquals("com.booking", CatalogoApp.cerca("booking", app).first().pacchetto)
        assertEquals("com.ryanair.cheapflights", CatalogoApp.cerca("rayanair", app).first().pacchetto)
        assertEquals("com.latuabancaperandroid", CatalogoApp.cerca("intesa", app).first().pacchetto)
        assertTrue(CatalogoApp.cerca("zzzz", app).isEmpty())
    }

    @Test
    fun `banca trova le app delle banche, la più usata prima`() {
        val r = CatalogoApp.cerca("banca", app, mapOf("com.fineco.it" to 10L, "com.latuabancaperandroid" to 5L))
        assertEquals(listOf("com.fineco.it", "com.latuabancaperandroid"), r.map { it.pacchetto })
    }

    @Test
    fun `l'elenco mette prima le app usate di recente`() {
        val r = CatalogoApp.ordinaPerUso(app, mapOf("com.booking" to 99L))
        assertEquals("com.booking", r.first().pacchetto)
    }

    @Test
    fun `ogni profilo ha almeno una chiave e i pacchetti sono unici`() {
        assertTrue(ProfiliApp.TUTTI.all { it.ricerca.isNotEmpty() || it.campo.isNotEmpty() || it.invio.isNotEmpty() })
        assertEquals(ProfiliApp.TUTTI.size, ProfiliApp.TUTTI.map { it.pacchetto }.toSet().size)
        assertEquals("WhatsApp", ProfiliApp.di("com.whatsapp")?.nome)
    }
}

class BersaglioTest {
    @Test
    fun `chiave per id e per testo`() {
        val nodi = listOf(
            NodoInfo(testo = "Chat"),
            NodoInfo(id = "com.whatsapp:id/entry", testo = "Messaggio"),
            NodoInfo(descrizione = "Cerca"),
        )
        assertEquals(1, Bersaglio.trova(listOf(":id/entry"), nodi))
        assertEquals(2, Bersaglio.trova(listOf(":id/nonc'è", "cerca"), nodi))
        assertEquals(-1, Bersaglio.trova(listOf("impostazioni"), nodi))
    }

    @Test
    fun `rubrica per nome e cognome`() {
        val c = listOf(
            CercaContatti.Contatto("Marco Esempio", listOf("333"), emptyList()),
            CercaContatti.Contatto("Marcello Bianchi", listOf("334"), emptyList()),
            CercaContatti.Contatto("Àlvaro Marco", listOf("335"), emptyList()),
        )
        assertEquals("Marco Esempio", CercaContatti.cerca("marco esempio", c).first().nome)
        assertEquals(2, CercaContatti.cerca("marco", c).size)
        assertEquals("Àlvaro Marco", CercaContatti.cerca("alvaro", c).first().nome)
    }

    @Test
    fun `il registro non supera il massimo`() {
        val f = File(Files.createTempDirectory("reg").toFile(), "r.log")
        val r = RegistroAzioni(f, max = 10)
        repeat(80) { r.scrivi("app", "tap", "n$it", "ok") }
        assertTrue(f.readLines().size <= 60)
        assertTrue(r.ultime(3).last().contains("n79"))
    }
}
