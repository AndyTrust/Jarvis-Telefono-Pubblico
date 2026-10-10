package com.jarvis.telefono

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class ConfirmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(PhoneActionExecutor.EXTRA_CALL_ID) ?: return
        val notificationId = intent.getIntExtra("notification_id", -1)
        // 1.2.4: la notifica ha solo Invia e Annulla. Qualunque altra azione (anche il «Ferma»
        // di una notifica rimasta dalla 1.2.3) vale Annulla: niente parte senza un Invia.
        val confirmed = intent.action == PhoneActionExecutor.ACTION_CONFIRM
        if (JarvisService.instance == null) {
            // Il servizio (e con lui il WebSocket) non gira più: la risposta
            // non arriverebbe comunque al server. Meglio dirlo all'utente che far
            // finta di avere confermato/annullato qualcosa nel vuoto.
            Toast.makeText(
                context, "JBoss è spento: riaccendilo per confermare o annullare.", Toast.LENGTH_LONG
            ).show()
        } else {
            PhoneActionExecutor.resolveConfirmation(callId, confirmed)
        }
        if (notificationId != -1) {
            context.getSystemService(NotificationManager::class.java).cancel(notificationId)
        }
    }
}
