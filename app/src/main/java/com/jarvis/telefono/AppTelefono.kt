package com.jarvis.telefono

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.telefono.mani.CatalogoApp
import com.jarvis.telefono.mani.CercaContatti
import com.jarvis.telefono.mani.ComponiIntent
import com.jarvis.telefono.mani.PianoIntent

/**
 * Le app del telefono viste da Jarvis (1.2.0): elenco, uso recente, apertura per nome,
 * app di posta predefinita, intent con la bozza pronta, rubrica.
 */
object AppTelefono {
    private const val TAG = "AppTelefono"

    /** Le app con un'icona nel launcher. */
    fun installate(context: Context): List<CatalogoApp.App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { CatalogoApp.App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pacchetto }
    }

    fun installata(context: Context, pacchetto: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pacchetto, 0); true }.getOrDefault(false)

    /** «Accesso all'uso» concesso? Serve solo a ordinare le app per uso recente. */
    fun usoPermesso(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val modo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return modo == AppOpsManager.MODE_ALLOWED
    }

    fun apriPermessoUso(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Pacchetto → ultimo uso (ms) negli ultimi 30 giorni. Vuoto senza permesso. */
    fun usoRecente(context: Context): Map<String, Long> {
        if (!usoPermesso(context)) return emptyMap()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val adesso = System.currentTimeMillis()
        return runCatching {
            usm.queryAndAggregateUsageStats(adesso - 30L * 86_400_000L, adesso)
                .mapValues { it.value.lastTimeUsed }
        }.getOrDefault(emptyMap())
    }

    /** Apre l'app che corrisponde a [nome]; restituisce l'app aperta o null. */
    fun apri(context: Context, nome: String): CatalogoApp.App? {
        val app = CatalogoApp.cerca(nome, installate(context), usoRecente(context), postaPredefinita(context)).firstOrNull()
        if (app == null) {
            Log.w(TAG, "nessuna app per \"$nome\"")
            return null
        }
        val intent = context.packageManager.getLaunchIntentForPackage(app.pacchetto) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); app }.getOrNull()
    }

    /** Il pacchetto che apre le mail di default, o null se il telefono chiede ogni volta. */
    fun postaPredefinita(context: Context): String? {
        val r = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), PackageManager.MATCH_DEFAULT_ONLY
        ) ?: return null
        val p = r.activityInfo?.packageName ?: return null
        return p.takeIf { it != "android" && !r.activityInfo.name.contains("Resolver", ignoreCase = true) }
    }

    /** «gmail», «samsung», un pacchetto o niente → il pacchetto della mail. */
    fun pacchettoPosta(context: Context, scelta: String?): String? {
        val s = scelta?.trim()?.lowercase().orEmpty()
        return when {
            // Vuoto o «predefinita»: l'app scelta nel sistema; se il telefono chiede ogni volta, la prima installata
            // fra le più comuni (CatalogoApp.POSTA). Nessuna marca ha la precedenza.
            s.isEmpty() || s == "predefinita" || s == "posta" -> postaPredefinita(context)
                ?: CatalogoApp.POSTA.firstOrNull { installata(context, it) }
            s.contains("gmail") || s == "google" -> ComponiIntent.GMAIL
            // Samsung Email solo se c'è davvero: altrove si ripiega sulla predefinita.
            s.contains("samsung") -> ComponiIntent.SAMSUNG_EMAIL.takeIf { installata(context, it) } ?: postaPredefinita(context)
            s.contains('.') -> s
            else -> CatalogoApp.cerca(s, installate(context)).firstOrNull()?.pacchetto
        }
    }

    /**
     * Esegue il piano: il primo pacchetto installato del piano, o l'app predefinita.
     * Restituisce il pacchetto usato ("" = scelta del sistema) o lancia un errore leggibile.
     */
    fun esegui(context: Context, piano: PianoIntent): String {
        val intent = Intent(piano.azione)
        val uri = piano.uri?.let { Uri.parse(it) }
        if (uri != null && piano.mime != null) intent.setDataAndType(uri, piano.mime)
        else if (uri != null) intent.data = uri
        else if (piano.mime != null) intent.type = piano.mime
        piano.extraTesti.forEach { (k, v) -> intent.putExtra(k, v) }
        piano.extraListe.forEach { (k, v) -> intent.putExtra(k, v.toTypedArray()) }
        piano.extraNumeri.forEach { (k, v) -> if (k == "allDay") intent.putExtra(k, v == 1L) else intent.putExtra(k, v) }
        piano.extraInteri.forEach { (k, v) -> intent.putExtra(k, v) }
        piano.extraSiNo.forEach { (k, v) -> intent.putExtra(k, v) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val scelto = piano.pacchetti.firstOrNull { installata(context, it) }
        if (piano.pacchetti.isNotEmpty() && scelto == null && piano.azione != ComponiIntent.VIEW) {
            throw IllegalStateException("Nessuna di queste app è installata: ${piano.pacchetti.joinToString()}")
        }
        if (scelto != null) intent.setPackage(scelto)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            if (scelto != null) {
                // L'app c'è ma non accetta questo intent (versione vecchia): si riprova senza pacchetto.
                intent.setPackage(null)
                context.startActivity(intent)
                return ""
            }
            throw IllegalStateException("Nessuna app del telefono sa aprire: ${piano.descrizione}")
        }
        return scelto ?: ""
    }

    fun rubricaPermessa(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** I contatti con numeri e mail, letti dalla rubrica. */
    fun contatti(context: Context): List<CercaContatti.Contatto> {
        val numeri = HashMap<String, MutableList<String>>()
        val mail = HashMap<String, MutableList<String>>()
        val cr = context.contentResolver
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nome = c.getString(0) ?: continue
                val n = c.getString(1) ?: continue
                val lista = numeri.getOrPut(nome) { mutableListOf() }
                if (lista.none { it.filter(Char::isDigit) == n.filter(Char::isDigit) }) lista.add(n)
            }
        }
        cr.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val nome = c.getString(0) ?: continue
                val a = c.getString(1) ?: continue
                mail.getOrPut(nome) { mutableListOf() }.add(a)
            }
        }
        return (numeri.keys + mail.keys).map { CercaContatti.Contatto(it, numeri[it].orEmpty(), mail[it].orEmpty()) }
    }
}
