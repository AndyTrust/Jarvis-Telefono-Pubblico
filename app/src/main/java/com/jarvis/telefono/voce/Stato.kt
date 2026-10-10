package com.jarvis.telefono.voce

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

// Lo stato condiviso fra il servizio (che lo scrive) e la schermata (che lo
// guarda). Rifondazione 1.0, seconda ondata. La forma di Stato e di Ascolto è
// fissata col ceo-android: la schermata (specialista G) ne ha una copia
// identica, quindi non si cambia un campo senza dirlo.

/** In che punto del giro è Jarvis. */
enum class Ascolto { SPENTO, ASCOLTO, CATTURA, PENSO, PARLO, GUASTO }

/** Tutto quello che la schermata mostra. Immutabile: si cambia con [StatoJarvis.aggiorna]. */
data class Stato(
    val ascolto: Ascolto = Ascolto.SPENTO,
    val guasto: String? = null,
    val improntaPronta: Boolean = false,
    val improntaOrigine: String = "nessuna",
    val whisperPresente: Boolean = false,
    val whisperByte: Long = 0L,
    val scaricoPercento: Int? = null,
    val glossarioTermini: Int = 0,
    val glossarioAggiornato: String? = null,
    val batteriaPerOra: Float? = null,
    val ultimoUtente: String? = null,
    val ultimoJarvis: String? = null,
    val fineParlatoS: Double = 1.5,
    val fineParlatoLungoS: Double = 2.5,
    val parlatoLungoS: Double = 4.0,
    /** 0.2.0: l'id dell'agente che sta eseguendo adesso (oggi solo «mani»), null se nessuno. */
    val agenteAlLavoro: String? = null,
    /** 0.2.0: che cosa sta facendo, in parole (il titolo della bolla, es. «Apro Spotify…»). */
    val lavoroInCorso: String? = null,
    /** 09/10 (voce senza mani): acceso, in pausa o spento. In pausa e spento il microfono è chiuso. */
    val modoVoce: ModoVoce = ModoVoce.ACCESO,
    /** 09/10: il popup della conversazione è aperto (ascolto continuo). */
    val conversazioneAperta: Boolean = false,
)

/**
 * L'unico contenitore dello [Stato] dell'app.
 *
 * - [corrente] si legge da qualunque thread.
 * - [aggiorna] si chiama da qualunque thread (audio, rete, principale): la
 *   funzione riceve lo stato attuale e restituisce quello nuovo; gli
 *   aggiornamenti sono serializzati, nessuno si perde.
 * - [osserva] consegna subito lo stato corrente e poi ogni cambiamento, sul
 *   thread principale e nell'ordine in cui sono avvenuti. Uno stato uguale al
 *   precedente non si consegna. Restituisce la funzione per disiscriversi:
 *   dopo averla chiamata non arriva più niente, neanche una consegna già in
 *   coda sul thread principale.
 *
 * Uso tipico nella schermata:
 * ```
 * private var smetti: (() -> Unit)? = null
 * override fun onStart() { super.onStart(); smetti = StatoJarvis.osserva { mostra(it) } }
 * override fun onStop() { smetti?.invoke(); smetti = null; super.onStop() }
 * ```
 */
object StatoJarvis {
    private val lock = Any()

    @Volatile
    private var stato = Stato()

    private class Osservatore(val o: (Stato) -> Unit) {
        @Volatile
        var vivo = true
    }

    private val osservatori = CopyOnWriteArrayList<Osservatore>()

    private val principale by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Come si porta una consegna sul thread principale. Nelle prove si
     * sostituisce con un esecutore sincrono (`{ it() }`): sulla JVM non c'è
     * un Looper.
     */
    internal var consegna: ((() -> Unit) -> Unit) = { azione -> principale.post(azione) }

    /** Lo stato di adesso. Lettura thread-safe. */
    val corrente: Stato
        get() = stato

    /** Cambia lo stato: `StatoJarvis.aggiorna { it.copy(ascolto = Ascolto.PENSO) }`. */
    fun aggiorna(f: (Stato) -> Stato) {
        synchronized(lock) {
            val nuovo = f(stato)
            if (nuovo == stato) return
            stato = nuovo
            // Le consegne partono dentro il lock: così arrivano sul thread
            // principale nello stesso ordine degli aggiornamenti.
            for (os in osservatori) invia(os, nuovo)
        }
    }

    /** Si iscrive ai cambiamenti. Vedi il KDoc dell'oggetto. */
    fun osserva(o: (Stato) -> Unit): () -> Unit {
        val os = Osservatore(o)
        synchronized(lock) {
            osservatori.add(os)
            invia(os, stato)
        }
        return {
            os.vivo = false
            osservatori.remove(os)
        }
    }

    private fun invia(os: Osservatore, s: Stato) {
        consegna { if (os.vivo) os.o(s) }
    }

    /** Solo per le prove: torna allo stato iniziale senza osservatori. */
    internal fun azzera() {
        synchronized(lock) {
            osservatori.forEach { it.vivo = false }
            osservatori.clear()
            stato = Stato()
        }
    }
}
