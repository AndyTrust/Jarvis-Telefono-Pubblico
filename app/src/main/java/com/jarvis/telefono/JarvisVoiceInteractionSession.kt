package com.jarvis.telefono

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

// Quello che si apre quando tieni premuto il tasto laterale, una volta che
// Jarvis è scelto come app assistente in Impostazioni. Non ha una vita
// propria: si appoggia a JarvisService (lo accende se non gira già) per
// ascoltare, manda quello che hai detto, e si chiude da sola.
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private var statusView: TextView? = null

    override fun onCreateContentView(): View {
        val view = LayoutInflater.from(context).inflate(R.layout.voice_session_content, null)
        statusView = view.findViewById(R.id.voice_session_status)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        statusView?.text = "Ti ascolto…"

        if (JarvisService.instance == null) {
            val intent = Intent(context, JarvisService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        // Il servizio potrebbe essere appena partito: gli do un attimo prima
        // di chiedergli di ascoltare, così fa in tempo a inizializzarsi.
        window.window?.decorView?.postDelayed({
            val service = JarvisService.instance
            if (service == null) {
                statusView?.text = "JBoss non è riuscito a partire, apri l'app"
                window.window?.decorView?.postDelayed({ hide() }, 2000)
                return@postDelayed
            }
            service.startVoiceSession {
                statusView?.text = "Fatto"
                window.window?.decorView?.postDelayed({ hide() }, 600)
            }
        }, 400)
    }
}
