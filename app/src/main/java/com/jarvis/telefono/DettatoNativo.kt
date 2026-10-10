package com.jarvis.telefono

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.jarvis.telefono.voce.FineDettato
import com.jarvis.telefono.voce.Glossario
import com.jarvis.telefono.voce.Microfono
import com.jarvis.telefono.voce.Modelli
import com.jarvis.telefono.voce.Modello
import com.jarvis.telefono.voce.Opzioni
import com.jarvis.telefono.voce.RiconoscitoreGoogle
import com.jarvis.telefono.voce.TrascrittoreWhisper
import com.jarvis.telefono.voce.VadSilero
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Il dettato del campo di testo della schermata principale (Jarvis Telefono, passo A; nell'app
 * 1.1.x serviva alla chat del sito dentro la WebView). Il testo arriva in [consegna].
 *
 * Stessa pipeline della voce: [Microfono] (16 kHz mono), Silero per capire quando l'utente ha finito
 * ([FineDettato]), Whisper offline e glossario. Un solo `AudioRecord` alla volta: se il servizio è
 * acceso, il suo microfono va in pausa ([JarvisService.pausaPerDettato]) e si riapre appena la
 * registrazione finisce; la trascrizione la fa il Whisper già caricato nel servizio. A servizio
 * spento si carica un Whisper qui, e si libera con [rilascia].
 *
 * 1.1.8 (Boss 2026-10-07, «il tasto parla con Jarvis è molto lento»): prima si prova il riconoscimento
 * di Google ([RiconoscitoreGoogle]: sul dispositivo, offline preferito, in rete) che manda il testo
 * mentre si parla con [parziale]; Whisper resta come ripiego quando Google non c'è o non risponde.
 * Il motivo della lentezza: Whisper small int8 sulla CPU lavora su finestre fisse da 30 s (anche per
 * una frase di 3 s), parte solo dopo 2,5 s di silenzio e il testo compare tutto alla fine.
 *
 * [consegna] arriva sempre sul thread principale, una volta per ogni [avvia] riuscito o rifiutato:
 * con il testo, o con "" (niente parlato, Whisper non scaricato, microfono negato o guasto). Il
 * perché sta in [motivo]. [parziale] (solo con Google) porta il testo provvisorio, più volte, prima
 * di [consegna]. Il testo non va mai nei log.
 */
class DettatoNativo(
    context: Context,
    private val parziale: (String) -> Unit = {},
    private val consegna: (String) -> Unit,
) {

    companion object {
        private const val TAG = "JarvisDettato"
        private const val SCADENZA_MS = 65_000L
        private const val TRASCRIZIONE_MAX_S = 120L
    }

    enum class Fase { PRONTO, REGISTRA, TRASCRIVE }

    private val app = context.applicationContext
    private val principale = Handler(Looper.getMainLooper())
    private val lavoro = Executors.newSingleThreadExecutor { Thread(it, "jarvis-dettato") }
    private val modelli by lazy { Modelli(Inventario.cartellaModelli(app), OkHttpClient()) }

    @Volatile
    var fase = Fase.PRONTO
        private set

    /** L'ultimo esito: pronto, registra, trascrive, ok, nessun_parlato, whisper_assente, microfono_negato, microfono_guasto, trascrizione_fallita, annullato. */
    @Volatile
    var motivo = "pronto"
        private set

    // Solo sul thread «jarvis-dettato» (il Microfono lo chiama dal suo thread: pezzi/vad/fine sotto lock).
    private var microfono: Microfono? = null
    private val lock = Any()
    private var vad: VadSilero? = null
    private var fine = FineDettato()
    private val pezzi = ArrayList<FloatArray>()
    @Volatile
    private var chiusuraChiesta = false
    @Volatile
    private var scartaTesto = false

    private var trascrittoreLocale: TrascrittoreWhisper? = null
    private var vadLocale: VadSilero? = null
    private var glossarioLocale: Glossario? = null

    private val scadenza = Runnable { ferma() }

    // Google: solo sul thread principale.
    private val google by lazy { RiconoscitoreGoogle(app) }
    private var conGoogle = false
    private var inizioMs = 0L

    /** Chi ascolta adesso o ha ascoltato l'ultima volta: google-dispositivo, google-offline, google, whisper. */
    @Volatile
    var motore = ""
        private set

    fun whisperPresente(): Boolean = runCatching { modelli.presente(Modello.WHISPER_SMALL) }.getOrDefault(false)

    fun googlePresente(): Boolean = RiconoscitoreGoogle.disponibile(app)

    private fun ms() = SystemClock.elapsedRealtime() - inizioMs

    /** Sul thread principale. Falso se non parte (e [consegna] riceve "" subito dopo). */
    fun avvia(): Boolean {
        if (fase != Fase.PRONTO) {
            // Un dettato è già in corso: la pagina che chiede riceve "" (il testo va al primo).
            principale.post { consegna("") }
            return false
        }
        val usaGoogle = googlePresente()
        val rifiuto = when {
            !Permessi.microfono(app) -> "microfono_negato"
            !usaGoogle && !whisperPresente() -> "whisper_assente"
            else -> null
        }
        if (rifiuto != null) {
            motivo = rifiuto
            principale.post { consegna("") }
            return false
        }
        fase = Fase.REGISTRA
        motivo = "registra"
        chiusuraChiesta = false
        scartaTesto = false
        inizioMs = SystemClock.elapsedRealtime()
        // Prima si chiude il microfono del servizio (sul principale: ferma() aspetta il suo thread).
        JarvisService.instance?.pausaPerDettato()
        principale.postDelayed(scadenza, SCADENZA_MS)
        if (usaGoogle) avviaGoogle() else avviaWhisper()
        return true
    }

    private fun avviaWhisper() {
        conGoogle = false
        motore = "whisper"
        lavoro.execute { apri() }
    }

    // ------------------------------------------------------------ Google, sul thread principale

    private fun avviaGoogle() {
        conGoogle = true
        motore = "google"
        google.avvia(object : RiconoscitoreGoogle.Ascolto {
            override fun parziale(testo: String) {
                motore = google.viaCorrente?.etichetta ?: motore
                if (!scartaTesto && fase == Fase.REGISTRA) parziale.invoke(testo)
            }

            override fun finale(testo: String) {
                motore = google.viaCorrente?.etichetta ?: motore
                fineGoogle()
                fase = Fase.TRASCRIVE
                motivo = "trascrive"
                // Il glossario («Jarvis» e i nomi di Boss) vale anche per Google: legge un file, quindi fuori dal principale.
                runCatching {
                    lavoro.execute {
                        val t = runCatching {
                            val g = glossarioLocale ?: Glossario(Inventario.cartellaGlossario(app)).also { glossarioLocale = it }
                            g.postCorreggi(testo).first
                        }.getOrDefault(testo)
                        finisci(t.trim(), "ok")
                    }
                }.onFailure { finisci(testo, "ok") }
            }

            override fun senzaTesto(motivo: String) {
                fineGoogle()
                finisci("", motivo)
            }

            override fun nonDisponibile() {
                if (scartaTesto || chiusuraChiesta) { fineGoogle(); finisci("", if (scartaTesto) "annullato" else "nessun_parlato"); return }
                if (!whisperPresente()) { fineGoogle(); finisci("", "whisper_assente"); return }
                Log.i(TAG, "dettato: Google non c'è, uso Whisper (${ms()} ms persi)")
                avviaWhisper()
            }
        })
    }

    /** Google ha chiuso il suo microfono: il servizio torna ad ascoltare «Hey Boss». */
    private fun fineGoogle() {
        principale.removeCallbacks(scadenza)
        JarvisService.instance?.riprendiDopoDettato()
    }

    /** Sul thread principale: l'utente ha toccato di nuovo il microfono. Si trascrive quello che c'è. */
    fun ferma() {
        if (fase != Fase.REGISTRA || chiusuraChiesta) return
        chiusuraChiesta = true
        if (conGoogle) google.ferma() else lavoro.execute { chiudi() }
    }

    /** Sul thread principale: la pagina è andata via. Niente testo, solo il microfono chiuso. */
    fun annulla() {
        scartaTesto = true
        if (conGoogle && fase == Fase.REGISTRA) {
            google.annulla()
            fineGoogle()
            finisci("", "annullato")
            return
        }
        if (fase == Fase.REGISTRA && !chiusuraChiesta) {
            chiusuraChiesta = true
            lavoro.execute { chiudi() }
        }
    }

    /** Sul thread principale, in onDestroy. */
    fun rilascia() {
        annulla()
        principale.removeCallbacks(scadenza)
        runCatching {
            lavoro.execute {
                runCatching { trascrittoreLocale?.release() }
                runCatching { vadLocale?.close() }
                trascrittoreLocale = null
                vadLocale = null
            }
        }
        lavoro.shutdown()
    }

    // ------------------------------------------------------------ sul thread «jarvis-dettato»

    private fun apri() {
        try {
            synchronized(lock) {
                pezzi.clear()
                fine = FineDettato()
                vad = VadSilero(app.assets, soglia = Opzioni().sogliaVad)
            }
            val m = Microfono(app, ::suBlocco, ::suErrore)
            microfono = m
            m.avvia()
            Log.i(TAG, "dettato: microfono aperto a ${ms()} ms")
        } catch (t: Throwable) {
            Log.w(TAG, "dettato: apertura fallita (${t.javaClass.simpleName})")
            motivo = "microfono_guasto"
            chiusuraChiesta = true
            chiudi(guasto = true)
        }
    }

    /** Sul thread del Microfono. */
    private fun suBlocco(blocco: ShortArray) {
        if (chiusuraChiesta) return
        val f = FloatArray(blocco.size) { blocco[it] / 32768f }
        var basta = false
        synchronized(lock) {
            pezzi.add(f)
            val v = vad ?: return
            var i = 0
            while (i + Opzioni.FRAME_LEN <= f.size) {
                val parlato = runCatching { v.parlato(f.copyOfRange(i, i + Opzioni.FRAME_LEN)) }.getOrDefault(false)
                if (fine.fotogramma(parlato)) basta = true
                i += Opzioni.FRAME_LEN
            }
        }
        if (basta && !chiusuraChiesta) {
            chiusuraChiesta = true
            runCatching { lavoro.execute { chiudi() } }
        }
    }

    /** Sul thread del Microfono, quando non si apre o si rompe (il Microfono è già fermo). */
    private fun suErrore(testo: String) {
        Log.w(TAG, "dettato: $testo")
        motivo = "microfono_guasto"
        if (!chiusuraChiesta) {
            chiusuraChiesta = true
            runCatching { lavoro.execute { chiudi(guasto = true) } }
        }
    }

    private fun chiudi(guasto: Boolean = false) {
        if (fase != Fase.REGISTRA) return
        runCatching { microfono?.ferma() }
        microfono = null
        val pcm: FloatArray
        val parlato: Boolean
        synchronized(lock) {
            runCatching { vad?.close() }
            vad = null
            parlato = fine.parlatoVisto
            pcm = FloatArray(pezzi.sumOf { it.size })
            var k = 0
            for (p in pezzi) { p.copyInto(pcm, k); k += p.size }
            pezzi.clear()
        }
        // Il microfono è libero: il servizio torna ad ascoltare «Hey Boss» mentre si trascrive.
        principale.post {
            principale.removeCallbacks(scadenza)
            JarvisService.instance?.riprendiDopoDettato()
        }
        if (guasto) return finisci("", "microfono_guasto")
        if (scartaTesto) return finisci("", "annullato")
        if (!parlato || pcm.isEmpty()) return finisci("", "nessun_parlato")
        fase = Fase.TRASCRIVE
        motivo = "trascrive"
        val tChiuso = ms()
        Log.i(TAG, "dettato: registrazione chiusa a $tChiuso ms (${pcm.size / 16} ms di audio), trascrivo")
        val testo = trascrivi(pcm)
        Log.i(TAG, "dettato: Whisper ha impiegato ${ms() - tChiuso} ms")
        when {
            testo == null -> finisci("", "trascrizione_fallita")
            testo.isBlank() -> finisci("", "nessun_parlato")
            else -> finisci(testo.trim(), "ok")
        }
    }

    /** Con il Whisper del servizio se è acceso, altrimenti con uno caricato qui. null = fallita. */
    private fun trascrivi(pcm: FloatArray): String? {
        val servizio = JarvisService.instance
        if (servizio != null) {
            val fatto = CountDownLatch(1)
            var esito: String? = null
            servizio.trascriviPerDettato(pcm) { esito = it; fatto.countDown() }
            if (fatto.await(TRASCRIZIONE_MAX_S, TimeUnit.SECONDS) && esito != null) return esito
            Log.i(TAG, "dettato: il servizio non ha trascritto, provo da qui")
        }
        return try {
            val v = vadLocale ?: VadSilero(app.assets, soglia = Opzioni().sogliaVad).also { vadLocale = it }
            val tr = trascrittoreLocale ?: TrascrittoreWhisper(modelli, v).also { trascrittoreLocale = it }
            val g = glossarioLocale ?: Glossario(Inventario.cartellaGlossario(app)).also { glossarioLocale = it }
            g.postCorreggi(tr.trascrivi(pcm)).first
        } catch (t: Throwable) {
            Log.w(TAG, "dettato: trascrizione fallita (${t.javaClass.simpleName})")
            null
        }
    }

    private fun finisci(testo: String, perche: String) {
        if (fase == Fase.PRONTO) return // già consegnato: una sola consegna per ogni avvia
        motivo = perche
        fase = Fase.PRONTO
        Log.i(TAG, "dettato: $perche con $motore a ${ms()} ms dal tocco, ${testo.length} caratteri")
        principale.post { if (!scartaTesto || testo.isEmpty()) consegna(testo) else consegna("") }
    }
}
