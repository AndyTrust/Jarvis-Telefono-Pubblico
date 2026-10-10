package com.jarvis.telefono.voce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpezzaTest {

    private val hz = 16_000

    /** Un VAD finto: parla se nel fotogramma c'è energia. */
    private val vadEnergia = object : Vad {
        override fun parlato(pcm: FloatArray) = pcm.any { kotlin.math.abs(it) > 0.01f }
    }

    /** [secondi] di «voce» (un'onda a 0,5) con silenzi da [silenzioS] centrati sui secondi in [silenzi]. */
    private fun frase(secondi: Float, silenzi: List<Float> = emptyList(), silenzioS: Float = 1f): FloatArray {
        val a = FloatArray((secondi * hz).toInt()) { i -> if (i % 2 == 0) 0.5f else -0.5f }
        for (c in silenzi) {
            val da = ((c - silenzioS / 2) * hz).toInt()
            val a2 = ((c + silenzioS / 2) * hz).toInt()
            for (i in da until a2) a[i] = 0f
        }
        return a
    }

    private fun secondi(pezzo: FloatArray) = pezzo.size.toFloat() / hz

    @Test
    fun `una frase di 10 secondi resta intera`() {
        val pcm = frase(10f)
        val pezzi = spezza(pcm, vadEnergia)
        assertEquals(1, pezzi.size)
        assertTrue(pezzi[0] === pcm || pezzi[0].contentEquals(pcm))
    }

    @Test
    fun `70 secondi con silenzi ogni 25 danno 3 pezzi tagliati nei silenzi`() {
        val pcm = frase(70f, silenzi = listOf(25f, 50f))
        val pezzi = spezza(pcm, vadEnergia)

        assertEquals(3, pezzi.size)
        pezzi.forEach { assertTrue("pezzo di ${secondi(it)} s", secondi(it) <= 30f) }
        assertEquals(pcm.size, pezzi.sumOf { it.size })

        // I tagli cadono dentro i silenzi (24,5-25,5 s e 49,5-50,5 s).
        val taglio1 = secondi(pezzi[0])
        val taglio2 = taglio1 + secondi(pezzi[1])
        assertTrue("primo taglio a $taglio1 s", taglio1 in 24.5f..25.5f)
        assertTrue("secondo taglio a $taglio2 s", taglio2 in 49.5f..50.5f)
        // Proprio nel silenzio: il campione dove si taglia è muto.
        assertEquals(0f, pcm[pezzi[0].size], 0f)
        assertEquals(0f, pcm[pezzi[0].size + pezzi[1].size], 0f)
    }

    @Test
    fun `sceglie il silenzio piu lungo prima dei 30 secondi`() {
        // Un silenzio corto a 10 s e uno lungo (2 s) a 20 s: vince quello a 20.
        val pcm = frase(40f, silenzi = listOf(10f)).also { a ->
            for (i in (19 * hz) until (21 * hz)) a[i] = 0f
        }
        val pezzi = spezza(pcm, vadEnergia)
        assertEquals(2, pezzi.size)
        assertTrue("taglio a ${secondi(pezzi[0])} s", secondi(pezzi[0]) in 19f..21f)
    }

    @Test
    fun `senza silenzi taglia a 30 secondi netti`() {
        val pcm = frase(70f)
        val pezzi = spezza(pcm, vadEnergia)

        assertEquals(listOf(30 * hz, 30 * hz, 10 * hz), pezzi.map { it.size })
        assertEquals(pcm.size, pezzi.sumOf { it.size })
    }

    @Test
    fun `i pezzi rimessi in fila ridanno l'audio di partenza`() {
        val pcm = frase(95.3f, silenzi = listOf(12f, 40f, 71f))
        val pezzi = spezza(pcm, vadEnergia)
        pezzi.forEach { assertTrue(secondi(it) <= 30f) }
        val rifatto = FloatArray(pcm.size)
        var p = 0
        for (x in pezzi) { x.copyInto(rifatto, p); p += x.size }
        assertTrue(rifatto.contentEquals(pcm))
    }

    @Test
    fun `il VAD vede ogni fotogramma da 480 campioni una volta sola e in ordine`() {
        val visti = mutableListOf<Int>()
        val vad = object : Vad {
            override fun parlato(pcm: FloatArray): Boolean { visti += pcm.size; return true }
        }
        val pcm = frase(61f)
        spezza(pcm, vad)
        assertEquals(pcm.size / 480, visti.size)
        assertTrue(visti.all { it == 480 })
    }
}
