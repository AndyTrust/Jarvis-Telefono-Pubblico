package com.jarvis.telefono

import android.content.Context
import android.util.Log
import com.jarvis.telefono.voce.EsitoImport
import com.jarvis.telefono.voce.Glossario
import com.jarvis.telefono.voce.Modelli
import com.jarvis.telefono.voce.Modello
import com.jarvis.telefono.voce.StatoJarvis
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Quello che la schermata sa leggendo i file del telefono, senza il servizio:
 * se Whisper è scaricato e quanto pesa, quanti termini ha il glossario e di
 * quando è. Serve perché la schermata dica il vero anche a servizio spento.
 *
 * Le cartelle stanno qui, in un posto solo: il servizio (JarvisService) deve
 * usare le stesse.
 */
object Inventario {

    /** Whisper ed ERes2Net (Impronta.kt: «lo scarica Modelli in filesDir/modelli/»). */
    fun cartellaModelli(context: Context): File = File(context.filesDir, "modelli")

    /** glossario.txt, correzioni.jsonl, parole-comuni.txt scaricati dal ponte. */
    fun cartellaGlossario(context: Context): File = File(context.filesDir, "glossario")

    /** Un solo client per gli scarichi lunghi: la lettura può restare ferma a lungo su una rete lenta. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun modelli(context: Context) = Modelli(cartellaModelli(context), client, registro = { Log.i(TAG, it) })

    /**
     * Dove `adb push` può mettere i file di un modello perché l'app li importi:
     * `/sdcard/Android/data/com.jarvis.telefono/files/modelli/<nome>/` (scripts/importa-modelli.sh).
     * La cartella interna dei modelli (filesDir) adb non la raggiunge su un'app firmata.
     */
    fun cartellaImport(context: Context, modello: Modello): File? =
        context.getExternalFilesDir(null)?.let { File(File(it, "modelli"), modello.nome) }

    /**
     * Importa da [cartellaImport] i modelli che ci trova (Whisper ed ERes2Net). Tocca il
     * disco e copia centinaia di MB: mai sul thread principale. Torna l'ultimo esito utile
     * (un errore vince su un «fatto»), [EsitoImport.Niente] se non c'era niente da importare.
     * Un solo import alla volta: chi arriva mentre un altro copia trova Niente.
     */
    fun importaModelli(context: Context): EsitoImport {
        if (!importando.compareAndSet(false, true)) return EsitoImport.Niente
        try {
            val app = context.applicationContext
            val m = modelli(app)
            var esito: EsitoImport = EsitoImport.Niente
            for (modello in listOf(Modello.WHISPER_SMALL, Modello.ERES2NET)) {
                val sorgente = cartellaImport(app, modello) ?: continue
                if (!sorgente.isDirectory) continue
                val e = m.importa(modello, sorgente)
                Log.i(TAG, "importa ${modello.nome}: $e")
                if (e is EsitoImport.Errore || esito == EsitoImport.Niente) esito = e
                if (e is EsitoImport.Errore) break
            }
            ultimoImport = esito
            return esito
        } finally {
            importando.set(false)
        }
    }

    /** L'ultimo esito dell'importazione automatica, per la schermata Impostazioni. */
    @Volatile var ultimoImport: EsitoImport = EsitoImport.Niente
        private set

    private val importando = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val TAG = "JarvisModelli"

    /** Byte dei file di Whisper che ci sono davvero sul telefono. */
    fun byteWhisper(context: Context): Long {
        val m = modelli(context)
        return Modello.WHISPER_SMALL.file.sumOf { f -> m.percorso(f.nome).takeIf { it.isFile }?.length() ?: 0L }
    }

    /** Rilegge i file e aggiorna lo stato. Tocca il disco: mai sul thread principale. */
    fun rileggi(context: Context) {
        val app = context.applicationContext
        // 0.3.1: se con adb sono arrivati i file di un modello, si importano qui (all'avvio
        // del servizio e a ogni apertura delle schermate), prima di dire se Whisper c'è.
        runCatching { importaModelli(app) }.onFailure { Log.w(TAG, "importa: ${it.javaClass.simpleName}") }
        val m = modelli(app)
        val whisper = m.presente(Modello.WHISPER_SMALL)
        val byte = byteWhisper(app)
        val cartellaG = cartellaGlossario(app)
        val termini = Glossario(cartellaG).termini().size
        val fileG = File(cartellaG, Glossario.GLOSSARIO)
        val quando = if (fileG.isFile) quando(fileG.lastModified(), System.currentTimeMillis()) else null
        StatoJarvis.aggiorna {
            it.copy(
                whisperPresente = whisper,
                whisperByte = byte,
                glossarioTermini = termini,
                glossarioAggiornato = quando,
            )
        }
    }

    fun rileggiInSfondo(context: Context) {
        val app = context.applicationContext
        Thread({ runCatching { rileggi(app) } }, "jarvis-inventario").start()
    }
}
