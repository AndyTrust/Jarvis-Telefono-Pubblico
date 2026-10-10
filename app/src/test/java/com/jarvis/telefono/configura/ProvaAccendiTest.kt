package com.jarvis.telefono.configura

import com.jarvis.telefono.voce.ModoVoce
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prova guidata, riga «JBoss in ascolto»: verde solo se JBoss ascolta davvero. */
class ProvaAccendiTest {
    @Test fun `acceso con microfono e voce accesa - verde`() {
        assertTrue(ProvaGuidata.ascoltoAcceso(attivo = true, microfono = true, modo = ModoVoce.ACCESO))
    }

    @Test fun `voce in pausa o spenta dalla notifica - non verde anche col servizio acceso`() {
        assertFalse(ProvaGuidata.ascoltoAcceso(attivo = true, microfono = true, modo = ModoVoce.PAUSA))
        assertFalse(ProvaGuidata.ascoltoAcceso(attivo = true, microfono = true, modo = ModoVoce.SPENTO))
    }

    @Test fun `senza microfono o servizio spento - non verde`() {
        assertFalse(ProvaGuidata.ascoltoAcceso(attivo = true, microfono = false, modo = ModoVoce.ACCESO))
        assertFalse(ProvaGuidata.ascoltoAcceso(attivo = false, microfono = true, modo = ModoVoce.ACCESO))
    }
}
