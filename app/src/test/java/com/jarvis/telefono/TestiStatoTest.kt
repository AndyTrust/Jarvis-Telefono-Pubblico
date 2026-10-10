package com.jarvis.telefono

import com.jarvis.telefono.voce.Ascolto
import com.jarvis.telefono.voce.ModoVoce
import com.jarvis.telefono.voce.Modello
import com.jarvis.telefono.voce.Stato
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

class TestiStatoTest {

    private fun s(a: Ascolto, guasto: String? = null) = Stato(ascolto = a, guasto = guasto)

    @Test
    fun `i sei stati della spia hanno testo e colore giusti`() {
        assertEquals("spento", testoSpia(s(Ascolto.SPENTO)))
        assertEquals(Tinte.GRIGIO, coloreSpia(s(Ascolto.SPENTO)))
        assertEquals("ascolto", testoSpia(s(Ascolto.ASCOLTO)))
        assertEquals(Tinte.VERDE, coloreSpia(s(Ascolto.ASCOLTO)))
        assertEquals("ti ascolto", testoSpia(s(Ascolto.CATTURA)))
        assertEquals(Tinte.VERDE, coloreSpia(s(Ascolto.CATTURA)))
        assertEquals("penso", testoSpia(s(Ascolto.PENSO)))
        assertEquals(Tinte.GIALLO, coloreSpia(s(Ascolto.PENSO)))
        assertEquals("parlo", testoSpia(s(Ascolto.PARLO)))
        assertEquals(Tinte.GIALLO, coloreSpia(s(Ascolto.PARLO)))
        assertEquals("guasto: microfono occupato", testoSpia(s(Ascolto.GUASTO, "microfono occupato")))
        assertEquals("guasto", testoSpia(s(Ascolto.GUASTO)))
        assertEquals(Tinte.ROSSO, coloreSpia(s(Ascolto.GUASTO)))
    }

    @Test
    fun `lampeggia solo mentre cattura la frase`() {
        for (a in Ascolto.values()) assertEquals(a == Ascolto.CATTURA, spiaLampeggia(s(a)))
    }

    @Test
    fun `il tasto dice spegni se ascolta o se l'utente l'ha lasciato acceso`() {
        assertFalse(jarvisAcceso(presenzaJBoss(s(Ascolto.SPENTO), servizioVivo = false, attivoSalvato = false)))
        assertTrue(jarvisAcceso(presenzaJBoss(s(Ascolto.SPENTO), servizioVivo = false, attivoSalvato = true)))
        assertTrue(jarvisAcceso(presenzaJBoss(s(Ascolto.ASCOLTO), servizioVivo = true, attivoSalvato = false)))
    }

    // 09/10: Parla e la pagina Voce leggono la stessa cosa. Con la voce «Spento» (notifica o popup) prima la pagina
    // diceva «JBoss acceso» (il servizio era vivo) e Parla un'altra cosa.
    @Test
    fun `Parla e l'interruttore JBoss acceso non si contraddicono mai`() {
        for (a in Ascolto.values()) for (m in ModoVoce.values()) for (vivo in listOf(true, false)) for (salvato in listOf(true, false)) {
            val p = presenzaJBoss(Stato(ascolto = a, modoVoce = m), vivo, salvato)
            val parlaAscolta = avvisoParla(p) == null
            // Parla ascolta solo se l'interruttore è acceso, e mai con la voce in pausa o spenta.
            if (parlaAscolta) assertTrue("$a $m $vivo $salvato", jarvisAcceso(p))
            if (m != ModoVoce.ACCESO) assertFalse("$a $m $vivo $salvato", parlaAscolta)
            // Interruttore spento ⇒ Parla non ascolta e lo dice.
            if (!jarvisAcceso(p)) assertNotNull(avvisoParla(p))
        }
    }

    @Test
    fun `voce spenta dalla notifica, interruttore spento e Parla dice Riprendi`() {
        val p = presenzaJBoss(Stato(ascolto = Ascolto.ASCOLTO, modoVoce = ModoVoce.SPENTO), servizioVivo = true, attivoSalvato = true)
        assertEquals(PresenzaJBoss.VOCE_SPENTA, p)
        assertFalse(jarvisAcceso(p))
        assertTrue(avvisoParla(p)!!.contains("Riprendi"))
        val pausa = presenzaJBoss(Stato(ascolto = Ascolto.ASCOLTO, modoVoce = ModoVoce.PAUSA), servizioVivo = true, attivoSalvato = true)
        assertEquals(PresenzaJBoss.PAUSA, pausa)
        assertFalse(jarvisAcceso(pausa))
        val acceso = presenzaJBoss(Stato(ascolto = Ascolto.ASCOLTO, modoVoce = ModoVoce.ACCESO), servizioVivo = true, attivoSalvato = true)
        assertEquals(PresenzaJBoss.ACCESO, acceso)
        assertTrue(jarvisAcceso(acceso))
        assertNull(avvisoParla(acceso))
        // Servizio morto ma lasciato acceso: in avvio, Parla lo riaccende (prima la pagina diceva acceso e Parla spento).
        assertEquals(PresenzaJBoss.IN_AVVIO, presenzaJBoss(Stato(ascolto = Ascolto.ASCOLTO), servizioVivo = false, attivoSalvato = true))
    }

    @Test
    fun `batteria con la virgola italiana`() {
        assertEquals("1,4 %/ora", percentoOra(1.4f))
        assertEquals("1,4 %/ora (ultima ora)", testoBatteria(Stato(batteriaPerOra = 1.4f)))
        assertEquals("misura in corso", testoBatteria(Stato()))
    }

    @Test
    fun `megabyte dai byte veri dei modelli`() {
        val whisper = Modello.WHISPER_SMALL.file.sumOf { it.byte!! }
        assertEquals("375 MB", megabyte(whisper))
        assertEquals("40 MB", megabyte(Modello.ERES2NET.byteDaScaricare!!))
        val r = rigaWhisper(Stato(whisperPresente = true, whisperByte = whisper))
        assertEquals("scaricato 375 MB", r.testo)
        assertEquals(Tinte.VERDE, r.colore)
    }

    @Test
    fun `whisper mentre scarica e quando manca`() {
        assertEquals("scarico 42 %", rigaWhisper(Stato(scaricoPercento = 42)).testo)
        assertEquals(Tinte.GIALLO, rigaWhisper(Stato(scaricoPercento = 42)).colore)
        assertEquals(Tinte.ROSSO, rigaWhisper(Stato()).colore)
    }

    @Test
    fun `impronta imparata da 5 frasi`() {
        val r = rigaImpronta(Stato(improntaPronta = true, improntaOrigine = "imparata da 5 frasi"))
        assertEquals("pronta (imparata da 5 frasi)", r.testo)
        assertEquals(Tinte.VERDE, r.colore)
        assertEquals(Tinte.GRIGIO, rigaImpronta(Stato()).colore)
    }

    @Test
    fun `glossario e fine frase`() {
        assertEquals("148 termini, 19:40", rigaGlossario(Stato(glossarioTermini = 148, glossarioAggiornato = "19:40")).testo)
        assertEquals(Tinte.GRIGIO, rigaGlossario(Stato(glossarioTermini = 43)).colore)
        assertEquals("Frase chiusa dopo 1,5 s di silenzio (2,5 s dopo 4 s di parlato)", testoFineFrase(Stato()))
        assertEquals("Frase chiusa dopo 3 s di silenzio", testoFineFrase(Stato(fineParlatoS = 3.0, fineParlatoLungoS = 3.0)))
        assertEquals("2,5", secondi(2.5))
        assertEquals("Tu: —", testoUltimoUtente(Stato()))
        assertEquals("JBoss: due mail nuove", testoUltimoJarvis(Stato(ultimoJarvis = "due mail nuove")))
    }

    @Test
    fun `percentuale e ora`() {
        assertEquals(50, percentuale(50, 100))
        assertEquals(100, percentuale(200, 100))
        assertNull(percentuale(5, -1))
        val roma = TimeZone.getTimeZone("Europe/Rome")
        val t = 1_790_444_400_000L // 2026-09-26 19:40 a Roma
        assertEquals("19:40", quando(t, t + 60_000, roma))
        assertEquals("26/09 19:40", quando(t, t + 86_400_000L, roma))
    }


    /** La palette sta in due posti (colors.xml per i layout, Tinte per il codice): non devono separarsi. */
    @Test
    fun `le tinte del codice sono quelle di colors xml`() {
        val xml = listOf(File("src/main/res/values/colors.xml"), File("app/src/main/res/values/colors.xml"))
            .first { it.isFile }.readText()
        fun colore(nome: String): Int {
            val hex = Regex("""<color name="$nome">#([0-9A-Fa-f]{6})</color>""").find(xml)!!.groupValues[1]
            return (0xFF000000L or hex.toLong(16)).toInt()
        }
        assertEquals(colore("jarvis_fondo"), Tinte.FONDO)
        assertEquals(colore("jarvis_rialzo"), Tinte.RIALZO)
        assertEquals(colore("jarvis_testo"), Tinte.TESTO)
        assertEquals(colore("jarvis_accento"), Tinte.ACCENTO)
        assertEquals(colore("spia_verde"), Tinte.VERDE)
        assertEquals(colore("spia_giallo"), Tinte.GIALLO)
        assertEquals(colore("spia_rosso"), Tinte.ROSSO)
        assertEquals(colore("spia_grigio"), Tinte.GRIGIO)
    }
}
