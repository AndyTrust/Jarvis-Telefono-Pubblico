package com.jarvis.telefono.ui

import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.nucleo.Cronologia
import com.jarvis.telefono.nucleo.FiltroCronologia
import com.jarvis.telefono.voce.Stato
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.2.0, la Home a due titolari (Boss 07/10): chip degli agenti solo mentre lavorano, Postino
 * titolare senza chip, filtri della cronologia Tutto · Jarvis · Postino, chat per agente, niente
 * tasto flottante.
 */
class HomeTest {

    // ------------------------------------------------------------ chip

    @Test
    fun `chip di un lavoro sulla VPS, il locale ha la precedenza e il Postino non ha chip`() {
        assertEquals(CatalogoAgenti.RICERCATORE, ChipLavoro.agenteVps(Stato(), "ricercatore"))
        assertNull(ChipLavoro.agenteVps(Stato(), "postino"))
        assertNull(ChipLavoro.agenteVps(Stato(), "generico"))
        assertNull(ChipLavoro.agenteVps(Stato(agenteAlLavoro = CatalogoAgenti.MANI), "ricercatore"))
        assertTrue(ChipLavoro.testoVps("ricercatore", "Cerco le fonti").contains("sulla VPS · Cerco le fonti"))
        assertTrue(ChipLavoro.testoVps("social", "").endsWith("sulla VPS…"))
    }

    @Test
    fun `nessun chip a riposo`() {
        assertNull(ChipLavoro.agente(Stato()))
        assertNull(ChipLavoro.testo(Stato()))
    }

    @Test
    fun `chip solo mentre un agente lavora, con quello che fa`() {
        val s = Stato(agenteAlLavoro = CatalogoAgenti.MANI, lavoroInCorso = "Apro Spotify…")
        assertEquals(CatalogoAgenti.MANI, ChipLavoro.agente(s))
        assertEquals("Mani: Apro Spotify…", ChipLavoro.testo(s))
        assertEquals("Ricercatore: al lavoro…", ChipLavoro.testo(Stato(agenteAlLavoro = CatalogoAgenti.RICERCATORE)))
    }

    @Test
    fun `il chip sparisce quando il lavoro finisce`() {
        val dopo = Stato(agenteAlLavoro = CatalogoAgenti.MANI).copy(agenteAlLavoro = null, lavoroInCorso = null)
        assertNull(ChipLavoro.agente(dopo))
    }

    @Test
    fun `il Postino è titolare - niente chip, segno in corso`() {
        val s = Stato(agenteAlLavoro = CatalogoAgenti.POSTINO, lavoroInCorso = "Apro la mail…")
        assertNull(ChipLavoro.agente(s))
        assertTrue(ChipLavoro.postinoAlLavoro(s))
        assertFalse(ChipLavoro.postinoAlLavoro(Stato(agenteAlLavoro = CatalogoAgenti.MANI)))
    }

    @Test
    fun `un id sconosciuto non fa comparire chip`() {
        assertNull(ChipLavoro.agente(Stato(agenteAlLavoro = "fantasma")))
    }

    // ------------------------------------------------------------ filtri della cronologia

    private var n = 0L
    private fun boss(t: String, esito: String = "scritto") = Cronologia.Voce(++n, n * 1000, Cronologia.BOSS, t, esito = esito)
    private fun jarvis(t: String, agente: String = "", esito: String = "ok") =
        Cronologia.Voce(++n, n * 1000, Cronologia.JARVIS, t, "regole", esito, 100, agente)

    private val voci = listOf(
        boss("apri spotify"), jarvis("Ho aperto Spotify.", CatalogoAgenti.MANI),
        boss("che ore sono"), jarvis("Sono le 18 e 25."),
        boss("manda una mail a me stesso"), jarvis("Aspetto il tuo invia.", CatalogoAgenti.POSTINO),
        boss("cerca meteo Olbia"), jarvis("Ecco cosa trova Google.", CatalogoAgenti.RICERCATORE),
        boss("ciao", esito = "chat-postino"), jarvis("Non ho capito.", esito = "non capito"),
    )

    private fun testi(l: List<Cronologia.Voce>) = l.map { it.testo }

    @Test
    fun `i filtri della Home sono Tutto, JBoss, Postino, Errori`() {
        assertEquals(listOf("Tutto", "JBoss", "Postino", "Errori"), FiltroCronologia.FILTRI_HOME.map { it.second })
    }

    @Test
    fun `Errori tiene solo gli scambi andati male, e i conteggi tornano`() {
        val conErrore = voci + listOf(boss("apri la banca"), jarvis("Non ci riesco.", CatalogoAgenti.MANI, esito = "errore"))
        assertEquals(listOf("apri la banca", "Non ci riesco."), testi(FiltroCronologia.filtra(conErrore, FiltroCronologia.ERRORI)))
        val c = FiltroCronologia.conteggi(conErrore, FiltroCronologia.FILTRI_HOME.map { it.first })
        assertEquals(6, c[FiltroCronologia.TUTTI]); assertEquals(1, c[FiltroCronologia.ERRORI])
        assertEquals(2, c[FiltroCronologia.POSTINO]); assertEquals(4, c[FiltroCronologia.JARVIS])
    }

    @Test
    fun `Tutto tiene tutto`() = assertEquals(voci, FiltroCronologia.filtra(voci))

    @Test
    fun `Postino tiene la posta e quello scritto nella sua chat`() {
        assertEquals(
            listOf("manda una mail a me stesso", "Aspetto il tuo invia.", "ciao", "Non ho capito."),
            testi(FiltroCronologia.filtra(voci, FiltroCronologia.POSTINO)),
        )
    }

    @Test
    fun `Jarvis tiene tutto tranne la posta, anche le deleghe agli altri`() {
        assertEquals(
            listOf("apri spotify", "Ho aperto Spotify.", "che ore sono", "Sono le 18 e 25.", "cerca meteo Olbia", "Ecco cosa trova Google."),
            testi(FiltroCronologia.filtra(voci, FiltroCronologia.JARVIS)),
        )
    }

    @Test
    fun `chat di un agente - solo i suoi scambi`() {
        assertEquals(listOf("apri spotify", "Ho aperto Spotify."), testi(FiltroCronologia.filtra(voci, CatalogoAgenti.MANI)))
        assertTrue(FiltroCronologia.filtra(voci, CatalogoAgenti.SOCIAL).isEmpty())
    }

    @Test
    fun `ricerca nel testo, senza maiuscole, scambio intero`() {
        assertEquals(listOf("cerca meteo Olbia", "Ecco cosa trova Google."), testi(FiltroCronologia.filtra(voci, testo = "OLBIA")))
        assertEquals(listOf("che ore sono", "Sono le 18 e 25."), testi(FiltroCronologia.filtra(voci, FiltroCronologia.JARVIS, "18 e 25")))
        assertTrue(FiltroCronologia.filtra(voci, FiltroCronologia.POSTINO, "spotify").isEmpty())
    }

    @Test
    fun `scambi - ogni frase di Boss apre uno scambio`() {
        assertEquals(5, FiltroCronologia.scambi(voci).size)
        assertEquals(1, FiltroCronologia.scambi(listOf(jarvis("ciao"))).size)
    }

    // ------------------------------------------------------------ niente tasto flottante

    @Test
    fun `tasto flottante e permesso sopra le altre app tolti`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        assertFalse(File(main, "java/com/jarvis/telefono/FloatingWidget.kt").exists())
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("uses-permission android:name=\"android.permission.SYSTEM_ALERT_WINDOW\""))
        val imp = File(main, "res/layout/activity_impostazioni.xml")
        assertFalse(imp.exists() && imp.readText().contains("interruttore_widget"))
        assertFalse(File(main, "java/com/jarvis/telefono/JarvisService.kt").readText().contains("TYPE_APPLICATION_OVERLAY"))
    }
}

/** Ogni Activity del codice è dichiarata nel Manifest (il 07/10 la chat degli agenti mancava: crash al tocco). */
class ManifestTest {
    @Test
    fun `ogni Activity è nel Manifest`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val manifest = File(main, "AndroidManifest.xml").readText()
        val attivita = File(main, "java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("""class (\w+Activity)\b[^\n]*:\s*\w*Activity\(""").findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()
        assertTrue("trovate: $attivita", attivita.contains("AgenteChatActivity") && attivita.size >= 6)
        val mancano = attivita.filter { !manifest.contains(".$it\"") && !manifest.contains(".mani.$it\"") }
        assertTrue("non dichiarate: $mancano", mancano.isEmpty())
    }
}
