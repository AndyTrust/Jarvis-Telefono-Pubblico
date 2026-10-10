package com.jarvis.telefono.collegamento

import com.jarvis.telefono.collegamento.Arbitro.Chi
import com.jarvis.telefono.vps.Instradamento
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Collegamento Jarvis (0.6.0): arbitro, coda unica delle notifiche, memoria condivisa, contesti, comandi, webapp. */
class CollegamentoTest {

    // ─── Arbitro ───────────────────────────────────────────────────────────────
    private fun arb(origine: String?, rid: String?, tardiva: Boolean = false, inCorso: String? = null, annullati: Set<String> = emptySet(), debiti: Int = 0, tardive: Int = 0) =
        Arbitro.risposta(origine, rid, tardiva, inCorso, annullati, debiti, tardive)

    @Test fun `il sito non parla mai da JBoss, il sottofondo e una notifica`() {
        assertEquals(Chi.IGNORA, arb("sito", null, inCorso = "jb-1"))
        assertEquals(Chi.IGNORA, arb("sito", null, tardiva = true))
        assertEquals(Chi.NOTIFICA, arb("sottofondo", null, inCorso = "jb-1"))
    }

    @Test fun `con il rid decide la frase giusta`() {
        assertEquals(Chi.FRASE_IN_CORSO, arb("telefono", "jb-2", inCorso = "jb-2"))
        assertEquals(Chi.TARDIVA, arb("telefono", "jb-1", inCorso = "jb-2"))
        assertEquals(Chi.SCARTA, arb("telefono", "jb-1", inCorso = "jb-2", annullati = setOf("jb-1")))
        assertEquals(Chi.SCARTA, arb("telefono", "jb-1", tardiva = true, annullati = setOf("jb-1")))
        assertEquals(Chi.TARDIVA, arb("telefono", "jb-1", tardiva = true))
    }

    @Test fun `VPS vecchia senza origine e rid si comporta come la 0_5_0`() {
        assertEquals(Chi.SCARTA, arb(null, null, inCorso = "", debiti = 1))
        assertEquals(Chi.FRASE_IN_CORSO, arb(null, null, inCorso = ""))
        assertEquals(Chi.TARDIVA, arb(null, null, tardive = 1))
        assertEquals(Chi.IGNORA, arb(null, null))
    }

    // ─── Coda unica delle notifiche ────────────────────────────────────────────
    private fun n(chiave: String, titolo: String, testo: String, filo: String = "notifiche-jarvis", importante: Boolean = false, fonte: String = "report") =
        CodaNotifiche.Notifica(chiave, fonte, titolo, testo, 0L, filo, importante)

    @Test fun `stesso id una volta sola, anche dopo il riavvio`() {
        val q = CodaNotifiche()
        assertEquals(CodaNotifiche.Esito.MOSTRA, q.entra(n("ponte:1", "Jarvis · Report", "Il giro è finito"), 1_000))
        assertEquals(CodaNotifiche.Esito.DOPPIONE, q.entra(n("ponte:1", "Jarvis · Report", "Il giro è finito"), 2_000))
        val q2 = CodaNotifiche().apply { carica(q.salva()) }
        assertEquals(CodaNotifiche.Esito.DOPPIONE, q2.entra(n("ponte:1", "x", "y"), 3_000))
    }

    @Test fun `stesso contenuto da due fonti entro 30 minuti diventa una notifica`() {
        val q = CodaNotifiche()
        assertEquals(CodaNotifiche.Esito.MOSTRA, q.entra(n("lavoro:a", "Postino · Report posta", "12 mail nuove, 2 urgenti", fonte = "lavoro"), 0))
        assertEquals(CodaNotifiche.Esito.DOPPIONE, q.entra(n("ponte:9", "Postino · Report posta", "12 mail nuove, 2 urgenti", fonte = "report"), 10 * 60_000))
        assertEquals(CodaNotifiche.Esito.MOSTRA, q.entra(n("ponte:10", "Postino · Report posta", "12 mail nuove, 2 urgenti"), 45 * 60_000))
    }

    @Test fun `contesti seguiti solo se importanti`() {
        val q = CodaNotifiche()
        assertEquals(CodaNotifiche.Esito.SILENZIO, q.entra(n("ponte:s1", "CRM", "Aggiornato un contatto", filo = "crm"), 0))
        assertEquals(CodaNotifiche.Esito.MOSTRA, q.entra(n("ponte:s2", "CRM", "Richiesta cliente nuova", filo = "crm", importante = true), 0))
        assertEquals(CodaNotifiche.Esito.SILENZIO, q.entra(n("ponte:p1", "Patrimonio", "Valori aggiornati", filo = "patrimonio"), 0))
    }

    @Test fun `una notifica del ponte prende filo e priorita`() {
        val a = CodaNotifiche.daPonte(JSONObject("""{"id":"7","ts":1791400000.5,"k":"postino","mittente":"","titolo":"Report","testo":"t"}"""))
        assertEquals("ponte:7", a.chiave); assertEquals("postino", a.filo); assertEquals("Postino · Report", a.titolo)
        assertEquals(1_791_400_000_500L, a.ts)
        val b = CodaNotifiche.daPonte(JSONObject("""{"id":"8","ts":1,"k":"crm","titolo":"Cliente","testo":"t","priorita":"alta"}"""))
        assertEquals("crm", b.filo); assertTrue(b.importante)
    }

    // ─── Memoria condivisa ─────────────────────────────────────────────────────
    @Test fun `il telefono manda le sue preferenze, mai i segreti`() {
        val conf = JSONObject("""{"app_mail":"samsung","whatsapp_me":"393330000001","fine_frase_s":1.5,"vps_token":"x","password":"y","altro":"z"}""")
        val f = MemoriaCondivisa.fattiDelTelefono(conf, mapOf("parole_attivazione" to "jboss, hey jboss, hey boss"), 10)
        assertEquals(setOf("app_mail", "whatsapp_me", "fine_frase_s", "parole_attivazione"), f.map { it.chiave }.toSet())
        val corpo = MemoriaCondivisa.corpoAllinea(f, "0.6.0")
        assertFalse(corpo.contains("token")); assertFalse(corpo.contains("password"))
        assertEquals("jboss", JSONObject(corpo).getString("app"))
    }

    @Test fun `risposta della VPS letta, conflitti per Boss, buchi riempiti`() {
        val allinea = JSONObject("""{"ora":5,"fatti":[{"chiave":"app_mail","valore":"gmail","ts":1,"fonte":"vps"},{"chiave":"account_mail","valore":"boss@example.com","ts":1,"fonte":"vps"}],
            "conflitti":[{"chiave":"app_mail","telefono":{"valore":"samsung"},"vps":{"valore":"gmail"}}]}""")
        val profilo = JSONObject("""{"voci":[{"tema":"conferme","titolo":"Conferma prima dell'irreversibile","testo":"…","data":"2026-09-30"}],
            "contesti":[{"id":"crm","nome":"CRM di lavoro","indirizzo":"https://crm.example.org/odoo/action-561"},{"id":"patrimonio","nome":"Patrimonio","indirizzo":""}]}""")
        val e = MemoriaCondivisa.leggi(allinea, profilo)
        assertEquals(1, e.conflitti.size); assertEquals("samsung", e.conflitti[0].telefono); assertEquals("gmail", e.conflitti[0].jarvis)
        assertEquals("https://crm.example.org/odoo/action-561", e.indirizzo("crm")); assertNull(e.indirizzo("patrimonio"))
        val telefono = listOf(MemoriaCondivisa.Fatto("app_mail", "samsung", 1))
        assertEquals(listOf("account_mail"), MemoriaCondivisa.daAdottare(telefono, e.fattiVps).map { it.chiave })
        val ricaricato = MemoriaCondivisa.carica(MemoriaCondivisa.salva(e))!!
        assertEquals(e.voci, ricaricato.voci); assertEquals(e.conflitti, ricaricato.conflitti); assertEquals(e.contesti, ricaricato.contesti)
        val r = MemoriaCondivisa.riassuntoVoce(e)
        assertTrue(r, r.contains("Conferme")); assertTrue(r, r.contains("decidi tu")); assertTrue(r, r.contains("CRM di lavoro"))
        assertEquals("https://jarvis-agent.example.org", MemoriaCondivisa.base("wss://jarvis-agent.example.org/phone"))
        assertEquals("""{"chiave":"app_mail","vince":"vps"}""", MemoriaCondivisa.corpoDecidi("app_mail", false))
    }

    // ─── Contesti seguiti e instradamento ──────────────────────────────────────
    @Test fun `CRM e Patrimonio vanno alla VPS in sola lettura`() {
        assertEquals("crm", Contesti.di("come va il CRM oggi")?.id)
        assertEquals("crm", Contesti.di("ci sono richieste dei clienti nuove?")?.id)
        assertEquals("patrimonio", Contesti.di("com'è andato il patrimonio questa settimana")?.id)
        assertNull(Contesti.di("apri il patrimonio"))
        assertNull(Contesti.di("che tempo fa domani"))
        val e = Instradamento.decidi("come va il CRM oggi", moduloAcceso = true, rete = true)
        assertTrue(e is Instradamento.Esito.Vps)
        e as Instradamento.Esito.Vps
        assertEquals("crm", e.agente); assertTrue(e.frase, e.frase.contains("SOLA LETTURA")); assertTrue(e.frase.contains("come va il CRM"))
        val spento = Instradamento.decidi("novità sul patrimonio", moduloAcceso = false, rete = true)
        assertTrue(spento is Instradamento.Esito.Rifiuto)
        assertTrue(Instradamento.decidi("novità sul patrimonio", moduloAcceso = true, rete = false) is Instradamento.Esito.InCoda)
        assertTrue(Contesti.chiedeAzione("manda una mail al cliente del CRM"))
    }

    // ─── Comandi a voce ────────────────────────────────────────────────────────
    @Test fun `comandi del collegamento`() {
        val C = ComandiCollegamento
        assertEquals(ComandiCollegamento.Comando.Webapp(), C.riconosci("Apri Jarvis"))
        assertEquals(ComandiCollegamento.Comando.Webapp(), C.riconosci("Hey Boss, apri la webapp"))
        assertEquals(ComandiCollegamento.Comando.Webapp(), C.riconosci("apri il command center"))
        assertEquals(ComandiCollegamento.Comando.Webapp("postino"), C.riconosci("apri i report del postino"))
        assertEquals(ComandiCollegamento.Comando.ApriContesto("crm"), C.riconosci("apri il CRM"))
        assertEquals(ComandiCollegamento.Comando.ApriContesto("patrimonio"), C.riconosci("apri il patrimonio"))
        assertEquals(ComandiCollegamento.Comando.AllineaMemoria, C.riconosci("allinea la memoria"))
        assertEquals(ComandiCollegamento.Comando.AllineaMemoria, C.riconosci("JBoss sincronizza la memoria con Jarvis"))
        assertEquals(ComandiCollegamento.Comando.CosaSaiDiMe, C.riconosci("cosa sai di me?"))
        assertEquals(ComandiCollegamento.Comando.Stato, C.riconosci("sei collegato a Jarvis?"))
        assertEquals(ComandiCollegamento.Comando.GiroNotifiche, C.riconosci("controlla i report di Jarvis"))
        assertNull(C.riconosci("apri WhatsApp"))
        assertNull(C.riconosci("controlla se ho notifiche nuove"))
        assertNull(C.riconosci("come va il CRM"))
    }

    // ─── Webapp ────────────────────────────────────────────────────────────────
    @Test fun `webapp sul suo host, il resto fuori o bloccato`() {
        val base = "https://jarvis.example.org/"
        assertTrue(AccessoWeb.eHostBase("https://jarvis.example.org/?filo=postino#chat", base))
        assertFalse(AccessoWeb.eHostBase("http://jarvis.example.org/", base))
        assertFalse(AccessoWeb.eHostBase("https://user@jarvis.example.org/", base))
        assertFalse(AccessoWeb.eHostBase("https://altro.example.org/", base))
        assertTrue(AccessoWeb.ePaginaAccesso("https://jarvis.example.org/_ponte/entra?x=1", base))
        assertTrue(AccessoWeb.daAprireFuori("mailto:a@b.it")); assertFalse(AccessoWeb.daAprireFuori("intent://x")); assertFalse(AccessoWeb.daAprireFuori("javascript:alert(1)"))
        assertEquals("https://jarvis.example.org/?filo=postino#chat", AccessoWeb.indirizzo(base, "postino"))
        assertEquals("https://jarvis.example.org/?filo=xscript#chat", AccessoWeb.indirizzo(base, "x<script>"))
        assertEquals("https://jarvis.example.org/", AccessoWeb.indirizzo("https://jarvis.example.org"))
        assertEquals("https://jarvis.example.org/", AccessoWeb.baseDalPonte("wss://jarvis-agent.example.org/phone"))
        assertNull(AccessoWeb.baseDalPonte("wss://altro.example.org/phone"))
        assertNotNull(AccessoWeb.indirizzoBaseValido(base))
    }
}
