package com.jarvis.telefono.nucleo

import com.jarvis.telefono.mani.CercaContatti.Contatto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Il cervello a regole sulle frasi di tutti i giorni, come le scrive Whisper o il dettato di Google
 * (minuscole, senza due punti, parole spezzate). Rubrica e configurazione sono finte: nessun dato vero.
 */
class CervelloRegoleTest {

    private val rubrica = listOf(
        Contatto("Marco Esempio", listOf("+390000000001"), listOf("marco.rossi@example.com")),
        Contatto("Marco Bianchi", listOf("+390000000002"), emptyList()),
        Contatto("Giulia Verdi", listOf("335 111 2233", "070 123456"), listOf("giulia@example.com")),
        Contatto("Mamma", listOf("3409998887"), emptyList()),
        Contatto("Luca Neri", listOf("3331111111", "3332222222"), emptyList()),
        Contatto("Paolo", listOf("3471234000"), emptyList()),
    )
    // Numero di esempio neutro (non è di nessuno): il numero vero sta solo nella configurazione fuori da git.
    private val config = ConfigPersonale(appMail = "", accountMail = "boss@example.com", chatSeStesso = "PROVA BOSS (Tu)", whatsappMe = "390000000001")
    private val ctx = Contesto(rubrica, config, ora = 18, minuti = 25)
    private val cervello = CervelloRegole()

    private fun p(frase: String, c: Contesto = ctx) = cervello.interpreta(frase, c)
    private fun azioni(frase: String) = p(frase).azioni.map { it.action }

    // ------------------------------------------------------------ apri

    @Test fun `apri Spotify`() {
        val pi = p("apri Spotify")
        assertEquals(listOf("apri_app"), pi.azioni.map { it.action })
        assertEquals("Spotify", pi.azioni[0]["nome"])
        assertEquals("Ho aperto Spotify.", pi.dire)
    }

    @Test fun `apri con Jarvis davanti e punto in fondo`() = assertEquals("spotify", p("Jarvis, apri spotify.").azioni[0]["nome"])
    @Test fun `hey jarvis aprimi whatsapp`() = assertEquals("WhatsApp", p("Hey Jarvis aprimi whats app").azioni[0]["nome"])
    @Test fun `puoi aprire le impostazioni per favore`() = assertEquals("impostazioni", p("puoi aprire le impostazioni per favore").azioni[0]["nome"])
    @Test fun `avvia l'app di YouTube`() = assertEquals("YouTube", p("avvia l'app YouTube").azioni[0]["nome"])
    @Test fun `mi apri Gmail`() = assertEquals("Gmail", p("mi apri Gmail?").azioni[0]["nome"])
    @Test fun `lancia la fotocamera`() = assertEquals("fotocamera", p("lancia la fotocamera").azioni[0]["nome"])

    // ------------------------------------------------------------ ricerche

    @Test fun `cerca su google`() {
        val pi = p("cerca su Google meteo Olbia")
        assertEquals(listOf("cerca_google"), pi.azioni.map { it.action })
        assertEquals("meteo Olbia", pi.azioni[0]["domanda"])
    }

    @Test fun `cerca X su google`() = assertEquals("orari farmacia", p("cerca orari farmacia su google").azioni[0]["domanda"])
    @Test fun `cerca senza dire google`() = assertEquals("ristorante sushi Cagliari", p("cerca ristorante sushi Cagliari").azioni[0]["domanda"])

    @Test fun `cerca su youtube`() {
        // 0.5.0: la pagina dei risultati di YouTube si apre con un intent (prima: cerca_in_app, KO del 08/10).
        val pi = p("cerca Vasco Rossi su YouTube")
        assertEquals("componi", pi.azioni[0].action)
        assertEquals("youtube", pi.azioni[0]["tipo"])
        assertEquals("Vasco Rossi", pi.azioni[0]["domanda"])
    }

    @Test fun `you tube spezzato`() = assertEquals("YouTube", p("cerca la ricetta della carbonara su you tube").azioni[0]["tipo"].toString().let { if (it == "youtube") "YouTube" else it })
    @Test fun `cerca su youtube prima del testo`() = assertEquals("musica rilassante", p("cerca su youtube musica rilassante").azioni[0]["domanda"])
    @Test fun `metti su spotify`() = assertEquals(listOf("cerca_in_app"), azioni("metti Ligabue su Spotify"))
    @Test fun `chiedi a gemini`() = assertEquals("quanto è alto il Monte Bianco", p("chiedi a Gemini quanto è alto il Monte Bianco").azioni[0]["domanda"])

    // ------------------------------------------------------------ WhatsApp

    @Test fun `manda un whatsapp a Marco Esempio con due punti`() {
        val pi = p("manda un WhatsApp a Marco Esempio: arrivo tra dieci minuti")
        assertEquals(listOf("componi", "invia_bozza"), pi.azioni.map { it.action })
        assertEquals("whatsapp", pi.azioni[0]["tipo"])
        assertEquals("+390000000001", pi.azioni[0]["numero"])
        assertEquals("Arrivo tra dieci minuti", pi.azioni[0]["testo"])
        assertEquals("Marco Esempio", pi.azioni[1]["destinatario"])
        assertEquals("Inviato a Marco Esempio.", pi.dire)
    }

    @Test fun `whatsapp senza due punti trova il nome in rubrica`() {
        val pi = p("manda un whatsapp a mamma arrivo per cena")
        assertEquals("3409998887", pi.azioni[0]["numero"])
        assertEquals("Arrivo per cena", pi.azioni[0]["testo"])
    }

    @Test fun `scrivi a X su whatsapp che`() {
        val pi = p("scrivi a Paolo su WhatsApp che domani non ci sono")
        assertEquals("3471234000", pi.azioni[0]["numero"])
        assertEquals("Domani non ci sono", pi.azioni[0]["testo"])
    }

    @Test fun `messaggio senza app vale whatsapp`() {
        val pi = p("manda un messaggio a Paolo dicendo ci vediamo alle otto")
        assertEquals("whatsapp", pi.azioni[0]["tipo"])
        assertEquals("Ci vediamo alle otto", pi.azioni[0]["testo"])
    }

    @Test fun `whatsapp storpiato da whisper`() = assertEquals("whatsapp", p("invia un wazzap a Paolo, sono in ritardo").azioni[0]["tipo"])

    @Test fun `whatsapp con il nome dopo l'app`() {
        val pi = p("whatsapp a Mamma: ciao")
        assertEquals("3409998887", pi.azioni[0]["numero"])
    }

    @Test fun `whatsapp a un numero detto`() {
        val pi = p("manda un whatsapp al 333 765 4321: ok")
        assertEquals("3337654321", pi.azioni[0]["numero"])
        assertEquals("Ok", pi.azioni[0]["testo"])
    }

    @Test fun `contatto con un fisso e un cellulare prende il cellulare`() =
        assertEquals("335 111 2233", p("manda un whatsapp a Giulia: ciao").azioni[0]["numero"])

    @Test fun `nome ambiguo non fa niente e lo dice`() {
        val pi = p("manda un whatsapp a Marco: ciao")
        assertTrue(pi.azioni.isEmpty())
        assertTrue(pi.dire, pi.dire.contains("Marco Esempio") && pi.dire.contains("Marco Bianchi"))
    }

    @Test fun `contatto con due cellulari non sceglie a caso`() {
        val pi = p("manda un whatsapp a Luca: ciao")
        assertTrue(pi.azioni.isEmpty())
        assertTrue(pi.dire, pi.dire.contains("più numeri"))
    }

    @Test fun `contatto che non c'è non inventa un numero`() {
        val pi = p("manda un whatsapp a Ermenegildo: ciao")
        assertTrue(pi.azioni.isEmpty())
        assertTrue(pi.dire.startsWith("Non trovo"))
    }

    @Test fun `senza permesso della rubrica lo chiede`() {
        val pi = p("manda un whatsapp a Paolo: ciao", ctx.copy(contatti = null))
        assertTrue(pi.azioni.isEmpty())
        assertTrue(pi.serveRubrica)
    }

    @Test fun `senza testo chiede cosa scrivere`() {
        val pi = p("manda un whatsapp a Paolo")
        assertTrue(pi.azioni.isEmpty())
        assertTrue(pi.dire.startsWith("Cosa scrivo"))
    }

    @Test fun `whatsapp a me stesso apre la mia chat col mio numero`() {
        val pi = p("manda un whatsapp a me stesso: prova bozza")
        assertEquals(listOf("componi", "invia_bozza"), pi.azioni.map { it.action })
        assertEquals("whatsapp", pi.azioni[0]["tipo"])
        assertEquals("+390000000001", pi.azioni[0]["numero"])
        assertEquals("Prova bozza", pi.azioni[0]["testo"])
        assertEquals("te stesso", pi.azioni[1]["destinatario"])
        assertEquals("Prova bozza", pi.azioni[1]["bozza"])
    }

    @Test fun `le frasi per me stesso usano tutte il mio numero`() {
        for (f in listOf(
            "manda un whatsapp a me stesso: prova",
            "manda un whatsapp a me: prova",
            "Jarvis, manda un WhatsApp a me stesso prova",
            "manda un whatsapp sulla mia chat: prova",
            "scrivi nella mia chat su whatsapp: prova",
            "mandami un whatsapp: prova",
            "scrivimi su whatsapp: prova",
            "manda un whats app a me stesso, prova",
            "scrivi a me stesso su WhatsApp che prova",
        )) {
            val pi = p(f)
            assertEquals(f, listOf("componi", "invia_bozza"), pi.azioni.map { it.action })
            assertEquals(f, "+390000000001", pi.azioni[0]["numero"])
            assertEquals(f, "Prova", pi.azioni[0]["testo"])
        }
    }

    @Test fun `whatsapp a me senza numero in configurazione non inventa niente`() {
        val pi = p("manda un whatsapp a me stesso: prova", ctx.copy(config = config.copy(whatsappMe = "")))
        assertTrue(pi.azioni.isEmpty())
        assertTrue(pi.dire.contains("whatsapp_me"))
    }

    @Test fun `mandami un whatsapp a Paolo resta Paolo`() =
        assertEquals("3471234000", p("mandami un whatsapp a Paolo: ciao").azioni[0]["numero"])

    @Test fun `ogni invio passa dalla conferma`() {
        for (f in listOf("manda un whatsapp a Paolo: ciao", "scrivi una mail a Marco Esempio: ciao", "manda un sms a Paolo: ciao")) {
            assertEquals(f, "invia_bozza", p(f).azioni.last().action)
        }
    }

    // ------------------------------------------------------------ mail e sms

    @Test fun `scrivi una mail`() {
        val pi = p("scrivi una mail a Marco Esempio: ci vediamo domani")
        assertEquals("mail", pi.azioni[0]["tipo"])
        assertEquals(listOf("marco.rossi@example.com"), pi.azioni[0]["a"])
        assertEquals("Ci vediamo domani", pi.azioni[0]["corpo"])
        // Nessuna scelta nelle impostazioni = l'app di posta predefinita del telefono, su ogni marca.
        assertEquals("", pi.azioni[0]["app"])
        assertEquals("Mail", pi.azioni[1]["app"])
    }

    @Test fun `gmail solo se nominato`() {
        val pi = p("scrivi una mail con gmail a Marco Esempio: ciao")
        assertEquals("gmail", pi.azioni[0]["app"])
        assertEquals("Gmail", pi.azioni[1]["app"])
        // Con «gmail» nella configurazione vale la configurazione, ma la frase con «samsung» no: resta la scelta di Boss.
        assertEquals("gmail", p("scrivi una mail a Marco Esempio: ciao", ctx.copy(config = config.copy(appMail = "gmail"))).azioni[0]["app"])
    }

    @Test fun `mail a me stesso con samsung fino al pannello`() {
        val pi = p("manda una mail a me stesso con oggetto prova: prova bozza", ctx.copy(config = config.copy(appMail = "samsung")))
        assertEquals(listOf("componi", "invia_bozza"), pi.azioni.map { it.action })
        assertEquals("samsung", pi.azioni[0]["app"])
        assertEquals(listOf("boss@example.com"), pi.azioni[0]["a"])
        assertEquals("Prova", pi.azioni[0]["oggetto"])
        assertEquals("Prova bozza", pi.azioni[0]["corpo"])
        assertEquals("Samsung Email", pi.azioni[1]["app"])
    }

    @Test fun `mail con oggetto`() {
        val pi = p("manda una email a Giulia con oggetto riunione: sposto alle cinque")
        assertEquals("Riunione", pi.azioni[0]["oggetto"])
        assertEquals("Sposto alle cinque", pi.azioni[0]["corpo"])
        assertEquals(listOf("giulia@example.com"), pi.azioni[0]["a"])
    }

    @Test fun `mail a un indirizzo dettato con chiocciola`() =
        assertEquals(listOf("anna.bi@example.com"), p("scrivi una mail a anna punto bi chiocciola example punto com: ciao").azioni[0]["a"])

    @Test fun `mail a me stesso usa l'account della configurazione`() =
        assertEquals(listOf("boss@example.com"), p("scrivi una mail a me: promemoria fattura").azioni[0]["a"])

    @Test fun `mail a chi non ha la mail non inventa`() {
        val pi = p("scrivi una mail a Paolo: ciao")
        assertTrue(pi.azioni.isEmpty())
        assertTrue(pi.dire.contains("con una mail"))
    }

    @Test fun `sms`() {
        val pi = p("manda un sms a Mamma: sto arrivando")
        assertEquals("sms", pi.azioni[0]["tipo"])
        assertEquals("3409998887", pi.azioni[0]["numero"])
    }

    @Test fun `la prima app detta vince`() = assertEquals("mail", p("scrivi una mail a Marco Esempio: ti ho mandato il whatsapp").azioni[0]["tipo"])

    // ------------------------------------------------------------ chiama, naviga, tasti, ora

    @Test fun `chiama la mamma`() {
        val pi = p("chiama Mamma")
        assertEquals("chiama", pi.azioni[0]["tipo"])
        assertEquals("3409998887", pi.azioni[0]["numero"])
    }

    @Test fun `telefona a un numero`() = assertEquals("070123456", p("telefona al 070 123456").azioni[0]["numero"])
    @Test fun `chiama chi non c'è`() = assertTrue(p("chiama Ermenegildo").azioni.isEmpty())

    @Test fun `portami a`() {
        val pi = p("portami a Piazza Yenne Cagliari")
        assertEquals("naviga", pi.azioni[0]["tipo"])
        assertEquals("Piazza Yenne Cagliari", pi.azioni[0]["dove"])
    }

    @Test fun `torna indietro`() = assertEquals("BACK", p("torna indietro").azioni[0]["name"])
    @Test fun `vai alla home`() = assertEquals("HOME", p("vai alla home").azioni[0]["name"])
    @Test fun `scorri giu`() = assertEquals("giu", p("scorri giù").azioni[0]["direzione"])

    @Test fun `che ore sono`() {
        val pi = p("che ore sono?")
        assertTrue(pi.azioni.isEmpty())
        assertEquals("Sono le 18 e 25.", pi.dire)
    }

    // ------------------------------------------------------------ non capito

    @Test fun `frase fuori dalle regole`() {
        val pi = p("controlla la posta e dimmi se c'è qualcosa di urgente")
        assertFalse(pi.capito)
        // 0.3.3: si dice cosa si è sentito e cosa si può chiedere.
        assertTrue(pi.dire, pi.dire.startsWith("Ho sentito «controlla la posta"))
    }

    @Test fun `frase vuota`() = assertFalse(p("  ").capito)
    @Test fun `solo jarvis`() = assertFalse(p("Jarvis.").capito)

    // ------------------------------------------------------------ pezzi

    @Test fun `cifre`() {
        assertEquals("+390000000001", CervelloRegole.cifre("+39 000 000 0001"))
        assertNull(CervelloRegole.cifre("Marco"))
        assertNull(CervelloRegole.cifre("12"))
    }

    @Test fun `azione in json`() {
        val j = Azione("componi", mapOf("tipo" to "mail", "a" to listOf("x@example.com"), "vuoto" to null)).json()
        assertEquals("componi", j.getString("action"))
        assertEquals("x@example.com", j.getJSONArray("a").getString(0))
        assertFalse(j.has("vuoto"))
    }

    @Test fun `capisci suspend da lo stesso piano`() = runTest {
        assertEquals(p("apri Spotify"), cervello.capisci("apri Spotify", ctx))
    }

    @Test fun `il sorgente non usa flag che Android rifiuta`() {
        val f = listOf(
            File("src/main/java/com/jarvis/telefono/nucleo/CervelloRegole.kt"),
            File("app/src/main/java/com/jarvis/telefono/nucleo/CervelloRegole.kt"),
        ).first { it.exists() }
        val s = f.readText()
        assertFalse("UNICODE_CHARACTER_CLASS" in s)
        assertFalse("(?U)" in s)
    }
}
