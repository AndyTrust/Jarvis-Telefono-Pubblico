package com.jarvis.telefono.vps

import com.jarvis.telefono.nucleo.Cronologia
import com.jarvis.telefono.ui.VistaCronologia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La scheda della delega a Jarvis sulla VPS nel filo di JBoss (accordo con Boss del 2026-10-09). */
class SchedaDelegaTest {

    private fun lavoro(
        stato: String,
        esito: String? = null,
        riassunto: String? = null,
        origine: String = "scritto",
        creato: Long = 1_000L,
        finito: Long = 0L,
        agente: String = "generico",
        titolo: String = "cerca i bandi per la formazione",
        testo: String = titolo,
    ) = LavoroLocale(
        id = "tel-$stato-$creato", agente = agente, titolo = titolo, testo = testo, stato = stato,
        esito = esito, riassunto = riassunto, creato = creato, finito = finito, origine = origine,
    )

    // ─── stato → testo ─────────────────────────────────────────────────────────

    @Test
    fun `ogni stato aperto si legge come in corso`() {
        val attesi = mapOf(
            LavoroLocale.DA_MANDARE to "In corso: parte appena c'è la rete",
            LavoroLocale.INVIATO to "In corso: mandato a Jarvis",
            LavoroLocale.CODA to "In corso: in fila sulla VPS",
            LavoroLocale.ATTESA to "In corso: aspetta il tuo sì",
            LavoroLocale.LAVORO to "In corso",
        )
        for ((stato, testo) in attesi) {
            val s = SchedaDelega.da(lavoro(stato))
            assertEquals(stato, testo, s.testoStato)
            assertEquals(stato, SchedaDelega.Fase.IN_CORSO, s.fase)
        }
    }

    @Test
    fun `un lavoro finito si legge fatto, errore o annullato`() {
        assertEquals("Fatto", SchedaDelega.da(lavoro(LavoroLocale.FINITO, "ok", "Trovati 4 bandi")).testoStato)
        assertEquals(SchedaDelega.Fase.FATTO, SchedaDelega.da(lavoro(LavoroLocale.FINITO, "ok")).fase)
        assertEquals("Errore", SchedaDelega.da(lavoro(LavoroLocale.FINITO, "errore")).testoStato)
        assertEquals(SchedaDelega.Fase.ERRORE, SchedaDelega.da(lavoro(LavoroLocale.FINITO, "errore")).fase)
        assertEquals("Annullato", SchedaDelega.da(lavoro(LavoroLocale.FINITO, "annullato")).testoStato)
        // un esito sconosciuto non diventa mai un finto «fatto»
        assertEquals("Errore", SchedaDelega.da(lavoro(LavoroLocale.FINITO, "boh")).testoStato)
        assertEquals("Errore", SchedaDelega.da(lavoro(LavoroLocale.FINITO, null)).testoStato)
    }

    @Test
    fun `l'esito e il riassunto della VPS in una riga, o una frase onesta`() {
        assertEquals("Trovati 4 bandi", SchedaDelega.da(lavoro(LavoroLocale.FINITO, "ok", "**Trovati 4 bandi**\nDettagli…")).esito)
        assertEquals("Ancora nessuno: ti avviso quando ha finito", SchedaDelega.da(lavoro(LavoroLocale.LAVORO)).esito)
        assertEquals("Ferma finché non scegli Invia o Annulla", SchedaDelega.da(lavoro(LavoroLocale.ATTESA)).esito)
        assertEquals("Finito: il resoconto è nel dettaglio del lavoro", SchedaDelega.da(lavoro(LavoroLocale.FINITO, "ok", "")).esito)
    }

    @Test
    fun `codice, stack trace e nomi interni non arrivano mai sulla scheda`() {
        val tecnici = listOf(
            "java.lang.IllegalStateException: boom",
            "    at com.jarvis.telefono.vps.NucleoVps.evento(NucleoVps.kt:181)",
            "Traceback (most recent call last):",
            "curl -s https://esempio.it | grep titolo",
            "{\"type\":\"job_errore\",\"motivo\":\"x\"}",
            "errore in /root/jarvis/strumenti/posta.py",
            "job_start rifiutato",
            "TypeError: null is not an object",
        )
        for (t in tecnici) {
            val s = SchedaDelega.da(lavoro(LavoroLocale.FINITO, "errore", t))
            assertEquals(t, "Non è riuscito: tocca la scheda per sapere perché", s.esito)
        }
        assertNull(SchedaDelega.rigaSemplice("sudo docker restart jarvis-agent"))
        assertEquals("interrotto dal riavvio del server", SchedaDelega.rigaSemplice("interrotto dal riavvio del server"))
    }

    @Test
    fun `la richiesta e la frase di Boss, anche per i contesti con le regole davanti`() {
        assertEquals("cerca i bandi per la formazione", SchedaDelega.da(lavoro(LavoroLocale.LAVORO)).richiesta)
        val contesto = lavoro(
            LavoroLocale.LAVORO, agente = "crm", titolo = "[CRM di lavoro · database crm] Solo lettura…",
            testo = "[CRM di lavoro · database crm] Solo lettura.\nRichiesta di Boss: quanti preventivi a ottobre?",
        )
        assertEquals("quanti preventivi a ottobre?", SchedaDelega.da(contesto).richiesta)
        assertEquals("Delega a CRM di lavoro sulla VPS", SchedaDelega.da(contesto).titolo)
        assertEquals("Delega a Jarvis sulla VPS", SchedaDelega.da(lavoro(LavoroLocale.LAVORO)).titolo)
        val lunga = SchedaDelega.da(lavoro(LavoroLocale.LAVORO, titolo = "a".repeat(400))).richiesta
        assertTrue(lunga.length <= 140 && lunga.endsWith("…"))
    }

    // ─── la riga del browser ───────────────────────────────────────────────────

    @Test
    fun `senza dato dalla VPS la riga del browser non c'e`() {
        val s = SchedaDelega.da(lavoro(LavoroLocale.LAVORO))
        assertNull(s.browser)
        assertNull(s.rigaBrowser)
        // perJBoss senza fonte del browser: nessuna scheda ne inventa una
        assertTrue(SchedaDelega.perJBoss(listOf(lavoro(LavoroLocale.LAVORO)), 0L).all { it.rigaBrowser == null })
    }

    @Test
    fun `con il dato la riga dice aperto o chiuso e cosa fa`() {
        assertEquals(
            "Browser: aperto · Legge i bandi sul sito della Regione",
            SchedaDelega.da(lavoro(LavoroLocale.LAVORO), StatoBrowser(true, "Legge i bandi sul sito della Regione")).rigaBrowser,
        )
        assertEquals("Browser: chiuso", SchedaDelega.da(lavoro(LavoroLocale.FINITO, "ok"), StatoBrowser(false)).rigaBrowser)
        // una nota tecnica non passa: resta solo aperto/chiuso
        assertEquals("Browser: aperto", SchedaDelega.rigaBrowser(StatoBrowser(true, "page.goto('https://x.it') {timeout: 3000}")))
        val l = lavoro(LavoroLocale.LAVORO)
        val conFonte = SchedaDelega.perJBoss(listOf(l), 0L) { id -> if (id == l.id) StatoBrowser(true, "Apre il sito") else null }
        assertEquals("Browser: aperto · Apre il sito", conFonte.single().rigaBrowser)
    }

    // ─── in quale chat ─────────────────────────────────────────────────────────

    @Test
    fun `le deleghe stanno nel filo di JBoss, non quelle partite dalle chat degli altri agenti`() {
        assertTrue(SchedaDelega.diJBoss(lavoro(LavoroLocale.LAVORO, origine = "voce")))
        assertTrue(SchedaDelega.diJBoss(lavoro(LavoroLocale.LAVORO, origine = "scritto")))
        assertTrue(SchedaDelega.diJBoss(lavoro(LavoroLocale.LAVORO, origine = SchedaDelega.ORIGINE_SCHERMATA)))
        assertTrue(SchedaDelega.diJBoss(lavoro(LavoroLocale.LAVORO, origine = "")))
        assertFalse(SchedaDelega.diJBoss(lavoro(LavoroLocale.LAVORO, origine = "chat-ricercatore")))
        assertFalse(SchedaDelega.diJBoss(lavoro(LavoroLocale.LAVORO, origine = "chat-postino")))
    }

    @Test
    fun `solo la sessione della chat, piu le deleghe ancora aperte`() {
        val inizio = 10_000L
        val vecchiaFinita = lavoro(LavoroLocale.FINITO, "ok", creato = 1_000L, finito = 2_000L)
        val vecchiaAperta = lavoro(LavoroLocale.LAVORO, creato = 3_000L)
        val finitaOra = lavoro(LavoroLocale.FINITO, "ok", creato = 4_000L, finito = 12_000L)
        val nuova = lavoro(LavoroLocale.INVIATO, creato = 11_000L)
        val altraChat = lavoro(LavoroLocale.LAVORO, creato = 11_500L, origine = "chat-social")
        val schede = SchedaDelega.perJBoss(listOf(nuova, vecchiaFinita, altraChat, finitaOra, vecchiaAperta), inizio)
        assertEquals(listOf(vecchiaAperta.id, finitaOra.id, nuova.id), schede.map { it.id })
    }

    @Test
    fun `l'origine del lavoro resta nel registro`() {
        val archivio = ArchivioInMemoria()
        val nu = NucleoVps(archivio, ora = { 5_000L }, caso = { 7L })
        val id = nu.nuovoLavoro("generico", "testo lungo per la VPS", titolo = "la frase di Boss", origine = "voce")
        val l = archivio.lavoro(id)!!
        assertEquals("voce", l.origine)
        assertEquals("la frase di Boss", l.titolo)
        assertEquals("la frase di Boss", SchedaDelega.da(l).richiesta)
    }

    @Test
    fun `nel filo di JBoss le righe VPS che la scheda sostituisce non si ripetono`() {
        fun voce(chi: String, testo: String, cervello: String = "") = Cronologia.Voce(1, 1, chi, testo, cervello)
        assertTrue(VistaCronologia.rigaDiDelega(voce(Cronologia.JARVIS, "[VPS · Ricercatore web] In corso sulla VPS: ti avviso quando ha finito.", "vps")))
        assertTrue(VistaCronologia.rigaDiDelega(voce(Cronologia.JARVIS, "[VPS · JBoss] Finito: fatto", "vps")))
        // il rifiuto e la coda senza rete restano: spiegano perché non è partita
        assertFalse(VistaCronologia.rigaDiDelega(voce(Cronologia.JARVIS, "Il Collegamento Jarvis è spento…", "vps")))
        assertFalse(VistaCronologia.rigaDiDelega(voce(Cronologia.BOSS, "[VPS · x] frase di Boss")))
        assertFalse(VistaCronologia.rigaDiDelega(voce(Cronologia.JARVIS, "[VPS · x] dal cervello", "regole")))
    }

    @Test
    fun `un resoconto del Postino senza origine non diventa una scheda nel filo di JBoss`() {
        // 09/10 (Boss: «il resoconto arriva doppio»): il Postino scrive già il resoconto nel filo; un suo lavoro senza
        // origine (job_lista, registro di prima) compariva anche come scheda della delega, cioè due volte.
        val posta = LavoroLocale(
            id = "postino-1791547599409-7800", agente = "postino", titolo = "Postino", testo = "controlla la posta",
            stato = LavoroLocale.FINITO, esito = "ok", riassunto = "Ho letto 5 caselle", creato = 2_000L, finito = 3_000L,
        )
        val delega = lavoro(LavoroLocale.FINITO, esito = "ok", riassunto = "Trovati 3 bandi.", origine = "", creato = 2_500L, finito = 3_500L)
        assertFalse(SchedaDelega.diJBoss(posta))
        assertEquals(listOf(delega.id), SchedaDelega.perJBoss(listOf(posta, delega), 0L).map { it.id })
    }
}
