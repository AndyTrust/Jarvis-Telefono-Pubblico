package com.jarvis.telefono.voce

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Il post-correttore della trascrizione, portato da `post_correggi`
 * del computer. Le frasi del confronto hanno l'uscita che il Python del computer ha
 * dato sugli stessi tre file (26/09/2026): se cambia una delle due parti,
 * questa prova se ne accorge.
 */
class GlossarioTest {

    @get:Rule
    val cartella = TemporaryFolder()

    private val glossario = """
        # glossario di prova
        # termine<TAB>fonte<TAB>peso
        iCassa	fisso	5
        Primavera	fisso	5
        Matteo Brain	fisso	5
        Matteo	fisso	5
        Odoo	fisso	5
        Passbolt	fisso	5
        Aruba	fisso	5
        Rossi	fisso	5
        sportello.cloud	fisso	5
        Luna Charter	fisso	5
        Zeta Bot	fisso	5
        Supabase	fisso	5
        Jarvis	fisso	5
    """.trimIndent() + "\n"

    private val correzioni =
        "{\"ts\": \"2026-09-26 19:00:00\", \"sbagliata\": \"gervis\", \"giusta\": \"Jarvis\", \"frase\": \"gervis ci sei\"}\n"

    private val comuni = listOf(
        "e", "il", "la", "di", "che", "non", "un", "una", "per", "in", "con", "su", "mi", "ho",
        "i", "a", "lo", "le", "da", "apri", "controlla", "oggi", "salva", "nota", "poi", "manda",
        "login", "va", "matteo", "bryan", "pratico", "odio", "dove", "risponde", "private",
        "chartr", "boot", "zeta", "bot", "luna", "ci", "sei", "pieno",
    ).joinToString("\n") + "\n"

    private fun prepara(
        conGlossario: Boolean = true,
        conCorrezioni: Boolean = true,
        conComuni: Boolean = true,
    ): Glossario {
        val d = cartella.root
        if (conGlossario) File(d, Glossario.GLOSSARIO).writeText(glossario)
        if (conCorrezioni) File(d, Glossario.CORREZIONI).writeText(correzioni)
        if (conComuni) File(d, Glossario.COMUNI).writeText(comuni)
        return Glossario(d)
    }

    @Test
    fun `due parole attaccate danno un termine`() {
        assertEquals("Apri iCassa", prepara().postCorreggi("Apri I cassa").first)
    }

    @Test
    fun `un termine di piu parole si corregge con l ancora`() {
        // «bryan» è comune, ma «matteo» è scritta giusta: basta come ancora.
        assertEquals("Salva in Matteo Brain", prepara().postCorreggi("Salva in Matteo Bryan").first)
    }

    @Test
    fun `una parola gia nel glossario resta com e`() {
        val (testo, fatte) = prepara().postCorreggi("il Primavera di oggi")
        assertEquals("il Primavera di oggi", testo)
        assertTrue(fatte.isEmpty())
    }

    @Test
    fun `una parola comune non si tocca anche se vicina a un termine`() {
        // «odio» è a distanza 1 da «Odoo», ma è comune.
        assertEquals("odio il login", prepara().postCorreggi("odio il login").first)
    }

    @Test
    fun `una parola rara a distanza 1 si corregge, a distanza 3 no`() {
        val g = prepara()
        assertEquals("apri Passbolt", g.postCorreggi("apri Pasbolt").first)
        assertEquals("apri Pazzbalt", g.postCorreggi("apri Pazzbalt").first)
    }

    @Test
    fun `la correzione esplicita vince e ignora le maiuscole`() {
        val (testo, fatte) = prepara().postCorreggi("GERVIS, ci sei?")
        assertEquals("Jarvis, ci sei?", testo)
        assertEquals(listOf("gervis" to "Jarvis"), fatte)
    }

    @Test
    fun `la correzione esplicita vale solo a parola intera`() {
        assertEquals("apri gervisxy", prepara(conComuni = false).postCorreggi("apri gervisxy").first)
    }

    @Test
    fun `testo vuoto resta vuoto`() {
        val (testo, fatte) = prepara().postCorreggi("")
        assertEquals("", testo)
        assertTrue(fatte.isEmpty())
    }

    @Test
    fun `la lista delle sostituzioni riporta le coppie`() {
        val (_, fatte) = prepara().postCorreggi("Odoi e Supabse. Zeta Boot risponde?")
        assertEquals(
            listOf("Zeta Boot" to "Zeta Bot", "Supabse" to "Supabase", "Odoi" to "Odoo"),
            fatte,
        )
    }

    @Test
    fun `senza parole comuni si applicano solo le correzioni esplicite`() {
        val g = prepara(conComuni = false)
        assertFalse(g.pronto)
        val (testo, fatte) = g.postCorreggi("gervis apri I cassa e Pasbolt")
        assertEquals("Jarvis apri I cassa e Pasbolt", testo)
        assertEquals(listOf("gervis" to "Jarvis"), fatte)
    }

    @Test
    fun `senza glossario valgono i termini fissi del computer`() {
        val g = prepara(conGlossario = false)
        assertFalse(g.pronto)
        assertEquals(Glossario.FISSI, g.termini())
        assertEquals("apri Supabase", g.postCorreggi("apri Supabse").first)
    }

    @Test
    fun `con glossario e parole comuni e pronto`() {
        val g = prepara()
        assertTrue(g.pronto)
        assertEquals("iCassa", g.termini().first())
        assertEquals(13, g.termini().size)
    }

    @Test
    fun `il glossario si rilegge quando il file cambia`() {
        val g = prepara()
        assertEquals("apri Kokoro", g.postCorreggi("apri Kokoro").first)
        File(cartella.root, Glossario.GLOSSARIO).writeText("Kokoro\tfisso\t5\n")
        File(cartella.root, Glossario.GLOSSARIO).setLastModified(System.currentTimeMillis() + 5000)
        assertEquals("apri Kokoro", g.postCorreggi("apri Kokoru").first)
    }

    @Test
    fun `norm e distanza come sul computer`() {
        assertEquals("perche citta", Glossario.norm("Perché Città"))
        assertEquals(1, Glossario.distanza("odoi", "odoo"))
        assertEquals(3, Glossario.distanza("pazzbalt", "passbolt"))
        // Oltre il limite esce subito con limite + 1.
        assertEquals(3, Glossario.distanza("pazzbalt", "passbolt", 2))
        assertEquals(3, Glossario.distanza("ab", "abcdef", 2))
    }

    // ------------------------------------------ confronto col Python del computer

    /** Uscite reali di post_correggi sugli stessi tre file. */
    @Test
    fun `cinque frasi danno la stessa uscita del computer`() {
        val g = prepara()
        val casi = listOf(
            "Apri I cassa e controlla il Primavra di oggi" to
                ("Apri iCassa e controlla il Primavera di oggi" to
                    listOf("Primavra" to "Primavera", "I cassa" to "iCassa")),
            "Salva la nota in Matteo Bryan, poi manda a Rossi" to
                ("Salva la nota in Matteo Brain, poi manda a Rossi" to
                    listOf("Matteo Bryan" to "Matteo Brain")),
            "Gervis, il login di Pasbolt su Aruba non va" to
                ("Jarvis, il login di Passbolt su Aruba non va" to
                    listOf("gervis" to "Jarvis", "Pasbolt" to "Passbolt")),
            "Controlla Luna Chartr e lo sportello.cloud" to
                ("Controlla Luna Charter e lo sportello.cloud" to
                    listOf("Luna Chartr" to "Luna Charter")),
            "Odoi e Supabse. Zeta Boot risponde?" to
                ("Odoo e Supabase. Zeta Bot risponde?" to
                    listOf("Zeta Boot" to "Zeta Bot", "Supabse" to "Supabase", "Odoi" to "Odoo")),
            "Odio il Pazzbalt" to ("Odio il Pazzbalt" to emptyList()),
        )
        for ((ingresso, atteso) in casi) {
            assertEquals(ingresso, atteso, g.postCorreggi(ingresso))
        }
    }

    /**
     * Confronto largo sui dati veri del computer, solo a mano: si salta se le
     * variabili non ci sono (in CI non ci sono mai). GLOSSARIO_CARTELLA è
     * `la cartella delle voci`, GLOSSARIO_CONFRONTO il JSON prodotto dal Python
     * ([{"in", "out", "fatte"}]).
     */
    @Test
    fun `confronto largo con i dati veri del computer`() {
        val dir = System.getenv("GLOSSARIO_CARTELLA")
        val attesi = System.getenv("GLOSSARIO_CONFRONTO")
        assumeTrue(dir != null && attesi != null)
        val g = Glossario(File(dir!!))
        val testo = File(attesi!!).readText()
        // Il JSON è una lista: lo si avvolge in un oggetto per il lettore minimo.
        @Suppress("UNCHECKED_CAST")
        val lista = Json("{\"x\": $testo}").oggetto()!!["x"] as List<Map<String, Any?>>
        var diverse = 0
        for (c in lista) {
            val ing = c["in"] as String
            val out = c["out"] as String
            @Suppress("UNCHECKED_CAST")
            val fatte = (c["fatte"] as List<List<String>>).map { it[0] to it[1] }
            val mio = g.postCorreggi(ing)
            if (mio.first != out || mio.second != fatte) {
                diverse++
                println("DIVERSA: $ing\n  atteso: $out $fatte\n  kotlin: ${mio.first} ${mio.second}")
            }
        }
        println("CONFRONTO: ${lista.size - diverse}/${lista.size} uguali")
        assertEquals(0, diverse)
    }

    // ------------------------------------------------------- aggiornamento

    @Test
    fun `aggiorna scarica i file, rispetta i 404 e manda il token`() {
        val dir = cartella.newFolder("voce")
        File(dir, Glossario.CORREZIONI).writeText("vecchio\n")
        val visti = mutableListOf<String?>()
        // Il ponte finto: un intercettore che risponde senza rete.
        val client = OkHttpClient.Builder().addInterceptor { catena ->
            val req = catena.request()
            visti.add(req.header("Authorization"))
            val corpo = when (req.url.encodedPath) {
                "/voce/glossario.txt" -> glossario
                "/voce/parole-comuni.txt" -> comuni
                else -> null
            }
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(if (corpo == null) 404 else 200)
                .message(if (corpo == null) "Not Found" else "OK")
                .body((corpo ?: "").toResponseBody("text/plain".toMediaType()))
                .build()
        }.build()
        run {
            val base = "https://ponte.prova/"
            val g = Glossario(dir)
            assertFalse(g.pronto)
            assertTrue(g.aggiorna(client, base, "segreto"))
            assertEquals(glossario, File(dir, Glossario.GLOSSARIO).readText())
            assertEquals(comuni, File(dir, Glossario.COMUNI).readText())
            // 404: resta il file di prima.
            assertEquals("vecchio\n", File(dir, Glossario.CORREZIONI).readText())
            assertTrue(visti.all { it == "Bearer segreto" })
            assertEquals(3, visti.size)
            assertTrue(g.pronto)
            assertEquals("Apri iCassa", g.postCorreggi("Apri I cassa").first)
            // Niente di nuovo: false, e nessun file temporaneo lasciato in giro.
            assertFalse(g.aggiorna(client, base, "segreto"))
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun `il token non finisce nel registro`() {
        val righe = mutableListOf<String>()
        val g = Glossario(cartella.newFolder("voce2")) { righe.add(it) }
        // Nessun server in ascolto: tre errori di rete, tutti registrati.
        assertFalse(g.aggiorna(OkHttpClient(), "http://127.0.0.1:1", "segretissimo"))
        assertEquals(3, righe.size)
        assertTrue(righe.none { "segretissimo" in it })
    }
}
