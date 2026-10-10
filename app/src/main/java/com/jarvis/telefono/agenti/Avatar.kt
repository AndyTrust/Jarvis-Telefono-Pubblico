package com.jarvis.telefono.agenti

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.jarvis.telefono.R
import java.util.concurrent.Executors

/**
 * Gli avatar dei 5 agenti (Dots di OpenDots, licenza MIT: vedi Impostazioni → Info e licenze).
 * WebP in `drawable-nodpi`: 128 px per striscia, righe, cronologia e bolla; 256 px per le schede.
 * Si decodificano fuori dal thread principale e restano in una cache piccola (10 bitmap, ~1,6 MB
 * al massimo). Le immagini si mostrano intere, senza ritaglio: la forma del Dot è il carattere.
 */
object Avatar {

    private val cache = LruCache<String, Bitmap>(14)
    private val lavoro = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-avatar") }
    private val principale = Handler(Looper.getMainLooper())

    /** La risorsa dell'avatar, o null se l'agente non ne ha (anche Jarvis, il capo: [CatalogoAgenti.JARVIS]). */
    fun risorsa(id: String, grande: Boolean): Int? = when (id) {
        CatalogoAgenti.POSTINO -> if (grande) R.drawable.avatar_postino_256 else R.drawable.avatar_postino_128
        CatalogoAgenti.RICERCATORE -> if (grande) R.drawable.avatar_ricercatore_256 else R.drawable.avatar_ricercatore_128
        CatalogoAgenti.SOCIAL -> if (grande) R.drawable.avatar_social_256 else R.drawable.avatar_social_128
        CatalogoAgenti.MANI -> if (grande) R.drawable.avatar_mani_256 else R.drawable.avatar_mani_128
        CatalogoAgenti.SCRITTORE -> if (grande) R.drawable.avatar_scrittore_256 else R.drawable.avatar_scrittore_128
        // 09/10: JBoss ha un solo volto, quello della Home approvato da Boss (giacca e cuffie), anche nella lista
        // Agenti, nelle risposte e nella bolla. Le immagini avatar_jarvis sono Jarvis della webapp (felpa e medaglione): resta
        // solo sulla tessera della webapp in Home e nella scheda Collegamento Jarvis.
        CatalogoAgenti.JARVIS -> R.drawable.jboss_grande_256
        else -> null
    }

    /** Già decodificato? (sul thread principale, per la bolla che non deve aspettare). */
    fun subito(context: Context, id: String, grande: Boolean = false): Bitmap? {
        val res = risorsa(id, grande) ?: return null
        val k = chiave(id, grande)
        return cache.get(k) ?: runCatching { BitmapFactory.decodeResource(context.resources, res) }.getOrNull()
            ?.also { cache.put(k, it) }
    }

    /** Mette l'avatar di [id] in [v]; se non è in cache lo decodifica su un altro thread. */
    fun metti(v: ImageView, id: String, grande: Boolean = false) {
        val res = risorsa(id, grande) ?: run { v.setImageDrawable(null); return }
        val k = chiave(id, grande)
        v.tag = k
        cache.get(k)?.let { v.setImageBitmap(it); return }
        v.setImageDrawable(null)
        val r = v.context.applicationContext.resources
        lavoro.execute {
            val b = runCatching { BitmapFactory.decodeResource(r, res) }.getOrNull() ?: return@execute
            cache.put(k, b)
            principale.post { if (v.tag == k) v.setImageBitmap(b) }
        }
    }

    /** All'avvio della schermata: le 5 piccole in cache prima che servano. */
    fun preparaPiccoli(context: Context) {
        val r = context.applicationContext.resources
        lavoro.execute {
            (CatalogoAgenti.ID + CatalogoAgenti.JARVIS).forEach { id ->
                val k = chiave(id, false)
                if (cache.get(k) == null) risorsa(id, false)?.let { res ->
                    runCatching { BitmapFactory.decodeResource(r, res) }.getOrNull()?.let { cache.put(k, it) }
                }
            }
        }
    }

    private fun chiave(id: String, grande: Boolean) = if (grande) "$id/256" else "$id/128"
}
