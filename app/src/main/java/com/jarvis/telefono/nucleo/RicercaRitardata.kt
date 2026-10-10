package com.jarvis.telefono.nucleo

import java.util.concurrent.atomic.AtomicLong

/**
 * 0.6.1 (audit 08/10, KO 7): la ricerca nella cronologia faceva un thread nuovo a ogni lettera, senza ordine, e un
 * risultato vecchio poteva coprire quello nuovo. Qui le due regole, senza Android (si provano sulla JVM):
 *
 * - [Rinvio]: aspetta [ritardoMs] di pausa fra le lettere; ogni nuova richiesta cancella quella in attesa,
 *   quindi parte un solo lavoro per pausa.
 * - [Turni]: ogni lavoro prende un numero; quando torna, si disegna solo se è ancora l'ultimo chiesto.
 */
class Rinvio(
    private val ritardoMs: Long,
    private val pianifica: (Long, Runnable) -> Unit,
    private val annulla: (Runnable) -> Unit,
) {
    private var inAttesa: Runnable? = null

    /** [f] partirà dopo [ritardoMs] se nel frattempo non arriva un'altra richiesta. Da un solo thread (il principale). */
    fun chiedi(f: () -> Unit) {
        inAttesa?.let(annulla)
        val r = object : Runnable {
            override fun run() {
                if (inAttesa === this) inAttesa = null
                f()
            }
        }
        inAttesa = r
        pianifica(ritardoMs, r)
    }

    /** Toglie la richiesta in attesa (la schermata va via). */
    fun cancella() {
        inAttesa?.let(annulla)
        inAttesa = null
    }

    val haInAttesa: Boolean get() = inAttesa != null

    companion object {
        /** La pausa fra le lettere approvata nel piano (300 ms). */
        const val RICERCA_MS = 300L
    }
}

/** Il numero dell'ultimo lavoro chiesto: i risultati di un lavoro superato si buttano. */
class Turni {
    private val ultimo = AtomicLong(0)

    fun nuovo(): Long = ultimo.incrementAndGet()

    fun valido(turno: Long): Boolean = ultimo.get() == turno
}
