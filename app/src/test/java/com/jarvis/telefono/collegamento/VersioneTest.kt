package com.jarvis.telefono.collegamento

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 09/10: Impostazioni › Collegamento Jarvis diceva «Versione 0.6.5-integra» e «sei alla 0.6.5-integra».
class VersioneTest {

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun `un esito salvato da un'altra versione non si mostra`() {
        assertTrue(AggiornamentiJBoss.esitoDiQuestaVersione("0.7.1", "0.7.1"))
        assertFalse(AggiornamentiJBoss.esitoDiQuestaVersione("0.6.5-integra", "0.7.1"))
        assertFalse(AggiornamentiJBoss.esitoDiQuestaVersione(null, "0.7.1"))
    }

    @Test
    fun `nessun numero di versione scritto a mano nel codice e nelle risorse`() {
        val vietati = Regex("""0\.6\.5|-integra\b""")
        val trovati = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, r -> if (vietati.containsMatchIn(r)) "${f.name}:${i + 1}" else null } }
            .toList()
        assertTrue("versioni scritte a mano: $trovati", trovati.isEmpty())
    }

    @Test
    fun `la pagina Collegamento legge la versione compilata, non quella del pacchetto installato`() {
        val ui = File(main, "java/com/jarvis/telefono/collegamento/CollegamentoUi.kt").readText()
        assertTrue(ui.contains("AggiornamentiJBoss.versioneAttuale()"))
        assertFalse(ui.contains("getPackageInfo"))
        val agg = File(main, "java/com/jarvis/telefono/collegamento/AggiornamentiJBoss.kt").readText()
        assertTrue(agg.contains("BuildConfig.VERSION_NAME"))
    }
}
