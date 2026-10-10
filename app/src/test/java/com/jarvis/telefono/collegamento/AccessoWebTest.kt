package com.jarvis.telefono.collegamento

import com.jarvis.telefono.voce.FineDettato
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le regole dell'interfaccia del sito dentro l'app (WebActivity, 1.1.0). */
class AccessoWebTest {

    private val base = "https://jarvis.example.org/"

    // ---------------------------------------------------------------- host di base

    @Test
    fun `stesso host in https vale, anche con percorso, query e maiuscole`() {
        assertTrue(AccessoWeb.eHostBase("https://jarvis.example.org/", base))
        assertTrue(AccessoWeb.eHostBase("https://jarvis.example.org/_ponte/entra?x=1#y", base))
        assertTrue(AccessoWeb.eHostBase("https://JARVIS.example.ORG/static/app.js", base))
        assertTrue(AccessoWeb.eHostBase("https://jarvis.example.org:443/", base))
    }

    @Test
    fun `altro host, sottodominio finto e porta diversa non valgono`() {
        assertFalse(AccessoWeb.eHostBase("https://evil.example.com/", base))
        assertFalse(AccessoWeb.eHostBase("https://jarvis.example.org.evil.com/", base))
        assertFalse(AccessoWeb.eHostBase("https://evil.com/?r=https://jarvis.example.org/", base))
        assertFalse(AccessoWeb.eHostBase("https://jarvis.example.org@evil.com/", base))
        assertFalse(AccessoWeb.eHostBase("https://utente@jarvis.example.org/", base))
        assertFalse(AccessoWeb.eHostBase("https://jarvis.example.org:8443/", base))
        assertFalse(AccessoWeb.eHostBase("https://jarvis-agent.example.org/", base))
    }

    @Test
    fun `http, javascript, file, data, intent e content non valgono`() {
        assertFalse(AccessoWeb.eHostBase("http://jarvis.example.org/", base))
        assertFalse(AccessoWeb.eHostBase("javascript:alert(1)", base))
        assertFalse(AccessoWeb.eHostBase("javascript://jarvis.example.org/%0aalert(1)", base))
        assertFalse(AccessoWeb.eHostBase("file:///android_asset/offline.html", base))
        assertFalse(AccessoWeb.eHostBase("file://jarvis.example.org/etc/passwd", base))
        assertFalse(AccessoWeb.eHostBase("data:text/html,<b>x</b>", base))
        assertFalse(AccessoWeb.eHostBase("intent://jarvis.example.org/#Intent;end", base))
        assertFalse(AccessoWeb.eHostBase("content://com.jarvis.app.fileprovider/x", base))
        assertFalse(AccessoWeb.eHostBase("about:blank", base))
        assertFalse(AccessoWeb.eHostBase("", base))
        assertFalse(AccessoWeb.eHostBase(null, base))
        assertFalse(AccessoWeb.eHostBase("https://", base))
        assertFalse(AccessoWeb.eHostBase("ht tp://non un indirizzo", base))
    }

    @Test
    fun `solo http, https, mailto e tel vanno al browser esterno`() {
        assertTrue(AccessoWeb.daAprireFuori("https://github.com/"))
        assertTrue(AccessoWeb.daAprireFuori("http://example.com/"))
        assertTrue(AccessoWeb.daAprireFuori("mailto:utente@example.com"))
        assertTrue(AccessoWeb.daAprireFuori("tel:+390000000"))
        assertFalse(AccessoWeb.daAprireFuori("javascript:alert(1)"))
        assertFalse(AccessoWeb.daAprireFuori("intent://x#Intent;end"))
        assertFalse(AccessoWeb.daAprireFuori("file:///sdcard/x"))
        assertFalse(AccessoWeb.daAprireFuori(null))
    }

    @Test
    fun `l'indirizzo di base deve essere https con un host`() {
        assertTrue(AccessoWeb.indirizzoBaseValido(base))
        assertFalse(AccessoWeb.indirizzoBaseValido("http://jarvis.example.org/"))
        assertFalse(AccessoWeb.indirizzoBaseValido(""))
        assertFalse(AccessoWeb.indirizzoBaseValido("jarvis.example.org"))
        assertFalse(AccessoWeb.indirizzoBaseValido("https://a@jarvis.example.org/"))
    }

    @Test
    fun `ripiego offline solo per gli errori 5xx`() {
        assertTrue(AccessoWeb.eRipiego(500)); assertTrue(AccessoWeb.eRipiego(502)); assertTrue(AccessoWeb.eRipiego(503))
        assertFalse(AccessoWeb.eRipiego(200)); assertFalse(AccessoWeb.eRipiego(401)); assertFalse(AccessoWeb.eRipiego(404))
    }

    // ---------------------------------------------------------------- pagina di accesso

    @Test
    fun `riconosce la pagina di accesso solo sull'host di base`() {
        assertTrue(AccessoWeb.ePaginaAccesso("https://jarvis.example.org/_ponte/entra", base))
        assertTrue(AccessoWeb.ePaginaAccesso("https://jarvis.example.org/_ponte/entra/", base))
        assertTrue(AccessoWeb.ePaginaAccesso("https://jarvis.example.org/_ponte/entra?dopo=%2F", base))
        assertFalse(AccessoWeb.ePaginaAccesso("https://jarvis.example.org/", base))
        assertFalse(AccessoWeb.ePaginaAccesso("https://jarvis.example.org/_ponte/entrata", base))
        assertFalse(AccessoWeb.ePaginaAccesso("https://jarvis.example.org/x/_ponte/entra", base))
        assertFalse(AccessoWeb.ePaginaAccesso("https://evil.com/_ponte/entra", base))
        assertFalse(AccessoWeb.ePaginaAccesso("http://jarvis.example.org/_ponte/entra", base))
    }

    // ---------------------------------------------------------------- escaping

    /** Rilegge un letterale prodotto da [AccessoWeb.js] come farebbe un parser JSON. */
    private fun rileggi(lett: String): String {
        assertTrue(lett.startsWith("\"") && lett.endsWith("\""))
        val sb = StringBuilder()
        var i = 1
        while (i < lett.length - 1) {
            val c = lett[i]
            assertFalse("virgoletta non protetta in $lett", c == '"')
            assertTrue("carattere di controllo crudo", c >= ' ')
            if (c != '\\') { sb.append(c); i++; continue }
            when (val e = lett[i + 1]) {
                '"' -> sb.append('"'); '\\' -> sb.append('\\'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r')
                't' -> sb.append('\t'); '/' -> sb.append('/')
                'u' -> { sb.append(lett.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                else -> throw AssertionError("escape sconosciuto \\$e")
            }
            i += 2
        }
        return sb.toString()
    }

    @Test
    fun `la password con apici, virgolette, backslash, a capo e chiusura di script resta intatta e chiusa`() {
        val difficili = listOf(
            "pa'ss", "pa\"ss", "a\\b", "riga1\nriga2\r\n", "</script><script>alert(1)</script>",
            "\"); alert(1); (\"", "'); alert(1); ('", "\\\"", "tab\tqui", "  ", "àèìòù € 🙂", "",
        )
        for (p in difficili) {
            val l = AccessoWeb.js(p)
            assertEquals("andata e ritorno di ${p.length} caratteri", p, rileggi(l))
            assertFalse(l.contains("</script", ignoreCase = true))
            assertFalse(l.contains("'"))
            assertFalse(l.contains("\n") || l.contains("\r") || l.contains(" ") || l.contains(" "))
        }
    }

    @Test
    fun `lo script di compilazione mette i valori solo come letterali`() {
        val pw = "x\"); window.rubato=1; (\"</script>"
        val s = AccessoWeb.scriptCompila("bo'ss", pw)
        assertTrue(s.endsWith("(" + AccessoWeb.js("bo'ss") + "," + AccessoWeb.js(pw) + ")"))
        assertFalse(s.contains(pw))
        assertFalse(s.contains("bo'ss"))
        assertTrue(s.contains("getElementById(\"utente\")") && s.contains("getElementById(\"password\")"))
        // una sola funzione invocata subito, che invia il modulo
        assertTrue(s.startsWith("(function(u,p){") && s.contains("requestSubmit"))
    }

    @Test
    fun `il testo del dettato arriva alla pagina con lo stesso escaping`() {
        val t = "ciao \"l'utente\"\n</script>"
        assertEquals("window.CCDettatoNativo && window.CCDettatoNativo(" + AccessoWeb.js(t) + ")", AccessoWeb.scriptDettato(t))
        assertEquals("window.CCDettatoNativo && window.CCDettatoNativo(\"\")", AccessoWeb.scriptDettato(""))
    }

    @Test
    fun `il testo provvisorio arriva alla pagina con lo stesso escaping`() {
        val t = "ciao \"l'utente\"\n</script>"
        assertEquals("window.CCDettatoParziale && window.CCDettatoParziale(" + AccessoWeb.js(t) + ")", AccessoWeb.scriptParziale(t))
    }

    // ---------------------------------------------------------------- decisioni

    @Test
    fun `un solo tentativo automatico per sessione`() {
        val t = TentativoAutomatico()
        assertTrue(t.libero)
        assertTrue(t.prendi())
        assertFalse(t.libero)
        assertFalse(t.prendi())
        assertFalse(t.prendi())
    }

    @Test
    fun `con credenziali compila una volta, poi lascia scrivere all'utente`() {
        val t = TentativoAutomatico()
        fun passo() = AccessoWeb.decidi(true, credenzialiSalvate = true, tentativoLibero = t.libero, rifiutato = false)
            .also { if (it == AccessoWeb.Azione.COMPILA_E_INVIA) assertTrue(t.prendi()) }
        assertEquals(AccessoWeb.Azione.COMPILA_E_INVIA, passo())
        // la pagina torna con l'errore: niente secondo invio automatico
        assertEquals(AccessoWeb.Azione.ASCOLTA_INVIO, passo())
        assertEquals(AccessoWeb.Azione.ASCOLTA_INVIO, passo())
    }

    @Test
    fun `senza credenziali si ascolta l'invio, salvo il No dell'utente, e fuori dall'accesso niente`() {
        assertEquals(AccessoWeb.Azione.ASCOLTA_INVIO, AccessoWeb.decidi(true, false, true, false))
        assertEquals(AccessoWeb.Azione.NIENTE, AccessoWeb.decidi(true, false, true, true))
        assertEquals(AccessoWeb.Azione.NIENTE, AccessoWeb.decidi(false, true, true, false))
        assertEquals(AccessoWeb.Azione.NIENTE, AccessoWeb.decidi(false, false, true, false))
    }

    @Test
    fun `si propone di salvare solo se l'accesso e' riuscito`() {
        assertEquals(AccessoWeb.Candidato.PROPONI, AccessoWeb.dopoInvio("https://jarvis.example.org/", base))
        assertEquals(AccessoWeb.Candidato.SCARTA, AccessoWeb.dopoInvio("https://jarvis.example.org/_ponte/entra", base))
        assertEquals(AccessoWeb.Candidato.ASPETTA, AccessoWeb.dopoInvio("about:blank", base))
        assertEquals(AccessoWeb.Candidato.ASPETTA, AccessoWeb.dopoInvio("https://evil.com/", base))
    }

    // ---------------------------------------------------------------- fine del dettato

    @Test
    fun `il dettato si chiude dopo il silenzio, se nessuno parla e al massimo`() {
        val f = FineDettato(silenzioMs = 300, attesaInizioMs = 900, maxMs = 3000, fotogrammaMs = 30)
        repeat(5) { assertFalse(f.fotogramma(false)) }
        repeat(20) { assertFalse(f.fotogramma(true)) }
        repeat(9) { assertFalse(f.fotogramma(false)) }
        assertTrue(f.fotogramma(false)) // 10 × 30 ms = 300 ms di silenzio
        assertTrue(f.parlatoVisto)

        val muto = FineDettato(silenzioMs = 300, attesaInizioMs = 900, maxMs = 3000, fotogrammaMs = 30)
        repeat(29) { assertFalse(muto.fotogramma(false)) }
        assertTrue(muto.fotogramma(false)) // 900 ms senza parlato
        assertFalse(muto.parlatoVisto)

        val lungo = FineDettato(silenzioMs = 300, attesaInizioMs = 900, maxMs = 3000, fotogrammaMs = 30)
        repeat(99) { assertFalse(lungo.fotogramma(true)) }
        assertTrue(lungo.fotogramma(true)) // 3 s di parlato continuo
    }
}
