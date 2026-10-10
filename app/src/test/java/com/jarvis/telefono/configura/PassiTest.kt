package com.jarvis.telefono.configura

import com.jarvis.telefono.nucleo.Cronologia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stato dei passi della procedura guidata, avvio «nuova» o «ripresa», prova guidata (0.4.0). */
class PassiTest {
    private val tutto = Fatti(true, true, true, true, true, true, true, 1, 2, true)

    @Test fun `cinque passi in ordine, numeri 1-5`() {
        assertEquals(listOf(1, 2, 3, 4, 5), Passo.values().map { it.numero })
        assertEquals(listOf("Benvenuto in JBoss", "Modelli vocali", "Cervello", "Account mail", "Prova guidata"), Passo.values().map { it.titolo })
    }

    @Test fun `benvenuto è verde con microfono, notifiche e accessibilità - rubrica e batteria non bloccano`() {
        assertTrue(Passi.fatto(Passo.BENVENUTO, Fatti(microfono = true, notifiche = true, accessibilita = true)))
        assertFalse(Passi.fatto(Passo.BENVENUTO, Fatti(microfono = true, notifiche = true, accessibilita = false, rubrica = true, batteria = true)))
    }

    @Test fun `cervello verde con la VPS o con una chiave nel telefono`() {
        assertTrue(Passi.fatto(Passo.CERVELLO, Fatti(vpsPronta = true)))
        assertTrue(Passi.fatto(Passo.CERVELLO, Fatti(cervelli = 1)))
        assertFalse(Passi.fatto(Passo.CERVELLO, Fatti()))
    }

    @Test fun `saltato si vede, ma fatto vince su saltato`() {
        assertEquals(StatoPasso.SALTATO, Passi.stato(Passo.POSTA, Fatti(), setOf(Passo.POSTA)))
        assertEquals(StatoPasso.FATTO, Passi.stato(Passo.POSTA, Fatti(caselle = 1), setOf(Passo.POSTA)))
        assertEquals(StatoPasso.DA_FARE, Passi.stato(Passo.POSTA, Fatti(), emptySet()))
    }

    @Test fun `primo da fare salta i verdi e i saltati`() {
        val f = Fatti(microfono = true, notifiche = true, accessibilita = true, vpsPronta = true)
        assertEquals(Passo.MODELLI, Passi.primoDaFare(f, emptySet()))
        assertEquals(Passo.POSTA, Passi.primoDaFare(f, setOf(Passo.MODELLI)))
        assertEquals(Passo.PROVA, Passi.primoDaFare(tutto, emptySet()))
    }

    @Test fun `parte da sola solo con cassaforte vuota e nessun passo mai completato`() {
        assertEquals(ModoAvvio.NUOVA, Passi.decidiAvvio(chiusa = false, cassaforteVuota = true, passiCompletati = 0, vociMigrate = 0))
        assertEquals(ModoAvvio.NESSUNA, Passi.decidiAvvio(false, true, 2, 0))
        assertEquals(ModoAvvio.NESSUNA, Passi.decidiAvvio(false, false, 0, 0))
    }

    @Test fun `dati migrati da config-boss - parte ripresa`() {
        assertEquals(ModoAvvio.RIPRESA, Passi.decidiAvvio(false, false, 0, 3))
        assertEquals(ModoAvvio.RIPRESA, Passi.decidiAvvio(false, false, 2, 1))
    }

    @Test fun `chiusa una volta - non riparte mai da sola`() {
        assertEquals(ModoAvvio.NESSUNA, Passi.decidiAvvio(true, true, 0, 0))
        assertEquals(ModoAvvio.NESSUNA, Passi.decidiAvvio(true, false, 0, 5))
    }

    @Test fun `sottotitolo della ripresa conta i passi verdi`() {
        val f = Fatti(microfono = true, notifiche = true, accessibilita = true, vpsPronta = true)
        assertEquals("Riprendiamo da dove eri: 2 passi su 5 sono già a posto.", Passi.sottotitolo(ModoAvvio.RIPRESA, f))
        assertTrue(Passi.sottotitolo(ModoAvvio.NUOVA, f).contains("saltabili"))
    }

    private var n = 0L
    private fun v(chi: String, t: String, quando: Long) = Cronologia.Voce(++n, quando, chi, t)

    @Test fun `prova guidata - ascoltata poi fatta, prima dell'apertura non conta`() {
        val voci = listOf(
            v(Cronologia.BOSS, "che ore sono", 50),
            v(Cronologia.JARVIS, "Sono le 21", 60),
            v(Cronologia.BOSS, "Hey Boss che ore sono", 1000),
            v(Cronologia.JARVIS, "Sono le 22:10", 1001),
            v(Cronologia.BOSS, "apri Spotify", 1002),
        )
        val f = ProvaGuidata.fasi(voci, dal = 500)
        assertEquals(ProvaGuidata.Fase.FATTA, f[ProvaGuidata.Prova.ORA])
        assertEquals(ProvaGuidata.Fase.ASCOLTATA, f[ProvaGuidata.Prova.APP])
        assertEquals(ProvaGuidata.Fase.ATTESA, f[ProvaGuidata.Prova.BOZZA])
        assertFalse(ProvaGuidata.tutteFatte(f))
        val tutte = voci + listOf(v(Cronologia.JARVIS, "Aperta", 1003), v(Cronologia.BOSS, "manda un WhatsApp a me con scritto prova", 1004), v(Cronologia.JARVIS, "Annullato", 1005))
        assertTrue(ProvaGuidata.tutteFatte(ProvaGuidata.fasi(tutte, 500)))
    }

    @Test fun `guida accessibilità - Android 14 con le due schermate, Samsung dice App installate`() {
        val s = TestiConfigura.guidaAccessibilita(34, samsung = true)
        assertTrue(s.contains("«App installate»"))
        assertTrue(s.contains("seconda schermata"))
        assertTrue(s.contains("Consenti impostazioni con restrizioni"))
        assertTrue(TestiConfigura.guidaAccessibilita(34, samsung = false).contains("«Servizi installati»"))
        assertFalse(TestiConfigura.guidaAccessibilita(30, samsung = false).contains("seconda schermata"))
    }
}
