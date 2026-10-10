package com.jarvis.telefono.voce

// Tutti i parametri della voce a mani libere, in un solo posto (Rifondazione
// 1.0, docs/RIFONDAZIONE-1.0.md). I default sono quelli del dizionario
// DEFAULT di ~/Jarvis/backtalk/backtalk/manilibere.py (26/09/2026), uno a uno:
// chi cambia un valore qui lo cambia anche sul computer, o lo dice.
data class Opzioni(
    // Sul computer le mani libere partono spente («enabled»: False). Sul telefono
    // decide la seconda ondata (JarvisService): il valore è qui per fedeltà.
    val abilitato: Boolean = false,
    // Frase chiusa dopo questi secondi di silenzio. Il DEFAULT del computer dice 7,
    // ma l'utente la sera del 26/09/2026 l'ha abbassata a 3 in backtalk.json
    // («la sentiva lenta»): l'app parte dal valore che l'utente usa davvero.
    // 1.2.4 (Boss 07/10, «veloce»): 1,5 s. Basta per i comandi corti («invia», «annulla»,
    // «che ore sono»): le pause di respiro dentro una frase stanno sotto il secondo.
    val fineParlatoS: Double = 1.5,
    // ...ma dopo [parlatoLungoS] di parlato (un messaggio dettato, una richiesta lunga) Boss si
    // ferma a pensare a metà frase: lì la finestra sale a [fineParlatoLungoS] per non tagliarla.
    // Il parlato si conta dal primo all'ultimo fotogramma di voce vera (pause comprese).
    val fineParlatoLungoS: Double = 2.5,
    val parlatoLungoS: Double = 4.0,
    // Frase chiusa comunque a questa durata.
    val maxFraseS: Double = 90.0,
    // Dopo che Jarvis ha smesso di parlare l'audio si butta ancora per
    // questi ms (coda dell'eco e del buffer di uscita).
    val codaEcoMs: Long = 700,
    // Se dopo la parola non parli entro questi secondi, torna in attesa.
    val attesaInizioS: Double = 10.0,
    // Cosa accetta dal KWS.
    // 0.6.0 (Boss 08/10: «devo avere attivo solo Hey Boss e Hey JBoss»): «Hey Jarvis» e «Jarvis» tolti
    // da qui e da keywords.txt. Banco: scripts/banco-parole.py («Ok boss lo faccio io» non deve scattare).
    // 0.2.0 (Boss 07/10: l'app si chiama JBoss): «JBoss» in cinque pronunce («jay boss», «hey jay
    // boss», «jei boss», «gei boss», «ci boss») escono tutte come «JBOSS» (@JBOSS in keywords.txt).
    // 0.3.0 (Boss 07/10 20:50: «hey boss o hey jboss, entrambe vanno bene»): «Hey Boss» esce anche lui come
    // «JBOSS» (riga «▁HE Y ▁BO S S :2.0 #0.2», soglia più permissiva: 12 su 15 al banco, 2 falsi su 40 con «ok boss»).
    // Se scatta troppo: alzare #0.2 a #0.35 in keywords.txt (al banco 9 su 15 e 1 falso su 40).
    val parole: List<String> = listOf("jboss", "hey jboss", "hey boss"),
    // Filtro del rumore GTCRN prima del KWS e del VAD.
    val filtroRumore: Boolean = true,
    // Sotto questa somiglianza con l'impronta la frase si scarta. -1 = filtro spento (Boss, 07/10: la scartava a 0,32-0,52).
    val sogliaImpronta: Float = -1f,
    // Un «tin» leggero appena sente la parola.
    val suonoAscolto: Boolean = true,
    // webrtcvad 0-3 sul computer. Sul telefono il VAD è Silero, che non ha
    // questo parametro: si tiene per fedeltà e non si usa.
    val vadAggressivita: Int = 3,
    // I primi ms dopo la parola si buttano: dentro c'è il «tin» e la coda
    // della parola, che il VAD scambierebbe per l'inizio della frase.
    val ignoraInizialiMs: Long = 300,
    // L'impronta dell'utente si impara dalle frasi dette col tasto/widget.
    val imparaDalTasto: Boolean = true,

    // --- Impronta (manilibere.py, costanti fuori da DEFAULT). Le usa
    // voce/Impronta.kt: stanno qui perché i parametri sono in un posto solo.
    val minFrasiImpronta: Int = 3,
    val decadimentoImpronta: Float = 0.97f,
    val minVoceImparaS: Double = 1.5,
    val soloVoceDb: Double = 25.0,
    val soloVoceMargine: Int = 3,

    // --- Cancello di energia prima del KWS (solo telefono, per la batteria).
    // Aperto quando l'RMS supera il pavimento del rumore di questo fattore
    // (4 = 12 dB)...
    val cancelloRapporto: Float = 4.0f,
    // ...oppure supera questo RMS assoluto (-40 dBFS).
    val cancelloMinimo: Float = 0.01f,
    // Resta aperto questi ms dopo l'ultimo suono.
    val cancelloCodaMs: Long = 600,
    // Quando il cancello si apre, il KWS riceve prima gli ultimi ms buttati:
    // l'attacco di «Hey» è debole e starebbe sotto la soglia.
    val cancelloPrerollMs: Long = 300,
    val cancelloAttivo: Boolean = true,

    // --- Silero VAD (sostituisce webrtcvad sul telefono).
    val sogliaVad: Float = 0.5f,

    // --- 0.3.3 (Boss 07/10 sera: «funziona malissimo»): chi trascrive dopo la parola.
    // «google» = riconoscimento di Google sul telefono sull'audio già catturato, Whisper di ripiego;
    // «whisper» = solo Whisper small. Si cambia dal banco ADB (SceltaTrascrittore).
    val trascrittore: String = "google",
    // Quanti ms di microfono crudo PRIMA della parola/tocco si tengono per la trascrizione e per il
    // debug: dentro c'è la coda della parola e l'inizio di una frase detta di fila («JBoss che ore sono»).
    val preRollMs: Long = 1_000,
) {
    companion object {
        const val RATE = 16000
        const val FRAME_MS = 30
        const val FRAME_LEN = RATE * FRAME_MS / 1000   // 480 campioni
    }
}
