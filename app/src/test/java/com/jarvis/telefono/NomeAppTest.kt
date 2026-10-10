package com.jarvis.telefono

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Il nome visibile dell'app e del capo è «JBoss» (Boss, 2026-10-07). Restano «jarvis» l'applicationId
 * (com.jarvis.telefono: cambiarlo farebbe un'app nuova), i pacchetti Kotlin e la parola di
 * attivazione «Jarvis» come alias di «JBoss».
 */
class NomeAppTest {

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private fun stringhe(): Map<String, String> =
        Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(main, "res/values/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `il nome visibile è JBoss`() {
        val s = stringhe()
        assertEquals("JBoss", s["app_name"])
        assertEquals("JBoss", s["titolo"])
        assertTrue(File(main, "AndroidManifest.xml").readText().contains("android:label=\"@string/app_name\""))
        assertTrue(s.getValue("versione_app").startsWith("JBoss "))
        assertTrue(s.getValue("accessibility_service_description").contains("JBoss"))
    }

    @Test
    fun `nessuna stringa visibile dice ancora Jarvis Telefono`() {
        val vecchie = stringhe().filterValues { it.contains("Jarvis Telefono", ignoreCase = true) }
        assertTrue("stringhe: ${vecchie.keys}", vecchie.isEmpty())
        // Testi tra virgolette nel codice (notifiche, risposte dette, bolla): niente nome vecchio.
        val nelCodice = File(main, "java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> Regex("\"[^\"\\n]*Jarvis Telefono[^\"\\n]*\"").findAll(f.readText()).map { "${f.name}: ${it.value}" } }
            .toList()
        assertTrue("nel codice: $nelCodice", nelCodice.isEmpty())
        val etichette = Regex("android:label=\"([^\"@]+)\"").findAll(File(main, "AndroidManifest.xml").readText()).map { it.groupValues[1] }.toList()
        assertTrue("etichette: $etichette", etichette.none { it.contains("Jarvis Telefono") })
    }

    @Test
    fun `il capo è JBoss, le parole sono Hey Boss e Hey JBoss, applicationId invariato`() {
        // 0.6.0 (Boss 08/10: «solo Hey Boss e Hey JBoss»): «Jarvis» non è più una parola di attivazione.
        assertEquals("JBoss", stringhe()["nome_capo"])
        assertTrue(stringhe().getValue("parole_chiave").contains("«Hey Boss»"))
        assertTrue(stringhe().getValue("parole_chiave").contains("«Hey JBoss»"))
        assertTrue(!stringhe().getValue("parole_chiave").contains("«Jarvis»"))
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
        assertTrue(gradle.contains("applicationId = \"com.jarvis.telefono\""))
    }

    @Test
    fun `Jarvis compare solo fra virgolette o come Collegamento Jarvis`() {
        // 0.6.1: «Collegamento Jarvis» è il nome della sezione che collega JBoss al Jarvis della VPS e del Mac.
        val fuori = stringhe().filter { (_, v) ->
            Regex("Jarvis").findAll(v).any { m ->
                v.getOrNull(m.range.first - 1) != '«' && !v.substring(0, m.range.first).endsWith("Hey ") &&
                    !v.substring(0, m.range.first).endsWith("Collegamento ")
            }
        }
        // Info e licenze nomina il file «Jarvis» del pacchetto: è un nome di file, fra virgolette.
        assertTrue("stringhe: ${fuori.keys}", fuori.isEmpty())
    }
}
