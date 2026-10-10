package com.jarvis.telefono.bolla

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Boss, 2026-10-09: «il popup della voce solo quando l'app è chiusa». Questo è il segno «app in primo piano»:
 * un contatore delle schermate di JBoss visibili (onStart +1, onStop −1), aggiornato da [com.jarvis.telefono.JBossApp]
 * con gli ActivityLifecycleCallbacks, e a parte quante chat di JBoss e quante pagine del Postino sono visibili.
 *
 * Puro e senza Android: si prova sulla JVM ([ContatorePrimoPiano]). Le chiamate arrivano dal thread principale
 * (il ciclo di vita delle activity), e lì arrivano anche gli avvisi.
 */
class ContatorePrimoPiano {
    /**
     * Com'è adesso: [app] = almeno una schermata di JBoss visibile; [chatJBoss] = la chat di JBoss visibile;
     * [paginaPostino] = la pagina del Postino visibile (Boss 09/10: «passa al Postino» la apre, e lì la voce resta accesa).
     */
    data class Cambio(val app: Boolean, val chatJBoss: Boolean, val paginaPostino: Boolean = false)

    private var visibili = 0
    private var chat = 0
    private var postino = 0
    private val ascoltatori = CopyOnWriteArrayList<(Cambio) -> Unit>()

    val app: Boolean get() = visibili > 0
    val chatJBoss: Boolean get() = chat > 0
    val paginaPostino: Boolean get() = postino > 0
    val adesso: Cambio get() = Cambio(app, chatJBoss, paginaPostino)

    /** Una schermata di JBoss è diventata visibile (onStart). [eChatJBoss] = è la chat di JBoss; [ePostino] = la pagina del Postino. */
    fun avviata(eChatJBoss: Boolean, ePostino: Boolean = false) {
        val prima = adesso
        visibili++
        if (eChatJBoss) chat++
        if (ePostino) postino++
        avvisa(prima)
    }

    /**
     * Una schermata di JBoss non si vede più (onStop). [cambioConfigurazione] = si sta solo girando lo schermo: la
     * schermata nuova arriva subito, quindi niente avviso (il popup non deve lampeggiare).
     */
    fun fermata(eChatJBoss: Boolean, cambioConfigurazione: Boolean = false, ePostino: Boolean = false) {
        val prima = adesso
        visibili = (visibili - 1).coerceAtLeast(0)
        if (eChatJBoss) chat = (chat - 1).coerceAtLeast(0)
        if (ePostino) postino = (postino - 1).coerceAtLeast(0)
        if (!cambioConfigurazione) avvisa(prima)
    }

    /** [f] a ogni cambio di [app], [chatJBoss] o [paginaPostino]. Restituisce chi smette di ascoltare. */
    fun osserva(f: (Cambio) -> Unit): () -> Unit {
        ascoltatori += f
        return { ascoltatori -= f }
    }

    /** Solo per le prove. */
    fun azzera() {
        visibili = 0; chat = 0; postino = 0; ascoltatori.clear()
    }

    private fun avvisa(prima: Cambio) {
        val ora = adesso
        if (ora == prima) return
        ascoltatori.forEach { runCatching { it(ora) } }
    }
}

/** L'unico contatore dell'app. */
val PrimoPiano = ContatorePrimoPiano()

/**
 * Le regole del popup e dell'ascolto continuo (Boss, 2026-10-09), in un punto solo e senza Android (si provano sulla JVM):
 * 1. il popup compare solo quando JBoss opera FUORI dall'app (app chiusa o in sottofondo), mai dentro;
 * 2. sparisce appena l'app torna davanti e appena l'operazione finisce;
 * 3. l'ascolto continuo dipende dall'APP davanti, non dalla singola schermata: aperto nella chat di JBoss, resta
 *    acceso passando alla pagina del Postino (o a un'altra schermata dell'app) e si chiude solo quando l'app va
 *    dietro senza un'operazione in corso. Pausa e Spegni restano quelli della voce.
 */
object RegolePopup {

    /** Cosa fare quando l'app passa davanti o dietro. */
    enum class Azione { NASCONDI, RIMOSTRA, CHIUDI_CONVERSAZIONE }

    /** Il popup si può disegnare adesso? Solo con l'app chiusa o in sottofondo. */
    fun puoMostrare(appInPrimoPiano: Boolean): Boolean = !appInPrimoPiano

    /**
     * L'app è passata davanti o dietro, o è cambiata la schermata davanti ([c]). [inCorso] = JBoss sta lavorando a una
     * richiesta (pensa, parla, un agente è al lavoro, una conferma aspetta Boss); [conversazioneAperta] = l'ascolto
     * continuo è aperto.
     * - davanti (qualunque schermata dell'app, anche la pagina del Postino): il popup sparisce, l'ascolto continuo resta;
     * - dietro con un'operazione in corso (JBoss ha aperto un'altra app per fare il lavoro): il popup ricompare;
     * - dietro senza operazione: niente popup, e l'ascolto continuo si chiude.
     */
    fun alCambio(c: ContatorePrimoPiano.Cambio, inCorso: Boolean, conversazioneAperta: Boolean): Set<Azione> {
        val out = LinkedHashSet<Azione>()
        if (c.app) {
            out += Azione.NASCONDI
        } else if (inCorso) {
            out += Azione.RIMOSTRA
        } else if (conversazioneAperta) {
            out += Azione.CHIUDI_CONVERSAZIONE
        }
        return out
    }

    /**
     * L'ascolto continuo si può aprire? Dentro l'app sì (la voce va nel filo, senza popup); fuori dall'app solo se il
     * popup si può disegnare ([finestra]: l'accessibilità è accesa).
     */
    fun conversazionePossibile(appInPrimoPiano: Boolean, finestra: Boolean): Boolean = appInPrimoPiano || finestra

    /**
     * Finita la risposta, con l'ascolto continuo aperto: true = si riascolta (dentro l'app, qualunque schermata);
     * false = si chiude, così fuori dall'app il popup sparisce appena l'operazione è finita.
     */
    fun riascoltaDopoRisposta(c: ContatorePrimoPiano.Cambio): Boolean = c.app

    /**
     * Una cattura senza parlato: dentro l'app si riascolta sempre (l'ascolto continuo vive con l'app davanti); fuori
     * dall'app mai (il popup non deve restare sullo schermo ad aspettare).
     */
    fun riascoltaDopoSilenzio(c: ContatorePrimoPiano.Cambio): Boolean = c.app

    /**
     * La parola d'attivazione (o Parla della Home) con l'app davanti ma fuori dalla chat di JBoss e dalla pagina del
     * Postino: si apre la chat, così domanda e risposta finiscono nel filo e non in un popup. Nella pagina del Postino
     * si resta lì (la conversazione è sua dopo «passa al Postino»).
     */
    fun apriChatJBoss(c: ContatorePrimoPiano.Cambio): Boolean = c.app && !c.chatJBoss && !c.paginaPostino

    /**
     * Le schermate che ascoltano da sole appena sono davanti (senza la parola): la chat di JBoss e, con la conversazione
     * passata al Postino, la sua pagina ([conPostino]).
     */
    fun ascoltaDaSola(c: ContatorePrimoPiano.Cambio, conPostino: Boolean): Boolean =
        c.app && (c.chatJBoss || (c.paginaPostino && conPostino))
}
