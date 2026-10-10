package com.jarvis.telefono

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.telefono.ui.Tema

/**
 * Info e licenze (0.2.0). Gli avatar degli agenti derivano dai Dots di OpenDots / CopilotKit
 * (licenza MIT): la licenza chiede di tenere l'avviso di copyright e il testo intero con le
 * immagini. I due testi sono quelli del pacchetto (07_LICENZA), copiati in res/raw senza cambi.
 */
class LicenzeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenze)
        findViewById<ImageButton>(R.id.tasto_indietro).setOnClickListener { finish() }
        // Gli agenti (07_LICENZA) e Jarvis (Jarvis/Licenza): due avvisi, la stessa licenza MIT.
        findViewById<TextView>(R.id.testo_attribuzione).text =
            leggi(R.raw.attribuzione_opendots) + "\n\nJBoss, il capo (file «Jarvis» del pacchetto)\n" + leggi(R.raw.attribuzione_jarvis)
        // Stesso testo del file, con i paragrafi ricomposti: gli a capo fissi a 80 colonne su un
        // telefono spezzano le righe a metà.
        findViewById<TextView>(R.id.testo_mit).text = paragrafi(leggi(R.raw.licenza_opendots))
        findViewById<TextView>(R.id.testo_versione_licenze).text =
            getString(R.string.versione_app, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    override fun finish() {
        super.finish()
        Tema.chiudi(this)
    }

    private fun paragrafi(t: String): String =
        t.split(Regex("\\n\\s*\\n")).joinToString("\n\n") { it.trim().replace(Regex("\\s*\\n\\s*"), " ") }

    private fun leggi(id: Int): String =
        runCatching { resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText().trim() } }.getOrDefault("")
}
