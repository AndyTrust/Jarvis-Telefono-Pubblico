package com.jarvis.telefono.vps

import org.json.JSONObject

/**
 * Il protocollo del ruolo «mani» (JBoss 0.3.4, 2026-10-07): lo stesso dell'app com.jarvis.app 1.2.x
 * verso jarvis-agent (server/index.js, phoneBridge.js, phoneTools.js). Kotlin puro, provato in CanaleManiTest.
 *
 *   telefono → VPS: auth {token, ruolo:"mani"} → user_message {text} → tool_result {id, result | error}
 *   VPS → telefono: connected → tool_call {id, command:{action,…}} → assistant_message {text, error?}
 *                   risposta_conferma {testo} (Boss ha scritto nel sito mentre una bozza aspetta)
 *                   in_lavoro {testo, secondi} (0.4.2: segno di vita mentre Claude usa gli strumenti della VPS)
 *                   assistant_message {text, tardiva:true} (0.4.2: risposta di una frase già chiusa sul telefono,
 *                   tenuta da parte dalla VPS finché il telefono non si ricollega)
 *
 * 0.6.0 (collegamento unico): auth porta `app: "jboss"` (la VPS lo dice in /health, `phoneApp`), user_message porta il
 * `rid` della frase e ogni assistant_message torna con `origine` («telefono», «sito», «sottofondo») e il `rid`:
 * chi risponde lo decide [com.jarvis.telefono.collegamento.Arbitro].
 *
 * La VPS tratta come «telefono delle mani» ogni auth senza `ruolo: "lavori"`: `ruolo: "mani"` è solo
 * documentazione. Un solo telefono alla volta: un secondo telefono chiude il primo con 4000.
 * Il token non va mai nei log: [auth] è l'unico posto dove compare.
 */
object ProtocolloMani {
    const val RUOLO = "mani"

    /** Chiusure della VPS. */
    const val CHIUSO_SOSTITUITO = 4000
    const val CHIUSO_TOKEN = 4001

    const val APP = "jboss"

    fun auth(token: String): String =
        JSONObject().put("type", "auth").put("token", token).put("ruolo", RUOLO).put("versione", 2).put("app", APP).toString()

    fun userMessage(testo: String, rid: String? = null): String =
        JSONObject().put("type", "user_message").put("text", testo).apply { if (!rid.isNullOrBlank()) put("rid", rid) }.toString()

    /** Il risultato delle mani, con l'id della VPS. [payload] è quello di PhoneActionExecutor (result o error). */
    fun toolResult(id: String, payload: JSONObject?): String {
        val o = JSONObject().put("type", "tool_result").put("id", id)
        when {
            payload == null -> o.put("error", "Nessuna risposta dalle mani di JBoss in tempo: non è stato fatto niente di sicuro.")
            payload.has("error") -> o.put("error", payload.opt("error"))
            payload.has("result") -> o.put("result", payload.opt("result"))
            else -> o.put("result", true)
        }
        return o.toString()
    }

    /** 0.6.1: il token FCM per la sveglia della VPS (server/sveglia.js lo salva in /root/jarvis, 600). */
    fun tokenFcm(token: String): String =
        JSONObject().put("type", "fcm_token").put("token", token).put("app", APP).toString()

    fun toolErrore(id: String, motivo: String): String =
        JSONObject().put("type", "tool_result").put("id", id).put("error", motivo).toString()

    /** `wss://host/phone` → `https://host/health` (stesso host, senza query). null se l'indirizzo è strano. */
    fun indirizzoSalute(url: String): String? {
        val u = url.trim()
        val schema = when {
            u.startsWith("wss://") -> "https://"
            u.startsWith("ws://") -> "http://"
            else -> return null
        }
        val resto = u.substringAfter("://").substringBefore('?').substringBefore('#')
        val host = resto.substringBefore('/')
        if (host.isBlank()) return null
        return "$schema$host/health"
    }

    sealed class Messaggio {
        object Collegato : Messaggio()
        data class ChiamataStrumento(val id: String, val comando: JSONObject) : Messaggio() {
            val azione: String get() = comando.optString("action")
        }
        data class Risposta(
            val testo: String,
            val errore: Boolean,
            val tardiva: Boolean = false,
            /** 0.6.0: «telefono», «sito», «sottofondo»; null = VPS di prima della 0.6.0. */
            val origine: String? = null,
            val rid: String? = null,
        ) : Messaggio()
        /** 0.4.2: la VPS sta lavorando (strumenti suoi, non del telefono): la frase è viva. */
        data class InLavoro(val testo: String, val secondi: Int?) : Messaggio()
        data class RispostaConferma(val testo: String) : Messaggio()
        data class Altro(val tipo: String) : Messaggio()
    }

    fun leggi(testo: String): Messaggio? {
        val o = runCatching { JSONObject(testo) }.getOrNull() ?: return null
        return when (val t = o.optString("type")) {
            "connected" -> if (o.optString("ruolo").let { it.isEmpty() || it == RUOLO }) Messaggio.Collegato else Messaggio.Altro(t)
            "tool_call" -> {
                val c = o.optJSONObject("command") ?: return Messaggio.Altro(t)
                Messaggio.ChiamataStrumento(o.optString("id"), c)
            }
            "assistant_message" -> Messaggio.Risposta(
                o.optString("text"), o.optBoolean("error"), o.optBoolean("tardiva"),
                o.optString("origine").ifBlank { null }, o.optString("rid").ifBlank { null },
            )
            "in_lavoro" -> Messaggio.InLavoro(o.optString("testo"), if (o.isNull("secondi") || !o.has("secondi")) null else o.optInt("secondi"))
            "risposta_conferma" -> Messaggio.RispostaConferma(o.optString("testo"))
            else -> Messaggio.Altro(t)
        }
    }
}
