package com.jarvis.telefono.agenti

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.0: ordine dei fissati, autonomia bloccata di default, stati degli agenti. */
class AgentiTest {

    private val base = CatalogoAgenti.PREDEFINITI
    /** Una squadra con tre fissati scelti da Boss: Postino, Ricercatore, Mani. */
    private val tre = listOf("postino", "ricercatore", "mani").fold(base) { l, id -> Fissati.fissa(l, id) }
    private fun ids(l: List<Agente>) = l.map { it.id }

    // ------------------------------------------------------------ catalogo

    @Test
    fun `i cinque agenti scelti da Boss, nell'ordine della schermata`() {
        assertEquals(listOf("postino", "ricercatore", "social", "mani", "scrittore"), CatalogoAgenti.ID)
        assertEquals(5, base.map { it.id }.toSet().size)
    }

    @Test
    fun `di partenza nessun agente fissato (Home pulita)`() {
        assertTrue(Fissati.fissati(base).isEmpty())
        assertEquals(CatalogoAgenti.ID, ids(Fissati.altri(base)))
    }

    @Test
    fun `dove gira come da tabella approvata`() {
        val d = base.associate { it.id to it.dove }
        assertEquals(Dove.ESTERNO, d["postino"]); assertEquals(Dove.ESTERNO, d["ricercatore"]); assertEquals(Dove.ESTERNO, d["social"])
        assertEquals(Dove.LOCALE, d["mani"]); assertEquals(Dove.LOCALE, d["scrittore"])
    }

    @Test
    fun `ogni agente ha missione in una riga e istruzioni di sistema`() {
        for (a in base) {
            assertTrue(a.id, a.missione.isNotBlank() && !a.missione.contains('\n') && a.missione.length <= 40)
            assertTrue(a.id, a.istruzioni.startsWith("Sei ") && a.istruzioni.contains("Non inventare"))
        }
    }

    @Test
    fun `nessun dato personale nelle istruzioni`() {
        val tutto = base.joinToString("\n") { it.istruzioni + it.missione }
        assertFalse(Regex("@[a-z0-9.-]+\\.[a-z]{2,}").containsMatchIn(tutto))
        assertFalse(Regex("\\+?\\d[\\d ]{8,}").containsMatchIn(tutto))
    }

    // ------------------------------------------------------------ autonomia

    @Test
    fun `autonomia di default sempre chiedi prima`() {
        for (a in base) assertEquals(a.id, Autonomia.CHIEDI_PRIMA, a.autonomia)
    }

    @Test
    fun `vai da solo si sceglie solo per il Ricercatore (default sicuro 0_6_1)`() {
        val bloccati = base.filter { it.autonomiaBloccata }.map { it.id }.toSet()
        assertEquals(setOf("postino", "social", "mani", "scrittore"), bloccati)
        assertFalse(Autonomie.puoAndareDaSolo(CatalogoAgenti.predefinito("mani")!!))
        assertFalse(Autonomie.puoAndareDaSolo(CatalogoAgenti.predefinito("postino")!!))
        assertTrue(Autonomie.puoAndareDaSolo(CatalogoAgenti.predefinito("ricercatore")!!))
        assertEquals(setOf("ricercatore"), Autonomie.PUO_ANDARE_DA_SOLO)
    }

    @Test
    fun `vai da solo su un agente bloccato non passa`() {
        val mani = CatalogoAgenti.predefinito("mani")!!
        assertEquals(Autonomia.CHIEDI_PRIMA, Autonomie.cambia(mani, Autonomia.DA_SOLO).autonomia)
        val social = CatalogoAgenti.predefinito("social")!!
        assertEquals(Autonomia.CHIEDI_PRIMA, Autonomie.cambia(social, Autonomia.DA_SOLO).autonomia)
    }

    @Test
    fun `vai da solo su un agente libero passa e torna indietro`() {
        val p = CatalogoAgenti.predefinito("ricercatore")!!
        val solo = Autonomie.cambia(p, Autonomia.DA_SOLO)
        assertEquals(Autonomia.DA_SOLO, solo.autonomia)
        assertEquals(Autonomia.CHIEDI_PRIMA, Autonomie.cambia(solo, Autonomia.CHIEDI_PRIMA).autonomia)
    }

    @Test
    fun `gli invii chiedono sempre conferma`() {
        assertTrue(Autonomie.INVII_SEMPRE_CON_CONFERMA)
    }

    // ------------------------------------------------------------ 0.6.1: l'autonomia decide davvero

    @Test
    fun `Ricercatore da solo - le letture passano da sole`() {
        val r = "ricercatore"; val solo = Autonomia.DA_SOLO
        assertTrue(Autonomie.confermaDaSola(r, solo, "altro", "strumento mcp__brave__web_search"))
        assertTrue(Autonomie.confermaDaSola(r, solo, "altro", "strumento mcp__fonti__fetch_page"))
        assertTrue(Autonomie.confermaDaSola(r, solo, "altro", "strumento WebFetch"))
    }

    @Test
    fun `Ricercatore da solo - invii, scritture e strumenti che scrivono tornano a Boss`() {
        val r = "ricercatore"; val solo = Autonomia.DA_SOLO
        for (azione in listOf("invio", "scrittura", "cancellazione", "servizio", "remoto", "rete", "installazione")) {
            assertFalse(azione, Autonomie.confermaDaSola(r, solo, azione, "strumento WebFetch"))
        }
        assertFalse(Autonomie.confermaDaSola(r, solo, "altro", "strumento mcp__gmail__send_message"))
        assertFalse(Autonomie.confermaDaSola(r, solo, "altro", "strumento mcp__drive__get_and_delete"))
        assertFalse(Autonomie.confermaDaSola(r, solo, "altro", "scrittura di un file sulla VPS"))
        assertFalse(Autonomie.confermaDaSola(r, solo, "altro", ""))
    }

    @Test
    fun `chiedi prima, altri agenti o agente ignoto - sempre a Boss`() {
        assertFalse(Autonomie.confermaDaSola("ricercatore", Autonomia.CHIEDI_PRIMA, "altro", "strumento WebSearch"))
        assertFalse(Autonomie.confermaDaSola("ricercatore", null, "altro", "strumento WebSearch"))
        for (id in listOf("postino", "social", "mani", "scrittore", "generico")) {
            assertFalse(id, Autonomie.confermaDaSola(id, Autonomia.DA_SOLO, "altro", "strumento WebSearch"))
        }
        assertFalse(Autonomie.confermaDaSola(null, Autonomia.DA_SOLO, "altro", "strumento WebSearch"))
    }

    // ------------------------------------------------------------ fissati e ordine

    @Test
    fun `fissa mette in fondo alla striscia`() {
        val l = Fissati.fissa(tre, "social")
        assertEquals(listOf("postino", "ricercatore", "mani", "social"), ids(Fissati.fissati(l)))
        assertEquals(listOf("scrittore"), ids(Fissati.altri(l)))
    }

    @Test
    fun `fissare due volte non cambia niente`() {
        val l = Fissati.fissa(Fissati.fissa(tre, "social"), "social")
        assertEquals(listOf("postino", "ricercatore", "mani", "social"), ids(Fissati.fissati(l)))
    }

    @Test
    fun `togli lascia l'ordine degli altri senza buchi`() {
        val l = Fissati.togli(tre, "ricercatore")
        val f = Fissati.fissati(l)
        assertEquals(listOf("postino", "mani"), ids(f))
        assertEquals(listOf(0, 1), f.map { it.ordine })
        assertEquals(listOf("ricercatore", "social", "scrittore"), ids(Fissati.altri(l)))
    }

    @Test
    fun `sposta a sinistra e a destra`() {
        val sx = Fissati.sposta(tre, "mani", -1)
        assertEquals(listOf("postino", "mani", "ricercatore"), ids(Fissati.fissati(sx)))
        val dx = Fissati.sposta(sx, "postino", +1)
        assertEquals(listOf("mani", "postino", "ricercatore"), ids(Fissati.fissati(dx)))
    }

    @Test
    fun `ai bordi non si sposta`() {
        assertEquals(ids(Fissati.fissati(tre)), ids(Fissati.fissati(Fissati.sposta(tre, "postino", -1))))
        assertEquals(ids(Fissati.fissati(tre)), ids(Fissati.fissati(Fissati.sposta(tre, "mani", +1))))
        assertFalse(Fissati.puoSpostare(tre, "postino", -1))
        assertTrue(Fissati.puoSpostare(tre, "postino", +1))
        assertFalse(Fissati.puoSpostare(tre, "social", +1)) // non fissato
    }

    @Test
    fun `un agente non fissato non si sposta`() {
        assertEquals(tre, Fissati.sposta(tre, "social", -1))
    }

    @Test
    fun `l'ordine scelto sopravvive a togli e rifissa`() {
        var l = Fissati.sposta(tre, "mani", -1)       // postino, mani, ricercatore
        l = Fissati.sposta(l, "mani", -1)              // mani, postino, ricercatore
        l = Fissati.togli(l, "postino")                // mani, ricercatore
        l = Fissati.fissa(l, "postino")                // mani, ricercatore, postino
        assertEquals(listOf("mani", "ricercatore", "postino"), ids(Fissati.fissati(l)))
        assertEquals(listOf(0, 1, 2), Fissati.fissati(l).map { it.ordine })
    }

    @Test
    fun `rinumera toglie i buchi e azzera i non fissati`() {
        val sporca = tre.map { if (it.id == "mani") it.copy(ordine = 9) else if (it.id == "social") it.copy(ordine = 4) else it }
        val r = Fissati.rinumera(sporca)
        assertEquals(listOf(0, 1, 2), Fissati.fissati(r).map { it.ordine })
        assertEquals(0, r.first { it.id == "social" }.ordine)
    }

    @Test
    fun `tutti tolti e tutti fissati`() {
        var l = base
        for (id in CatalogoAgenti.ID) l = Fissati.togli(l, id)
        assertTrue(Fissati.fissati(l).isEmpty())
        for (id in CatalogoAgenti.ID) l = Fissati.fissa(l, id)
        assertEquals(CatalogoAgenti.ID, ids(Fissati.fissati(l)))
        assertTrue(Fissati.altri(l).isEmpty())
    }

    // ------------------------------------------------------------ stati

    private val tutto = Situazione(accessibilita = true, maniFerme = false)

    @Test
    fun `esterni non collegati con il Collegamento Jarvis spento`() {
        for (id in listOf("postino", "ricercatore", "social")) {
            val a = CatalogoAgenti.predefinito(id)!!
            assertEquals(id, StatoAgente.NON_COLLEGATO, StatiAgenti.stato(a, tutto))
            assertEquals("Collegamento Jarvis spento", StatiAgenti.testo(a, StatoAgente.NON_COLLEGATO))
            assertTrue(StatiAgenti.moduloEsterno(a, tutto)!!.contains("spento"))
        }
    }

    @Test
    fun `KO 1 corretto - con il collegamento acceso gli esterni sono pronti, collegati, al lavoro`() {
        val acceso = tutto.copy(moduloVps = true)
        for (id in listOf("postino", "ricercatore", "social")) {
            val a = CatalogoAgenti.predefinito(id)!!
            assertEquals(id, StatoAgente.PRONTO, StatiAgenti.stato(a, acceso))
            assertEquals("pronto · sulla VPS", StatiAgenti.testo(a, StatoAgente.PRONTO, acceso))
            assertEquals("VPS collegata", StatiAgenti.testo(a, StatoAgente.PRONTO, acceso.copy(vpsCollegata = true)))
            assertEquals("Collegamento Jarvis acceso", StatiAgenti.moduloEsterno(a, acceso))
            assertEquals(StatoAgente.LAVORA, StatiAgenti.stato(a, acceso.copy(alLavoro = id)))
        }
    }

    @Test
    fun `un esterno non risulta al lavoro senza modulo VPS`() {
        val p = CatalogoAgenti.predefinito("postino")!!
        assertEquals(StatoAgente.NON_COLLEGATO, StatiAgenti.stato(p, tutto.copy(alLavoro = "postino")))
    }

    @Test
    fun `Mani pronto, al lavoro, spento, fermo`() {
        val m = CatalogoAgenti.predefinito("mani")!!
        assertEquals(StatoAgente.PRONTO, StatiAgenti.stato(m, tutto))
        assertEquals(StatoAgente.LAVORA, StatiAgenti.stato(m, tutto.copy(alLavoro = "mani")))
        assertEquals(StatoAgente.SPENTO, StatiAgenti.stato(m, tutto.copy(accessibilita = false)))
        assertEquals("accessibilità spenta", StatiAgenti.testo(m, StatoAgente.SPENTO))
        assertEquals(StatoAgente.ERRORE, StatiAgenti.stato(m, tutto.copy(maniFerme = true)))
        assertEquals("mani ferme (emergenza)", StatiAgenti.testo(m, StatoAgente.ERRORE))
    }

    @Test
    fun `Scrittore resta, scrive nel telefono e non va mai da solo`() {
        val s = CatalogoAgenti.predefinito("scrittore")!!
        assertEquals(StatoAgente.PRONTO, StatiAgenti.stato(s, tutto))
        assertTrue(s.autonomiaBloccata)
        assertEquals(null, StatiAgenti.moduloEsterno(s, tutto))
    }

    @Test
    fun `solo l'agente al lavoro pulsa`() {
        val s = tutto.copy(alLavoro = "mani")
        val lavorano = base.filter { StatiAgenti.stato(it, s) == StatoAgente.LAVORA }.map { it.id }
        assertEquals(listOf("mani"), lavorano)
    }

    @Test
    fun `dove gira in parole`() {
        assertEquals("Lavora sulla VPS", StatiAgenti.dove(CatalogoAgenti.predefinito("postino")!!))
        assertEquals("Lavora nel telefono", StatiAgenti.dove(CatalogoAgenti.predefinito("mani")!!))
    }
}
