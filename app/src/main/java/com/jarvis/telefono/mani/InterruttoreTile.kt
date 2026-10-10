package com.jarvis.telefono.mani

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.jarvis.telefono.PhoneActionExecutor

/**
 * Il riquadro «Ferma Jarvis» nelle impostazioni rapide (tendina).
 *
 * 1.2.2 (Boss 07/10): prima era un interruttore che fermava le mani PER SEMPRE, finché Boss non
 * lo riaccendeva da qui, e Jarvis sembrava rotto. Adesso un tocco ferma solo l'operazione in
 * corso (bozza annullata, azioni di quella richiesta rifiutate); Jarvis resta in ascolto e la
 * richiesta successiva riparte da sola. Il riquadro resta sempre «attivo».
 */
class InterruttoreTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        aggiorna()
    }

    override fun onClick() {
        super.onClick()
        PhoneActionExecutor.attachSeServe(this)
        PhoneActionExecutor.fermaOperazione("riquadro delle impostazioni rapide")
        Toast.makeText(this, "Operazione fermata: niente inviato. JBoss resta attivo.", Toast.LENGTH_SHORT).show()
        aggiorna()
    }

    private fun aggiorna() {
        val t = qsTile ?: return
        t.state = Tile.STATE_ACTIVE
        t.label = "Ferma JBoss"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            t.subtitle = "solo l'operazione in corso"
        }
        t.updateTile()
    }
}
