package com.jarvis.telefono.postino

import com.jarvis.telefono.nucleo.Cronologia
import com.jarvis.telefono.nucleo.FiltroCronologia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La posta unica di JBoss e del Postino (Boss, 2026-10-09: «ci sono interferenze, JBoss dovrebbe subito collegarsi al
 * Postino e aggiornare la pagina della posta»; «ogni cancellazione passa dalla conferma nel filo»).
 */
class PostaCondivisaTest {

    /** Il canale del modulo VPS, finto: registra quello che parte. */
    private class Finto(var su: Boolean = true) : CanalePostino {
        override val collegato get() = su
        override var ascoltatore: ((JSONObject) -> Unit)? = null
        override var suCollegamento: ((Boolean) -> Unit)? = null
        val avviati = mutableListOf<Pair<String, String>>()
        val seguiti = mutableListOf<Pair<String, Int>>()
        var tenuto = false
        override fun avvia(idLavoro: String, testo: String, opzioni: JSONObject) { avviati += idLavoro to testo }
        override fun conferma(idLavoro: String, azioneId: String, scelta: String) {}
        override fun segui(idLavoro: String, ultimoEvento: Int) { seguiti += idLavoro to ultimoEvento }
        override fun tieniAperto(si: Boolean) { tenuto = si }
        fun dalla(m: JSONObject) = ascoltatore!!.invoke(m)
    }

    private var adesso = 1_000_000L
    private val eventi = mutableListOf<PostaCondivisa.Evento>()
    private val pianificati = mutableListOf<() -> Unit>()

    private fun posta(c: Finto = Finto()): Pair<PostaCondivisa, Finto> {
        val h = PostaCondivisa(ora = { adesso }, pianifica = { _, f -> pianificati += f })
        h.suEvento = { eventi += it }
        h.collegaCanale(c)
        return h to c
    }

    private val contatori = mutableMapOf<String, Int>()

    private fun evento(id: String, dati: JSONObject) = JSONObject().put("type", "job_event").put("id", id)
        .put("n", contatori.merge(id, 1, Int::plus)).put("kind", "postino").put("dati", dati)

    private fun fine(id: String, esito: String = "ok") = JSONObject().put("type", "job_done").put("id", id)
        .put("n", contatori.merge(id, 1, Int::plus)).put("esito", esito).put("riassunto", "finito")

    private fun voce(n: Int, da: String, oggetto: String) = JSONObject().put("numero", n).put("casella", "negozio").put("da", da)
        .put("indirizzo", "x$n@example.com").put("oggetto", oggetto).put("data", "09/10").put("categoria", "lavoro")
        .put("livello", 2).put("urgenza", "").put("proposta", JSONObject().put("azione", "leggi"))
        .put("anteprima", "Testo della mail $n.").put("allegati", 0)

    /** Il resoconto dalla VPS per il lavoro [id]: 4 mail, 2 nuove in negozio. */
    private fun resoconto(c: Finto, id: String, report: String = "r1") {
        c.dalla(evento(id, JSONObject().put("tipo", "totali").put("report_id", report).put("totale", 4).put("pagine", 1)
            .put("caselle", JSONArray().put(JSONObject().put("casella", "negozio").put("nuove", 2)))))
        c.dalla(evento(id, JSONObject().put("tipo", "voci").put("report_id", report).put("pagina", 1)
            .put("voci", JSONArray().put(voce(1, "Mario", "Fattura")).put(voce(2, "Anna", "Riunione"))
                .put(voce(3, "Luca", "Preventivo")).put(voce(4, "Banca", "Estratto")))))
        c.dalla(fine(id))
    }

    private fun conResoconto(): Pair<PostaCondivisa, Finto> {
        val (h, c) = posta()
        val e = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        resoconto(c, e.id)
        return h to c
    }

    // ------------------------------------------------------------ stato condiviso

    @Test
    fun `JBoss e la pagina leggono lo stesso stato, anche quello arrivato a pagina chiusa`() {
        val (h, c) = posta()
        // JBoss chiede la posta senza la pagina aperta: il resoconto arriva e finisce nella posta unica
        val r = h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        assertNotNull(r.idLavoro)
        assertEquals("controlla la posta", c.avviati.single().second)
        resoconto(c, r.idLavoro!!)
        // la pagina si apre dopo: vede le 4 mail senza chiedere niente alla VPS
        var ridisegni = 0
        h.osserva { ridisegni++ }
        h.vistaAperta(pagina = true)
        assertEquals(4, h.stato.voci().size)
        assertEquals(1, c.avviati.size)
        // e la mail corrente è quella che JBoss sta leggendo
        assertEquals(1, h.corrente)
        val fineJBoss = eventi.filterIsInstance<PostaCondivisa.Evento.FineJBoss>().single()
        assertTrue(fineJBoss.testo, fineJBoss.testo.contains("Mail 1") && fineJBoss.testo.contains("Mario"))
        // JBoss va avanti: la pagina viene avvisata e vede la stessa mail corrente
        h.perJBoss(ComandiPostaJBoss.Comando.Avanti)
        assertEquals(2, h.corrente)
        assertTrue(ridisegni > 0)
    }

    @Test
    fun `un resoconto chiesto da JBoss non fa anche l'avviso, uno della pagina sì, come riga`() {
        val (h, c) = posta()
        val r = h.perJBoss(ComandiPostaJBoss.Comando.Aggiorna)
        resoconto(c, r.idLavoro!!, "r1")
        assertTrue(eventi.none { it is PostaCondivisa.Evento.NuoveMail })
        val e = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        resoconto(c, e.id, "r2")
        val avviso = eventi.filterIsInstance<PostaCondivisa.Evento.NuoveMail>().single()
        // 09/10: il numero unico (le 4 mail da fare del resoconto), non le «nuove» delle caselle (2)
        assertEquals(4, avviso.nuove)
        assertTrue(avviso.testo, avviso.testo.startsWith("Postino: 4 mail da fare (negozio 4)"))
    }

    @Test
    fun `senza collegamento il comando aspetta il canale, non parte nel vuoto`() {
        val c = Finto(su = false)
        val (h, _) = posta(c)
        val e = h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        assertNotNull(e.idLavoro)
        assertTrue(c.avviati.isEmpty())
        assertTrue("il socket deve restare su finché il comando non parte", c.tenuto)
        c.su = true
        c.suCollegamento!!.invoke(true)
        assertEquals(listOf("controlla la posta"), c.avviati.map { it.second })
        assertFalse(c.tenuto)
    }

    @Test
    fun `il collegamento che non arriva chiude il comando con il motivo`() {
        val c = Finto(su = false)
        val (h, _) = posta(c)
        val r = h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        pianificati.single().invoke()
        val f = eventi.filterIsInstance<PostaCondivisa.Evento.FineJBoss>().single()
        assertEquals(r.idLavoro, f.idLavoro)
        assertTrue(f.errore)
        assertTrue(h.sessione.inCorso.isEmpty())
    }

    @Test
    fun `senza canale JBoss lo dice e non finge`() {
        val h = PostaCondivisa()
        val r = h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        assertTrue(r.errore)
        assertEquals(PostaCondivisa.NON_COLLEGATO, r.dire)
    }

    // ------------------------------------------------------------ la cancellazione chiede il sì

    @Test
    fun `cancella questa da JBoss non parte senza il tocco su Elimina`() {
        val (h, c) = conResoconto()
        val partiti = c.avviati.size
        h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        val r = h.perJBoss(ComandiPostaJBoss.Comando.Cancella(null))
        assertNotNull(r.chiaveConferma)
        assertEquals(partiti, c.avviati.size)
        val k = h.daConfermare!!
        assertEquals("Elimina", k.etichettaSi)
        assertEquals(listOf(1), k.numeri)
        assertTrue(eventi.any { it is PostaCondivisa.Evento.Conferma && it.richiesta?.chiave == k.chiave })
        // Annulla: non parte niente
        assertEquals(PostaCondivisa.Esito.Annullato, h.rispondiConferma(k.chiave, si = false))
        assertEquals(partiti, c.avviati.size)
        assertNull(h.daConfermare)
    }

    @Test
    fun `dopo il tocco su Elimina parte cestina e la mail corrente passa alla prossima`() {
        val (h, c) = conResoconto()
        h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        h.perJBoss(ComandiPostaJBoss.Comando.Cancella(null))
        val e = h.rispondiConferma(h.daConfermare!!.chiave, si = true) as PostaCondivisa.Esito.Partito
        assertEquals("cestina 1", c.avviati.last().second)
        assertEquals(2, h.corrente)
        assertTrue(h.inViaggio(1))
        // la VPS verifica e finisce: JBoss dice l'esito e legge la prossima
        c.dalla(evento(e.id, JSONObject().put("tipo", "stato").put("report_id", "r1").put("numero", 1).put("esito", "ok")
            .put("stato", "nel Cestino").put("verbo", "cestina").put("prova", "uid 5")))
        c.dalla(fine(e.id))
        val f = eventi.filterIsInstance<PostaCondivisa.Evento.FineJBoss>().last()
        assertEquals(e.id, f.idLavoro)
        assertTrue(f.testo, f.testo.startsWith("Fatto: la mail 1 è nel Cestino") && f.testo.contains("Prossima: Mail 2"))
        assertTrue(h.stato.voce(1)!!.stato!!.fatto)
    }

    @Test
    fun `un cestina scritto nella pagina o un'altra conferma non scavalcano il sì`() {
        val (h, c) = conResoconto()
        val partiti = c.avviati.size
        val e = h.manda("cestina 3, 4")
        assertTrue(e is PostaCondivisa.Esito.DaConfermare)
        assertEquals(listOf(3, 4), (e as PostaCondivisa.Esito.DaConfermare).richiesta.numeri)
        assertTrue(h.manda("spam 2") is PostaCondivisa.Esito.DaConfermare)
        assertTrue(h.mandaCatena(listOf("cestina 2")) is PostaCondivisa.Esito.DaConfermare)
        assertEquals(partiti, c.avviati.size)
        // una chiave vecchia non fa partire niente
        assertTrue(h.rispondiConferma("posta:vecchia", si = true) is PostaCondivisa.Esito.Errore)
        assertEquals(partiti, c.avviati.size)
        // archiviare non è irreversibile: parte subito
        assertTrue(h.manda("sposta 2 in Rumore") is PostaCondivisa.Esito.Partito)
    }

    @Test
    fun `la pagina chiede il sì per Elimina e Spam con lo stesso box`() {
        val (h, c) = conResoconto()
        val partiti = c.avviati.size
        assertNull(h.chiediConferma(AzioniPostino.Azione.ARCHIVIA, listOf(1), PostaCondivisa.Da.PAGINA))
        val r = h.chiediConferma(AzioniPostino.Azione.SPAM, listOf(4, 2), PostaCondivisa.Da.PAGINA)!!
        assertEquals(listOf(2, 4), r.numeri)
        assertEquals("Spam", r.etichettaSi)
        assertTrue(r.corpo, r.corpo.contains("2 · Anna — Riunione"))
        assertEquals(partiti, c.avviati.size)
        h.rispondiConferma(r.chiave, si = true)
        assertEquals("spam 2, 4", c.avviati.last().second)
    }

    // ------------------------------------------------------------ avanti, indietro, lettura

    @Test
    fun `avanti e indietro scorrono le mail da fare e la lettura dice numero, mittente e oggetto`() {
        val (h, _) = conResoconto()
        val primo = h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        assertTrue(primo.dire, primo.dire.contains("Mail 1, 1 di 4 da fare. Da Mario, oggetto «Fattura»."))
        assertEquals(1, h.corrente)
        assertTrue(h.perJBoss(ComandiPostaJBoss.Comando.Avanti).dire.startsWith("Mail 2, 2 di 4"))
        assertTrue(h.perJBoss(ComandiPostaJBoss.Comando.Avanti).dire.startsWith("Mail 3"))
        assertTrue(h.perJBoss(ComandiPostaJBoss.Comando.Indietro).dire.startsWith("Mail 2"))
        h.perJBoss(ComandiPostaJBoss.Comando.Apri(4))
        assertEquals("Era l'ultima mail da fare.", h.perJBoss(ComandiPostaJBoss.Comando.Avanti).dire)
        assertEquals(4, h.corrente)
        assertTrue(h.perJBoss(ComandiPostaJBoss.Comando.Apri(9)).errore)
    }

    @Test
    fun `avanti salta le mail già partite in un lavoro e la finestra della posta scade`() {
        val (h, _) = conResoconto()
        h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        assertTrue(h.manda("sposta 2 in Rumore") is PostaCondivisa.Esito.Partito)
        h.segnaInViaggio(listOf(2), h.sessione.inCorso.last())
        assertTrue(h.perJBoss(ComandiPostaJBoss.Comando.Avanti).dire.startsWith("Mail 3"))
        assertTrue(h.inPosta())
        adesso += PostaCondivisa.FINESTRA_MS + 1
        assertFalse(h.inPosta())
    }

    @Test
    fun `un resoconto nuovo rinumera, la mail corrente di prima non vale più`() {
        val (h, c) = conResoconto()
        h.perJBoss(ComandiPostaJBoss.Comando.Apri(3))
        val e = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        resoconto(c, e.id, "r2")
        assertNull(h.corrente)
    }

    // ------------------------------------------------------------ la pagina riceve l'aggiornamento

    @Test
    fun `la pagina viene avvisata a ogni evento della VPS, anche di un comando partito da JBoss`() {
        val (h, c) = conResoconto()
        val viste = mutableListOf<Long>()
        val smetti = h.osserva { viste += h.versione }
        h.perJBoss(ComandiPostaJBoss.Comando.Leggi)
        val idApri = h.perJBoss(ComandiPostaJBoss.Comando.Rileggi).idLavoro!!
        assertEquals("apri 1", c.avviati.last().second)
        val prima = viste.size
        c.dalla(evento(idApri, JSONObject().put("tipo", "scheda").put("report_id", "r1").put("numero", 1)
            .put("da", "Mario").put("oggetto", "Fattura").put("testo", "Testo intero").put("riassunto", "La fattura di settembre, 120 euro.")))
        assertTrue(viste.size > prima)
        assertNotNull("la scheda arrivata per JBoss è anche della pagina", h.stato.schede[1])
        c.dalla(fine(idApri))
        assertTrue(eventi.filterIsInstance<PostaCondivisa.Evento.FineJBoss>().last().testo.contains("La fattura di settembre"))
        smetti()
        val n = viste.size
        c.dalla(evento(idApri, JSONObject().put("tipo", "passo").put("testo", "x")))
        assertEquals(n, viste.size)
    }

    @Test
    fun `riaprire la pagina con il canale già su riprende i lavori non finiti`() {
        val (h, c) = posta()
        val e = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        c.dalla(evento(e.id, JSONObject().put("tipo", "passo").put("testo", "leggo negozio")))
        h.vistaAperta(pagina = true)
        assertEquals(e.id to 1, c.seguiti.last())
    }

    // ------------------------------------------------------------ le frasi di JBoss

    @Test
    fun `le frasi di JBoss sulla posta`() {
        val p = ComandiPostaJBoss
        assertEquals(ComandiPostaJBoss.Comando.Leggi, p.capisci("Leggi le mail", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Leggi, p.capisci("JBoss, leggimi la posta per favore", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Leggi, p.capisci("ci sono mail nuove?", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Aggiorna, p.capisci("aggiorna la posta", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Avanti, p.capisci("la prossima mail", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Apri(5), p.capisci("leggi la mail 5", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Apri(12), p.capisci("apri la mail dodici", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Cancella(7), p.capisci("cancella la mail 7", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Cancella(null), p.capisci("cancella questa", inPosta = false))
        // le frasi corte valgono solo mentre Boss scorre la posta
        for (f in listOf("avanti", "la prossima", "aggiorna", "cancella", "ripeti", "indietro")) {
            assertNull(f, p.capisci(f, inPosta = false))
            assertNotNull(f, p.capisci(f, inPosta = true))
        }
        assertEquals(ComandiPostaJBoss.Comando.Avanti, p.capisci("Avanti.", inPosta = true))
        assertEquals(ComandiPostaJBoss.Comando.Cancella(null), p.capisci("cancellala", inPosta = true))
        // non è posta: resta a JBoss
        assertNull(p.capisci("manda una mail a Marco: arrivo", inPosta = true))
        assertNull(p.capisci("che ore sono", inPosta = true))
        assertNull(p.capisci("apri WhatsApp", inPosta = true))
        assertNull(p.capisci("cancella la sveglia", inPosta = false))
    }

    @Test
    fun `la causa - le regole del telefono non capiscono leggi le mail, la frase finiva al cervello della VPS`() {
        val ctx = com.jarvis.telefono.nucleo.Contesto(contatti = null, ora = 10, minuti = 0)
        val regole = com.jarvis.telefono.nucleo.CervelloRegole()
        for (f in listOf("leggi le mail", "avanti", "cancella questa", "aggiorna la posta")) {
            val p = regole.interpreta(f, ctx)
            assertTrue("$f: ${p.dire}", !p.capito || com.jarvis.telefono.nucleo.Complessita.pianoDebole(p))
        }
    }

    // ------------------------------------------------------------ 09/10: il numero unico

    @Test
    fun `Home, pagina e JBoss leggono lo stesso numero, e scende dopo una cancellazione confermata`() {
        val (h, c) = posta()
        val r = h.perJBoss(ComandiPostaJBoss.Comando.Aggiorna)
        resoconto(c, r.idLavoro!!)   // 4 mail nel resoconto, le caselle dicono «2 nuove»: un altro conto
        assertEquals(4, h.stato.numeroUnico())
        // la pagina: la riga sotto il titolo e il chip «Da fare»
        assertTrue(h.stato.rigaRiassunto(), h.stato.rigaRiassunto().endsWith("4 da fare"))
        assertEquals(4, h.stato.conta(StatoPostino.Filtro.DA_FARE))
        // JBoss: niente «2 nuove» accanto, e la lettura conta sulle stesse 4
        val fineJBoss = eventi.filterIsInstance<PostaCondivisa.Evento.FineJBoss>().single().testo
        assertFalse(fineJBoss, fineJBoss.contains("nuove"))
        assertTrue(fineJBoss, fineJBoss.contains("4 da fare") && fineJBoss.contains("1 di 4 da fare"))
        assertTrue(fineJBoss, fineJBoss.startsWith("Ho letto 1 casella:"))
        // cancellazione confermata e verificata: il numero scende per tutti
        h.perJBoss(ComandiPostaJBoss.Comando.Cancella(2))
        val e = h.rispondiConferma(h.daConfermare!!.chiave, si = true) as PostaCondivisa.Esito.Partito
        c.dalla(evento(e.id, JSONObject().put("tipo", "stato").put("report_id", "r1").put("numero", 2).put("esito", "ok")
            .put("stato", "nel Cestino").put("verbo", "cestina").put("prova", "uid 9")))
        assertEquals(3, h.stato.numeroUnico())
        assertEquals("3 mail da fare", h.stato.rigaNumero())
    }

    @Test
    fun `le frasi della VPS con uno davanti tornano al singolare`() {
        assertEquals("riassumo 6 mail in 1 blocco", StatoPostino.italiano("riassumo 6 mail in 1 blocchi"))
        assertEquals("riassumo 60 mail in 3 blocchi", StatoPostino.italiano("riassumo 60 mail in 3 blocchi"))
        assertEquals("riassumo 6 mail in 11 blocchi", StatoPostino.italiano("riassumo 6 mail in 11 blocchi"))
        assertEquals("Ho letto 1 casella: 6 mail", StatoPostino.italiano("Ho letto 1 caselle: 6 mail"))
        assertEquals("controllo che la mail sia ancora al suo posto".substringBefore(" ancora"),
            StatoPostino.italiano("controllo che le 1 mail siano ancora al loro posto").substringBefore(" ancora"))
        // e lo stato la corregge da solo, prima di mostrarla nella pagina
        val st = StatoPostino()
        st.applicaDati(JSONObject().put("tipo", "passo").put("testo", "riassumo 6 mail in 1 blocchi"))
        assertEquals("riassumo 6 mail in 1 blocco", st.ultimaFrase)
    }

    @Test
    fun `un secondo resoconto mentre il primo gira non ne apre un altro`() {
        val (h, c) = posta()
        val primo = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        val bolle = h.sessione.bolle.size
        val secondo = h.manda("resoconto") as PostaCondivisa.Esito.Partito
        assertEquals(primo.id, secondo.id)
        assertEquals(1, c.avviati.size)
        assertEquals(bolle, h.sessione.bolle.size)
        resoconto(c, primo.id)
        assertEquals(4, h.stato.voci().size)
        // finito il primo, un resoconto nuovo parte davvero
        assertTrue((h.manda("resoconto") as PostaCondivisa.Esito.Partito).id != primo.id)
        assertEquals(2, c.avviati.size)
    }

    // ------------------------------------------------------------ 09/10: il Postino capisce le frasi libere

    @Test
    fun `nel Postino leggi le mail è capito, non «non capisco»`() {
        val (h, c) = conResoconto()
        // prima: il parser a numeri della pagina non la capiva
        assertTrue(runCatching { ComandiPostino.capisci("leggi le mail") }.isFailure)
        val r = h.perPostino("leggi le mail", PostaCondivisa.Da.PAGINA)!!
        assertFalse(r.dire, r.errore)
        assertTrue(r.dire, r.dire.contains("Mail 1, 1 di 4 da fare"))
        assertEquals(1, h.corrente)
        // avanti, aggiorna la posta: frasi corte valide nel Postino anche senza «finestra»
        adesso += PostaCondivisa.FINESTRA_MS + 1
        assertTrue(h.perPostino("avanti", PostaCondivisa.Da.PAGINA)!!.dire.startsWith("Mail 2"))
        val partiti = c.avviati.size
        val ag = h.perPostino("aggiorna la posta", PostaCondivisa.Da.PAGINA)!!
        assertNotNull(ag.idLavoro)
        assertEquals("controlla la posta", c.avviati.last().second)
        assertEquals(partiti + 1, c.avviati.size)
        // un lavoro della pagina non torna a JBoss come risposta da dire
        resoconto(c, ag.idLavoro!!, "r2")
        assertTrue(eventi.none { it is PostaCondivisa.Evento.FineJBoss && it.idLavoro == ag.idLavoro })
        // i comandi a numeri restano; una frase a caso no
        assertNotNull(h.perPostino("sposta 2 in Rumore", PostaCondivisa.Da.PAGINA)!!.idLavoro)
        assertNull(h.perPostino("che tempo fa domani", PostaCondivisa.Da.PAGINA))
    }

    @Test
    fun `nel Postino nessuna cancellazione parte dalla voce o dal testo, anche di più mail insieme`() {
        val (h, c) = conResoconto()
        val partiti = c.avviati.size
        h.perPostino("leggi le mail", PostaCondivisa.Da.JBOSS)
        for (frase in listOf("cancella questa", "cancella le mail 2, 3 e 4", "cestina 3 e 4", "elimina la mail 2", "spam 4")) {
            val r = h.perPostino(frase, PostaCondivisa.Da.JBOSS)!!
            assertNotNull(frase, r.chiaveConferma)
            assertNull(frase, r.idLavoro)
            assertEquals(frase, partiti, c.avviati.size)
            assertEquals(frase, r.chiaveConferma, h.daConfermare!!.chiave)
        }
        // più mail insieme: un box solo con tutte, e parte solo col tocco
        val r = h.perPostino("cancella le mail 2, 3 e 4", PostaCondivisa.Da.PAGINA)!!
        assertEquals(listOf(2, 3, 4), h.daConfermare!!.numeri)
        assertTrue(r.dire, r.dire.contains("3 mail") && r.dire.contains("Elimina"))
        // «sì» detto non è il tocco: Annulla chiude senza partire, il tocco su Elimina fa partire il comando
        assertEquals(PostaCondivisa.Esito.Annullato, h.rispondiConferma(r.chiaveConferma!!, si = false))
        assertEquals(partiti, c.avviati.size)
        val r2 = h.perPostino("cancella le mail 2, 3 e 4", PostaCondivisa.Da.PAGINA)!!
        assertTrue(h.rispondiConferma(r2.chiaveConferma!!, si = true) is PostaCondivisa.Esito.Partito)
        assertEquals("cestina 2-4", c.avviati.last().second)
        // una mail che non c'è ferma tutto
        assertTrue(h.perPostino("cancella le mail 3 e 40", PostaCondivisa.Da.PAGINA)!!.errore)
    }

    @Test
    fun `le frasi libere per più mail`() {
        assertEquals(ComandiPostaJBoss.Comando.CancellaPiu(listOf(3, 5, 7)), ComandiPostaJBoss.capisci("cancella le mail 3, 5 e 7", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.CancellaPiu(listOf(2, 3, 4)), ComandiPostaJBoss.capisci("elimina le mail da 2 a 4", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Cancella(7), ComandiPostaJBoss.capisci("cancella la mail 7", inPosta = false))
    }

    // ------------------------------------------------------------ avvisi e filo di JBoss

    @Test
    fun `un avviso del Postino con l'app davanti è una riga, con l'app chiusa una notifica`() {
        assertTrue(PostaCondivisa.avvisoComeRiga("postino", appDavanti = true))
        assertFalse(PostaCondivisa.avvisoComeRiga("postino", appDavanti = false))
        assertFalse(PostaCondivisa.avvisoComeRiga("ricercatore", appDavanti = true))
    }

    private var id = 0L
    private fun boss(t: String, esito: String = "scritto") = Cronologia.Voce(++id, id * 1000, Cronologia.BOSS, t, "", esito, 0, "")
    private fun jarvis(t: String, agente: String = "", esito: String = "ok") = Cronologia.Voce(++id, id * 1000, Cronologia.JARVIS, t, "regole", esito, 0, agente)

    @Test
    fun `il filo di JBoss mostra la posta chiesta a lui e gli avvisi, non la chat di un altro agente`() {
        val voci = listOf(
            boss("che ore sono"), jarvis("Sono le 10."),
            jarvis("Postino: 2 mail nuove (negozio 2).", "postino", FiltroCronologia.AVVISO),
            boss("leggi le mail"), jarvis("Mail 1. Da Mario.", "postino"),
            boss("cerca bandi", "chat-ricercatore"), jarvis("Ecco i bandi.", "ricercatore"),
        )
        val filo = FiltroCronologia.filtra(voci, FiltroCronologia.FILO_JBOSS).map { it.testo }
        assertEquals(listOf("che ore sono", "Sono le 10.", "Postino: 2 mail nuove (negozio 2).", "leggi le mail", "Mail 1. Da Mario."), filo)
        // l'avviso apre uno scambio suo: «che ore sono» non diventa posta e resta sotto il chip JBoss della Home
        assertEquals(listOf("che ore sono", "Sono le 10.", "cerca bandi", "Ecco i bandi."),
            FiltroCronologia.filtra(voci, FiltroCronologia.JARVIS).map { it.testo })
        assertTrue(FiltroCronologia.filtra(voci, FiltroCronologia.POSTINO).map { it.testo }.containsAll(listOf("leggi le mail", "Postino: 2 mail nuove (negozio 2).")))
    }

    // ------------------------------------------------------------ 09/10: passaggio al Postino con la pagina aperta

    @Test
    fun `dopo il passaggio la pagina e JBoss restano sullo stesso resoconto, il numero non cambia`() {
        val (h, c) = conResoconto()
        val partiti = c.avviati.size
        val numero = h.stato.numeroUnico()
        // la pagina si apre col passaggio: niente resoconto nuovo, c'è già
        h.vistaAperta(pagina = true)
        // una frase detta con la pagina davanti (la strada di Postino.perPostino): risposta e conversazione della pagina
        val bolle = h.sessione.bolle.size
        val r = h.perPostino("leggi le mail", PostaCondivisa.Da.JBOSS, nellaPagina = true)!!
        assertTrue(r.dire, r.dire.contains("Mail 1"))
        assertEquals("domanda e risposta una volta sola nella pagina", bolle + 2, h.sessione.bolle.size)
        assertEquals("leggi le mail", h.sessione.bolle[bolle].testo)
        assertEquals(partiti, c.avviati.size)
        assertEquals(numero, h.stato.numeroUnico())
        // senza la pagina davanti la frase resta solo nel filo di JBoss
        h.perPostino("avanti", PostaCondivisa.Da.JBOSS, nellaPagina = false)
        assertEquals(bolle + 2, h.sessione.bolle.size)
        // scritta nella pagina: una coppia sola (prima la pagina la aggiungeva anche lei)
        h.perPostino("avanti", PostaCondivisa.Da.PAGINA)
        assertEquals(bolle + 4, h.sessione.bolle.size)
        assertEquals(numero, h.stato.numeroUnico())
    }

    @Test
    fun `il resoconto parte una volta sola anche chiesto dalla pagina, scritto e da JBoss insieme`() {
        // la sequenza vista sulla VPS il 09/10 alle 14:06: la pagina chiede «controlla la posta», Boss scrive «resoconto»
        // mentre il primo lavora, e JBoss dice «aggiorna la posta»: prima partivano due resoconti
        val (h, c) = posta()
        val primo = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        val bolle = h.sessione.bolle.size
        assertEquals(primo, h.manda("resoconto"))
        val j = h.perPostino("aggiorna la posta", PostaCondivisa.Da.JBOSS)!!
        assertEquals(primo.id, j.idLavoro)
        assertEquals(1, c.avviati.size)
        assertEquals("niente seconda bolla «Leggo tutte le caselle»", bolle, h.sessione.bolle.size)
        resoconto(c, primo.id)
        // la pagina riaperta riprende il lavoro e la VPS rimanda tutto: niente cambia due volte
        h.vistaAperta(pagina = true)
        c.dalla(JSONObject().put("type", "job_done").put("id", primo.id).put("n", 3).put("esito", "ok").put("riassunto", "finito"))
        assertEquals(1, eventi.filterIsInstance<PostaCondivisa.Evento.FineJBoss>().size)
        assertTrue(eventi.none { it is PostaCondivisa.Evento.NuoveMail })
        assertEquals(4, h.stato.voci().size)
    }

    @Test
    fun `il filo di JBoss tiene tutto il passaggio, niente si svuota andando e tornando dal Postino`() {
        // le righe che scrivono Postino.passa (chat di JBoss o voce) e Postino.tornaAJBoss (dalla pagina)
        val voci = listOf(
            boss("che ore sono"), jarvis("Sono le 10."),
            boss("passa al Postino", "voce"), jarvis(PassaggioPostino.AL_POSTINO, "postino"),
            boss("leggi le mail", "voce"), jarvis("Mail 1. Da Mario.", "postino"),
            boss("torna a JBoss", "scritto"), jarvis(PassaggioPostino.A_JBOSS),
        )
        val filo = FiltroCronologia.perFilo(voci, FiltroCronologia.FILO_JBOSS, "", inizioSessione = Long.MAX_VALUE, storico = true)
        assertEquals(voci.map { it.testo }, filo.map { it.testo })
    }

    // ------------------------------------------------------------ 09/10 sera: i tre difetti del verificatore sul telefono

    @Test
    fun `nel Postino resoconto, riassunto, aggiorna la posta e dimmi le mail fanno il resoconto, mai una bozza`() {
        val (h, c) = conResoconto()
        h.perPostino("leggi le mail", PostaCondivisa.Da.PAGINA)
        assertEquals(1, h.corrente)
        for (frase in listOf("resoconto", "Riassunto", "aggiorna la posta", "dimmi le mail", "fammi il resoconto della posta")) {
            val partiti = c.avviati.size
            val r = h.perPostino(frase, PostaCondivisa.Da.PAGINA)
            assertNotNull(frase, r)
            assertNotNull(frase, r!!.idLavoro)
            if (c.avviati.size > partiti) assertEquals(frase, "controlla la posta", c.avviati.last().second)
            assertTrue(frase, c.avviati.none { it.second.startsWith("istruisci") })
            // il resoconto finisce prima della frase dopo (un secondo resoconto mentre gira aspetta il primo)
            if (c.avviati.size > partiti) resoconto(c, c.avviati.last().first, "r-$frase")
        }
        // fuori dalla posta «resoconto» da solo non è posta (resta a JBoss); «dimmi le mail» sì
        assertNull(ComandiPostaJBoss.capisci("resoconto", inPosta = false))
        assertEquals(ComandiPostaJBoss.Comando.Aggiorna, ComandiPostaJBoss.capisci("dimmi le mail", inPosta = false))
    }

    @Test
    fun `sulla mail aperta una bozza nasce solo da un testo di risposta chiaro`() {
        // la frase del verificatore: prima diventava «istruisci 1: resoconto»
        assertNull(ComandiBrevi.istruzione("resoconto", 1, attesaRisposta = false))
        assertNull(ComandiBrevi.istruzione("che tempo fa domani", 1, attesaRisposta = false))
        assertNull(ComandiBrevi.istruzione("di quale mail parli", 1, attesaRisposta = false))
        assertNull(ComandiBrevi.istruzione("rispondi che", 1, attesaRisposta = false))
        assertNull(ComandiBrevi.istruzione("rispondi che arrivo lunedì", null, attesaRisposta = false))
        assertEquals("istruisci 1: rispondi che arrivo lunedì", ComandiBrevi.istruzione("rispondi che arrivo lunedì", 1, attesaRisposta = false))
        assertEquals("istruisci 4: Digli di sì, grazie", ComandiBrevi.istruzione("Digli di sì, grazie", 4, attesaRisposta = false))
        assertEquals("istruisci 2: risposta: confermo per giovedì", ComandiBrevi.istruzione("risposta: confermo per giovedì", 2, attesaRisposta = false))
        // dopo il tocco su Rispondi la frase è la risposta, ma una parola sola non basta
        assertEquals("istruisci 3: arrivo lunedì alle dieci", ComandiBrevi.istruzione("arrivo lunedì alle dieci", 3, attesaRisposta = true))
        assertNull(ComandiBrevi.istruzione("resoconto", 3, attesaRisposta = true))
    }

    @Test
    fun `la mail mostrata nella pagina va anche nel filo di JBoss, una volta, senza id`() {
        val (h, _) = conResoconto()
        eventi.clear()
        h.perPostino("leggi le mail", PostaCondivisa.Da.PAGINA)
        val letta = eventi.filterIsInstance<PostaCondivisa.Evento.Letta>().single()
        assertEquals(1, letta.numero)
        assertTrue(letta.testo, letta.testo.startsWith("Mail 1, 1 di 4. Da Mario, «Fattura»."))
        assertTrue(letta.testo, letta.testo.contains("Testo della mail 1."))
        assertFalse(letta.testo, Regex("r1|postino-|report", RegexOption.IGNORE_CASE).containsMatchIn(letta.testo))
        // la pagina che apre la stessa mail (ridisegno dopo la frase) non la riscrive
        h.apri(1, PostaCondivisa.Da.PAGINA)
        assertEquals(1, eventi.filterIsInstance<PostaCondivisa.Evento.Letta>().size)
        // ▶ nella pagina o un tocco su un'altra mail: una riga nuova
        h.apri(3, PostaCondivisa.Da.PAGINA)
        assertEquals(listOf(1, 3), eventi.filterIsInstance<PostaCondivisa.Evento.Letta>().map { it.numero })
        // detta a JBoss la risposta è già nel filo: niente riga in più, nemmeno quando la pagina la segue
        h.perPostino("avanti", PostaCondivisa.Da.JBOSS, nellaPagina = true)
        assertEquals(4, h.corrente)
        h.apri(4, PostaCondivisa.Da.PAGINA)
        assertEquals(listOf(1, 3), eventi.filterIsInstance<PostaCondivisa.Evento.Letta>().map { it.numero })
    }

    @Test
    fun `un secondo resoconto con le stesse mail non ripete l'avviso nel filo`() {
        // il doppione del verificatore: «Postino: 6 mail…» alle 14:06 e alle 14:07, due giri con le stesse mail
        val (h, c) = posta()
        val primo = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        resoconto(c, primo.id, "r1")
        adesso += 60_000
        val secondo = h.manda("controlla la posta") as PostaCondivisa.Esito.Partito
        assertTrue(primo.id != secondo.id)
        resoconto(c, secondo.id, "r2")
        assertEquals(1, eventi.filterIsInstance<PostaCondivisa.Evento.NuoveMail>().size)
    }

    @Test
    fun `lo stesso avviso delle mail dal ponte e dal telefono è un doppione per 30 minuti`() {
        val t0 = 10_000_000L
        val prima = listOf("Postino · Postino: 6 mail nuove (gmail 1, negozio 4, jarvis 1)" to t0)
        assertTrue(PostaCondivisa.avvisoDoppione("Postino · Postino: 6 mail nuove (gmail 1, negozio 4, jarvis 1)", t0 + 60_000, prima))
        assertTrue(PostaCondivisa.avvisoDoppione("Postino: 6 mail da fare (gmail 1, negozio 4, jarvis 1). Di' «leggi le mail».", t0 + 60_000, prima))
        assertFalse(PostaCondivisa.avvisoDoppione("Postino: 7 mail da fare (gmail 2, negozio 4, jarvis 1).", t0 + 60_000, prima))
        assertFalse(PostaCondivisa.avvisoDoppione("Postino · Postino: 6 mail nuove (gmail 1, negozio 4, jarvis 1)", t0 + PostaCondivisa.FINESTRA_AVVISI_MS, prima))
        assertFalse(PostaCondivisa.avvisoDoppione("Postino: 6 mail nuove (gmail 1, negozio 4, jarvis 1)", t0, emptyList()))
    }
}
