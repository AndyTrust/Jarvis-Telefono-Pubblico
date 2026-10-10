package com.jarvis.telefono

import com.jarvis.telefono.voce.Ascolto
import com.jarvis.telefono.voce.ModoVoce
import com.jarvis.telefono.voce.Stato
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

// Cosa dice la schermata, a parte dalla Activity: sono funzioni pure, così si
// provano sulla JVM (TestiStatoTest) senza telefono. La Activity legge lo
// Stato, chiama queste e mette testo e colore dove vanno. Niente Android qui.

/** La palette Claude del sito (1.2.3). Deve restare uguale a res/values/colors.xml (lo controlla la prova). */
object Tinte {
    const val FONDO = 0xFFFAF9F5.toInt()
    const val RIALZO = 0xFFFFFFFF.toInt()
    const val TESTO = 0xFF1F1E1B.toInt()
    const val ACCENTO = 0xFFB4532F.toInt()
    const val VERDE = 0xFF2B7443.toInt()
    const val GIALLO = 0xFF865700.toInt()
    const val ROSSO = 0xFFB3261E.toInt()
    const val GRIGIO = 0xFF8F8B80.toInt()
}

private val ITALIANO: Locale = Locale.ITALY

// ------------------------------------------------------------------ la spia

/** Il testo accanto alla spia in alto a destra. */
fun testoSpia(stato: Stato): String = when (stato.ascolto) {
    Ascolto.SPENTO -> "spento"
    Ascolto.ASCOLTO -> "ascolto"
    Ascolto.CATTURA -> "ti ascolto"
    Ascolto.PENSO -> "penso"
    Ascolto.PARLO -> "parlo"
    Ascolto.GUASTO -> stato.guasto?.takeIf { it.isNotBlank() }?.let { "guasto: $it" } ?: "guasto"
}

/** Verde ascolto · giallo penso/parlo · grigio spento · rosso guasto. */
fun coloreSpia(stato: Stato): Int = when (stato.ascolto) {
    Ascolto.SPENTO -> Tinte.GRIGIO
    Ascolto.ASCOLTO, Ascolto.CATTURA -> Tinte.VERDE
    Ascolto.PENSO, Ascolto.PARLO -> Tinte.GIALLO
    Ascolto.GUASTO -> Tinte.ROSSO
}

/** Solo mentre registra la frase la spia lampeggia: si vede che sta prendendo la voce. */
fun spiaLampeggia(stato: Stato): Boolean = stato.ascolto == Ascolto.CATTURA

/**
 * 09/10 (Boss: con JBoss su «Spento» il tasto Parla diceva «JBoss è spento» ma la pagina Voce mostrava «JBoss acceso»):
 * una sola lettura dello stato di JBoss per il tasto Parla (Home e chat) e per l'interruttore «JBoss acceso» (pagina
 * Voce e card Voce delle Impostazioni). Prima la pagina guardava solo il servizio e la preferenza, non il modo della
 * voce (acceso, pausa, spento dalla notifica o dal popup), e Parla guardava solo lo stato del giro.
 */
enum class PresenzaJBoss {
    /** Servizio vivo, voce accesa: Parla ascolta. */
    ACCESO,
    /** Boss l'ha lasciato acceso ma il servizio non c'è (ucciso, in partenza): Parla lo riaccende. */
    IN_AVVIO,
    /** Voce in pausa (notifica o popup): il microfono è chiuso. */
    PAUSA,
    /** Voce spenta (notifica o popup): il microfono è chiuso, il servizio resta per «Riprendi». */
    VOCE_SPENTA,
    /** Spento da Boss (Impostazioni → Voce o «Esci»). */
    SPENTO,
    /** Il servizio c'è ma il microfono è guasto. */
    GUASTO,
}

fun presenzaJBoss(stato: Stato, servizioVivo: Boolean, attivoSalvato: Boolean): PresenzaJBoss = when {
    !servizioVivo || stato.ascolto == Ascolto.SPENTO -> if (attivoSalvato) PresenzaJBoss.IN_AVVIO else PresenzaJBoss.SPENTO
    stato.modoVoce == ModoVoce.SPENTO -> PresenzaJBoss.VOCE_SPENTA
    stato.modoVoce == ModoVoce.PAUSA -> PresenzaJBoss.PAUSA
    stato.ascolto == Ascolto.GUASTO -> PresenzaJBoss.GUASTO
    else -> PresenzaJBoss.ACCESO
}

/** L'interruttore «JBoss acceso»: acceso solo se la voce lo è davvero (o sta ripartendo, o ha un guasto da riparare). */
fun jarvisAcceso(p: PresenzaJBoss): Boolean =
    p == PresenzaJBoss.ACCESO || p == PresenzaJBoss.IN_AVVIO || p == PresenzaJBoss.GUASTO

/** Cosa dice il tasto Parla quando non può ascoltare subito. null = ascolta. */
fun avvisoParla(p: PresenzaJBoss): String? = when (p) {
    PresenzaJBoss.ACCESO -> null
    PresenzaJBoss.IN_AVVIO -> "Riaccendo JBoss: riprova fra un attimo"
    PresenzaJBoss.PAUSA -> "JBoss è in pausa: tocca Riprendi per farlo ascoltare"
    PresenzaJBoss.VOCE_SPENTA -> "JBoss è spento: tocca Riprendi per farlo ascoltare"
    PresenzaJBoss.SPENTO -> "JBoss è spento: accendilo in Impostazioni, Voce"
    PresenzaJBoss.GUASTO -> "La voce di JBoss ha un guasto: guarda Impostazioni, Voce"
}

// ------------------------------------------------------------------ numeri

/** 3.0 → «3», 2.5 → «2,5»: la virgola italiana, e niente «,0». */
fun secondi(s: Double): String {
    val decimi = (s * 10).roundToLong()
    return if (decimi % 10 == 0L) (decimi / 10).toString()
    else String.format(ITALIANO, "%.1f", decimi / 10.0)
}

fun testoFineFrase(stato: Stato): String =
    "Frase chiusa dopo ${secondi(stato.fineParlatoS)} s di silenzio" +
        if (stato.fineParlatoLungoS > stato.fineParlatoS)
            " (${secondi(stato.fineParlatoLungoS)} s dopo ${secondi(stato.parlatoLungoS)} s di parlato)"
        else ""

/** 1.4f → «1,4 %/ora». */
fun percentoOra(v: Float): String = String.format(ITALIANO, "%.1f %%/ora", v)

/** Megabyte decimali, come li dice il piano: 375.485.327 byte → «375 MB». */
fun megabyte(byte: Long): String = "${(byte / 1_000_000.0).roundToLong()} MB"

/** Percentuale intera 0-100, o null se il totale non è noto. */
fun percentuale(fatti: Long, totali: Long): Int? {
    if (totali <= 0) return null
    return ((fatti * 100) / totali).toInt().coerceIn(0, 100)
}

/** «19:40» se è oggi, «25/09 19:40» se no: un'ora sola di un altro giorno mente. */
fun quando(ms: Long, adessoMs: Long, zona: TimeZone = TimeZone.getDefault()): String {
    val a = Calendar.getInstance(zona).apply { timeInMillis = ms }
    val b = Calendar.getInstance(zona).apply { timeInMillis = adessoMs }
    val stessoGiorno = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val f = SimpleDateFormat(if (stessoGiorno) "HH:mm" else "dd/MM HH:mm", ITALIANO)
    f.timeZone = zona
    return f.format(Date(ms))
}

// ------------------------------------------------------------------ le righe

class Riga(val testo: String, val colore: Int)

fun rigaImpronta(stato: Stato): Riga =
    if (stato.improntaPronta) Riga("pronta (${stato.improntaOrigine})", Tinte.VERDE)
    else Riga("non ancora: filtro voce spento", Tinte.GRIGIO)

fun rigaWhisper(stato: Stato): Riga {
    val p = stato.scaricoPercento
    return when {
        p != null -> Riga("scarico $p %", Tinte.GIALLO)
        stato.whisperPresente -> Riga("scaricato ${megabyte(stato.whisperByte)}", Tinte.VERDE)
        else -> Riga("da scaricare (Impostazioni)", Tinte.ROSSO)
    }
}

fun rigaGlossario(stato: Stato): Riga {
    val quando = stato.glossarioAggiornato
    return if (quando != null) Riga("${stato.glossarioTermini} termini, $quando", Tinte.VERDE)
    else Riga("${stato.glossarioTermini} termini, mai aggiornato", Tinte.GRIGIO)
}

fun testoBatteria(stato: Stato): String =
    stato.batteriaPerOra?.let { "${percentoOra(it)} (ultima ora)" } ?: "misura in corso"

fun testoUltimoUtente(stato: Stato): String = "Tu: ${stato.ultimoUtente?.takeIf { it.isNotBlank() } ?: "—"}"

fun testoUltimoJarvis(stato: Stato): String = "JBoss: ${stato.ultimoJarvis?.takeIf { it.isNotBlank() } ?: "—"}"
