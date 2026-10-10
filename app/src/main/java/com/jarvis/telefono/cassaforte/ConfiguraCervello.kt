package com.jarvis.telefono.cassaforte

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Esito della prova di un cervello: in italiano, mai con la chiave dentro. */
data class EsitoCervello(val ok: Boolean, val messaggio: String, val codice: Int = 0)

/**
 * Le azioni sul cervello (0.3.1, 2026-10-07), da collegare alla schermata «Cervello».
 * La prova è a spesa zero: chiede l'elenco dei modelli (GET /v1/models), non genera testo.
 */
interface ConfiguraCervello {
    /** Controllo di forma, senza rete. null = va bene, altrimenti il motivo. */
    fun controllaForma(provider: ProviderCervello, token: String): String?

    /** Prova vera contro il servizio. Per Claude Code sulla VPS la prova la fa la VPS (account_set + giro di prova). */
    fun prova(c: Cervello): EsitoCervello
}

class ConfiguraCervelloHttp(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val baseAnthropic: String = "https://api.anthropic.com",
    private val baseOpenAi: String = "https://api.openai.com",
) : ConfiguraCervello {

    override fun controllaForma(provider: ProviderCervello, token: String): String? {
        val t = token.trim()
        if (t != token || t.any { it.isWhitespace() }) return "togli spazi e a capo dal token"
        return when (provider) {
            ProviderCervello.CLAUDE_CODE_VPS -> if (Regex("^sk-ant-oat\\d\\d-[A-Za-z0-9_-]{20,}$").matches(t)) null
                else "non sembra il token dell'abbonamento Claude: copialo intero, senza spazi"
            ProviderCervello.API_ANTHROPIC -> when {
                t.startsWith("sk-ant-oat") -> "questo è un token dell'abbonamento, non una chiave API: va nel cervello «Claude Code sulla VPS»"
                Regex("^sk-ant-api\\d\\d-[A-Za-z0-9_-]{20,}$").matches(t) -> null
                else -> "una chiave API Anthropic comincia con sk-ant-api"
            }
            ProviderCervello.API_OPENAI, ProviderCervello.CODEX -> if (Regex("^sk-[A-Za-z0-9_-]{20,}$").matches(t)) null
                else "una chiave OpenAI comincia con sk-"
        }
    }

    override fun prova(c: Cervello): EsitoCervello {
        controllaForma(c.provider, c.token)?.let { return EsitoCervello(false, it) }
        val req = when (c.provider) {
            ProviderCervello.CLAUDE_CODE_VPS ->
                return EsitoCervello(false, "Il token dell'abbonamento si prova sulla VPS: mandalo e la VPS fa un giro di prova con Claude Code.")
            ProviderCervello.API_ANTHROPIC -> Request.Builder().url("$baseAnthropic/v1/models?limit=1")
                .header("x-api-key", c.token).header("anthropic-version", "2023-06-01").get().build()
            ProviderCervello.API_OPENAI, ProviderCervello.CODEX -> Request.Builder().url("$baseOpenAi/v1/models")
                .header("Authorization", "Bearer " + c.token).get().build()
        }
        return try {
            http.newCall(req).execute().use { r ->
                val corpo = r.body?.string().orEmpty().take(2000)
                if (r.isSuccessful) EsitoCervello(true, "Chiave valida: il servizio risponde.", r.code)
                else EsitoCervello(false, testoErrore(r.code, corpo).let { pulisci(it, c.token) }, r.code)
            }
        } catch (e: IOException) {
            EsitoCervello(false, "Non raggiungo il servizio: niente rete o servizio irraggiungibile.")
        }
    }

    companion object {
        fun testoErrore(codice: Int, corpo: String): String {
            val b = corpo.lowercase()
            return when {
                codice == 401 -> "Chiave non valida o revocata."
                codice == 403 -> "La chiave è valida ma non ha il permesso per questa richiesta."
                codice == 429 -> "Troppe richieste o limite raggiunto: riprova tra poco."
                codice == 400 && ("credit" in b || "billing" in b) -> "Credito esaurito: ricarica dalla console del servizio."
                codice == 402 || "insufficient_quota" in b -> "Credito esaurito: ricarica dalla console del servizio."
                codice in 500..599 -> "Il servizio ha un problema in questo momento ($codice)."
                else -> "Risposta inattesa dal servizio ($codice)."
            }
        }

        fun pulisci(t: String, segreto: String) = if (segreto.length >= 6) t.replace(segreto, "••••") else t
    }
}
