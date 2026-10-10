package com.jarvis.telefono.agenti

import com.jarvis.telefono.bolla.TestiBolla
import com.jarvis.telefono.mani.CercaContatti.Contatto
import com.jarvis.telefono.nucleo.Azione
import com.jarvis.telefono.nucleo.CervelloRegole
import com.jarvis.telefono.nucleo.ConfigPersonale
import com.jarvis.telefono.nucleo.Contesto
import com.jarvis.telefono.nucleo.Piano
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.2.0: Jarvis è il capo e passa il lavoro per competenza. Frasi di tutti i giorni (anche
 * ambigue) → l'agente giusto; azioni del cervello a regole → l'agente che le esegue; la bolla
 * «Jarvis → agente». Rubrica e configurazione finte.
 */
class CompetenzeTest {

    private val P = CatalogoAgenti.POSTINO
    private val R = CatalogoAgenti.RICERCATORE
    private val S = CatalogoAgenti.SOCIAL
    private val M = CatalogoAgenti.MANI
    private val W = CatalogoAgenti.SCRITTORE

    // ------------------------------------------------------------ dalla frase (prima scelta del capo)

    private val casi: List<Pair<String, String?>> = listOf(
        // Postino
        "controlla la posta" to P,
        "c'è qualcosa di urgente nelle PEC?" to P,
        "manda una mail a Marco" to P,
        "scrivi una mail a Giulia per domani" to P,          // ambigua: scrivi + mail → Postino
        "prepara le bozze delle risposte" to P,
        "Jarvis, leggi la casella del negozio" to P,
        // Ricercatore
        "cerca il meteo di Olbia" to R,
        "cerca su youtube un video sulla pasta fresca" to R,
        "chiedi a gemini chi ha vinto ieri" to R,
        "trova le notizie sul bando digitale" to R,
        "fai una ricerca sui prezzi del grano" to R,
        // Social
        "rispondi ai commenti su instagram" to S,
        "scrivi un post per facebook sul menu" to S,         // ambigua: scrivi + post → Social
        "cosa dice il feed oggi" to S,
        "prepara un piano per i social" to S,
        // Mani
        "apri WhatsApp" to M,
        "apri youtube" to M,                                   // ambigua: apri + youtube → Mani (apre l'app)
        "manda un whatsapp a Paolo che arrivo" to M,
        "scrivi su whatsapp a Marco che arrivo" to M,         // ambigua: scrivi + whatsapp → Mani
        "chiama la mamma" to M,
        "portami a Cagliari" to M,
        "manda un sms a Luca" to M,
        "cerca Marco in rubrica" to M,                        // ambigua: cerca + rubrica → Mani
        "scorri giù" to M,
        "componi il numero di Giulia" to M,                   // ambigua: componi + numero → Mani
        // Scrittore
        "riscrivi questo testo più corto" to W,
        "scrivimi due righe di auguri" to W,
        "correggi questa frase" to W,
        "traduci in inglese buongiorno a tutti" to W,
        // Jarvis stesso
        "che ore sono" to null,
        "Hey Jarvis" to null,
        "grazie" to null,
        "come stai" to null,
    )

    @Test
    fun `ogni frase va all'agente giusto`() {
        val sbagliate = casi.filter { (f, atteso) -> Competenze.perFrase(f) != atteso }
            .map { (f, atteso) -> "«$f»: atteso ${atteso ?: "Jarvis"}, uscito ${Competenze.perFrase(f) ?: "Jarvis"}" }
        assertTrue(sbagliate.joinToString("\n"), sbagliate.isEmpty())
        assertTrue("almeno 20 frasi", casi.size >= 20)
    }

    @Test
    fun `ogni agente ha almeno tre frasi e Jarvis tiene il resto`() {
        for (id in CatalogoAgenti.ID) assertTrue(id, casi.count { it.second == id } >= 3)
        assertTrue(casi.count { it.second == null } >= 3)
    }

    @Test
    fun `JBoss davanti, in ogni pronuncia, non cambia l'agente`() {
        assertEquals(M, Competenze.perFrase("JBoss, apri spotify"))
        assertEquals(P, Competenze.perFrase("hey gei boss controlla la posta"))
        assertEquals(R, Competenze.perFrase("jay boss cerca il meteo"))
        assertEquals("apri spotify", Competenze.pulisci("J Boss: apri spotify"))
    }

    @Test
    fun `il nome Jarvis davanti non cambia l'agente`() {
        assertEquals(M, Competenze.perFrase("Jarvis, apri spotify"))
        assertEquals(P, Competenze.perFrase("hey jarvis controlla la posta"))
        assertEquals("apri spotify", Competenze.pulisci("Ok Jarvis: apri spotify"))
    }

    // ------------------------------------------------------------ dalle azioni (chi esegue davvero)

    @Test
    fun `azioni delle mani agli agenti`() {
        fun a(action: String, vararg c: Pair<String, Any?>) = Azione(action, mapOf(*c))
        assertEquals(P, Competenze.perAzione(a("componi", "tipo" to "mail")))
        assertEquals(M, Competenze.perAzione(a("componi", "tipo" to "whatsapp")))
        assertEquals(M, Competenze.perAzione(a("componi", "tipo" to "sms")))
        assertEquals(M, Competenze.perAzione(a("componi", "tipo" to "chiama")))
        assertEquals(M, Competenze.perAzione(a("componi", "tipo" to "mappe")))
        assertEquals(R, Competenze.perAzione(a("cerca_google", "domanda" to "x")))
        assertEquals(R, Competenze.perAzione(a("gemini_chiedi")))
        assertEquals(R, Competenze.perAzione(a("cerca_in_app", "app" to "YouTube")))
        assertEquals(M, Competenze.perAzione(a("cerca_in_app", "app" to "WhatsApp")))
        assertEquals(M, Competenze.perAzione(a("apri_app", "nome" to "Spotify")))
        assertEquals(M, Competenze.perAzione(a("scorri")))
        assertNull(Competenze.perAzione(a("invia_bozza")))
    }

    @Test
    fun `invia_bozza non ruba la delega a chi ha preparato`() {
        val piano = Piano(listOf(Azione("componi", mapOf("tipo" to "mail")), Azione("invia_bozza")), "ok")
        assertEquals(P, Competenze.perPiano(piano))
    }

    @Test
    fun `piano vuoto o non capito resta a Jarvis`() {
        assertNull(Competenze.perPiano(Piano(emptyList(), "Sono le 18 e 25.")))
        assertNull(Competenze.perPiano(Piano.nonCapito()))
    }

    private val rubrica = listOf(
        Contatto("Marco Rossi", listOf("+39 333 1234567"), listOf("marco.rossi@example.com")),
        Contatto("Mamma", listOf("3330000002"), emptyList()),
    )
    private val ctx = Contesto(rubrica, ConfigPersonale(accountMail = "boss@example.com", whatsappMe = "393330000001"), ora = 18, minuti = 25)

    @Test
    fun `il cervello a regole e la mappa insieme`() {
        val c = CervelloRegole()
        fun chi(f: String) = Competenze.perPiano(c.interpreta(f, ctx))
        assertEquals(M, chi("apri Spotify"))
        assertEquals(R, chi("cerca su Google meteo Olbia"))
        assertEquals(M, chi("chiama Mamma"))
        assertEquals(null, chi("che ore sono"))
        assertEquals(null, chi("frase senza senso per la prova"))
    }

    // ------------------------------------------------------------ la bolla e la cronologia

    @Test
    fun `bolla JBoss freccia agente`() {
        val r = TestiBolla.Riga("Apro la mail…", "a Marco", TestiBolla.Tono.LAVORO, agente = P)
        assertEquals("JBoss → Postino: Apro la mail…", TestiBolla.titolo(r))
        val rc = r.copy(agente = R, titolo = "Cerco su Google…")
        assertEquals("JBoss → Ricercatore: Cerco su Google…", TestiBolla.titolo(rc))
    }

    @Test
    fun `bolla senza delega resta di Jarvis`() {
        assertEquals("Ti ascolto…", TestiBolla.titolo(TestiBolla.TI_ASCOLTO))
        assertEquals("Fatto", TestiBolla.titolo(TestiBolla.Riga("Fatto", "", TestiBolla.Tono.FATTO, agente = "sconosciuto")))
    }

    @Test
    fun `cronologia dice chi ha passato a chi`() {
        assertEquals("JBoss → Mani", Competenze.delega(M))
        assertEquals("JBoss → Ricercatore", Competenze.delega(R))
        assertEquals("JBoss", Competenze.delega(null))
        assertEquals("JBoss", Competenze.delega(""))
    }

    @Test
    fun `competenze in parole per tutti e cinque`() {
        assertEquals(CatalogoAgenti.ID.toSet(), Competenze.IN_PAROLE.map { it.first }.toSet())
    }

    @Test
    fun `Jarvis ha il suo avatar e l'icona dell'app, senza vettori finti`() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        assertTrue(File(res, "drawable-nodpi/avatar_jarvis_128.webp").length() in 1..20_000)
        assertTrue(File(res, "drawable-nodpi/avatar_jarvis_256.webp").length() in 1..40_000)
        assertTrue(!File(res, "drawable/avatar_jarvis.xml").exists())
        val icona = File(res, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(icona.contains("@mipmap/ic_launcher_foreground") && icona.contains("<monochrome"))
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            for (n in listOf("ic_launcher_foreground", "ic_launcher_monochrome", "ic_launcher", "ic_launcher_round")) {
                assertTrue("$d/$n", File(res, "mipmap-$d/$n.webp").length() > 0)
            }
        }
    }

    @Test
    fun `colonna agente_esecutore nella cronologia`() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val t = File(src, "com/jarvis/telefono/nucleo/Cronologia.kt").readText()
        assertTrue(t.contains("agente_esecutore TEXT NOT NULL DEFAULT ''"))
        assertTrue(t.contains("ALTER TABLE scambi ADD COLUMN agente_esecutore"))
    }
}
