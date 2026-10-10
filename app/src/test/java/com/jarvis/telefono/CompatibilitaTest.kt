package com.jarvis.telefono

import com.jarvis.telefono.configura.RegoleVoce
import com.jarvis.telefono.configura.TestiConfigura
import com.jarvis.telefono.configura.TestiConfigura.Marca
import com.jarvis.telefono.mani.CatalogoApp
import com.jarvis.telefono.mani.CatalogoApp.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'app deve funzionare su ogni Android, non solo su Samsung. Qui si provano le scelte che prima
 * dipendevano dalla marca: la posta, i testi di aiuto, l'elenco delle app.
 */
class CompatibilitaTest {

    private val gmail = App("Gmail", "com.google.android.gm")
    private val samsungMail = App("E-mail", "com.samsung.android.email.provider")
    private val outlook = App("Outlook", "com.microsoft.office.outlook")
    private val aosp = App("Email", "com.android.email")

    @Test fun `Pixel con solo Gmail - la posta e Gmail`() {
        val pixel = listOf(gmail, App("Telefono", "com.google.android.dialer"))
        assertEquals(gmail, CatalogoApp.cerca("posta", pixel).first())
        assertEquals(gmail, CatalogoApp.cerca("mail", pixel, postaPredefinita = "com.google.android.gm").first())
    }

    @Test fun `Samsung con Gmail predefinita - vince la predefinita, non Samsung Email`() {
        val s = listOf(samsungMail, gmail)
        assertEquals(gmail, CatalogoApp.cerca("posta", s, postaPredefinita = gmail.pacchetto).first())
        assertEquals(samsungMail, CatalogoApp.cerca("posta", s, postaPredefinita = samsungMail.pacchetto).first())
    }

    @Test fun `senza predefinita nessuna marca ha la precedenza - Gmail prima di Samsung Email`() {
        assertEquals(gmail, CatalogoApp.cerca("email", listOf(samsungMail, gmail)).first())
    }

    @Test fun `Xiaomi o telefono senza Google - la posta di sistema o Outlook`() {
        assertEquals(aosp, CatalogoApp.cerca("posta", listOf(aosp)).first())
        assertEquals(outlook, CatalogoApp.cerca("mail", listOf(aosp, outlook)).first())
    }

    @Test fun `una predefinita non installata non rompe niente`() {
        assertEquals(gmail, CatalogoApp.cerca("posta", listOf(gmail), postaPredefinita = "pacchetto.che.non.esiste").first())
    }

    @Test fun `Samsung Email si apre per nome solo se c e`() {
        assertEquals(samsungMail, CatalogoApp.cerca("samsung email", listOf(gmail, samsungMail)).first())
        assertTrue(CatalogoApp.cerca("samsung email", listOf(gmail)).none { it.pacchetto == samsungMail.pacchetto })
    }

    @Test fun `la scelta della posta nelle impostazioni parte da quella del telefono`() {
        assertEquals("Quella del telefono", RegoleVoce.appPosta(""))
        assertEquals("predefinita", RegoleVoce.APP_POSTA.first().first)
        assertEquals("Samsung Email", RegoleVoce.appPosta("samsung"))
        assertEquals("Gmail", RegoleVoce.appPosta("gmail"))
    }

    @Test fun `la marca si riconosce da produttore e marchio`() {
        assertEquals(Marca.SAMSUNG, Marca.da("samsung", "samsung"))
        assertEquals(Marca.PIXEL, Marca.da("Google", "google"))
        assertEquals(Marca.XIAOMI, Marca.da("Xiaomi", "Redmi"))
        assertEquals(Marca.XIAOMI, Marca.da("Xiaomi", "POCO"))
        assertEquals(Marca.ONEPLUS, Marca.da("OnePlus", "OnePlus"))
        assertEquals(Marca.MOTOROLA, Marca.da("motorola", "motorola"))
        assertEquals(Marca.HUAWEI, Marca.da("HUAWEI", "HONOR"))
        assertEquals(Marca.OPPO, Marca.da("realme", "realme"))
        assertEquals(Marca.ALTRA, Marca.da("unknown", "generic"))
        assertEquals(Marca.ALTRA, Marca.da(null, null))
    }

    @Test fun `ogni marca ha la sua guida batteria con il link`() {
        for (m in Marca.entries) {
            val g = TestiConfigura.guidaBatteria(m)
            assertTrue(m.name, g.startsWith("1. Tocca «Escludi ora»"))
            assertTrue(m.name, g.contains("https://dontkillmyapp.com"))
            assertFalse(m.name, Regex("\\bBoss\\b").containsMatchIn(g))
        }
        assertTrue(TestiConfigura.guidaBatteria(Marca.XIAOMI).contains("Avvio automatico"))
        assertTrue(TestiConfigura.guidaBatteria(Marca.SAMSUNG).contains("App mai in sospensione"))
        assertTrue(TestiConfigura.guidaBatteria(Marca.HUAWEI).contains("servizi Google"))
    }

    @Test fun `la guida accessibilita usa le parole della marca`() {
        assertTrue(TestiConfigura.guidaAccessibilita(34, Marca.SAMSUNG).contains("App installate"))
        assertTrue(TestiConfigura.guidaAccessibilita(34, Marca.PIXEL).contains("Servizi installati"))
        assertTrue(TestiConfigura.guidaAccessibilita(28, Marca.XIAOMI).contains("App scaricate"))
        // La firma vecchia resta uguale.
        assertEquals(TestiConfigura.guidaAccessibilita(34, Marca.SAMSUNG), TestiConfigura.guidaAccessibilita(34, true))
    }
}

class ApriPostaSenzaVpsTest {
    @Test fun `apri la posta e riconosciuta come apertura dell app`() {
        for (f in listOf("apri la posta", "apri le mail", "apri email", "Apri la mia posta", "JBoss apri la posta", "apri l'app della posta")) {
            org.junit.Assert.assertTrue(f, com.jarvis.telefono.postino.ComandiPostaJBoss.apreLaPosta(f))
        }
        for (f in listOf("leggi le mail", "apri la mail 5", "ci sono mail nuove", "apri whatsapp")) {
            org.junit.Assert.assertFalse(f, com.jarvis.telefono.postino.ComandiPostaJBoss.apreLaPosta(f))
        }
    }
}
