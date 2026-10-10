package com.jarvis.telefono.cassaforte

import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifra e decifra i byte della cassaforte. Formato: [1 byte versione][12 byte IV][testo cifrato + tag GCM 16 byte].
 * Sul telefono la chiave sta nell'Android Keystore ([CifratoreKeystore]); nelle prove JVM una chiave software.
 */
interface Cifratore {
    fun cifra(chiaro: ByteArray): ByteArray
    fun decifra(cifrato: ByteArray): ByteArray
}

/** AES-256-GCM con una chiave data. La usa [CifratoreKeystore] (chiave del Keystore) e le prove (chiave software). */
open class CifratoreAesGcm(private val chiave: () -> SecretKey) : Cifratore {
    override fun cifra(chiaro: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRASFORMAZIONE)
        // Il Keystore vuole scegliere lui l'IV (randomizedEncryptionRequired): lo si legge dopo init.
        c.init(Cipher.ENCRYPT_MODE, chiave())
        val iv = c.iv
        require(iv.size == IV) { "IV inatteso" }
        val corpo = c.doFinal(chiaro)
        return byteArrayOf(VERSIONE) + iv + corpo
    }

    override fun decifra(cifrato: ByteArray): ByteArray {
        require(cifrato.size > 1 + IV + 16 && cifrato[0] == VERSIONE) { "formato della cassaforte non riconosciuto" }
        val c = Cipher.getInstance(TRASFORMAZIONE)
        c.init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, cifrato, 1, IV))
        return c.doFinal(cifrato, 1 + IV, cifrato.size - 1 - IV)
    }

    companion object {
        const val TRASFORMAZIONE = "AES/GCM/NoPadding"
        const val IV = 12
        const val VERSIONE: Byte = 1

        /** Una chiave software a caso (solo prove JVM). */
        fun chiaveSoftware(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

/**
 * La chiave vive nell'Android Keystore: non esportabile, non esce mai dal telefono (sui telefoni con chip sicuro
 * resta nell'hardware). Non chiede l'impronta per ogni uso: la voce e il cervello in sottofondo devono poter
 * leggere la cassaforte. L'impronta o il PIN servono per MOSTRARE un segreto sullo schermo ([Sblocco]).
 * Se il telefono viene ripristinato su un altro apparecchio la chiave non c'è: la cassaforte si rifà
 * (oppure si importa l'esportazione cifrata con la frase, vedi [Esportazione]).
 */
class CifratoreKeystore(private val alias: String = ALIAS) : CifratoreAesGcm({ chiaveDelKeystore(alias) }) {
    companion object {
        const val ALIAS = "jboss_cassaforte_v1"

        @Synchronized
        fun chiaveDelKeystore(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            val g = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(spec)
            return g.generateKey()
        }

        fun cancellaChiave(alias: String = ALIAS) {
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
        }
    }
}

/**
 * Esportazione cifrata con una FRASE scelta da chi usa l'app (per cambiare telefono o tenere una copia):
 * PBKDF2-HMAC-SHA256 (210 000 giri) → AES-256-GCM. Senza la frase il file non si apre, nemmeno sulla VPS.
 * Formato testo: «jboss-cassaforte:1:<sale b64>:<blob b64>».
 */
object Esportazione {
    private const val GIRI = 210_000
    private const val PREFISSO = "jboss-cassaforte:1:"

    fun chiaveDaFrase(frase: CharArray, sale: ByteArray, giri: Int = GIRI): SecretKey {
        val spec = PBEKeySpec(frase, sale, giri, 256)
        try {
            val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(k, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun esporta(chiaro: ByteArray, frase: CharArray, giri: Int = GIRI): String {
        require(frase.size >= 8) { "la frase deve avere almeno 8 caratteri" }
        val sale = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val k = chiaveDaFrase(frase, sale, giri)
        val blob = CifratoreAesGcm { k }.cifra(chiaro)
        return PREFISSO + giri + ":" + b64(sale) + ":" + b64(blob)
    }

    fun importa(testo: String, frase: CharArray): ByteArray {
        require(testo.startsWith(PREFISSO)) { "non è un'esportazione di JBoss" }
        val parti = testo.removePrefix(PREFISSO).trim().split(":")
        require(parti.size == 3) { "esportazione rovinata" }
        val giri = parti[0].toIntOrNull()?.takeIf { it in 10_000..5_000_000 } ?: error("esportazione rovinata")
        val k = chiaveDaFrase(frase, deb64(parti[1]), giri)
        return runCatching { CifratoreAesGcm { k }.decifra(deb64(parti[2])) }
            .getOrElse { throw IllegalArgumentException("frase sbagliata o file rovinato") }
    }

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun deb64(s: String) = java.util.Base64.getDecoder().decode(s)
}
