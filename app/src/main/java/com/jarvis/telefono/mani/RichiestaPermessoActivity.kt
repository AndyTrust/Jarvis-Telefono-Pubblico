package com.jarvis.telefono.mani

import android.app.Activity
import android.os.Bundle

/**
 * Una schermata trasparente che chiede un permesso normale (oggi: la rubrica) e si chiude.
 * Serve perché la richiesta arriva dal ponte, dentro un servizio, e Android la vuole da
 * un'Activity.
 */
class RichiestaPermessoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permesso = intent.getStringExtra(EXTRA_PERMESSO)
        if (permesso == null) {
            finish()
            return
        }
        requestPermissions(arrayOf(permesso), 7)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    companion object {
        const val EXTRA_PERMESSO = "permesso"
    }
}
