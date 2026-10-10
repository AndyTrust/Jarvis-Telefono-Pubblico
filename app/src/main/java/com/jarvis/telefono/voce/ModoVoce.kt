package com.jarvis.telefono.voce

/**
 * L'interruttore della voce (Boss, 2026-10-09: «la possibilità di attivarlo e disattivarlo dal popup
 * stesso. Poi dalle notifiche posso spegnerlo o metterlo in pausa, quindi non ascolta»).
 *
 * - [ACCESO]: JBoss ascolta (la parola «Hey Boss» e, col popup aperto, la conversazione).
 * - [PAUSA]: non ascolta. Microfono rilasciato, nessun riconoscimento in corso. Si riprende con un tocco.
 * - [SPENTO]: come la pausa, ma scelto per restare: dalla notifica resta «Riprendi» ed «Esci».
 *
 * Lo stato è scritto in Prefs (sopravvive al popup chiuso, al processo ucciso, al riavvio).
 * Il servizio resta vivo anche in pausa e spento: la notifica con «Riprendi» deve esserci.
 * Puro: si prova sulla JVM (ModoVoceTest).
 */
enum class ModoVoce {
    ACCESO, PAUSA, SPENTO;

    /** Vero solo da acceso: il microfono si apre solo qui. */
    val ascolta: Boolean get() = this == ACCESO

    /** Il testo che si salva in Prefs. */
    val chiave: String get() = name.lowercase()

    companion object {
        /** Dal testo di Prefs; qualunque valore sconosciuto o assente = acceso (il comportamento di prima). */
        fun da(testo: String?): ModoVoce = entries.firstOrNull { it.chiave == testo?.trim()?.lowercase() } ?: ACCESO
    }
}

/** Le azioni della voce: tasti della notifica e del popup. */
enum class AzioneVoce { PAUSA, RIPRENDI, SPEGNI, ASCOLTA, ESCI, INTERRUTTORE }

object RegoleModo {

    /** Il modo dopo un'azione. [AzioneVoce.ASCOLTA] ed [AzioneVoce.ESCI] non cambiano il modo. */
    fun dopo(modo: ModoVoce, azione: AzioneVoce): ModoVoce = when (azione) {
        AzioneVoce.PAUSA -> if (modo == ModoVoce.SPENTO) ModoVoce.SPENTO else ModoVoce.PAUSA
        AzioneVoce.RIPRENDI -> ModoVoce.ACCESO
        AzioneVoce.SPEGNI -> ModoVoce.SPENTO
        // Il tocco su «● acceso» nel popup: acceso → spento; pausa o spento → acceso.
        AzioneVoce.INTERRUTTORE -> if (modo == ModoVoce.ACCESO) ModoVoce.SPENTO else ModoVoce.ACCESO
        AzioneVoce.ASCOLTA, AzioneVoce.ESCI -> modo
    }

    /**
     * I tasti della notifica persistente (Android ne mostra al massimo tre), in ordine.
     * Da acceso: Pausa, Spegni, Ascolta. In pausa: Riprendi, Spegni. Spento: Riprendi, Esci.
     */
    fun tastiNotifica(modo: ModoVoce): List<AzioneVoce> = when (modo) {
        ModoVoce.ACCESO -> listOf(AzioneVoce.PAUSA, AzioneVoce.SPEGNI, AzioneVoce.ASCOLTA)
        ModoVoce.PAUSA -> listOf(AzioneVoce.RIPRENDI, AzioneVoce.SPEGNI)
        ModoVoce.SPENTO -> listOf(AzioneVoce.RIPRENDI, AzioneVoce.ESCI)
    }

    /** I due tasti sotto le parole nel popup: [Pausa|Riprendi] [Spegni], null = tasto nascosto. */
    fun tastiPopup(modo: ModoVoce): Pair<AzioneVoce, AzioneVoce?> = when (modo) {
        ModoVoce.ACCESO -> AzioneVoce.PAUSA to AzioneVoce.SPEGNI
        ModoVoce.PAUSA -> AzioneVoce.RIPRENDI to AzioneVoce.SPEGNI
        ModoVoce.SPENTO -> AzioneVoce.RIPRENDI to null
    }

    /** Il microfono si può aprire? Mai in pausa o spento, mai durante il dettato della schermata. */
    fun microfonoConsentito(modo: ModoVoce, parolaAttiva: Boolean, inPausaDettato: Boolean): Boolean =
        modo.ascolta && parolaAttiva && !inPausaDettato
}

/**
 * 09/10 (difetto visto sul telefono: popup «JBoss non ascolta · microfono chiuso», poi parole raccolte e voce che
 * riparte). Fermare il microfono non bastava: quello che il microfono aveva GIÀ preso continuava la sua strada.
 * - i blocchi audio ancora in volo sul thread del microfono (ferma() aspetta il thread al massimo 1,5 s) entravano
 *   nel Motore anche col modo in pausa o spento;
 * - una frase chiusa un attimo prima della pausa andava avanti sul thread di lavoro (Google/Whisper, secondi) e poi
 *   al nucleo: JBoss «sentiva», pensava e rispondeva a voce con la notifica non più in pausa.
 * Questo cancello sta fra il modo e tutto ciò che arriva dal microfono: ogni cambio di modo apre un'«epoca» nuova,
 * e una frase vale solo se il modo è acceso e l'epoca è ancora quella in cui è stata catturata.
 * Thread-safe e puro: si prova sulla JVM (ModoVoceTest).
 */
class CancelloAscolto(iniziale: ModoVoce = ModoVoce.ACCESO) {
    @Volatile
    var modo: ModoVoce = iniziale
        private set

    @Volatile
    var epoca: Long = 0L
        private set

    /** Il modo nuovo. Se cambia, tutto quello che era in volo diventa vecchio. */
    @Synchronized
    fun imposta(nuovo: ModoVoce) {
        if (nuovo == modo) return
        modo = nuovo
        epoca++
    }

    /** Un blocco del microfono può entrare nel Motore? Solo da acceso. */
    fun bloccoAmmesso(): Boolean = modo.ascolta

    /** L'epoca da segnare su una frase appena chiusa dal Motore. */
    fun timbro(): Long = epoca

    /** La frase catturata nell'epoca [timbro] può andare avanti (trascrizione, nucleo, risposta)? */
    fun fraseValida(timbro: Long): Boolean = modo.ascolta && timbro == epoca
}
