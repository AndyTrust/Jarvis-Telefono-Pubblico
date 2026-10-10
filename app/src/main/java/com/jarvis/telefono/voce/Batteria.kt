package com.jarvis.telefono.voce

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale

// L'app misura sé stessa (Rifondazione 1.0, «La batteria», regola 8): a inizio
// ascolto e poi a ogni ora piena di ascolto continuo legge la batteria e
// stima quanti punti percentuali all'ora costa Jarvis. Il collaudo la
// confronta con la 0.7.6.

/**
 * Il conto puro, senza Android: si prova sulla JVM con un orologio finto.
 *
 * [inizia] segna il punto di partenza; [registra] va chiamata quando
 * [dovuta] è vera (o più spesso: se l'ora non è piena non fa niente) e dà la
 * stima dell'ultima ora piena. Un intervallo in cui il telefono era in carica
 * (a un capo o all'altro) o in cui la batteria è salita si scarta e il conto
 * riparte da lì.
 */
class ContoBatteria(private val intervalloMs: Long = ORA_MS) {
    companion object {
        const val ORA_MS = 3_600_000L
    }

    private var inizioMs: Long? = null
    private var inizioPercento = 0f
    private var inizioInCarica = false

    /** L'ultima stima buona in %/ora, o null se non c'è ancora un'ora piena. */
    var stima: Float? = null
        private set

    /** Quando scade la prossima ora piena (null se il conto non è partito). */
    val prossimaMs: Long?
        get() = inizioMs?.let { it + intervalloMs }

    fun inizia(adessoMs: Long, percento: Float, inCarica: Boolean) {
        inizioMs = adessoMs
        inizioPercento = percento
        inizioInCarica = inCarica
    }

    /** True se è passata un'ora piena dall'ultimo punto: tocca leggere la batteria. */
    fun dovuta(adessoMs: Long): Boolean {
        val p = prossimaMs ?: return false
        return adessoMs >= p
    }

    /**
     * Registra una lettura. Restituisce la stima nuova (%/ora) se si è chiusa
     * un'ora piena buona, altrimenti null (ora non piena, o intervallo
     * scartato). Se chiamata in ritardo divide per il tempo vero trascorso.
     */
    fun registra(adessoMs: Long, percento: Float, inCarica: Boolean): Float? {
        val t0 = inizioMs ?: run {
            inizia(adessoMs, percento, inCarica)
            return null
        }
        if (adessoMs - t0 < intervalloMs) return null
        val calo = inizioPercento - percento
        val buono = !inizioInCarica && !inCarica && calo >= 0f
        val risultato = if (buono) calo * ORA_MS / (adessoMs - t0).toFloat() else null
        if (risultato != null) stima = risultato
        inizia(adessoMs, percento, inCarica)
        return risultato
    }

    /** L'ascolto si è fermato: la prossima ora riparte da zero. La stima resta. */
    fun ferma() {
        inizioMs = null
    }
}

/**
 * La misura vera, con `BatteryManager`. Il servizio la usa così:
 * - [inizia] quando parte l'ascolto (microfono aperto);
 * - [misuraOraria] quando vuole (anche a ogni blocco del microfono: se l'ora
 *   non è piena torna subito senza leggere niente); oppure la programma
 *   all'istante [prossimaMs] con un solo `postDelayed`, senza timer periodici;
 * - [ferma] quando l'ascolto si spegne.
 *
 * Quando si chiude un'ora buona, [misuraOraria] mette la stima in
 * [StatoJarvis] (`batteriaPerOra`) e restituisce la riga da mandare in
 * diagnosi, SENZA il prefisso `diag: ` (che aggiunge `JarvisService.diag`):
 * `batteria 1,4 %/ora`.
 *
 * Il percento si calcola dal contatore di carica in µAh
 * (`BATTERY_PROPERTY_CHARGE_COUNTER`), che ha decimali: la capacità piena si
 * stima a inizio ascolto come contatore / livello. Se il telefono non dà il
 * contatore si usa il livello intero (`EXTRA_LEVEL` / `EXTRA_SCALE`).
 */
class Batteria(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val conto = ContoBatteria()
    private var capacitaUah: Double? = null

    val prossimaMs: Long? get() = conto.prossimaMs
    val stima: Float? get() = conto.stima

    fun inizia(adessoMs: Long) {
        val l = leggi(nuovaCapacita = true) ?: return
        conto.inizia(adessoMs, l.percento, l.inCarica)
    }

    /** La stima nuova in %/ora se si è chiusa un'ora buona, altrimenti null. */
    fun misuraOraria(adessoMs: Long): Float? {
        if (!conto.dovuta(adessoMs)) return null
        val l = leggi(nuovaCapacita = false) ?: return null
        val s = conto.registra(adessoMs, l.percento, l.inCarica) ?: return null
        StatoJarvis.aggiorna { it.copy(batteriaPerOra = s) }
        return s
    }

    /** La riga di diagnosi per [stima]: `batteria 1,4 %/ora`. */
    fun rigaDiag(stima: Float): String = riga(stima)

    fun ferma() {
        conto.ferma()
    }

    private class Lettura(val percento: Float, val inCarica: Boolean)

    private fun leggi(nuovaCapacita: Boolean): Lettura? {
        val i: Intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val livello = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).takeIf { it > 0 } ?: 100
        if (livello < 0) return null
        val stato = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val spina = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val inCarica = spina != 0 || stato == BatteryManager.BATTERY_STATUS_CHARGING ||
            stato == BatteryManager.BATTERY_STATUS_FULL
        val intero = livello * 100f / scala
        val contatore = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it > 0 && it != Int.MIN_VALUE }
        if (nuovaCapacita) {
            capacitaUah = if (contatore != null && intero > 0f) contatore * 100.0 / intero else null
        }
        val cap = capacitaUah
        val percento = if (contatore != null && cap != null && cap > 0) (contatore * 100.0 / cap).toFloat() else intero
        return Lettura(percento, inCarica)
    }
}

/** `batteria 1,4 %/ora`, con la virgola italiana. */
internal fun riga(stima: Float): String = "batteria " + String.format(Locale.ITALIAN, "%.1f", stima) + " %/ora"
