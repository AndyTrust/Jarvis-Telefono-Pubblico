package com.jarvis.telefono.cassaforte

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Cassaforte 0.3.1 (2026-10-07): cifratura, voci, migrazione, preset, prova IMAP con server finto, protocollo
 * verso la VPS, prova del cervello con server HTTP finto. Solo password e chiavi FINTE, niente rete fuori da 127.0.0.1.
 */
class CassaforteTest {
    private val pwFinta = "Finta-Pw-9x!"
    private fun cifratore() = CifratoreAesGcm(CifratoreAesGcm.chiaveSoftware().let { k -> { k } })

    private fun mailFinta(pw: String = pwFinta, porta: Int = 993, tipo: TipoMail = TipoMail.GENERICO) = AccountMail(
        id = "mail-prova", etichetta = "Prova", indirizzo = "prova@example.com", password = pw,
        imap = ServerPosta("127.0.0.1", porta, Sicurezza.NESSUNA), smtp = ServerPosta("smtp.example.com", 465), tipo = tipo,
    )

    // ─── cifratura ────────────────────────────────────────────────────────────
    @Test fun cifraEDecifra() {
        val c = cifratore()
        val a = c.cifra("ciao segreto".toByteArray())
        val b = c.cifra("ciao segreto".toByteArray())
        assertEquals("ciao segreto", String(c.decifra(a)))
        assertFalse("IV diverso a ogni cifratura", a.contentEquals(b))
        assertEquals(CifratoreAesGcm.VERSIONE, a[0])
    }

    @Test fun unByteCambiatoNonSiDecifra() {
        val c = cifratore()
        val a = c.cifra("x".repeat(40).toByteArray())
        a[a.size - 3] = (a[a.size - 3].toInt() xor 1).toByte()
        assertTrue(runCatching { c.decifra(a) }.isFailure)
    }

    // ─── cassaforte ───────────────────────────────────────────────────────────
    @Test fun salvaLeggiElimina_eNelDepositoNienteInChiaro() {
        val dep = DepositoMemoria()
        val log = CopyOnWriteArrayList<String>()
        val cf = cifratore()
        val c = Cassaforte(cf, dep) { log += it }
        c.salva(mailFinta())
        c.salva(Cervello(ProviderCervello.API_ANTHROPIC, "sk-ant-api03-FINTA-chiave-di-prova-1234567890"))
        c.salva(Vps("wss://vps.example.com/phone", "token-finto-0123456789"))
        c.salva(Altro(Altro.WHATSAPP_ME, "WhatsApp", "391234567890"))
        val grezzo = String(dep.byte!!, Charsets.ISO_8859_1)
        assertFalse(grezzo.contains(pwFinta))
        assertFalse(grezzo.contains("FINTA-chiave"))
        assertFalse(grezzo.contains("prova@example.com"))
        // Una cassaforte nuova sullo stesso deposito e la stessa chiave rilegge tutto.
        val c2 = Cassaforte(cf, dep)
        assertEquals(4, c2.elenco().size)
        assertEquals(pwFinta, (c2.leggi("mail-prova") as AccountMail).password)
        assertEquals("token-finto-0123456789", c2.vps()!!.token)
        assertNotNull(c2.cervello(ProviderCervello.API_ANTHROPIC))
        assertTrue(c2.elimina("mail-prova"))
        assertEquals(3, Cassaforte(cf, dep).elenco().size)
        assertTrue(log.none { it.contains(pwFinta) || it.contains("FINTA") || it.contains("token-finto") })
    }

    @Test fun chiavePersa_siRipartVuotiSenzaEccezioni() {
        val dep = object : Deposito {
            var b: ByteArray? = null; var accantonato = false
            override fun leggi() = b
            override fun scrivi(b: ByteArray) { this.b = b }
            override fun cancella() { b = null }
            override fun accantona() { accantonato = true; b = null }
        }
        Cassaforte(cifratore(), dep).salva(mailFinta())
        val altra = Cassaforte(cifratore(), dep)
        assertTrue(altra.elenco().isEmpty())
        assertTrue(altra.eraIlleggibile)
        assertTrue(dep.accantonato)
    }

    @Test fun depositoSuFile() {
        val dir = kotlin.io.path.createTempDirectory("cassaforte").toFile()
        val f = java.io.File(dir, Cassaforte.NOME_FILE)
        val cf = cifratore()
        Cassaforte(cf, DepositoFile(f)).salva(mailFinta())
        assertTrue(f.isFile)
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains(pwFinta))
        assertEquals(1, Cassaforte(cf, DepositoFile(f)).elenco().size)
        DepositoFile(f).accantona()
        assertFalse(f.exists())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith(Cassaforte.NOME_FILE + ".illeggibile-") })
        dir.deleteRecursively()
    }

    @Test fun idNonValidoRifiutato() {
        val c = Cassaforte(cifratore(), DepositoMemoria())
        assertTrue(runCatching { c.salva(Altro("ID MAIUSCOLO", "x", "y")) }.isFailure)
    }

    @Test fun nessunSegretoInToString() {
        val voci = listOf(
            mailFinta(),
            Cervello(ProviderCervello.CLAUDE_CODE_VPS, "sk-ant-oat01-FINTO-token-0000000000000000"),
            Vps("wss://vps.example.com/phone?x=1", "token-finto-0123456789"),
            Altro("chiave", "Chiave", "valore-segreto-finto", segreto = true),
        )
        val s = voci.joinToString("\n") { it.toString() }
        assertFalse(s.contains(pwFinta))
        assertFalse(s.contains("FINTO-token"))
        assertFalse(s.contains("token-finto"))
        assertFalse(s.contains("valore-segreto-finto"))
        assertTrue(s.contains("•••• ("))
        assertTrue(Altro("wa", "WA", "39123", segreto = false).toString().contains("39123"))
    }

    @Test fun jsonAndataERitorno() {
        val voci = listOf(
            mailFinta().copy(utente = "utente", cartelle = Cartelle(inviata = "Posta inviata"), suVps = true),
            Cervello(ProviderCervello.CODEX, "sk-finta-chiave-openai-000000000000", "gpt-x"),
            Vps("wss://a.example.com/phone", "token-finto-0123456789"),
            Google("prova@gmail.com"),
            Altro(Altro.WHATSAPP_ME, "WA", "391234567890"),
        )
        for (v in voci) assertEquals(v, Voce.daJson(JSONObject(v.json().toString())))
        assertNull(Voce.daJson(JSONObject("""{"genere":"boh"}""")))
    }

    @Test fun esportazioneConFrase() {
        val c = Cassaforte(cifratore(), DepositoMemoria())
        c.salva(mailFinta())
        val testo = c.esporta("frase-di-prova-lunga".toCharArray())
        assertTrue(testo.startsWith("jboss-cassaforte:1:"))
        assertFalse(testo.contains(pwFinta))
        val altra = Cassaforte(cifratore(), DepositoMemoria())
        assertTrue(runCatching { altra.importa(testo, "frase-sbagliata!!".toCharArray()) }.isFailure)
        assertEquals(1, altra.importa(testo, "frase-di-prova-lunga".toCharArray()))
        assertEquals(pwFinta, (altra.leggi("mail-prova") as AccountMail).password)
        assertTrue(runCatching { c.esporta("corta".toCharArray()) }.isFailure)
    }

    // ─── migrazione da config-boss.json ───────────────────────────────────────
    @Test fun migrazioneDaConfigBoss() {
        val cfg = """{"app_mail":"samsung","account_mail":"nome.cognome@example.com","whatsapp_me":"39 333 123 4567",
            "vps_url":"wss://vps.example.com/phone","fine_frase_s":1.5,"api_key_x":"non-deve-esserci"}"""
        val e = Migrazione.daConfigBoss(cfg, vpsToken = "token-finto-0123456789")
        assertEquals(3, e.voci.size)
        val m = e.voci.filterIsInstance<AccountMail>().single()
        assertEquals(TipoMail.SAMSUNG, m.tipo)
        assertEquals("mail-nome-cognome", m.id)
        assertNull(m.imap)
        assertEquals("393331234567", (e.voci.single { it is Altro } as Altro).valore)
        assertTrue((e.voci.single { it is Vps } as Vps).completa)
        assertTrue(e.righe.any { it.contains("chiavi segrete trovate") })
        val again = Migrazione.daConfigBoss(cfg, giaPresenti = e.voci.map { it.id }.toSet())
        assertTrue(again.voci.isEmpty())
        assertTrue(Migrazione.daConfigBoss("").righe.single().contains("niente"))
    }

    @Test fun migrazioneGmailPrendeIServer() {
        val e = Migrazione.daConfigBoss("""{"app_mail":"gmail","account_mail":"x@gmail.com"}""")
        val m = e.voci.single() as AccountMail
        assertEquals(TipoMail.GMAIL, m.tipo)
        assertEquals("imap.gmail.com", m.imap!!.host)
    }

    // ─── preset ───────────────────────────────────────────────────────────────
    @Test fun presetNoti() {
        val g = PresetPosta.di("gmail")!!
        assertEquals(ServerPosta("imap.gmail.com", 993), g.imap)
        assertEquals(465, g.smtp!!.porta)
        assertTrue(g.passwordPerApp)
        assertEquals("gmail", PresetPosta.perIndirizzo("a@GMAIL.com")!!.chiave)
        assertEquals("outlook", PresetPosta.perIndirizzo("a@hotmail.it")!!.chiave)
        assertEquals("pec-aruba", PresetPosta.perIndirizzo("a@pec.it")!!.chiave)
        assertNull(PresetPosta.perIndirizzo("a@dominio-sconosciuto.example"))
        assertNull(PresetPosta.di("samsung")!!.imap)
        assertTrue(PresetPosta.di("outlook")!!.nota.contains("OAuth"))
        assertFalse(PresetPosta.di("icloud")!!.vpsInviaAnche)
        for (p in PresetPosta.TUTTI) {
            p.imap?.let { assertTrue(p.chiave, it.valido && it.porta == 993 && it.sicurezza == Sicurezza.SSL) }
            p.smtp?.let { assertTrue(p.chiave, it.valido && it.porta in listOf(465, 587)) }
        }
        assertEquals(PresetPosta.TUTTI.size, PresetPosta.TUTTI.map { it.chiave }.toSet().size)
    }

    // ─── errori dei server ────────────────────────────────────────────────────
    @Test fun causeDalleRisposte() {
        assertEquals(CausaProva.PASSWORD_PER_APP, ErroriPosta.causaDaRisposta("NO [ALERT] Application-specific password required: https://support.google.com/accounts/answer/185833 (Failure)", TipoMail.GMAIL))
        assertEquals(CausaProva.PASSWORD_PER_APP, ErroriPosta.causaDaRisposta("NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)", TipoMail.GMAIL))
        assertEquals(CausaProva.PASSWORD_SBAGLIATA, ErroriPosta.causaDaRisposta("NO [AUTHENTICATIONFAILED] Authentication failed.", TipoMail.HOSTINGER))
        assertEquals(CausaProva.IMAP_SPENTO, ErroriPosta.causaDaRisposta("NO [ALERT] Your account is not enabled for IMAP use.", TipoMail.GMAIL))
        assertEquals(CausaProva.ACCESSO_WEB, ErroriPosta.causaDaRisposta("NO [WEBALERT https://x] Web login required. Please log in via your web browser", TipoMail.GMAIL))
        assertEquals(CausaProva.OAUTH_RICHIESTO, ErroriPosta.causaDaRisposta("NO LOGIN failed.", TipoMail.OUTLOOK))
        assertEquals(CausaProva.PASSWORD_SBAGLIATA, ErroriPosta.causaDaRisposta("NO qualcosa", TipoMail.GENERICO))
        assertTrue(ErroriPosta.testo(CausaProva.PASSWORD_PER_APP, TipoMail.GMAIL).contains("apppasswords"))
        assertEquals("a •••• b", ErroriPosta.pulisci("a segreto b", "segreto"))
    }

    @Test fun cartelleDaList() {
        assertEquals("Posta inviata" to "\\Sent", ErroriPosta.cartellaDaList("""* LIST (\HasNoChildren \Sent) "/" "Posta inviata""""))
        assertEquals("INBOX" to "", ErroriPosta.cartellaDaList("""* LIST (\HasNoChildren) "." INBOX"""))
        assertEquals("INBOX.Trash" to "\\Trash", ErroriPosta.cartellaDaList("""* LIST (\Trash \HasNoChildren) "." "INBOX.Trash""""))
        assertNull(ErroriPosta.cartellaDaList("* OK boh"))
    }

    // ─── prova IMAP con un server finto locale ────────────────────────────────
    /** Server IMAP finto: una connessione, poi si chiude. [ricevute] = le password arrivate (per controllare i letterali). */
    private class ImapFinto(
        val pwGiusta: String,
        val rifiuto: String = "NO [AUTHENTICATIONFAILED] Invalid credentials (Failure)",
        val loginDisattivato: Boolean = false,
        val saluto: String = "* OK finto pronto",
    ) : AutoCloseable {
        val ss = ServerSocket(0)
        val porta: Int get() = ss.localPort
        val ricevute = CopyOnWriteArrayList<String>()
        private val t = Thread { runCatching { ss.accept().use { servi(it) } } }.also { it.isDaemon = true; it.start() }

        private fun riga(ins: InputStream): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = ins.read(); if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
                if (c == '\n'.code) return b.toString("UTF-8"); if (c != '\r'.code) b.write(c)
            }
        }

        private fun servi(s: Socket) {
            val ins = BufferedInputStream(s.getInputStream()); val out = s.getOutputStream()
            fun scrivi(t: String) { out.write((t + "\r\n").toByteArray()); out.flush() }
            scrivi(saluto)
            while (true) {
                var r = riga(ins) ?: return
                val tag = r.substringBefore(' ')
                val cmd = r.substringAfter(' ').substringBefore(' ').uppercase()
                when (cmd) {
                    "CAPABILITY" -> { scrivi("* CAPABILITY IMAP4rev1" + if (loginDisattivato) " LOGINDISABLED" else ""); scrivi("$tag OK fatto") }
                    "LOGIN" -> {
                        // Raccoglie i letterali {n}: dopo ognuno manda «+» e legge n byte.
                        val pezzi = StringBuilder(r)
                        while (Regex("""\{(\d+)\}$""").containsMatchIn(pezzi)) {
                            val n = Regex("""\{(\d+)\}$""").find(pezzi)!!.groupValues[1].toInt()
                            scrivi("+ vai")
                            val buf = ByteArray(n); var letti = 0
                            while (letti < n) letti += ins.read(buf, letti, n - letti)
                            pezzi.replace(pezzi.length - "{$n}".length, pezzi.length, "\"" + String(buf, Charsets.UTF_8).replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                            pezzi.append(riga(ins) ?: "")
                        }
                        r = pezzi.toString()
                        val m = Regex("""LOGIN "((?:[^"\\]|\\.)*)" "((?:[^"\\]|\\.)*)"""").find(r)
                        val pw = m?.groupValues?.get(2)?.replace("\\\"", "\"")?.replace("\\\\", "\\").orEmpty()
                        ricevute += pw
                        scrivi(if (pw == pwGiusta) "$tag OK entrato" else "$tag $rifiuto")
                    }
                    "LIST" -> {
                        scrivi("""* LIST (\HasNoChildren) "/" "INBOX"""")
                        scrivi("""* LIST (\HasNoChildren \Sent) "/" "Posta inviata"""")
                        scrivi("""* LIST (\HasNoChildren \Trash) "/" "Cestino"""")
                        scrivi("$tag OK LIST fatto")
                    }
                    "LOGOUT" -> { scrivi("* BYE ciao"); scrivi("$tag OK"); return }
                    else -> scrivi("$tag BAD sconosciuto")
                }
            }
        }

        override fun close() { runCatching { ss.close() }; t.join(2000) }
    }

    @Test fun provaImapRiuscita_eCartelleSpeciali() = ImapFinto(pwFinta).use { srv ->
        val e = ProvaImap(timeoutMs = 3000).prova(mailFinta(porta = srv.porta))
        assertTrue(e.messaggio, e.ok)
        assertEquals(3, e.cartelle.size)
        assertEquals("Posta inviata", e.cartellaSpeciale("\\Sent"))
        assertEquals("Cestino", e.cartellaSpeciale("\\Trash"))
        assertFalse(e.messaggio.contains(pwFinta))
    }

    @Test fun provaImapPasswordSbagliata() = ImapFinto("altra").use { srv ->
        val e = ProvaImap(timeoutMs = 3000).prova(mailFinta(porta = srv.porta, tipo = TipoMail.HOSTINGER))
        assertEquals(CausaProva.PASSWORD_SBAGLIATA, e.causa)
        assertFalse(e.messaggio.contains(pwFinta))
    }

    @Test fun provaImapGmailVuoleLaPasswordPerApp() = ImapFinto("altra", rifiuto = "NO [ALERT] Application-specific password required (Failure)").use { srv ->
        val e = ProvaImap(timeoutMs = 3000).prova(mailFinta(porta = srv.porta, tipo = TipoMail.GMAIL))
        assertEquals(CausaProva.PASSWORD_PER_APP, e.causa)
        assertTrue(e.messaggio.contains("password per app"))
    }

    @Test fun provaImapLoginDisattivato() = ImapFinto(pwFinta, loginDisattivato = true).use { srv ->
        assertEquals(CausaProva.LOGIN_DISATTIVATO, ProvaImap(timeoutMs = 3000).prova(mailFinta(porta = srv.porta)).causa)
    }

    @Test fun provaImapPasswordConVirgoletteEAccenti() {
        val strana = "pà\"ss\\wörd 1"
        ImapFinto(strana).use { srv ->
            val e = ProvaImap(timeoutMs = 3000).prova(mailFinta(pw = strana, porta = srv.porta))
            assertTrue(e.messaggio, e.ok)
            assertEquals(strana, srv.ricevute.single())
        }
        val ascii = "pa\"ss\\word"
        ImapFinto(ascii).use { srv -> assertTrue(ProvaImap(timeoutMs = 3000).prova(mailFinta(pw = ascii, porta = srv.porta)).ok) }
    }

    @Test fun provaImapNonImap() = ImapFinto(pwFinta, saluto = "220 smtp.example.com ESMTP").use { srv ->
        assertEquals(CausaProva.PROTOCOLLO, ProvaImap(timeoutMs = 3000).prova(mailFinta(porta = srv.porta)).causa)
    }

    @Test fun provaImapPortaChiusa_eServerInesistente() {
        val libera = ServerSocket(0).use { it.localPort }
        assertEquals(CausaProva.RETE, ProvaImap(timeoutMs = 2000).prova(mailFinta(porta = libera)).causa)
        val m = mailFinta().copy(imap = ServerPosta("server-che-non-esiste.invalid", 993))
        assertEquals(CausaProva.SERVER_SCONOSCIUTO, ProvaImap(timeoutMs = 3000).prova(m).causa)
    }

    @Test fun provaImapTlsSuServerInChiaro() = ImapFinto(pwFinta).use { srv ->
        val m = mailFinta(porta = srv.porta).copy(imap = ServerPosta("127.0.0.1", srv.porta, Sicurezza.SSL))
        val e = ProvaImap(timeoutMs = 3000).prova(m)
        assertEquals(CausaProva.TLS, e.causa)
        assertTrue(srv.ricevute.isEmpty()) // la password non è partita
    }

    @Test fun provaImapRifiutiSenzaRete() {
        assertEquals(CausaProva.SOLO_APP, ProvaImap().prova(mailFinta(tipo = TipoMail.SAMSUNG)).causa)
        assertEquals(CausaProva.DATI_MANCANTI, ProvaImap().prova(mailFinta(pw = "")).causa)
        val inChiaro = mailFinta().copy(imap = ServerPosta("imap.example.com", 143, Sicurezza.NESSUNA))
        assertEquals(CausaProva.TLS, ProvaImap().prova(inChiaro).causa)
    }

    // ─── protocollo verso la VPS ──────────────────────────────────────────────
    @Test fun protocolloAnteprimaSenzaPassword_salvaConPassword() {
        val a = mailFinta().copy(imap = ServerPosta("imap.example.com", 993))
        val ant = JSONObject(ProtocolloAccount.anteprimaCasella("r1", a))
        assertEquals("account_set", ant.getString("type"))
        assertTrue(ant.getBoolean("anteprima"))
        assertFalse(ant.getJSONObject("dati").has("password"))
        assertFalse(ant.has("conferma"))
        assertEquals("prova", ant.getString("id"))
        val sal = JSONObject(ProtocolloAccount.salvaCasella("r2", a))
        assertEquals(pwFinta, sal.getJSONObject("dati").getString("password"))
        assertEquals("si", sal.getString("conferma"))
        assertEquals(993, sal.getJSONObject("dati").getJSONObject("imap").getInt("porta"))
        val el = JSONObject(ProtocolloAccount.eliminaCasella("r3", "prova"))
        assertEquals("account_elimina", el.getString("type"))
        val cer = JSONObject(ProtocolloAccount.anteprimaCervello("r4", Cervello(ProviderCervello.CLAUDE_CODE_VPS, "sk-ant-oat01-finto")))
        assertFalse(cer.getJSONObject("dati").has("token"))
        assertEquals("claude-code-vps", cer.getString("id"))
    }

    @Test fun protocolloIdVps() {
        assertEquals("nome-cognome", ProtocolloAccount.idVps(mailFinta().copy(id = "mail-nome-cognome")))
        assertEquals("jb-x", ProtocolloAccount.idVps(mailFinta().copy(id = "mail-x")))
    }

    @Test fun protocolloSoloWssPerISegreti() {
        val a = mailFinta()
        val sal = ProtocolloAccount.salvaCasella("r", a)
        assertTrue(ProtocolloAccount.puoPartire("wss://vps.example.com/phone", sal))
        assertFalse(ProtocolloAccount.puoPartire("ws://vps.example.com/phone", sal))
        assertTrue(ProtocolloAccount.puoPartire("ws://127.0.0.1:8793/phone", sal))
        assertTrue(ProtocolloAccount.puoPartire("ws://vps.example.com/phone", ProtocolloAccount.anteprimaCasella("r", a)))
        assertTrue(ProtocolloAccount.puoPartire("ws://vps.example.com/phone", ProtocolloAccount.lista()))
    }

    @Test fun protocolloRisposte() {
        val a = ProtocolloAccount.leggi("""{"type":"account_anteprima","richiesta_id":"r1","tipo":"casella","id":"prova","azione":"nuova","cosa":["aggiunge"],"avvisi":["x"]}""")
        assertTrue(a is RispostaAccount.Anteprima && a.cosa == listOf("aggiunge") && a.avvisi.size == 1)
        val e = ProtocolloAccount.leggi("""{"type":"account_esito","richiesta_id":"r2","tipo":"casella","id":"prova","ok":true,"testo":"fatto"}""")
        assertTrue(e is RispostaAccount.Esito && e.ok)
        val er = ProtocolloAccount.leggi("""{"type":"account_errore","richiesta_id":"r3","motivo":"manca la conferma"}""")
        assertEquals("manca la conferma", (er as RispostaAccount.Errore).motivo)
        val l = ProtocolloAccount.leggi("""{"type":"account_lista","caselle":[{"id":"a","indirizzo":"a@example.com","tipo":"gmail","password":true,"da_app":false}],"cervello":{"CLAUDE_CODE_OAUTH_TOKEN":true}}""")
        l as RispostaAccount.Lista
        assertEquals(1, l.caselle.size)
        assertTrue(l.caselle[0].password)
        assertEquals(true, l.cervello["CLAUDE_CODE_OAUTH_TOKEN"])
        assertNull(ProtocolloAccount.leggi("""{"type":"job_lista"}"""))
        assertNull(ProtocolloAccount.leggi("non json"))
    }

    // ─── cervello: forma e prova contro un server HTTP finto ──────────────────
    private val chiaveAnt = "sk-ant-api03-" + "F".repeat(30)
    private val chiaveOai = "sk-" + "f".repeat(30)

    @Test fun formaDeiToken() {
        val c = ConfiguraCervelloHttp()
        assertNull(c.controllaForma(ProviderCervello.CLAUDE_CODE_VPS, "sk-ant-oat01-" + "a".repeat(30)))
        assertNotNull(c.controllaForma(ProviderCervello.CLAUDE_CODE_VPS, chiaveAnt))
        assertNull(c.controllaForma(ProviderCervello.API_ANTHROPIC, chiaveAnt))
        assertTrue(c.controllaForma(ProviderCervello.API_ANTHROPIC, "sk-ant-oat01-" + "a".repeat(30))!!.contains("abbonamento"))
        assertNull(c.controllaForma(ProviderCervello.API_OPENAI, chiaveOai))
        assertNotNull(c.controllaForma(ProviderCervello.API_OPENAI, " $chiaveOai"))
    }

    @Test fun provaChiaveAnthropic_okE401() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            s.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"type":"authentication_error"}}"""))
            s.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Your credit balance is too low"}}"""))
            val c = ConfiguraCervelloHttp(baseAnthropic = s.url("/").toString().trimEnd('/'))
            val cer = Cervello(ProviderCervello.API_ANTHROPIC, chiaveAnt)
            assertTrue(c.prova(cer).ok)
            val r = s.takeRequest()
            assertEquals("GET", r.method)
            assertTrue(r.path!!.startsWith("/v1/models"))
            assertEquals(chiaveAnt, r.getHeader("x-api-key"))
            assertEquals("2023-06-01", r.getHeader("anthropic-version"))
            val no = c.prova(cer)
            assertFalse(no.ok); assertEquals(401, no.codice); assertFalse(no.messaggio.contains(chiaveAnt))
            assertTrue(c.prova(cer).messaggio.contains("Credito"))
        }
    }

    @Test fun provaChiaveOpenAi_eClaudeCodeNonVaInRete() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            val c = ConfiguraCervelloHttp(baseOpenAi = s.url("/").toString().trimEnd('/'))
            assertTrue(c.prova(Cervello(ProviderCervello.API_OPENAI, chiaveOai)).ok)
            assertEquals("Bearer $chiaveOai", s.takeRequest().getHeader("Authorization"))
            val cc = c.prova(Cervello(ProviderCervello.CLAUDE_CODE_VPS, "sk-ant-oat01-" + "a".repeat(30)))
            assertFalse(cc.ok)
            assertTrue(cc.messaggio.contains("VPS"))
            assertEquals(1, s.requestCount)
        }
    }

    @Test fun testiErroreHttp() {
        assertTrue(ConfiguraCervelloHttp.testoErrore(429, "").contains("Troppe"))
        assertTrue(ConfiguraCervelloHttp.testoErrore(503, "").contains("503"))
        assertTrue(ConfiguraCervelloHttp.testoErrore(429, """{"error":{"code":"insufficient_quota"}}""").contains("Troppe"))
    }

    // ─── sblocco ──────────────────────────────────────────────────────────────
    @Test fun finestraDiSblocco() {
        var t = 1_000L
        val f = FinestraSblocco(60_000L) { t }
        assertFalse(f.sbloccato())
        f.segnaSbloccato(); assertTrue(f.sbloccato())
        t += 59_999; assertTrue(f.sbloccato())
        t += 2; assertFalse(f.sbloccato())
        f.segnaSbloccato(); f.blocca(); assertFalse(f.sbloccato())
    }
}
