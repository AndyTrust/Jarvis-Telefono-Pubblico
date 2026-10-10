package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// La macchina a stati delle mani libere, con doppi finti e un orologio finto.
// Ogni prova ricalca un comportamento di MotoreManiLibere (manilibere.py).
class MotoreTest {

    private val ril = RilevatoreFinto()
    private val vad = VadFinto()
    private val ora = OrologioFinto(1_000_000)

    private fun motore(
        opzioni: Opzioni = Opzioni(),
        pulitore: Pulitore? = null,
        verificatore: Verificatore? = null,
    ) = Motore(opzioni, ril, vad, pulitore, verificatore)

    /** Dice la parola chiave e controlla che la cattura si apra. */
    private fun sveglia(m: Motore) {
        ril.scatta = "jboss"
        val ev = m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(Evento.Parola("JBOSS"), ev)
    }

    /** Dà `quanti` ms di un suono; si ferma al primo evento e lo restituisce. */
    private fun dai(m: Motore, suono: ShortArray, quantiMs: Long, altoparlanti: Boolean = false): Evento? {
        val fine = ora.ms + quantiMs
        while (ora.ms < fine) {
            val ev = m.feed(suono, ora.passo(), altoparlanti)
            if (ev != null) return ev
        }
        return null
    }

    @Test
    fun `la parola chiave apre la cattura`() {
        val m = motore()
        sveglia(m)
        assertEquals(Motore.Stato.CATTURA, m.stato)
        assertEquals(1, m.attivazioni)
        assertTrue("dopo lo scatto il KWS si azzera", ril.azzeramenti >= 1)
    }

    @Test
    fun `una parola che non e nella lista non apre niente`() {
        val m = motore(Opzioni(parole = listOf("hey boss")))
        ril.scatta = "  boss "
        assertNull(m.feed(SuoniFinti.voce(), ora.passo(), false))
        assertEquals(Motore.Stato.PAROLA, m.stato)
        assertEquals(0, m.attivazioni)
    }

    @Test
    fun `Hey Jarvis e Jarvis non svegliano piu (0_6_0)`() {
        val m = motore()
        for (w in listOf("hey jarvis", "jarvis")) {
            ril.scatta = w
            assertNull(m.feed(SuoniFinti.voce(), ora.passo(), false))
        }
        assertEquals(0, m.attivazioni)
    }

    @Test
    fun `la parola si normalizza come sul computer`() {
        assertEquals("HEY BOSS", Motore.normalizza("  hey   boss "))
        val m = motore()
        ril.scatta = " jboss"
        assertEquals(Evento.Parola("JBOSS"), m.feed(SuoniFinti.voce(), ora.passo(), false))
    }

    @Test
    fun `nessun parlato entro 10 secondi torna ad aspettare la parola`() {
        val m = motore()
        sveglia(m)
        val inizio = ora.ms
        val ev = dai(m, SuoniFinti.silenzio(), 12_000)
        assertEquals(Evento.NessunParlato, ev)
        assertTrue("non prima di 10 s", ora.ms - inizio > 10_000)
        assertTrue("subito dopo i 10 s", ora.ms - inizio <= 10_030)
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `un clic di due fotogrammi non e parlato vero`() {
        val m = motore()
        sveglia(m)
        dai(m, SuoniFinti.silenzio(), 600)
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(Evento.NessunParlato, dai(m, SuoniFinti.silenzio(), 12_000))
    }

    @Test
    fun `la frase corta si chiude dopo 1,5 secondi di silenzio`() {
        val m = motore()
        sveglia(m)
        assertNull(dai(m, SuoniFinti.voce(), 2_000))
        val ultimaVoce = ora.ms
        val ev = dai(m, SuoniFinti.silenzio(), 8_000)
        assertTrue("attesa una Frase, arrivato $ev", ev is Evento.Frase)
        ev as Evento.Frase
        assertEquals("silenzio", ev.motivo)
        // il primo fotogramma (passo 30 ms) con almeno 1,5 s di silenzio (1.2.4,
        // Boss 07/10: era 3 s): +1500 è multiplo di 30, scatta esatto
        assertEquals(ultimaVoce + 1_500, ora.ms)
        assertEquals(1.5, ev.chiusaDopoSilenzioS, 1e-9)
        assertTrue(ev.accettata)
        assertNull("senza verificatore niente somiglianza", ev.somiglianza)
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `una pausa di respiro di 1,2 secondi non taglia la frase corta`() {
        val m = motore()
        sveglia(m)
        assertNull(dai(m, SuoniFinti.voce(), 1_500))
        assertNull("1,2 s di pausa: la frase resta aperta", dai(m, SuoniFinti.silenzio(), 1_200))
        assertNull(dai(m, SuoniFinti.voce(), 600))
        val ultimaVoce = ora.ms
        val ev = dai(m, SuoniFinti.silenzio(), 8_000)
        assertTrue(ev is Evento.Frase)
        assertEquals(ultimaVoce + 1_500, ora.ms)
    }

    @Test
    fun `dopo 4 secondi di parlato la frase aspetta 2,5 secondi`() {
        val m = motore()
        sveglia(m)
        // un messaggio dettato: 3 s, una pausa per pensare di 2 s, ancora 2 s
        assertNull(dai(m, SuoniFinti.voce(), 3_000))
        assertNull(dai(m, SuoniFinti.voce(), 1_500))   // 4,5 s meno i primi 0,4 s buttati: oltre 4 s di parlato
        assertNull("parlato lungo: 2 s di pausa non chiudono", dai(m, SuoniFinti.silenzio(), 2_000))
        assertNull(dai(m, SuoniFinti.voce(), 2_000))
        val ultimaVoce = ora.ms
        val ev = dai(m, SuoniFinti.silenzio(), 8_000)
        assertTrue("attesa una Frase, arrivato $ev", ev is Evento.Frase)
        ev as Evento.Frase
        assertEquals("silenzio", ev.motivo)
        // 2500 non è multiplo del passo da 30 ms: scatta al primo fotogramma da 2,5 s in su (2,52)
        assertTrue("chiusa dopo ${ora.ms - ultimaVoce} ms", ora.ms - ultimaVoce in 2_500L until 2_530L)
        assertEquals(2.5, ev.chiusaDopoSilenzioS, 0.03)
    }

    @Test
    fun `la finestra di silenzio sale solo dopo il parlato lungo`() {
        val o = Opzioni()
        assertEquals(1.5, o.fineParlatoS, 1e-9)
        assertEquals(1.5, finestraSilenzioS(o, 0.0), 1e-9)
        assertEquals(1.5, finestraSilenzioS(o, 3.97), 1e-9)
        assertEquals(2.5, finestraSilenzioS(o, 4.0), 1e-9)
        assertEquals(2.5, finestraSilenzioS(o, 30.0), 1e-9)
        // una finestra lunga più corta di quella base non accorcia mai
        assertEquals(1.5, finestraSilenzioS(o.copy(fineParlatoLungoS = 1.0), 10.0), 1e-9)
    }

    @Test
    fun `la frase si chiude comunque a 90 secondi`() {
        val m = motore()
        sveglia(m)
        val inizio = ora.ms
        val ev = dai(m, SuoniFinti.voce(), 100_000)
        assertTrue(ev is Evento.Frase)
        ev as Evento.Frase
        assertEquals("limite", ev.motivo)
        assertEquals(90_000, ora.ms - inizio)
        assertEquals(90.0, ev.durataS, 1e-9)
    }

    @Test
    fun `i primi 300 ms dopo la parola si buttano`() {
        val m = motore()
        sveglia(m)
        val inizio = ora.ms
        // fotogrammi a +30 ... +270: 9 fotogrammi buttati
        while (ora.ms - inizio < 270) m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(0, vad.chiamate)
        m.feed(SuoniFinti.voce(), ora.passo(), false)   // +300: il primo che conta
        assertEquals(1, vad.chiamate)
        assertNull(dai(m, SuoniFinti.voce(), 1_000))
        val ev = dai(m, SuoniFinti.silenzio(), 10_000) as Evento.Frase
        // da +300 alla chiusura, un fotogramma ogni 30 ms
        val fotogrammi = (ora.ms - (inizio + 300)) / 30 + 1
        assertEquals(fotogrammi * Opzioni.FRAME_LEN, ev.crudo.size.toLong())
    }

    @Test
    fun `con gli altoparlanti attivi il KWS non gira`() {
        val m = motore(Opzioni(cancelloAttivo = false))
        ril.scatta = "jboss"
        assertNull(dai(m, SuoniFinti.voce(), 3_000, altoparlanti = true))
        assertEquals(0, ril.chiamate)
        assertEquals(Motore.Stato.MUTO, m.stato)
    }

    @Test
    fun `se Jarvis parla durante la cattura la cattura muore`() {
        val m = motore()
        sveglia(m)
        assertNull(dai(m, SuoniFinti.voce(), 2_000))
        assertNull(dai(m, SuoniFinti.voce(), 1_000, altoparlanti = true))
        assertEquals(Motore.Stato.MUTO, m.stato)
        // finito di parlare: nessuna frase esce mai, si torna ad aspettare
        assertNull(dai(m, SuoniFinti.silenzio(), 15_000))
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `la coda eco di 700 ms tiene chiuso il microfono`() {
        val m = motore(Opzioni(cancelloAttivo = false))
        m.feed(SuoniFinti.voce(), ora.passo(), true)
        val fineAltoparlanti = ora.ms
        while (ora.ms - fineAltoparlanti < 690) m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals("fino a +690 niente KWS", 0, ril.chiamate)
        assertTrue(m.muto(fineAltoparlanti + 699, false))
        assertFalse(m.muto(fineAltoparlanti + 700, false))
        m.feed(SuoniFinti.voce(), ora.passo(), false)       // +720
        assertEquals(1, ril.chiamate)
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `sotto la soglia dell impronta la frase non e accettata`() {
        val ver = VerificatoreFinto(pronta = true, valore = 0.41f)
        val m = motore(Opzioni(sogliaImpronta = 0.55f), verificatore = ver)
        sveglia(m)
        dai(m, SuoniFinti.voce(), 2_000)
        val ev = dai(m, SuoniFinti.silenzio(), 8_000) as Evento.Frase
        assertEquals(1, ver.chiamate)
        assertEquals(0.41f, ev.somiglianza)
        assertFalse(ev.accettata)
    }

    @Test
    fun `sopra la soglia dell impronta la frase e accettata`() {
        val ver = VerificatoreFinto(pronta = true, valore = 0.55f)
        val m = motore(Opzioni(sogliaImpronta = 0.55f), verificatore = ver)
        sveglia(m)
        dai(m, SuoniFinti.voce(), 2_000)
        val ev = dai(m, SuoniFinti.silenzio(), 8_000) as Evento.Frase
        assertTrue(ev.accettata)
    }

    @Test
    fun `impronta non pronta vuol dire filtro della voce spento`() {
        val ver = VerificatoreFinto(pronta = false, valore = 0.1f)
        val m = motore(verificatore = ver)
        sveglia(m)
        dai(m, SuoniFinti.voce(), 2_000)
        val ev = dai(m, SuoniFinti.silenzio(), 8_000) as Evento.Frase
        assertEquals(0, ver.chiamate)
        assertNull(ev.somiglianza)
        assertTrue(ev.accettata)
    }

    @Test
    fun `l impronta si calcola sul crudo non sul filtrato`() {
        val ver = VerificatoreFinto(pronta = true, valore = 0.9f)
        val m = motore(pulitore = PulitoreFinto(0.5f), verificatore = ver)
        sveglia(m)
        dai(m, SuoniFinti.voce(), 2_000)
        val ev = dai(m, SuoniFinti.silenzio(), 8_000) as Evento.Frase
        val crudo = ver.ricevuto!!
        assertTrue(crudo === ev.crudo)
        val picco = crudo.maxOrNull()!!
        assertEquals("il crudo ha l'ampiezza vera", 0.3f, picco, 0.01f)
        assertEquals("l'audio per Whisper è quello pulito", 0.15f, ev.audio.maxOrNull()!!, 0.01f)
    }

    @Test
    fun `sotto mezzo secondo di crudo l impronta non si calcola`() {
        // Una frase che si chiude a limite dopo 0,4 s: il crudo è di 14 fotogrammi.
        val ver = VerificatoreFinto(pronta = true, valore = 0.1f)
        val m = motore(Opzioni(maxFraseS = 0.4, ignoraInizialiMs = 0), verificatore = ver)
        sveglia(m)
        val ev = dai(m, SuoniFinti.voce(), 2_000) as Evento.Frase
        assertEquals("limite", ev.motivo)
        assertTrue(ev.crudo.size < Opzioni.RATE / 2)
        assertEquals(0, ver.chiamate)
        assertTrue(ev.accettata)
    }

    @Test
    fun `il rilevatore riceve l audio pulito quando c e il pulitore`() {
        val m = motore(Opzioni(cancelloAttivo = false), pulitore = PulitoreFinto(0.5f))
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(0.15f, ril.ricevuti.single().maxOrNull()!!, 0.01f)
    }

    @Test
    fun `con il filtro rumore spento il pulitore non si usa`() {
        val pul = PulitoreFinto()
        val m = motore(Opzioni(cancelloAttivo = false, filtroRumore = false), pulitore = pul)
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(0, pul.chiamate)
    }

    @Test
    fun `forza cattura apre la cattura senza la parola e chiude sul silenzio`() {
        val m = motore()
        m.forzaCattura(ora.ms)
        assertEquals(Motore.Stato.CATTURA, m.stato)
        assertEquals("il widget non e un'attivazione della parola", 0, m.attivazioni)
        assertNull(dai(m, SuoniFinti.voce(), 2_000))
        val ev = dai(m, SuoniFinti.silenzio(), 5_000)
        assertTrue("attesa una Frase, arrivato $ev", ev is Evento.Frase)
        assertEquals("silenzio", (ev as Evento.Frase).motivo)
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `forza cattura dimentica la coda d eco`() {
        val m = motore()
        // Jarvis parlava fino a un attimo fa: senza forzaCattura il motore
        // resterebbe muto per codaEcoMs.
        m.feed(SuoniFinti.voce(), ora.passo(), true)
        assertEquals(Motore.Stato.MUTO, m.stato)
        m.forzaCattura(ora.ms)
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(Motore.Stato.CATTURA, m.stato)
    }

    @Test
    fun `forza cattura senza parlato torna ad aspettare la parola`() {
        val m = motore()
        m.forzaCattura(ora.ms)
        assertEquals(Evento.NessunParlato, dai(m, SuoniFinti.silenzio(), 12_000))
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `chiudi ora chiude subito la frase se c e stato parlato`() {
        val m = motore()
        m.forzaCattura(ora.ms)
        assertNull(dai(m, SuoniFinti.voce(), 2_000))
        val ev = m.chiudiOra(ora.ms)
        assertTrue("attesa una Frase, arrivato $ev", ev is Evento.Frase)
        ev as Evento.Frase
        assertEquals("widget", ev.motivo)
        assertTrue("c'e l'audio della frase", ev.audio.isNotEmpty())
        assertEquals("chiusa senza aspettare il silenzio", 0.0, ev.chiusaDopoSilenzioS, 0.001)
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `chiudi ora senza parlato torna alla parola con nessun parlato`() {
        val m = motore()
        m.forzaCattura(ora.ms)
        assertNull(dai(m, SuoniFinti.silenzio(), 1_000))
        assertEquals(Evento.NessunParlato, m.chiudiOra(ora.ms))
        assertEquals(Motore.Stato.PAROLA, m.stato)
    }

    @Test
    fun `chiudi ora in attesa della parola non fa niente`() {
        val m = motore()
        m.feed(SuoniFinti.voce(), ora.passo(), false)
        assertEquals(Motore.Stato.PAROLA, m.stato)
        val azzeramenti = ril.azzeramenti
        assertNull(m.chiudiOra(ora.ms))
        assertEquals(Motore.Stato.PAROLA, m.stato)
        assertEquals("il KWS non si tocca", azzeramenti, ril.azzeramenti)
    }

    @Test
    fun `riblocca rimette il flusso in blocchi fissi e tiene il resto`() {
        val r = Riblocca(512)
        assertTrue(r.aggiungi(FloatArray(480) { 1f }).isEmpty())
        val due = r.aggiungi(FloatArray(600) { 2f })
        assertEquals(2, due.size)
        assertEquals(1f, due[0][479])
        assertEquals(2f, due[0][480])
        assertEquals(1080 - 1024, r.inAttesa)
        r.azzera()
        assertEquals(0, r.inAttesa)
    }
}
