package com.jarvis.telefono.collegamento

import android.content.Context
import android.util.Log
import com.jarvis.telefono.cassaforte.AccessoSito
import com.jarvis.telefono.cassaforte.Cassaforte

/**
 * Utente e password della webapp Jarvis (la pagina `/_ponte/entra`), presi dall'app 1.2.4 (CredenzialiWeb) e messi
 * nella cassaforte di JBoss: un solo posto cifrato per i segreti (Keystore, AES-GCM), visibile e cancellabile da
 * Impostazioni → Sicurezza come ogni «accesso sito». Niente file a parte, niente EncryptedSharedPreferences.
 *
 * Mai nei log: solo il nome della classe di un'eccezione. Il «No» a «Salvare?» resta nelle preferenze (non è un segreto).
 */
class AccessoSalvato(context: Context, private val base: String) {
    private val app = context.applicationContext
    private val id = AccessoSito.idPer(base)
    private val prefs = app.getSharedPreferences("webapp_jarvis", Context.MODE_PRIVATE)

    companion object { private const val TAG = "JarvisWebapp" }

    private fun cassaforte(): Cassaforte? = runCatching { Cassaforte.di(app) }.getOrNull()

    val disponibile: Boolean get() = cassaforte() != null

    fun leggi(): Pair<String, String>? = runCatching {
        val v = cassaforte()?.leggi(id) as? AccessoSito ?: return null
        if (v.utente.isEmpty() || v.password.isEmpty()) null else v.utente to v.password
    }.getOrElse { Log.w(TAG, "accesso: lettura non riuscita (${it.javaClass.simpleName})"); null }

    fun haCredenziali(): Boolean = leggi() != null

    fun salva(utente: String, password: String): Boolean = runCatching {
        val etichetta = "Webapp Jarvis (" + base.removePrefix("https://").substringBefore('/') + ")"
        cassaforte()?.salva(AccessoSito(id, etichetta, base, utente, password, "salvato dalla webapp dentro JBoss")) ?: return false
        prefs.edit().remove("rifiutato").apply()
        true
    }.getOrElse { Log.w(TAG, "accesso: salvataggio non riuscito (${it.javaClass.simpleName})"); false }

    fun dimentica() {
        runCatching { cassaforte()?.elimina(id) }.onFailure { Log.w(TAG, "accesso: cancellazione non riuscita (${it.javaClass.simpleName})") }
        prefs.edit().remove("rifiutato").apply()
    }

    fun rifiutato(): Boolean = prefs.getBoolean("rifiutato", false)
    fun segnaRifiutato() { prefs.edit().putBoolean("rifiutato", true).apply() }
}
