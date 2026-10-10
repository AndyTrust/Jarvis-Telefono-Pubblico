package com.jarvis.telefono.configura

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.jarvis.telefono.JarvisAccessibilityService
import com.jarvis.telefono.Permessi
import com.jarvis.telefono.agenti.Avatar
import com.jarvis.telefono.agenti.CatalogoAgenti
import com.jarvis.telefono.cassaforte.Sblocco

/** Passo 1: JBoss grande e i permessi, ognuno con la sua riga ✓/○ e il pulsante giusto. */
class PannelloPermessi(a: BaseConfigura, private val conAvatar: Boolean = true) : Pannello(a) {
    private lateinit var microfono: Mattoni.Riga
    private lateinit var notifiche: Mattoni.Riga
    private lateinit var accessibilita: Mattoni.Riga
    private lateinit var rubrica: Mattoni.Riga
    private lateinit var batteria: Mattoni.Riga
    private lateinit var blocco: Mattoni.Riga

    override fun costruisci(dentro: LinearLayout) {
        val c = a
        val s = TestiConfigura.SPIEGA_PERMESSO
        if (conAvatar) {
            val cornice = FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Mattoni.dp(c, 120)).apply { topMargin = Mattoni.dp(c, 8) }
            }
            val img = ImageView(c).apply {
                contentDescription = "JBoss"
                layoutParams = FrameLayout.LayoutParams(Mattoni.dp(c, 104), Mattoni.dp(c, 104), Gravity.CENTER)
            }
            Avatar.metti(img, CatalogoAgenti.JARVIS, grande = true)
            cornice.addView(img)
            dentro.addView(cornice)
            com.jarvis.telefono.ui.Movimento.compari(img, Mattoni.dp(c, 12).toFloat())
        }
        dentro.addView(Mattoni.testo(c, TestiConfigura.BENVENUTO))
        val box = Mattoni.scheda(c)
        box.addView(Mattoni.sezione(c, "PERMESSI"))
        microfono = Mattoni.Riga(c, "Microfono", s.getValue("microfono"), "Concedi") {
            a.chiediPermesso(Manifest.permission.RECORD_AUDIO) { ok -> if (!ok) Permessi.apriPaginaApp(a); aggiorna() }
        }
        notifiche = Mattoni.Riga(c, "Notifiche", s.getValue("notifiche"), "Concedi") {
            if (Build.VERSION.SDK_INT >= 33) a.chiediPermesso(Manifest.permission.POST_NOTIFICATIONS) { ok -> if (!ok) Permessi.apriPaginaApp(a); aggiorna() }
            else Permessi.apriPaginaApp(a)
        }
        accessibilita = Mattoni.Riga(c, "Accessibilità", s.getValue("accessibilita"), "Guida →") { guidaAccessibilita() }
        rubrica = Mattoni.Riga(c, "Rubrica", s.getValue("rubrica"), "Concedi") {
            a.chiediPermesso(Manifest.permission.READ_CONTACTS) { ok -> if (!ok) Permessi.apriPaginaApp(a); aggiorna() }
        }
        batteria = Mattoni.Riga(c, "Batteria", s.getValue("batteria"), "Guida →") { guidaBatteria() }
        blocco = Mattoni.Riga(c, "Blocco schermo", s.getValue("blocco"), "Imposta") { a.apri(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        listOf(microfono, notifiche, accessibilita, rubrica, batteria, blocco).forEach { box.addView(it.vista) }
        dentro.addView(box)
        aggiorna()
    }

    override fun aggiorna() {
        fun st(ok: Boolean) = if (ok) StatoPasso.FATTO else StatoPasso.DA_FARE
        val mic = Permessi.microfono(a)
        microfono.aggiorna(st(mic), if (mic) "Fatto" else "Concedi", !mic)
        val no = Permessi.notifiche(a)
        notifiche.aggiorna(st(no), if (no) "Fatto" else "Concedi", !no)
        val acc = Permessi.accessibilita(a)
        accessibilita.aggiorna(st(acc), if (acc) "Fatto" else "Guida →", !acc)
        val ru = StatoConfigurazione.permessoRubrica(a)
        rubrica.aggiorna(st(ru), if (ru) "Fatto" else "Concedi", !ru)
        val ba = Permessi.batteriaEsclusa(a)
        batteria.aggiorna(st(ba), if (ba) "Fatto" else "Guida →", true)
        val bl = Sblocco.telefonoProtetto(a)
        blocco.aggiorna(st(bl), if (bl) "Fatto" else "Imposta", !bl)
        blocco.vista.visibility = if (bl) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Il testo con cosa premere, poi la pagina giusta di Android. Al ritorno la riga si ricontrolla da sola (onResume). */
    private fun guidaAccessibilita() {
        val marca = TestiConfigura.Marca.da(Build.MANUFACTURER, Build.BRAND)
        AlertDialog.Builder(a)
            .setTitle("Accendere le «mani» di JBoss")
            .setMessage(TestiConfigura.guidaAccessibilita(Build.VERSION.SDK_INT, marca))
            .setPositiveButton("Apri Accessibilità") { _, _ -> apriPaginaAccessibilita() }
            .setNeutralButton("Informazioni app") { _, _ -> Permessi.apriPaginaApp(a) }
            .setNegativeButton("Dopo", null)
            .show()
    }

    /** Il risparmio batteria, con i passi della marca del telefono. Al ritorno la riga si ricontrolla (onResume). */
    private fun guidaBatteria() {
        val marca = TestiConfigura.Marca.da(Build.MANUFACTURER, Build.BRAND)
        AlertDialog.Builder(a)
            .setTitle("JBoss sempre pronto (" + marca.nome + ")")
            .setMessage(TestiConfigura.guidaBatteria(marca))
            .setPositiveButton("Escludi ora") { _, _ -> Permessi.apriEsclusioneBatteria(a); aggiorna() }
            .setNeutralButton("Informazioni app") { _, _ -> Permessi.apriPaginaApp(a) }
            .setNegativeButton("Guida online") { _, _ ->
                runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marca.link))) }
            }
            .show()
    }

    /** Prima la pagina del servizio di JBoss (Android 13+, una schermata in meno), poi quella generale. */
    private fun apriPaginaAccessibilita() {
        if (Build.VERSION.SDK_INT >= 33) {
            val i = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(a, JarvisAccessibilityService::class.java).flattenToString())
                .setData(Uri.parse("package:" + a.packageName))
            if (runCatching { a.startActivity(i) }.isSuccess) return
        }
        Permessi.apriAccessibilita(a)
    }
}
