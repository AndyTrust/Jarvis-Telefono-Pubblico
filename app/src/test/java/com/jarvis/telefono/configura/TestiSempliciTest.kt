package com.jarvis.telefono.configura

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.6.1 (audit 08/10, 11 testi tecnici): quello che Boss legge sullo schermo è in italiano semplice. Niente nomi di
 * script, file, comandi o sigle tecniche nelle stringhe dell'app e nei testi delle schermate di configurazione.
 */
class TestiSempliciTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private val VIETATE = Regex(
        """(\.sh\b|\.py\b|config-boss|\bjson\b|\badb\b|sk-ant-oat|setup-token|env_sync|whisper|eres2net|logcat|\.env\b|LOCALE ·|ESTERNO ·|modulo VPS non attivo)""",
        RegexOption.IGNORE_CASE,
    )

    private fun stringheXml(): List<String> =
        File(main, "res/values").listFiles { f -> f.extension == "xml" }!!.flatMap { f ->
            Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(f.readText())
                .map { "${f.name}/${it.groupValues[1]}: ${it.groupValues[2]}" }.toList()
        }

    /** I testi a schermo nel codice: argomenti di Mattoni e assegnazioni «text = "…"». */
    private fun testiNelCodice(): List<String> {
        val dove = listOf(
            "java/com/jarvis/telefono/configura", "java/com/jarvis/telefono/AgentiActivity.kt", "java/com/jarvis/telefono/AgenteChatActivity.kt",
            "java/com/jarvis/telefono/ImpostazioniActivity.kt", "java/com/jarvis/telefono/MainActivity.kt",
            "java/com/jarvis/telefono/vps/LavoriActivity.kt", "java/com/jarvis/telefono/vps/TerminaleVpsActivity.kt",
            "java/com/jarvis/telefono/cassaforte/TestiGuida.kt", "java/com/jarvis/telefono/agenti/Agenti.kt",
        )
        val schema = Regex("""(Mattoni\.(nota|testo|bottone|campo|sezione|titolo)\(\w+,\s*|Riga\(\w+,\s*|text\s*=\s*|const val \w+\s*=\s*|-> )"([^"\n]*)"""")
        return dove.map { File(main, it) }.flatMap { d -> if (d.isDirectory) d.walkTopDown().filter { it.extension == "kt" }.toList() else listOf(d) }
            .flatMap { f -> schema.findAll(f.readText()).map { "${f.name}: ${it.groupValues[3]}" }.toList() } +
            // i testi lunghi su più righe (TestiGuida, TestiConfigura)
            listOf("cassaforte/TestiGuida.kt", "configura/TestiConfigura.kt").map { File(main, "java/com/jarvis/telefono/$it").readText() }
                .flatMap { t -> Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).findAll(t).map { it.groupValues[1] }.toList() }
    }

    @Test
    fun `nessuna sigla tecnica nelle stringhe dell'app`() {
        val brutte = stringheXml().filter { VIETATE.containsMatchIn(it.substringAfter(": ")) }
        assertTrue("da riscrivere: $brutte", brutte.isEmpty())
    }

    @Test
    fun `nessuna sigla tecnica nei testi delle schermate`() {
        val testi = testiNelCodice()
        assertTrue("trovati pochi testi: ${testi.size}", testi.size > 100)
        // Le espressioni ${'$'}{…} sono codice (nomi di funzioni), non testo: si tolgono prima di guardare.
        val brutte = testi.filter { VIETATE.containsMatchIn(it.substringAfter(": ").replace(Regex("\\$\\{[^}]*\\}"), "")) }
        assertTrue("da riscrivere: $brutte", brutte.isEmpty())
    }
}
