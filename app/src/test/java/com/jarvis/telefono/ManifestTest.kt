package com.jarvis.telefono

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Errore noto (07/10): un'Activity senza la sua riga nel Manifest fa chiudere l'app al primo tocco.
 * Ogni classe che estende un'Activity deve stare nel Manifest, e ogni riga del Manifest deve avere la sua classe.
 * Più i paletti delle schermate di configurazione (0.4.0): FLAG_SECURE dove ci sono segreti, password senza
 * apprendimento della tastiera ma con il gestore di password.
 */
class ManifestTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val manifest = File(main, "AndroidManifest.xml").readText()
    private val sorgenti = File(main, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    private fun attivitaNelCodice(): Map<String, String> {
        val base = setOf("AppCompatActivity", "Activity", "ComponentActivity", "FragmentActivity", "BaseConfigura")
        val r = HashMap<String, String>()
        for (f in sorgenti) {
            val t = f.readText()
            val pkg = Regex("^package ([\\w.]+)", RegexOption.MULTILINE).find(t)?.groupValues?.get(1) ?: continue
            Regex("^(?:open |abstract )?class (\\w+)[^{\\n]*?:\\s*(\\w+)\\(", RegexOption.MULTILINE).findAll(t).forEach { m ->
                if (m.groupValues[2] in base && !m.value.contains("abstract ")) r["$pkg.${m.groupValues[1]}"] = f.name
            }
        }
        return r
    }

    private fun attivitaNelManifest(): List<String> =
        Regex("<activity[^>]*?android:name=\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL).findAll(manifest)
            .map { it.groupValues[1] }.map { if (it.startsWith(".")) "com.jarvis.telefono$it" else it }.toList()

    @Test fun `ogni Activity del codice ha la sua riga nel Manifest`() {
        val codice = attivitaNelCodice()
        assertTrue("trovate poche Activity: ${codice.keys}", codice.size >= 10)
        val mancanti = codice.keys - attivitaNelManifest().toSet()
        assertTrue("Activity senza riga nel Manifest: $mancanti", mancanti.isEmpty())
    }

    @Test fun `ogni riga del Manifest ha la sua classe`() {
        val nomi = sorgenti.flatMap { f ->
            val t = f.readText()
            val pkg = Regex("^package ([\\w.]+)", RegexOption.MULTILINE).find(t)?.groupValues?.get(1).orEmpty()
            Regex("^(?:open |abstract )?class (\\w+)", RegexOption.MULTILINE).findAll(t).map { "$pkg.${it.groupValues[1]}" }.toList()
        }.toSet()
        val orfane = attivitaNelManifest().filter { it !in nomi }
        assertTrue("righe del Manifest senza classe: $orfane", orfane.isEmpty())
    }

    @Test fun `le schermate di configurazione ci sono e non sono esportate`() {
        for (n in listOf("ConfigurazioneActivity", "SchedaActivity", "CasellaActivity")) {
            val blocco = Regex("<activity[^>]*?\\.configura\\.$n\"[^>]*>", RegexOption.DOT_MATCHES_ALL).find(manifest)?.value
            assertTrue("$n manca", blocco != null)
            assertTrue("$n esportata", blocco!!.contains("android:exported=\"false\""))
            assertTrue("$n senza adjustResize", blocco.contains("adjustResize"))
        }
    }

    @Test fun `i pannelli con segreti chiedono FLAG_SECURE`() {
        val dir = File(main, "java/com/jarvis/telefono/configura")
        for (n in listOf("PannelloCervello", "PannelloVps", "PannelloSicurezza")) {
            assertTrue("$n senza conSegreti", File(dir, "$n.kt").readText().contains("override val conSegreti = true"))
        }
        assertTrue(File(dir, "CasellaActivity.kt").readText().contains("override val conSegreti = true"))
        assertTrue(File(dir, "BaseConfigura.kt").readText().contains("Sblocco.proteggiSchermo"))
    }

    @Test fun `password - niente apprendimento della tastiera, sì al gestore di password`() {
        val m = File(main, "java/com/jarvis/telefono/configura/Mattoni.kt").readText()
        assertTrue(m.contains("IME_FLAG_NO_PERSONALIZED_LEARNING"))
        assertTrue(m.contains("setAutofillHints"))
        val casella = File(main, "java/com/jarvis/telefono/configura/CasellaActivity.kt").readText()
        assertTrue(casella.contains("AUTOFILL_HINT_PASSWORD"))
    }

    @Test fun `chi usa BiometricPrompt ha USE_BIOMETRIC (senza, crash al primo «Mostra»)`() {
        val usa = sorgenti.any { it.readText().contains("BiometricPrompt") }
        assertTrue(!usa || manifest.contains("android.permission.USE_BIOMETRIC"))
    }

    @Test fun `il lettore QR non chiede la fotocamera`() {
        assertTrue(!manifest.contains("android.permission.CAMERA"))
    }
}
