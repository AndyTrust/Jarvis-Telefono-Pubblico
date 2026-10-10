package com.jarvis.telefono.agenti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 09/10: JBoss aveva due volti (giacca in Home e chat, felpa e medaglione nella lista Agenti e nelle risposte).
class AvatarJBossTest {

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun `JBoss usa ovunque l'avatar della Home`() {
        val avatar = File(main, "java/com/jarvis/telefono/agenti/Avatar.kt").readText()
        val riga = avatar.lines().single { it.trim().startsWith("CatalogoAgenti.JARVIS ->") }
        assertTrue(riga, riga.contains("R.drawable.jboss_grande_256") && !riga.contains("avatar_jarvis"))
        val home = File(main, "java/com/jarvis/telefono/MainActivity.kt").readText()
        assertTrue(home.contains("avatarJarvis.setImageResource(R.drawable.jboss_grande_256)"))
        // avatar_jarvis_* resta solo a Jarvis della webapp: tessera in Home, Collegamento, icona della webapp.
        val usi = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { it.readText().contains("avatar_jarvis_") }.map { it.name }.toSortedSet()
        assertEquals(sortedSetOf("CollegamentoJarvis.kt", "MainActivity.kt", "impostazioni_card.xml"), usi)
    }
}
