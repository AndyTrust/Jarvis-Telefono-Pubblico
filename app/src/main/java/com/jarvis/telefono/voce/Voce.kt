package com.jarvis.telefono.voce

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * La voce di Jarvis Telefono (passo A, 2026-10-07): SOLO la voce di sistema in italiano
 * (`TextToSpeech`). La voce del ponte (Gemini su /voce/tts della VPS) non c'è più: niente rete.
 *
 * Il contratto con il Motore resta quello dell'app 1.2.x: [onInizio] arriva prima del primo
 * suono, [onFine] dopo l'ultimo, sempre in coppia (anche con [zitto], errore o scadenza di
 * sicurezza). Arrivano sul thread interno «jarvis-voce», mai sul principale.
 * Il testo non va mai nei log: al massimo la sua lunghezza.
 */
class Voce(
    context: Context,
    private val onInizio: () -> Unit,
    private val onFine: () -> Unit,
) {
    companion object {
        private const val TAG = "JarvisVoce"
        /** Scadenza di sicurezza della voce di sistema: 5 s + 100 ms per carattere. */
        private fun scadenzaSistemaMs(caratteri: Int) = 5_000L + caratteri * 100L
    }

    private val suona: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "jarvis-voce") }
    private val turno = AtomicInteger(0)

    @Volatile private var attesaSistema: CountDownLatch? = null
    @Volatile private var altoparlanti = false
    @Volatile private var rilasciata = false
    @Volatile private var ultimoPezzo: String? = null

    /** True fra [onInizio] e [onFine]. */
    val staParlando: Boolean get() = altoparlanti

    private val pronto = CountDownLatch(1)
    @Volatile private var sistemaOk = false

    private val sistema: TextToSpeech = TextToSpeech(context.applicationContext) { esito ->
        if (esito == TextToSpeech.SUCCESS) {
            val lingua = runCatching { tts().setLanguage(Locale.ITALIAN) }.getOrDefault(TextToSpeech.ERROR)
            sistemaOk = lingua != TextToSpeech.LANG_MISSING_DATA &&
                lingua != TextToSpeech.LANG_NOT_SUPPORTED && lingua != TextToSpeech.ERROR
            Log.i(TAG, "voce di sistema pronta, italiano=$lingua")
        } else {
            Log.w(TAG, "voce di sistema non disponibile ($esito)")
        }
        pronto.countDown()
    }

    private fun tts() = sistema

    init {
        sistema.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = fine(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = fine(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = fine(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = fine(utteranceId)
        })
    }

    private fun fine(id: String?) {
        if (id == ultimoPezzo || id == null) attesaSistema?.countDown()
    }

    /** Dice [testo], interrompendo quello che stava dicendo. Torna subito. */
    fun parla(testo: String) {
        if (rilasciata) return
        val pezzi = TestoVoce.spezza(testo)
        val mio = turno.incrementAndGet()
        interrompi()
        if (pezzi.isEmpty()) return
        Log.i(TAG, "parla: ${testo.length} caratteri in ${pezzi.size} pezzi")
        runCatching { suona.execute { turnoVoce(mio, pezzi) } }
    }

    fun zitto() {
        turno.incrementAndGet()
        interrompi()
    }

    fun rilascia() {
        rilasciata = true
        zitto()
        suona.shutdown()
        runCatching { suona.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { sistema.shutdown() }
    }

    private fun interrompi() {
        runCatching { sistema.stop() }
        attesaSistema?.countDown()
    }

    private fun vivo(mio: Int) = turno.get() == mio && !rilasciata

    private fun turnoVoce(mio: Int, pezzi: List<String>) {
        if (!vivo(mio)) return
        if (!pronto.await(3, TimeUnit.SECONDS) || !sistemaOk) {
            Log.w(TAG, "voce di sistema assente: la risposta non si dice")
            return
        }
        val fatto = CountDownLatch(1)
        attesaSistema = fatto
        val base = "jarvis-$mio-"
        ultimoPezzo = base + (pezzi.size - 1)
        altoparlanti = true
        runCatching { onInizio() }
        try {
            var ok = true
            pezzi.forEachIndexed { k, p ->
                val modo = if (k == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                if (sistema.speak(p, modo, null, base + k) != TextToSpeech.SUCCESS) ok = false
            }
            if (!ok) {
                Log.w(TAG, "voce di sistema: speak rifiutato")
                runCatching { sistema.stop() }
                return
            }
            if (!fatto.await(scadenzaSistemaMs(pezzi.sumOf { it.length }), TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "voce di sistema: nessuna fine entro la scadenza, chiudo io")
                runCatching { sistema.stop() }
            }
        } finally {
            attesaSistema = null
            altoparlanti = false
            runCatching { onFine() }
        }
    }
}

/** Le parti pure della Voce: si provano sulla JVM. */
object TestoVoce {
    const val MAX_PEZZO = 300

    private val FINE_FRASE = Regex("(?<=[.;!?…])\\s+")
    private val VIRGOLA = Regex("(?<=[,:])\\s+")

    /**
     * Spezza [testo] in pezzi di al massimo [max] caratteri, tagliando dopo
     * punto, punto e virgola, punto esclamativo o interrogativo. Le frasi
     * brevi si rimettono insieme finché stanno nel limite (meno chiamate al
     * ponte); una frase più lunga del limite si taglia alla virgola, poi allo
     * spazio, e solo in ultimo a metà parola. Niente pezzi vuoti.
     */
    fun spezza(testo: String, max: Int = MAX_PEZZO): List<String> {
        require(max > 0)
        val pulito = testo.replace(Regex("\\s+"), " ").trim()
        if (pulito.isEmpty()) return emptyList()
        if (pulito.length <= max) return listOf(pulito)
        val frasi = pulito.split(FINE_FRASE).flatMap { taglia(it, max) }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (f in frasi) {
            if (sb.isNotEmpty() && sb.length + 1 + f.length > max) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(f)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Una frase più lunga di [max]: prima sulle virgole, poi sugli spazi, poi a forza. */
    private fun taglia(frase: String, max: Int): List<String> {
        if (frase.length <= max) return listOf(frase)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (parte in frase.split(VIRGOLA)) {
            for (p in if (parte.length <= max) listOf(parte) else sulleParole(parte, max)) {
                if (sb.isNotEmpty() && sb.length + 1 + p.length > max) {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(p)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    private fun sulleParole(s: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (w in s.split(' ')) {
            var parola = w
            while (parola.length > max) {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
                out.add(parola.take(max))
                parola = parola.drop(max)
            }
            if (sb.isNotEmpty() && sb.length + 1 + parola.length > max) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(parola)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Una stringa JSON con le virgolette (org.json sulla JVM delle prove è vuoto). */
    fun jsonStringa(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
