package com.jarvis.telefono.ui

import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.bolla.TestiBolla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.2.0: il tema in un posto solo (chiaro e scuro con gli stessi nomi), durate delle animazioni
 * entro 150-300 ms e rispetto della scala di sistema, avatar presenti e leggeri, licenza MIT.
 */
class TemaTest {

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun nomi(f: File, tipo: String): Set<String> =
        Regex("<$tipo name=\"([a-z0-9_]+)\"").findAll(f.readText()).map { it.groupValues[1] }.toSet()

    private fun valori(f: File): Map<String, String> =
        Regex("<color name=\"([a-z0-9_]+)\">([^<]+)</color>").findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2].trim() }

    @Test
    fun `il modo scuro ridefinisce ogni colore vero del modo chiaro`() {
        val chiaro = valori(File(res, "values/colors.xml")).filterValues { it.startsWith("#") }.keys
        val scuro = nomi(File(res, "values-night/colors.xml"), "color")
        // L'icona dell'app ha il fondo crema fisso, come ogni icona del launcher.
        val mancano = chiaro - scuro - setOf("icona_fondo")
        assertTrue("mancano nel modo scuro: $mancano", mancano.isEmpty())
    }

    /** Contrasto WCAG fra due colori #RRGGBB. */
    private fun contrasto(a: String, b: String): Double {
        fun l(h: String): Double {
            val c = h.removePrefix("#").takeLast(6)
            val (r, g, bl) = listOf(0, 2, 4).map { c.substring(it, it + 2).toInt(16) / 255.0 }
            fun f(x: Double) = if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
            return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(bl)
        }
        val (x, y) = listOf(l(a), l(b)).sortedDescending()
        return (x + 0.05) / (y + 0.05)
    }

    @Test
    fun `testi in WCAG AA sulle schede, in chiaro e in scuro`() {
        for (cartella in listOf("values", "values-night")) {
            val c = valori(File(res, "values/colors.xml")) + valori(File(res, "$cartella/colors.xml"))
            val fondo = c.getValue("jarvis_rialzo")
            for (t in listOf("jarvis_testo", "jarvis_testo_tenue", "jarvis_accento", "spia_verde", "spia_rosso", "spia_giallo")) {
                val k = contrasto(c.getValue(t), fondo)
                assertTrue("$cartella $t su rialzo: $k", k >= 4.5)
            }
            val su = contrasto(c.getValue("jarvis_su_accento"), c.getValue("jarvis_accento"))
            assertTrue("$cartella testo sui pulsanti: $su", su >= 4.5)
            val tenue2 = contrasto(c.getValue("jarvis_testo_tenue"), c.getValue("jarvis_superficie_2"))
            assertTrue("$cartella tenue su superficie 2: $tenue2", tenue2 >= 4.5)
        }
    }

    @Test
    fun `durate delle animazioni fra 150 e 300 ms, pulsazione lenta`() {
        val t = File(res, "values/tokens.xml").readText()
        fun intero(n: String) = Regex("<integer name=\"$n\">(\\d+)</integer>").find(t)!!.groupValues[1].toInt()
        for (n in listOf("durata_breve", "durata_media", "durata_lunga")) assertTrue(n, intero(n) in 150..300)
        assertTrue(intero("durata_pulsazione") >= 600)
    }

    @Test
    fun `griglia di 8 dp negli spazi`() {
        val t = File(res, "values/tokens.xml").readText()
        val spazi = Regex("<dimen name=\"spazio_\\d\">(\\d+)dp</dimen>").findAll(t).map { it.groupValues[1].toInt() }.toList()
        assertEquals(4, spazi.size)
        assertTrue(spazi.all { it % 8 == 0 })
        assertTrue(Regex("<dimen name=\"tocco_minimo\">48dp</dimen>").containsMatchIn(t))
    }

    @Test
    fun `animazioni spente con scala zero, durate scalate`() {
        assertFalse(animazioniAttive(0f))
        assertTrue(animazioniAttive(0.5f))
        assertEquals(0L, durataEffettiva(220, 0f))
        assertEquals(110L, durataEffettiva(220, 0.5f))
        assertEquals(220L, durataEffettiva(220, 1f))
    }

    @Test
    fun `dodici avatar WebP, piccoli, solo Jarvis e i cinque agenti`() {
        val dir = File(res, "drawable-nodpi")
        val file = dir.listFiles()!!.filter { it.name.startsWith("avatar_") }.map { it.name }.toSet()
        val attesi = (CatalogoAgenti.ID + CatalogoAgenti.JARVIS).flatMap { listOf("avatar_${it}_128.webp", "avatar_${it}_256.webp") }.toSet()
        assertEquals(attesi, file)
        val totale = dir.listFiles()!!.filter { it.name.startsWith("avatar_") }.sumOf { it.length() }
        assertTrue("avatar: $totale byte", totale in 1..200_000)
        for (f in dir.listFiles()!!) {
            val b = f.readBytes()
            assertEquals(f.name, "RIFF", String(b, 0, 4)); assertEquals(f.name, "WEBP", String(b, 8, 4))
        }
    }

    @Test
    fun `la licenza MIT dei Dots è nell'app, intera`() {
        val mit = File(res, "raw/licenza_opendots.txt").readText()
        assertTrue(mit.contains("Copyright (c) Atai Barkai"))
        assertTrue(mit.contains("The above copyright notice and this permission notice shall be included"))
        val att = File(res, "raw/attribuzione_opendots.txt").readText()
        assertTrue(att.contains("OpenDots / CopilotKit"))
        assertTrue(File(res, "raw/attribuzione_jarvis.txt").readText().contains("Atai Barkai"))
    }

    @Test
    fun `la bolla porta l'agente solo se qualcuno lavora`() {
        assertNull(TestiBolla.TI_ASCOLTO.agente)
        assertNull(TestiBolla.perRisposta("Fatto.", false).agente)
        val r = TestiBolla.perStrumento("apri_app") { if (it == "nome") "WhatsApp" else "" }!!.copy(agente = "mani")
        assertEquals("mani", r.agente)
        assertEquals("Apro WhatsApp…", r.titolo)
    }
}
