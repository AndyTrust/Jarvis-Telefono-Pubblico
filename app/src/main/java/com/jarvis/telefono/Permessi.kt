package com.jarvis.telefono

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * I permessi dell'app letti dal telefono, in un posto solo: li usano la
 * schermata principale (primo avvio, ripartenza del servizio), le Impostazioni
 * e il BootReceiver. Si guarda sempre com'è il telefono adesso, mai come
 * l'avevamo lasciato: l'utente può togliere un permesso dalle impostazioni di
 * Android mentre l'app è aperta.
 */
object Permessi {

    private const val TAG = "Permessi"

    fun microfono(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Prima di Android 13 le notifiche non chiedono permesso: ci sono sempre. */
    fun notifiche(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Il servizio di Accessibilità di Jarvis è acceso in Impostazioni → Accessibilità? */
    fun accessibilita(context: Context): Boolean {
        val attivi = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return attivi.contains("${context.packageName}/${JarvisAccessibilityService::class.java.name}")
    }

    /** Jarvis escluso dall'ottimizzazione batteria: senza, molte marche (Samsung, Xiaomi, OnePlus…) fermano il servizio di notte. */
    fun batteriaEsclusa(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** La richiesta di sistema «togliere Jarvis dall'ottimizzazione batteria?». */
    fun apriEsclusioneBatteria(context: Context) {
        if (batteriaEsclusa(context)) return
        apri(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }

    /** La pagina Accessibilità di Android: lì si accende e si spegne il servizio di Jarvis. */
    fun apriAccessibilita(context: Context) {
        apri(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /**
     * La pagina «Informazioni app» di Jarvis. Un permesso concesso l'app non
     * può toglierselo da sola: per spegnerlo l'utente passa da qui.
     */
    fun apriPaginaApp(context: Context) {
        apri(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }

    /** Qualche ROM non ha la pagina: meglio una riga nel logcat che un'app chiusa. */
    private fun apri(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "pagina di sistema non disponibile: ${intent.action} (${it.javaClass.simpleName})") }
    }
}
