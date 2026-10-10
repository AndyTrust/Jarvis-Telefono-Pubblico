package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La voce sintetica sul telefono dice le stesse cose di quella del computer e del
 * browser. I 27 casi sono quelli di ~/Jarvis/backtalk/prove/prova_sintesi_voce.py
 * (20) e prova_voce_sintetica_js.py (7 in più); le uscite attese sono quelle
 * di `per_la_voce` in Python del 2026-10-04, copiate così come sono.
 */
class PerLaVoceTest {

    private class Caso(
        val nome: String,
        val testo: String,
        val atteso: String,
        val contiene: List<String> = emptyList(),
        val nonContiene: List<String> = emptyList(),
        val coda: Boolean? = null,
    )

    private val casi = listOf(
        Caso("comando ssh in mezzo", "Controllo lo spazio sulla server.\nssh mio-server 'df -h /'\nFatto: il disco è al 62%, restano 38 GB liberi.\nNon serve fare pulizia per ora.", "Fatto: il disco è al 62%, restano 38 GB liberi. Non serve fare pulizia per ora.", contiene = listOf("62%", "38 GB"), nonContiene = listOf("Controllo lo spazio", "df"), coda = false),
        Caso("percorso del computer", "Ho salvato il report in /Users/utente/Library/CloudStorage/cloud-Personale/Note/Memoria/Report/stato.md, è pronto.", "Ho salvato il report, è pronto.", contiene = listOf("Ho salvato il report", "pronto"), nonContiene = listOf("cloud", "stato.md"), coda = false),
        Caso("blocco di codice", "Ecco la correzione per il filtro:\n```python\ndef f(x):\n    return x * 2\n```\nFatto, la prova passa: 12 casi su 12.", "Fatto, la prova passa: 12 casi su 12. Il resto è nella chat.", contiene = listOf("12 casi su 12"), nonContiene = listOf("def", "return"), coda = true),
        Caso("tabella markdown", "Ecco gli incassi di ieri.\n\n| Locale | Incasso |\n|---|---|\n| Torino | 2.340 € |\n| Sassari | 1.980 € |\n\nIl totale è 4.320 €, in linea con la settimana scorsa.", "Ecco gli incassi di ieri. Il totale è 4.320 €, in linea con la settimana scorsa. Il resto è nella chat.", contiene = listOf("4.320"), nonContiene = listOf("Locale", "Torino"), coda = true),
        Caso("elenco di 8 punti", "Ho chiuso il giro del mattino. Ecco cosa ho fatto:\n- aggiornato Primavera\n- scaricato le fatture\n- allineato iPratico\n- controllato i turni\n- rifatto le quadrature\n- salvato il report\n- spento il tunnel\n- avvisato il PC dell'amministrazione\nVuoi che mandi il PDF alla commercialista?", "Ho chiuso il giro del mattino. Vuoi che mandi il PDF alla commercialista? Il resto è nella chat.", contiene = listOf("commercialista"), nonContiene = listOf(), coda = true),
        Caso("risposta già breve: non cambia", "Fatto, l'utente. Il backup è partito alle 12:30.", "Fatto, l'utente. Il backup è partito alle 12:30.", contiene = listOf(), nonContiene = listOf(), coda = null),
        Caso("risposta breve con domanda: non cambia", "Il volto è acceso. Vuoi che apra anche la lavagna?", "Il volto è acceso. Vuoi che apra anche la lavagna?", contiene = listOf(), nonContiene = listOf(), coda = null),
        Caso("risposta in inglese", "Let me check the logs.\n`tail -n 50 ~/Jarvis/backtalk/logs/backtalk.log`\nDone: the voice crashed at 11:42 because Gemini hit the daily limit. It switched to Kokoro by itself. Nothing else to fix. Do you want me to raise the limit?", "Done: the voice crashed at 11:42 because Gemini hit the daily limit. Do you want me to raise the limit? Il resto è nella chat.", contiene = listOf("11:42", "raise the limit"), nonContiene = listOf("Let me check", "tail"), coda = true),
        Caso("testo vuoto", "   \n  ", "", contiene = listOf(), nonContiene = listOf(), coda = null),
        Caso("solo comandi", "```bash\nssh mio-server 'docker ps'\n```\n\$ launchctl list | grep jarvis\ngit status --short", "Fatto, i dettagli sono nella chat.", contiene = listOf(), nonContiene = listOf(), coda = null),
        Caso("numeri e date restano", "Il bonifico di 1.250,00 € è arrivato il 03/10/2026 alle 18:05. La prossima rata scade il 15/11/2026.", "Il bonifico di 1.250,00 € è arrivato il 03/10/2026 alle 18:05. La prossima rata scade il 15/11/2026.", contiene = listOf("1.250,00", "03/10/2026", "18:05", "15/11/2026"), nonContiene = listOf(), coda = false),
        Caso("URL e IP", "Il Command Center risponde su http://127.0.0.1:7777 e da fuori su https://jarvis.example.org, tutti e due ok.", "Il Command Center risponde e da fuori, tutti e due ok.", contiene = listOf("Command Center", "ok"), nonContiene = listOf("7777", "example"), coda = false),
        Caso("hash e uuid", "Commit a3f9c2e1b7d4 fatto e mandato. La sessione 0c6e2f1a-9b8d-4e3f-a1b2-c3d4e5f60718 è chiusa.", "Commit fatto e mandato. La sessione è chiusa.", contiene = listOf("fatto", "chiusa"), nonContiene = listOf("a3f9", "0c6e"), coda = null),
        Caso("narrazione di esecuzione", "Sto controllando i container. Lancio il comando per vedere i log. Ora provo a riavviare.\nTutti e 9 i container sono su, nessun errore nelle ultime 24 ore.", "Tutti e 9 i container sono su, nessun errore nelle ultime 24 ore.", contiene = listOf("9 i container", "24 ore"), nonContiene = listOf("Sto controllando", "Lancio", "Ora provo"), coda = false),
        Caso("JSON", "Il file di configurazione adesso è questo:\n{\n  \"mani_libere\": {\"enabled\": false},\n  \"gemini_voce\": true\n}\nHo spento le mani libere, come volevi.", "Ho spento le mani libere, come volevi. Il resto è nella chat.", contiene = listOf("spento le mani libere"), nonContiene = listOf("enabled", "{"), coda = null),
        Caso("titoli, grassetto ed emoji", "## Esito\n✅ **Pronto**: la pagina Piani funziona su computer e telefono \uD83D\uDE80\n### Dettagli\nHo provato 3 schermate.", "Pronto: la pagina Piani funziona su computer e telefono. Ho provato 3 schermate.", contiene = listOf("Pronto", "3 schermate"), nonContiene = listOf("Esito", "Dettagli"), coda = null),
        Caso("risposta lunga senza esito: si taglia a fine frase", "La voce passa da tre motori. Il primo è Gemini con la voce Charon, che arriva dal webhook di n8n. Il secondo è Edge, che serve quando Gemini arriva al limite. Il terzo è Kokoro, locale, che parla con la voce di Sara. L'ordine si sceglie nel file di configurazione. Il cambio è automatico e non lascia silenzi.", "La voce passa da tre motori. Il primo è Gemini con la voce Charon, che arriva dal webhook di n8n. Il resto è nella chat.", contiene = listOf(), nonContiene = listOf(), coda = true),
        Caso("underscore e trattini lunghi", "La chiave mani_libere è spenta — la riaccendi dicendo «mani libere».", "La chiave mani libere è spenta, la riaccendi dicendo «mani libere».", contiene = listOf("mani libere è spenta"), nonContiene = listOf(), coda = false),
        Caso("percorso relativo e opzioni", "Ho corretto backtalk/backtalk/main.py e rilanciato le prove con --verbose: 15 su 15 passano.", "Ho corretto e rilanciato le prove: 15 su 15 passano.", contiene = listOf("15 su 15"), nonContiene = listOf("main.py", "verbose"), coda = null),
        Caso("indicazione di scena", "<<sorride>> Ciao l'utente, tutto a posto.", "Ciao l'utente, tutto a posto.", contiene = listOf(), nonContiene = listOf(), coda = null),
        Caso("extra 21", "La fattura di Azienda S.r.l. è arrivata.", "La fattura di Azienda S.r.l. è arrivata."),
        Caso("extra 22", "Git è aggiornato e Docker è su.", "Git è aggiornato e Docker è su."),
        Caso("extra 23", "Date le condizioni, conviene aspettare domani.", "Date le condizioni, conviene aspettare domani."),
        Caso("extra 24", "Sì, è tutto a posto.", "Sì, è tutto a posto."),
        Caso("extra 25", "Ho fermato il lavoro.", "Ho fermato il lavoro."),
        Caso("extra 26", "Ti ho scritto il codice in chat. Il resto è in chat.", "Ti ho scritto il codice in chat. Il resto è nella chat."),
        Caso("extra 27", "Ecco il risultato (codice) e il link (link), tutto ok.", "Ecco il risultato e il link, tutto ok. Il resto è nella chat."),
    )

    /** Le stesse prove di prova_sintesi_voce.py: niente percorsi, URL, comandi, markdown, hash, emoji. */
    private val vietati = listOf(
        Regex("""/Users|~/|C:\\""") to "percorso",
        Regex("""https?://|www\.""") to "URL",
        Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""") to "indirizzo IP",
        Regex("""\bssh\b|\bsudo\b|\bsystemctl\b|\bdocker\b|\bgit\b|\bpython3\b|\bcurl\b|\blaunchctl\b|\brclone\b""") to "comando",
        Regex("""`|```|\*\*|^#|\|""", RegexOption.MULTILINE) to "markdown",
        Regex("""\b[0-9a-f]{8,}\b""") to "hash",
        Regex("""[_\\]|—|–""") to "simbolo",
        Regex("""[\x{1F300}-\x{1FAFF}\u2600-\u27BF]""") to "emoji",
    )

    @Test
    fun `i 27 casi danno la stessa uscita di Python`() {
        assertEquals(27, casi.size)
        val diversi = casi.filter { PerLaVoce.perLaVoce(it.testo) != it.atteso }
            .map { "${it.nome}: atteso «${it.atteso}», uscito «${PerLaVoce.perLaVoce(it.testo)}»" }
        assertTrue(diversi.joinToString("\n"), diversi.isEmpty())
    }

    @Test
    fun `i controlli della prova Python valgono anche qui`() {
        val max = PerLaVoce.MAX_CARATTERI + PerLaVoce.CODA_RESTO.length + 2
        for (c in casi) {
            val fuori = PerLaVoce.perLaVoce(c.testo)
            for (x in c.contiene) assertTrue("${c.nome}: manca «$x»", x in fuori)
            for (x in c.nonContiene) assertFalse("${c.nome}: resta «$x»", x in fuori)
            if (fuori != "" && fuori != PerLaVoce.NIENTE_DA_DIRE) {
                for ((re, cosa) in vietati) assertFalse("${c.nome}: resta un $cosa", re.containsMatchIn(fuori))
            }
            assertTrue("${c.nome}: troppo lungo (${fuori.length})", fuori.length <= max)
            if (c.coda == true) assertTrue("${c.nome}: manca la coda", fuori.endsWith(PerLaVoce.CODA_RESTO))
            if (c.coda == false) assertFalse("${c.nome}: coda di troppo", PerLaVoce.CODA_RESTO in fuori)
            assertEquals("${c.nome}: una seconda passata cambia il testo", fuori, PerLaVoce.perLaVoce(fuori))
            assertTrue("${c.nome}: coda ripetuta", fuori.split(PerLaVoce.CODA_RESTO).size <= 2)
        }
    }

    @Test
    fun `testo vuoto o nullo non da eccezioni`() {
        assertEquals("", PerLaVoce.perLaVoce(""))
        assertEquals("", PerLaVoce.perLaVoce(null))
        assertEquals("", PerLaVoce.perLaVoce(" \n\t "))
    }

    @Test
    fun `testo enorme da 100 KB non da eccezioni e resta corto`() {
        val pezzo = "Ho controllato /Users/utente/file.txt con `ls -la` su https://esempio.com e 192.168.1.10. " +
            "Fatto: 12 file a posto 🚀, l'hash è a3f9c2e1b7d4.\n" +
            "ssh mio-server 'docker ps'\n| a | b |\n```\ncodice\n```\n- punto **uno** — due\n"
        val sb = StringBuilder()
        while (sb.length < 100_000) sb.append(pezzo)
        val inizio = System.nanoTime()
        val fuori = PerLaVoce.perLaVoce(sb.toString())
        val ms = (System.nanoTime() - inizio) / 1_000_000
        assertTrue("vuoto", fuori.isNotBlank())
        assertTrue("troppo lungo: ${fuori.length}", fuori.length <= PerLaVoce.MAX_CARATTERI + PerLaVoce.CODA_RESTO.length + 2)
        assertTrue("troppo lento: $ms ms", ms < 20_000)
        // un solo blocco enorme senza a capo
        val riga = "parola ".repeat(15_000) + "/a/b/c.txt " + "x".repeat(10_000)
        PerLaVoce.perLaVoce(riga)
    }

    @Test
    fun `il sorgente non usa flag che Android rifiuta`() {
        val f = listOf(
            File("src/main/java/com/jarvis/telefono/voce/PerLaVoce.kt"),
            File("app/src/main/java/com/jarvis/telefono/voce/PerLaVoce.kt"),
        ).first { it.exists() }
        val sorgente = f.readText()
        assertFalse("UNICODE_CHARACTER_CLASS nel sorgente", "UNICODE_CHARACTER_CLASS" in sorgente)
        assertFalse("(?U) nel sorgente", "(?U)" in sorgente)
        assertFalse("RegexOption nel sorgente", "RegexOption" in sorgente)
    }
}
