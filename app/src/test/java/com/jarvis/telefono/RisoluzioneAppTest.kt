package com.jarvis.telefono

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quale app apre Jarvis quando sente un nome.
 *
 * Regola per tutte le marche: «posta», «mail» ed «email» non sono legate a nessun produttore.
 * Si apre l'app di posta predefinita del telefono (vedi CompatibilitaTest); Samsung Email solo se
 * la si chiama per nome.
 */
class RisoluzioneAppTest {

    private val samsung = "com.samsung.android.email.provider"

    @Test
    fun `posta mail ed email non sono legate a una marca`() {
        for (n in listOf("posta", "mail", "email", "e-mail", "  Posta ", "MAIL")) {
            assertNull(n, RisoluzioneApp.preferita(n))
        }
    }

    @Test
    fun `samsung email per nome apre Samsung Email`() {
        assertEquals(samsung, RisoluzioneApp.preferita("samsung email"))
        assertEquals(samsung, RisoluzioneApp.preferita(" Samsung Email "))
    }

    @Test
    fun `gmail resta un nome suo e non viene dirottato`() {
        // Chi chiede Gmail per nome deve ottenere Gmail: la preferenza non lo
        // tocca, e la ricerca prosegue per etichetta.
        assertNull(RisoluzioneApp.preferita("gmail"))
        assertEquals(setOf("gmail"), RisoluzioneApp.alias("gmail"))
    }

    @Test
    fun `gmail non sta nel gruppo della posta`() {
        // Se ci tornasse dentro, «posta» potrebbe ripescare Gmail dall'etichetta.
        val gruppoPosta = RisoluzioneApp.alias("posta")
        assertTrue("gmail è rientrato nel gruppo: $gruppoPosta", "gmail" !in gruppoPosta)
        assertEquals(setOf("mail", "email", "posta", "e-mail"), gruppoPosta)
    }

    @Test
    fun `un'app senza preferenza si cerca per nome, non si dirotta`() {
        assertNull(RisoluzioneApp.preferita("whatsapp"))
        assertEquals(setOf("whatsapp"), RisoluzioneApp.alias("whatsapp"))
    }

    @Test
    fun `gli altri gruppi restano quelli che erano`() {
        assertTrue("sms" in RisoluzioneApp.alias("messaggi"))
        assertTrue("agenda" in RisoluzioneApp.alias("calendario"))
        assertTrue("dialer" in RisoluzioneApp.alias("telefono"))
    }
}
