package com.jarvis.telefono.postino

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * La posta di JBoss e del Postino, UNA per tutta l'app (Boss, 2026-10-09: «ci sono interferenze, JBoss dovrebbe subito
 * collegarsi al Postino e aggiornare la pagina della posta»). Codice puro, provato sulla JVM.
 *
 * Prima la conversazione ([SessionePostino]) stava dentro la pagina del Postino e ascoltava la VPS solo a pagina aperta;
 * JBoss non la vedeva e leggeva la posta da un'altra strada. Adesso:
 * - un canale solo ([collegaCanale]), che ascolta sempre: ogni evento della VPS aggiorna questo stato anche a pagina chiusa;
 * - la pagina del Postino e la chat di JBoss guardano lo stesso stato ([osserva]) e la stessa mail corrente ([corrente]);
 * - ogni cancellazione (cestina, spam) passa da [chiediConferma]: senza il tocco su Conferma non parte niente,
 *   da qualunque parte arrivi (campo della pagina, swipe, voce, chat di JBoss);
 * - i lavori chiesti da JBoss tornano a JBoss a fine lavoro ([Evento.FineJBoss]), le mail nuove come riga ([Evento.NuoveMail]).
 *
 * Tutto sul thread principale (gli eventi del modulo VPS arrivano già lì).
 */
class PostaCondivisa(
    val sessione: SessionePostino = SessionePostino(),
    private val ora: () -> Long = System::currentTimeMillis,
    /** Per la scadenza dei comandi in attesa del collegamento (Handler.postDelayed in app; a mano nelle prove). */
    private val pianifica: (Long, () -> Unit) -> Unit = { _, _ -> },
) {
    /** Chi ha chiesto: la pagina del Postino o JBoss (a JBoss la risposta arriva a fine lavoro). */
    enum class Da { PAGINA, JBOSS }

    /** Un sì che serve prima di una cancellazione. Si mostra nel filo di JBoss e nel riquadro della pagina. */
    data class RichiestaConferma(
        val chiave: String,
        val comandi: List<String>,
        val numeri: List<Int>,
        val titolo: String,
        val corpo: String,
        val etichettaSi: String,
        val da: Da,
    )

    sealed class Esito {
        data class Partito(val id: String) : Esito()
        data class DaConfermare(val richiesta: RichiestaConferma) : Esito()
        data class Errore(val motivo: String) : Esito()
        object Annullato : Esito()
    }

    sealed class Evento {
        /** Un lavoro chiesto da JBoss è finito: [testo] è la risposta da dire e scrivere nel filo. */
        data class FineJBoss(val idLavoro: String, val testo: String, val errore: Boolean) : Evento()
        /** Un resoconto nuovo con mail nuove, non chiesto da JBoss: una riga nel filo, niente popup. */
        data class NuoveMail(val testo: String, val nuove: Int) : Evento()
        /** Una conferma chiesta o chiusa: il filo di JBoss mostra o toglie il box. */
        data class Conferma(val richiesta: RichiestaConferma?, val chiusa: String?) : Evento()
        /**
         * 09/10 (verificatore: «la mail letta resta solo nella pagina del Postino»): la pagina mostra una mail nuova (tocco,
         * ◀ ▶, «leggi le mail» o «avanti» scritti lì). Il filo di JBoss la riceve come riga: mittente, oggetto, sunto breve.
         */
        data class Letta(val numero: Int, val testo: String) : Evento()
    }

    /** La risposta di JBoss a una frase sulla posta. [idLavoro]: il comando resta aperto finché la VPS non finisce. */
    data class RispostaJBoss(val dire: String, val errore: Boolean = false, val idLavoro: String? = null, val chiaveConferma: String? = null)

    val stato: StatoPostino get() = sessione.stato

    var canale: CanalePostino? = null
        private set

    /** La mail su cui stanno JBoss e la pagina (numero del resoconto). */
    var corrente: Int? = null
        private set

    var daConfermare: RichiestaConferma? = null
        private set

    /** Cresce a ogni cambio: chi guarda sa che c'è da ridisegnare (anche nelle prove). */
    var versione = 0L
        private set

    var suEvento: ((Evento) -> Unit)? = null

    private var ultimoUso = 0L
    private var viste = 0
    private var reportAvvisato: String? = null
    /** Il testo dell'ultimo avviso delle mail da fare: un resoconto nuovo con le stesse mail non lo ripete. */
    private var testoAvvisato: String? = null
    /** L'ultima mail detta o mostrata (resoconto, numero): la stessa non si riscrive nel filo di JBoss. */
    private var ultimaLetta: Pair<String?, Int>? = null
    private val inViaggio = mutableMapOf<Int, String>()
    private val diJBoss = linkedMapOf<String, String>()
    /** I resoconti partiti e non finiti: un secondo «resoconto» aspetta quello, non ne apre un altro (lista doppia). */
    private val resoconti = linkedSetOf<String>()
    private val coda = mutableListOf<Triple<String, String, JSONObject>>()
    private val osservatori = CopyOnWriteArrayList<() -> Unit>()

    val collegato: Boolean get() = canale?.collegato == true

    fun osserva(f: () -> Unit): () -> Unit {
        osservatori += f
        return { osservatori -= f }
    }

    private fun cambiato() {
        versione++
        osservatori.forEach { runCatching { it() } }
    }

    // ---------------------------------------------------------------- il canale

    /** Il canale unico. Ascolta da subito e per sempre (la pagina chiusa non stacca niente). */
    fun collegaCanale(c: CanalePostino?) {
        if (c === canale) return
        canale?.let { it.ascoltatore = null; it.suCollegamento = null; it.chiudi() }
        canale = c
        if (c == null) { cambiato(); return }
        c.ascoltatore = { ricevi(it) }
        c.suCollegamento = { su -> if (su) riprendi(); cambiato() }
        c.tieniAperto(serveAperto())
        if (c.collegato) riprendi()
        cambiato()
    }

    /** La pagina del Postino ([pagina]) o la chat di JBoss si apre: il canale si collega subito. */
    fun vistaAperta(pagina: Boolean) {
        viste++
        canale?.tieniAperto(true)
        if (pagina) canale?.paginaVisibile(true)
        if (collegato) riprendi()
    }

    fun vistaChiusa(pagina: Boolean) {
        viste = (viste - 1).coerceAtLeast(0)
        if (pagina) canale?.paginaVisibile(false)
        canale?.tieniAperto(serveAperto())
    }

    private fun serveAperto() = viste > 0 || coda.isNotEmpty()

    /** Al collegamento: partono i comandi in attesa e si riprendono i lavori non finiti (gli eventi visti si scartano). */
    private fun riprendi() {
        val c = canale ?: return
        if (!c.collegato) return
        val partiti = coda.toList()
        coda.clear()
        partiti.forEach { (id, t, op) -> c.avvia(id, t, op) }
        sessione.daSeguire().filter { (id, _) -> partiti.none { it.first == id } }.forEach { (id, n) -> c.segui(id, n) }
        c.tieniAperto(serveAperto())
    }

    /** I comandi rimasti in attesa del collegamento troppo a lungo: si chiudono con il motivo, non restano «in corso». */
    fun scadiCoda(motivo: String = "La VPS non si è collegata in tempo: non è partito niente.") {
        if (coda.isEmpty()) return
        val via = coda.toList()
        coda.clear()
        via.forEach { (id, _, _) -> ricevi(JSONObject().put("type", "job_errore").put("id", id).put("motivo", motivo)) }
        canale?.tieniAperto(serveAperto())
    }

    // ---------------------------------------------------------------- comandi

    /**
     * Un comando per numero («cestina 3», «controlla la posta»). Cestina e spam non partono da qui: tornano
     * [Esito.DaConfermare] e aspettano il tocco su Conferma ([rispondiConferma]). [confermato] lo usa solo quel tocco.
     */
    fun manda(testo: String, da: Da = Da.PAGINA, confermato: Boolean = false): Esito {
        val pulito = testo.trim()
        if (pulito.isEmpty()) return Esito.Errore("comando vuoto")
        val c = canale ?: return Esito.Errore(NON_COLLEGATO)
        val richieste = try { ComandiPostino.capisci(pulito) } catch (e: ComandiPostino.Errore) { return Esito.Errore(e.message ?: "comando non capito") }
        // 09/10 (Boss: «dopo "resoconto" la lista è duplicata»): un resoconto già in corso non se ne apre un secondo
        // (due giri insieme = due bolle e le schede rinumerate a metà). Chi lo chiede aspetta quello.
        val eResoconto = richieste.all { it.verbo == "resoconto" && it.casella == null }
        if (eResoconto) resocontoInCorso()?.let { gia ->
            if (da == Da.JBOSS) diJBoss.getOrPut(gia) { "controlla la posta" }
            return Esito.Partito(gia)
        }
        if (!confermato && richieste.any { it.verbo in IRREVERSIBILI }) {
            return Esito.DaConfermare(chiedi(listOf(pulito), richieste.flatMap { it.numeri }.distinct().sorted(),
                "Confermi? " + ComandiPostino.anteprima(richieste).removePrefix("Farò: "), "Conferma", da))
        }
        val (id, t, op) = try { sessione.prepara(pulito) } catch (e: ComandiPostino.Errore) { return Esito.Errore(e.message ?: "comando non capito") }
        if (da == Da.JBOSS) diJBoss[id] = t
        if (eResoconto) resoconti += id
        if (c.collegato) c.avvia(id, t, op)
        else {
            coda += Triple(id, t, op)
            c.tieniAperto(true)
            pianifica(ATTESA_COLLEGAMENTO_MS) { if (coda.any { it.first == id }) scadiCoda() }
        }
        cambiato()
        return Esito.Partito(id)
    }

    /** Il primo comando subito, i successivi solo dopo che la VPS ha verificato il primo (Fatto = «letta», poi «sposta»). */
    fun mandaCatena(comandi: List<String>, da: Da = Da.PAGINA, confermato: Boolean = false): Esito {
        val primo = comandi.firstOrNull() ?: return Esito.Errore("niente da mandare")
        val r = try { ComandiPostino.capisci(primo).first() } catch (e: ComandiPostino.Errore) { return Esito.Errore(e.message ?: "comando non capito") }
        val e = manda(primo, da, confermato)
        if (e is Esito.Partito) {
            if (comandi.size > 1) sessione.accoda(e.id, r.numeri, r.verbo, comandi.drop(1))
            segnaInViaggio(r.numeri, e.id)
        }
        return e
    }

    /** Il resoconto partito e non ancora finito, se c'è. */
    fun resocontoInCorso(): String? {
        resoconti.retainAll(sessione.inCorso)
        return resoconti.lastOrNull()
    }

    fun segnaInViaggio(numeri: Collection<Int>, id: String) { numeri.forEach { inViaggio[it] = id } }

    /** La mail [n] è in un lavoro mandato (dalla pagina o da JBoss) e non ancora finito. */
    fun inViaggio(n: Int): Boolean {
        val id = inViaggio[n] ?: return false
        if (id in sessione.inCorso) return true
        inViaggio.remove(n)
        return false
    }

    // ---------------------------------------------------------------- conferme

    /** Elimina e Spam: la domanda nel filo e nella pagina. Il comando parte solo dal tocco su Conferma. */
    fun chiediConferma(a: AzioniPostino.Azione, numeri: List<Int>, da: Da): RichiestaConferma? {
        if (numeri.isEmpty() || !AzioniPostino.chiedeConferma(a)) return null
        val el = numeri.distinct().sorted()
        return chiedi(AzioniPostino.comandi(a, el), el, AzioniPostino.domanda(a, el),
            if (a == AzioniPostino.Azione.ELIMINA) "Elimina" else "Spam", da)
    }

    private fun chiedi(comandi: List<String>, numeri: List<Int>, titolo: String, si: String, da: Da): RichiestaConferma {
        daConfermare?.let { suEvento?.invoke(Evento.Conferma(null, it.chiave)) }
        val corpo = numeri.take(6).joinToString("\n") { n ->
            stato.voce(n)?.let { "$n · ${it.da} — ${it.oggetto}".take(90) } ?: "$n"
        } + if (numeri.size > 6) "\n… e altre ${numeri.size - 6}" else ""
        val r = RichiestaConferma("posta:${ora()}:${numeri.joinToString("-")}", comandi, numeri, titolo, corpo, si, da)
        daConfermare = r
        suEvento?.invoke(Evento.Conferma(r, null))
        cambiato()
        return r
    }

    /** Il tocco su Conferma ([si]) o Annulla. Una chiave vecchia (già chiusa) non fa niente. */
    fun rispondiConferma(chiave: String, si: Boolean): Esito {
        val r = daConfermare?.takeIf { it.chiave == chiave } ?: return Esito.Errore("Questa conferma non c'è più.")
        daConfermare = null
        suEvento?.invoke(Evento.Conferma(null, chiave))
        if (!si) { cambiato(); return Esito.Annullato }
        val e = mandaCatena(r.comandi, r.da, confermato = true)
        // Il sì dato qui vale anche per la conferma della VPS sullo stesso lavoro (blocchi di più di 15 mail): una sola.
        if (e is Esito.Partito) PreConferme.registra(e.id, "cancellazione")
        // la mail corrente se ne va: si passa alla prossima da fare (la pagina e JBoss la vedono insieme)
        if (e is Esito.Partito && corrente in r.numeri) {
            corrente = prossimaDa(corrente!!, +1, escluse = r.numeri.toSet()) ?: prossimaDa(corrente!!, -1, escluse = r.numeri.toSet())
        }
        cambiato()
        return e
    }

    // ---------------------------------------------------------------- la mail corrente

    /**
     * La pagina o JBoss aprono la mail [n]. false se nel resoconto non c'è.
     * [annuncia] = [Da.PAGINA]: la mail si vede nella pagina; se non è quella già detta o mostrata, il filo di JBoss la
     * riceve come riga ([Evento.Letta]). Da JBoss ([Da.JBOSS]) no: la sua risposta è già nel filo.
     */
    fun apri(n: Int, annuncia: Da? = null): Boolean {
        if (stato.voce(n) == null) return false
        ultimoUso = ora()
        val chiave = stato.reportId to n
        if (annuncia == Da.PAGINA && ultimaLetta != chiave) suEvento?.invoke(Evento.Letta(n, rigaLettura(n)))
        ultimaLetta = chiave
        if (corrente != n) { corrente = n; cambiato() }
        return true
    }

    /** La riga del filo di JBoss per una mail mostrata nella pagina: mittente, oggetto, sunto breve. Niente id. */
    fun rigaLettura(n: Int): String {
        val v = stato.voce(n) ?: return "La mail $n non c'è nel resoconto."
        val el = stato.filtra(StatoPostino.Filtro.DA_FARE).map { it.numero }
        val pos = el.indexOf(n).takeIf { it >= 0 }?.let { ", ${it + 1} di ${el.size}" }.orEmpty()
        val testo = stato.schede[n]?.riassunto?.ifBlank { null } ?: v.anteprima
        val sunto = AzioniPostino.riassuntoBreve(testo, 160)
        return "Mail $n$pos. Da ${v.da}, «${v.oggetto}».${if (sunto.isNotEmpty()) " $sunto" else ""}"
    }

    /** Boss sta scorrendo le mail con JBoss: le frasi corte («avanti», «cancella questa») sono per il Postino. */
    fun inPosta(): Boolean = corrente != null && ora() - ultimoUso < FINESTRA_MS

    private fun daFare(escluse: Set<Int> = emptySet()): List<Int> =
        stato.filtra(StatoPostino.Filtro.DA_FARE).map { it.numero }.filter { it !in escluse && !inViaggio(it) }

    /** La prossima mail da fare dopo (o prima) di [da]; null se non c'è. */
    fun prossimaDa(da: Int, passo: Int, escluse: Set<Int> = emptySet()): Int? {
        val el = daFare(escluse)
        return if (passo > 0) el.firstOrNull { it > da } else el.lastOrNull { it < da }
    }

    /** Il testo che JBoss legge per la mail [n]: posizione, mittente, oggetto, riassunto. */
    fun lettura(n: Int, intera: Boolean = false): String {
        val v = stato.voce(n) ?: return "La mail $n non c'è nel resoconto."
        val el = stato.filtra(StatoPostino.Filtro.DA_FARE).map { it.numero }
        val pos = el.indexOf(n).takeIf { it >= 0 }?.let { ", ${it + 1} di ${el.size} da fare" }.orEmpty()
        val sc = stato.schede[n]
        val testo = sc?.riassunto?.ifBlank { null } ?: v.anteprima
        val riass = if (intera) testo.trim() else AzioniPostino.riassuntoBreve(testo, 300)
        val allegati = if (v.allegati > 0) " ${v.allegati} ${if (v.allegati == 1) "allegato" else "allegati"}." else ""
        val st = v.stato?.let { " Stato: ${StatoPostino.rigaStato(it)}." }.orEmpty()
        return "Mail $n$pos. Da ${v.da}, oggetto «${v.oggetto}».$allegati${if (riass.isNotEmpty()) " $riass" else ""}$st"
    }

    // ---------------------------------------------------------------- JBoss

    /** Una frase di JBoss già capita come posta ([ComandiPostaJBoss]). Sempre una risposta onesta. */
    fun perJBoss(cmd: ComandiPostaJBoss.Comando, da: Da = Da.JBOSS): RispostaJBoss {
        if (canale == null) return RispostaJBoss(NON_COLLEGATO, errore = true)
        fun resoconto(): RispostaJBoss = when (val e = manda("controlla la posta", da)) {
            is Esito.Partito -> RispostaJBoss("Leggo tutte le caselle sulla VPS: appena ho il resoconto ti leggo la prima mail da fare.", idLavoro = e.id)
            is Esito.Errore -> RispostaJBoss(e.motivo, errore = true)
            else -> RispostaJBoss("Non è partito niente.", errore = true)
        }
        if (cmd != ComandiPostaJBoss.Comando.Aggiorna && stato.reportId == null) {
            // senza resoconto l'unico lavoro possibile è un resoconto: se è già partito (dalla pagina) JBoss aspetta quello
            val gia = sessione.inCorso.lastOrNull() ?: return resoconto()
            if (da == Da.JBOSS) diJBoss.getOrPut(gia) { "controlla la posta" }
            return RispostaJBoss("Il Postino sta già leggendo le caselle: ti leggo la prima mail appena arriva il resoconto.", idLavoro = gia)
        }
        return when (cmd) {
            ComandiPostaJBoss.Comando.Aggiorna -> resoconto()
            ComandiPostaJBoss.Comando.Leggi -> {
                val n = corrente?.takeIf { it in daFare() } ?: daFare().firstOrNull()
                if (n == null) RispostaJBoss("${stato.rigaRiassunto()}. Nessuna mail da fare: di' «aggiorna la posta» per rileggere le caselle.")
                else { apri(n, da); RispostaJBoss("${stato.rigaRiassunto()}. ${lettura(n)}") }
            }
            ComandiPostaJBoss.Comando.Avanti, ComandiPostaJBoss.Comando.Indietro -> {
                val avanti = cmd == ComandiPostaJBoss.Comando.Avanti
                val prima = corrente
                val n = if (prima == null) daFare().firstOrNull() else prossimaDa(prima, if (avanti) +1 else -1)
                if (n == null) RispostaJBoss(if (avanti) "Era l'ultima mail da fare." else "Era la prima mail da fare.")
                else { apri(n, da); RispostaJBoss(lettura(n)) }
            }
            ComandiPostaJBoss.Comando.Rileggi -> {
                val n = corrente ?: return RispostaJBoss("Quale mail? Di' «leggi le mail» o «leggi la mail 3».")
                apri(n)
                if (stato.schede[n] != null) RispostaJBoss(lettura(n, intera = true))
                else when (val e = manda("apri $n", da)) {
                    is Esito.Partito -> RispostaJBoss("Apro la mail $n sulla VPS: leggo allegati e testo, poi te la dico tutta.", idLavoro = e.id)
                    is Esito.Errore -> RispostaJBoss(e.motivo, errore = true)
                    else -> RispostaJBoss(lettura(n))
                }
            }
            is ComandiPostaJBoss.Comando.Apri ->
                if (apri(cmd.numero, da)) RispostaJBoss(lettura(cmd.numero)) else RispostaJBoss("La mail ${cmd.numero} non c'è nel resoconto.", errore = true)
            is ComandiPostaJBoss.Comando.Cancella -> {
                val n = cmd.numero ?: corrente ?: return RispostaJBoss("Quale mail cancello? Di' «leggi le mail» o «cancella la mail 3».")
                if (stato.voce(n) == null) return RispostaJBoss("La mail $n non c'è nel resoconto.", errore = true)
                apri(n)
                val r = chiediConferma(AzioniPostino.Azione.ELIMINA, listOf(n), da)!!
                val v = stato.voce(n)!!
                RispostaJBoss("Elimino la mail $n, da ${v.da}, «${v.oggetto}»? Tocca Elimina nel riquadro qui sotto: a voce non cancello niente.",
                    chiaveConferma = r.chiave)
            }
            is ComandiPostaJBoss.Comando.CancellaPiu -> {
                val mancano = cmd.numeri.filter { stato.voce(it) == null }
                if (mancano.isNotEmpty()) return RispostaJBoss("Nel resoconto non c'è ${chiNon(mancano)}: non ho toccato niente.", errore = true)
                val r = chiediConferma(AzioniPostino.Azione.ELIMINA, cmd.numeri, da)!!
                RispostaJBoss("Elimino ${cmd.numeri.size} mail: ${ComandiPostino.elencoCorto(r.numeri)}? Tocca Elimina nel riquadro qui sotto: " +
                    "a voce non cancello niente.", chiaveConferma = r.chiave)
            }
        }
    }

    /**
     * 09/10 (Boss: «dal Postino devo poter parlare libero: leggi le mail, avanti, cancella questa, aggiorna la posta»):
     * una frase per il Postino, dalla sua pagina ([Da.PAGINA]) o dalla chat di JBoss passata al Postino ([Da.JBOSS]).
     * Prima le frasi libere di [ComandiPostaJBoss] (lo stesso riconoscimento di JBoss, con le frasi corte sempre valide:
     * qui si sta parlando di posta), poi i comandi a numeri di [ComandiPostino] («cestina 3 e 5», «sposta 2 in Rumore»).
     * Cestina e spam tornano con [RispostaJBoss.chiaveConferma]: partono solo dal tocco su Elimina. null = non capita.
     *
     * [nellaPagina]: la frase si vede anche nella conversazione della pagina del Postino (scritta lì, o detta con la
     * pagina davanti dopo «passa al Postino»). Un lavoro nuovo della VPS mette già le sue bolle; una lettura, una domanda
     * di conferma o «sto già leggendo» no: domanda e risposta vanno nella conversazione, una volta sola.
     */
    fun perPostino(frase: String, da: Da, nellaPagina: Boolean = da == Da.PAGINA): RispostaJBoss? {
        val bollePrima = sessione.bolle.size
        val r = rispostaPostino(frase, da) ?: return null
        if (nellaPagina && sessione.bolle.size == bollePrima) {
            sessione.dialogo(frase, r.dire, r.errore)
            cambiato()
        }
        return r
    }

    private fun rispostaPostino(frase: String, da: Da): RispostaJBoss? {
        ComandiPostaJBoss.capisci(frase, inPosta = true)?.let { return perJBoss(it, da) }
        val richieste = try { ComandiPostino.capisci(frase.trim()) } catch (e: ComandiPostino.Errore) { return null }
        if (canale == null) return RispostaJBoss(NON_COLLEGATO, errore = true)
        return when (val e = manda(frase, da)) {
            is Esito.Partito -> RispostaJBoss(ComandiPostino.anteprima(richieste), idLavoro = e.id)
            is Esito.DaConfermare -> RispostaJBoss("${e.richiesta.titolo} Tocca ${e.richiesta.etichettaSi} nel riquadro qui sotto: " +
                "a voce non cancello niente.", chiaveConferma = e.richiesta.chiave)
            is Esito.Errore -> RispostaJBoss(e.motivo, errore = true)
            Esito.Annullato -> RispostaJBoss("Annullato: non ho toccato niente.")
        }
    }

    /** Una domanda e la risposta nella conversazione della pagina (senza lavoro della VPS), e chi guarda ridisegna. */
    fun dialogo(domanda: String, risposta: String, errore: Boolean = false) {
        sessione.dialogo(domanda, risposta, errore)
        cambiato()
    }

    private fun chiNon(nn: List<Int>) = if (nn.size == 1) "la mail ${nn[0]}" else "le mail ${ComandiPostino.elencoCorto(nn)}"

    /** La risposta per JBoss a fine di un suo lavoro. */
    private fun rispostaFine(comando: String, m: JSONObject): Pair<String, Boolean> {
        val tipo = m.optString("type")
        if (tipo == "job_errore") return "Il Postino non è partito: ${m.optString("motivo")}" to true
        when (m.optString("esito")) {
            "annullato" -> return "Annullato: non ho toccato niente." to false
            "errore" -> return "Il Postino non ci è riuscito: ${stato.ultimoErrore ?: m.optString("riassunto")}" to true
        }
        val r = runCatching { ComandiPostino.capisci(comando) }.getOrNull()?.firstOrNull()
            ?: return stato.ultimaFrase.ifEmpty { m.optString("riassunto") } to false
        return when (r.verbo) {
            "resoconto" -> {
                // 09/10: un numero solo (quello della Home e della pagina), niente «nuove» delle caselle accanto.
                val testa = StatoPostino.italiano("Ho letto ${stato.caselle.size} caselle: ${stato.rigaRiassunto()}.")
                val n = daFare().firstOrNull()
                if (n == null) "$testa Nessuna mail da fare." to false
                else { apri(n); "$testa ${lettura(n)}" to false }
            }
            "apri" -> lettura(r.numeri.first(), intera = true) to false
            "cestina", "spam" -> {
                val fatte = r.numeri.filter { stato.voce(it)?.stato?.let { s -> s.fatto && s.verbo == r.verbo } == true }
                val dove = if (r.verbo == "spam") "nello Spam" else "nel Cestino"
                val testa = when {
                    fatte.size == r.numeri.size -> "Fatto: ${chi(r.numeri)} $dove, verificato sulla casella."
                    fatte.isEmpty() -> "La VPS non ha confermato lo spostamento ${dove}: ${stato.ultimaFrase}"
                    else -> "Solo ${chi(fatte)} $dove; le altre no: ${stato.ultimaFrase}"
                }
                val n = corrente?.takeIf { it !in r.numeri && stato.voce(it) != null }
                (if (n == null) "$testa Nessun'altra mail da fare." else "$testa Prossima: ${lettura(n)}") to fatte.isEmpty()
            }
            else -> stato.ultimaFrase.ifEmpty { m.optString("riassunto") } to false
        }
    }

    private fun chi(nn: List<Int>) = if (nn.size == 1) "la mail ${nn[0]} è" else "le mail ${ComandiPostino.elencoCorto(nn)} sono"

    // ---------------------------------------------------------------- dalla VPS

    /** Un messaggio del modulo VPS. true se riguardava il Postino (lo stato è cambiato). */
    fun ricevi(m: JSONObject): Boolean {
        val id = m.optString("id")
        val primaReport = stato.reportId
        if (!sessione.ricevi(m)) return false
        // un resoconto nuovo rinumera le mail: la corrente di prima non vale più
        if (stato.reportId != primaReport) { corrente = null; inViaggio.clear() }
        sessione.prendiPronti().forEach { mandaCatena(it, confermato = true) }
        val tipo = m.optString("type")
        // 09/10: l'avviso arriva a resoconto finito (con «totali» le mail non sono ancora arrivate) e dice IL numero
        // della posta ([StatoPostino.numeroUnico]), lo stesso della Home e della pagina.
        if (tipo == "job_done" && stato.reportId != null && stato.reportId != reportAvvisato) {
            reportAvvisato = stato.reportId
            val daFare = stato.numeroUnico() ?: 0
            // 09/10 (verificatore: due righe uguali «Postino: 6 mail…» alle 14:06 e alle 14:07): un resoconto nuovo con le
            // stesse mail da fare (un altro giro, la pagina riaperta, «resoconto» scritto) non ripete l'avviso.
            val testo = testoNuove(daFare)
            if (daFare > 0 && id !in diJBoss && testo != testoAvvisato) {
                testoAvvisato = testo
                suEvento?.invoke(Evento.NuoveMail(testo, daFare))
            }
        }
        if (tipo == "job_done" || tipo == "job_errore") {
            diJBoss.remove(id)?.let { comando ->
                val (testo, errore) = rispostaFine(comando, m)
                suEvento?.invoke(Evento.FineJBoss(id, testo, errore))
            }
        }
        cambiato()
        return true
    }

    private fun testoNuove(daFare: Int): String {
        val dove = stato.daFarePerCasella()
        return "Postino: ${if (daFare == 1) "1 mail da fare" else "$daFare mail da fare"}" + (if (dove.isNotEmpty()) " ($dove)" else "") +
            ". Di' «leggi le mail» e te le leggo una alla volta."
    }

    /** Il lavoro [id] è stato chiesto da JBoss e non è ancora finito. */
    fun diJBoss(id: String): Boolean = id in diJBoss

    companion object {
        const val NON_COLLEGATO = "Il Postino non è collegato: accendi il Collegamento Jarvis in Impostazioni. Non ho mandato niente."
        /** Dopo questo tempo senza parlarne, «avanti» e «cancella questa» tornano a JBoss. */
        const val FINESTRA_MS = 10 * 60_000L
        const val ATTESA_COLLEGAMENTO_MS = 20_000L
        /** I verbi che non partono senza il tocco su Conferma (Boss 09/10: ogni cancellazione è irreversibile). */
        val IRREVERSIBILI = setOf("cestina", "spam")

        /**
         * Un avviso del Postino con l'app davanti va nel filo di JBoss come riga, non come notifica a comparsa
         * (Boss 09/10). Con l'app chiusa resta la notifica.
         */
        fun avvisoComeRiga(filoOAgente: String, appDavanti: Boolean): Boolean = filoOAgente == "postino" && appDavanti

        /** Dopo questo tempo lo stesso avviso delle mail può tornare nel filo (come la coda delle notifiche). */
        const val FINESTRA_AVVISI_MS = 30 * 60_000L

        private val CONTEGGIO = Regex("(\\d+) mail (nuove|nuova|da fare)\\s*(\\(([^)]*)\\))?")

        /**
         * L'impronta di un avviso delle mail: il numero e le caselle («6|gmail 1 lavoro 4 jarvis 1»), da qualunque strada
         * arrivi (resoconto del telefono «6 mail da fare», report del Command Center «Postino · Postino: 6 mail nuove»).
         * Un avviso senza conteggio: il testo senza maiuscole, punteggiatura e «Postino» davanti.
         */
        fun improntaAvviso(testo: String): String {
            fun pulisci(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            CONTEGGIO.find(testo.lowercase())?.let { m -> return m.groupValues[1] + "|" + pulisci(m.groupValues[4]) }
            return pulisci(testo).replace(Regex("^(postino )+"), "")
        }

        /**
         * 09/10: l'avviso [testo] è già nel filo di JBoss? [precedenti] = gli avvisi già scritti (testo, quando). Lo stesso
         * conteggio entro [FINESTRA_AVVISI_MS] è un doppione: non si riscrive (due giri, report del ponte e del telefono).
         */
        fun avvisoDoppione(testo: String, ora: Long, precedenti: List<Pair<String, Long>>): Boolean {
            val imp = improntaAvviso(testo)
            return precedenti.any { (t, quando) -> ora - quando in 0 until FINESTRA_AVVISI_MS && improntaAvviso(t) == imp }
        }
    }
}
