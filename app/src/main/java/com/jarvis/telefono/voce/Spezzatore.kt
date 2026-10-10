package com.jarvis.telefono.voce

/**
 * Dal blocco del microfono ai fotogrammi del Motore (Rifondazione 1.0,
 * seconda ondata). Il Microfono consegna blocchi da 4800 campioni (300 ms);
 * il Motore vuole fotogrammi da 480 (30 ms). I campioni che non riempiono un
 * fotogramma intero si scartano: con READ_BLOCKING il blocco è sempre pieno,
 * quindi succede solo se qualcuno cambia la misura del blocco.
 *
 * Senza Android: si prova sulla JVM (SpezzatoreTest).
 */
object Spezzatore {

    fun inFotogrammi(blocco: ShortArray, lunghezza: Int = Opzioni.FRAME_LEN): List<ShortArray> {
        require(lunghezza > 0)
        val n = blocco.size / lunghezza
        return List(n) { i -> blocco.copyOfRange(i * lunghezza, (i + 1) * lunghezza) }
    }

    /** L'istante del fotogramma [i] di un blocco cominciato a [inizioMs]: 30 ms l'uno. */
    fun istante(inizioMs: Long, i: Int): Long = inizioMs + i.toLong() * Opzioni.FRAME_MS
}
