package com.jarvis.telefono.cassaforte

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Perché la prova è andata male (o bene). Il testo per chi usa l'app sta in [ErroriPosta.testo]. */
enum class CausaProva {
    OK, DATI_MANCANTI, SERVER_SCONOSCIUTO, RETE, TLS, LOGIN_DISATTIVATO, PASSWORD_SBAGLIATA, PASSWORD_PER_APP,
    IMAP_SPENTO, ACCESSO_WEB, OAUTH_RICHIESTO, SOLO_APP, PROTOCOLLO, ALTRO,
}

data class EsitoProva(
    val causa: CausaProva,
    /** In italiano, mai con la password dentro. */
    val messaggio: String,
    /** Le cartelle trovate (nome → flag speciale \Sent, \Trash, \Drafts o vuoto). */
    val cartelle: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = causa == CausaProva.OK
    fun cartellaSpeciale(flag: String): String? = cartelle.entries.firstOrNull { it.value.equals(flag, true) }?.key
}

/**
 * La prova di una casella (0.3.1, 2026-10-07): si collega al server IMAP, entra con utente e password, elenca le
 * cartelle ed esce. Niente librerie di posta: un socket TLS e i quattro comandi che servono (CAPABILITY, LOGIN, LIST,
 * LOGOUT), qualche KB di codice invece dei ~700 KB di Jakarta Mail. Non legge e non tocca nessuna mail.
 *
 * [apriSocket] si cambia nelle prove (server finto locale). La password non va mai in un log né nel messaggio.
 */
class ProvaImap(
    private val timeoutMs: Int = 15_000,
    private val apriSocket: (host: String, porta: Int, timeoutMs: Int) -> Socket = { h, p, t -> Socket().apply { connect(InetSocketAddress(h, p), t); soTimeout = t } },
    private val avvolgiTls: (Socket, String, Int) -> Socket = ::tlsConVerifica,
) {
    fun prova(a: AccountMail): EsitoProva {
        if (a.tipo == TipoMail.SAMSUNG) return EsitoProva(CausaProva.SOLO_APP, ErroriPosta.testo(CausaProva.SOLO_APP, a.tipo))
        val srv = a.imap
        if (srv == null || !srv.valido || a.indirizzo.isBlank() || a.password.isEmpty()) {
            return EsitoProva(CausaProva.DATI_MANCANTI, ErroriPosta.testo(CausaProva.DATI_MANCANTI, a.tipo))
        }
        if (srv.sicurezza == Sicurezza.NESSUNA && srv.host !in LOCALI) {
            return EsitoProva(CausaProva.TLS, "Senza cifratura la password viaggerebbe in chiaro: scegli SSL (porta 993) o STARTTLS.")
        }
        return try {
            dialogo(a, srv)
        } catch (e: Exception) {
            val c = when (e) {
                is UnknownHostException -> CausaProva.SERVER_SCONOSCIUTO
                is SSLException -> CausaProva.TLS
                is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> CausaProva.RETE
                is ErroreProtocollo -> CausaProva.PROTOCOLLO
                is IOException -> CausaProva.RETE
                else -> CausaProva.ALTRO
            }
            EsitoProva(c, ErroriPosta.pulisci(ErroriPosta.testo(c, a.tipo, srv), a.password))
        }
    }

    private class ErroreProtocollo(m: String) : IOException(m)

    private fun dialogo(a: AccountMail, srv: ServerPosta): EsitoProva {
        var sock = apriSocket(srv.host, srv.porta, timeoutMs)
        try {
            if (srv.sicurezza == Sicurezza.SSL) sock = avvolgiTls(sock, srv.host, srv.porta)
            var io = Canale(sock)
            val saluto = io.riga()
            if (!saluto.startsWith("* OK") && !saluto.startsWith("* PREAUTH")) throw ErroreProtocollo("saluto inatteso")
            if (srv.sicurezza == Sicurezza.STARTTLS) {
                val r = io.comando("s0", "STARTTLS")
                if (!r.ok) return EsitoProva(CausaProva.TLS, "Il server non accetta STARTTLS su questa porta: prova SSL sulla 993.")
                sock = avvolgiTls(sock, srv.host, srv.porta)
                io = Canale(sock)
            }
            val cap = io.comando("c1", "CAPABILITY")
            val capacita = cap.nonTaggate.joinToString(" ").uppercase()
            if ("LOGINDISABLED" in capacita) {
                val c = if (a.tipo == TipoMail.OUTLOOK) CausaProva.OAUTH_RICHIESTO else CausaProva.LOGIN_DISATTIVATO
                return EsitoProva(c, ErroriPosta.testo(c, a.tipo, srv))
            }
            val login = io.login("l1", a.utenteEffettivo, a.password)
            if (!login.ok) {
                val c = ErroriPosta.causaDaRisposta(login.finale, a.tipo)
                return EsitoProva(c, ErroriPosta.pulisci(ErroriPosta.testo(c, a.tipo, srv), a.password))
            }
            val lista = io.comando("l2", "LIST \"\" \"*\"")
            val cartelle = LinkedHashMap<String, String>()
            for (r in lista.nonTaggate) ErroriPosta.cartellaDaList(r)?.let { cartelle[it.first] = it.second }
            runCatching { io.comando("l3", "LOGOUT") }
            val n = cartelle.size
            return EsitoProva(CausaProva.OK, "Accesso riuscito: $n cartelle trovate.", cartelle)
        } finally {
            runCatching { sock.close() }
        }
    }

    private class Risposta(val ok: Boolean, val finale: String, val nonTaggate: List<String>)

    /** Lettura a righe CRLF e invio dei comandi, con i letterali IMAP per le password con caratteri speciali. */
    private class Canale(s: Socket) {
        private val ins: InputStream = BufferedInputStream(s.getInputStream())
        private val out: OutputStream = s.getOutputStream()

        fun riga(): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = ins.read()
                if (c < 0) { if (b.size() == 0) throw IOException("collegamento chiuso dal server") else break }
                if (c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
                if (b.size() > 64_000) throw ErroreProtocollo("riga troppo lunga")
            }
            return b.toString(Charsets.UTF_8.name())
        }

        private fun manda(b: ByteArray) { out.write(b); out.flush() }

        fun comando(tag: String, testo: String): Risposta {
            manda("$tag $testo\r\n".toByteArray(Charsets.UTF_8))
            return attendi(tag)
        }

        private fun attendi(tag: String): Risposta {
            val altre = ArrayList<String>()
            while (true) {
                val r = riga()
                if (r.startsWith("$tag ")) {
                    val resto = r.substring(tag.length + 1)
                    return Risposta(resto.startsWith("OK", true), resto, altre)
                }
                altre += r
                if (altre.size > 5000) throw ErroreProtocollo("troppe righe")
            }
        }

        /** LOGIN con stringhe tra virgolette se sono ASCII semplice, altrimenti letterali {n}. */
        fun login(tag: String, utente: String, password: String): Risposta {
            val parti = listOf(utente, password)
            val buf = ByteArrayOutputStream()
            buf.write("$tag LOGIN".toByteArray())
            for (p in parti) {
                buf.write(' '.code)
                if (p.all { it.code in 0x20..0x7e }) {
                    buf.write(("\"" + p.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toByteArray(Charsets.US_ASCII))
                } else {
                    val bytes = p.toByteArray(Charsets.UTF_8)
                    buf.write("{${bytes.size}}\r\n".toByteArray())
                    manda(buf.toByteArray()); buf.reset()
                    val cont = riga()
                    if (!cont.startsWith("+")) return Risposta(false, cont.removePrefix("$tag "), emptyList())
                    buf.write(bytes)
                    bytes.fill(0)
                }
            }
            buf.write("\r\n".toByteArray())
            manda(buf.toByteArray())
            buf.reset()
            return attendi(tag)
        }
    }

    companion object {
        private val LOCALI = setOf("127.0.0.1", "localhost", "::1")

        /** TLS con SNI e controllo del nome del server nel certificato (SSLSocket da solo non lo fa). */
        fun tlsConVerifica(s: Socket, host: String, porta: Int): Socket {
            val t = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, porta, true) as SSLSocket
            t.soTimeout = s.soTimeout
            t.startHandshake()
            val ok = runCatching { javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, t.session) }.getOrDefault(false)
            if (!ok) { runCatching { t.close() }; throw SSLException("certificato non valido per $host") }
            return t
        }
    }
}
