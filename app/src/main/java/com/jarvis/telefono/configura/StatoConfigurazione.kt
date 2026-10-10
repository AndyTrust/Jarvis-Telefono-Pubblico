package com.jarvis.telefono.configura

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.jarvis.telefono.Inventario
import com.jarvis.telefono.Permessi
import com.jarvis.telefono.cassaforte.AccountMail
import com.jarvis.telefono.cassaforte.AvvioCassaforte
import com.jarvis.telefono.cassaforte.Cassaforte
import com.jarvis.telefono.cassaforte.Cervello
import com.jarvis.telefono.ui.Tema
import com.jarvis.telefono.voce.Modello
import com.jarvis.telefono.vps.ConfigVps

/**
 * Dove sta la procedura guidata sul telefono (preferenze «configurazione», nessun segreto) e cosa dice il telefono
 * adesso ([fatti]). La decisione pura sta in [Passi].
 */
object StatoConfigurazione {
    private const val PREFS = "configurazione"
    private const val CHIUSA = "chiusa"
    private const val SALTATI = "saltati"
    private const val PROVA_FATTA = "prova_fatta"
    private const val COMPLETATI = "completati_mai"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun chiusa(c: Context) = p(c).getBoolean(CHIUSA, false)
    fun segnaChiusa(c: Context) { p(c).edit().putBoolean(CHIUSA, true).apply() }

    fun saltati(c: Context): Set<Passo> = p(c).getStringSet(SALTATI, emptySet()).orEmpty().mapNotNull { Passo.da(it) }.toSet()
    fun salta(c: Context, passo: Passo, si: Boolean = true) {
        val s = saltati(c).map { it.chiave }.toMutableSet()
        if (si) s += passo.chiave else s -= passo.chiave
        p(c).edit().putStringSet(SALTATI, s).apply()
    }

    fun segnaProvaFatta(c: Context) { p(c).edit().putBoolean(PROVA_FATTA, true).apply() }

    /**
     * Da quando contano le frasi della prova guidata: la prima apertura del passo 5 (resta valida un'ora), così si
     * può andare a scrivere nella Home e tornare senza perdere le frasi già dette.
     */
    fun inizioProva(c: Context, adesso: Long = System.currentTimeMillis()): Long {
        val t = p(c).getLong("inizio_prova", 0L)
        if (t > 0 && adesso - t < 3_600_000L) return t
        p(c).edit().putLong("inizio_prova", adesso - 1000).apply()
        return adesso - 1000
    }

    /** Quanti passi sono mai stati verdi (resta anche se un permesso si toglie dopo). */
    fun ricordaCompletati(c: Context, f: Fatti) {
        val n = Passi.quantiFatti(f)
        if (n > p(c).getInt(COMPLETATI, 0)) p(c).edit().putInt(COMPLETATI, n).apply()
    }

    fun permessoRubrica(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun modelliPronti(c: Context): Boolean = runCatching {
        val m = Inventario.modelli(c)
        Modello.CATALOGO.all { m.presente(it) }
    }.getOrDefault(false)

    fun fatti(c: Context): Fatti {
        val voci = runCatching { Cassaforte.di(c).elenco() }.getOrDefault(emptyList())
        return Fatti(
            microfono = Permessi.microfono(c),
            notifiche = Permessi.notifiche(c),
            accessibilita = Permessi.accessibilita(c),
            rubrica = permessoRubrica(c),
            batteria = Permessi.batteriaEsclusa(c),
            modelliPronti = modelliPronti(c),
            vpsPronta = ConfigVps.dati(c).completa,
            cervelli = voci.count { it is Cervello },
            caselle = voci.count { it is AccountMail },
            provaFatta = p(c).getBoolean(PROVA_FATTA, false),
        )
    }

    /**
     * Le voci arrivate dalla migrazione. Se la 0.3.x ha migrato prima che esistesse il contatore, vale «c'è stata»
     * quando config-boss.json c'è ancora e la cassaforte non è vuota.
     */
    private fun vociMigrate(c: Context, cassaforteVuota: Boolean): Int {
        val n = AvvioCassaforte.vociMigrate(c)
        if (n > 0) return n
        val f = com.jarvis.telefono.nucleo.ConfigPersonale.file(c)
        return if (!cassaforteVuota && f.isFile) 1 else 0
    }

    fun modo(c: Context): ModoAvvio {
        val vuota = runCatching { Cassaforte.di(c).elenco().isEmpty() }.getOrDefault(true)
        return Passi.decidiAvvio(chiusa(c), vuota, p(c).getInt(COMPLETATI, 0), vociMigrate(c, vuota))
    }

    /** Dalla Home: dopo la migrazione, apre la procedura se tocca. true = aperta (la Home non chiede i permessi da sé). */
    fun apriSeServe(a: Activity): Boolean {
        val m = modo(a)
        if (m == ModoAvvio.NESSUNA) return false
        Tema.apri(a, intento(a, m))
        return true
    }

    fun intento(c: Context, m: ModoAvvio = ModoAvvio.NUOVA): Intent =
        Intent(c, ConfigurazioneActivity::class.java).putExtra(ConfigurazioneActivity.EXTRA_MODO, m.name)
}
