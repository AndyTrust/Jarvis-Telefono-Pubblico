package com.jarvis.telefono.collegamento

import java.net.URI
import java.util.Locale

/**
 * Le regole pure della webapp Jarvis dentro JBoss (0.6.0, presa dall'app 1.2.4 `com.jarvis.app`, AccessoWeb.kt).
 * Kotlin puro, provato in AccessoWebTest. Niente regex sugli indirizzi: `java.net.URI` e confronti di stringhe.
 *
 * La webapp è il sito Jarvis (Command Center della VPS, lo stesso del Mac e del PC Windows): chat, lavagna, stato,
 * Patrimonio. Nella WebView si resta sull'host del sito; il resto si apre fuori (http, https, mailto, tel) o si blocca.
 */
object AccessoWeb {

    /** La pagina di accesso del ponte (cc_ponte.py). */
    const val PERCORSO_ACCESSO = "/_ponte/entra"

    private fun uri(url: String?): URI? {
        if (url.isNullOrBlank()) return null
        return try { URI(url.trim()) } catch (e: Exception) { null }
    }

    private fun porta(u: URI): Int = if (u.port == -1 && u.scheme.equals("https", true)) 443 else u.port

    /** Un indirizzo di base accettabile: https, con un host, senza utente@. */
    fun indirizzoBaseValido(url: String?): Boolean {
        val u = uri(url) ?: return false
        return u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty() && u.rawUserInfo == null
    }

    /** Vero solo se [url] è https sullo stesso host e porta di [base]. */
    fun eHostBase(url: String?, base: String): Boolean {
        val u = uri(url) ?: return false
        val b = uri(base) ?: return false
        if (!u.scheme.equals("https", ignoreCase = true) || !b.scheme.equals("https", ignoreCase = true)) return false
        if (u.rawUserInfo != null) return false
        val hu = u.host?.lowercase(Locale.ROOT) ?: return false
        val hb = b.host?.lowercase(Locale.ROOT) ?: return false
        return hu == hb && porta(u) == porta(b)
    }

    fun ePaginaAccesso(url: String?, base: String): Boolean {
        if (!eHostBase(url, base)) return false
        val p = uri(url)?.rawPath ?: return false
        return p == PERCORSO_ACCESSO || p == "$PERCORSO_ACCESSO/"
    }

    /** Fuori dall'host di base si apre nel browser esterno solo questo; il resto si blocca. */
    fun daAprireFuori(url: String?): Boolean {
        val s = uri(url)?.scheme?.lowercase(Locale.ROOT) ?: return false
        return s == "https" || s == "http" || s == "mailto" || s == "tel"
    }

    /** L'indirizzo da aprire: la base, il filo delle notifiche (`?filo=postino#chat`) o una pagina (`#patrimonio`). */
    fun indirizzo(base: String, filo: String? = null, pagina: String? = null): String {
        val b = if (base.endsWith("/")) base else "$base/"
        val f = filo?.filter { it.isLetterOrDigit() || it == '-' }?.takeIf { it.isNotBlank() }
        val p = pagina?.filter { it.isLetterOrDigit() || it == '-' || it == '/' }?.trim('/')?.takeIf { it.isNotBlank() }
        return when {
            f != null -> "${b}?filo=$f#chat"
            p != null -> "$b#$p"
            else -> b
        }
    }

    /** La base del sito dal ponte (`wss://jarvis-agent.<dominio>/phone` → `https://jarvis.<dominio>/`), se non è configurata. */
    fun baseDalPonte(urlVps: String): String? {
        val host = uri(urlVps.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://"))?.host ?: return null
        if (!host.startsWith("jarvis-agent.")) return null
        return "https://jarvis." + host.removePrefix("jarvis-agent.") + "/"
    }

    // ─── integra-jarvis (0.6.x, 2026-10-08): il resto della webapp dell'app 1.2.4, senza perdere niente ──────────

    /**
     * L'user agent della webapp. Il sito riconosce l'app da `JarvisApp/x.y` (index.html: classe `in-app-android` che
     * lascia la fascia al tasto ⋮; app.js: il campo prende il fuoco solo al tocco): resta, con accanto `JBoss/x.y`.
     */
    fun userAgent(diSerie: String, versione: String): String {
        val v = versione.filter { it.isLetterOrDigit() || it == '.' || it == '-' }.ifBlank { "0" }
        return diSerie.trim() + " JarvisApp/" + v + " JBoss/" + v
    }

    /** Un errore HTTP della pagina principale che porta alla pagina locale offline. */
    fun eRipiego(codiceHttp: Int): Boolean = codiceHttp in 500..599

    /**
     * Una stringa letterale JavaScript/JSON con le virgolette, sicura dentro qualunque script: oltre a `"` `\` e ai
     * caratteri di controllo si scrivono in `\uXXXX` anche `'`, `<`, `>`, `&` e U+2028/U+2029.
     */
    fun js(s: String): String {
        val sb = StringBuilder(s.length + 8).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' || c == '\'' || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ' ->
                    sb.append("\\u").append(String.format(Locale.ROOT, "%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Compila `input#utente` e `input#password` e invia il modulo. I valori entrano solo come letterali [js]. */
    fun scriptCompila(utente: String, password: String): String =
        "(function(u,p){try{" +
            "var a=document.getElementById(\"utente\"),b=document.getElementById(\"password\");" +
            "var f=a&&a.form;if(!a||!b||!f)return \"manca\";" +
            "a.value=u;b.value=p;" +
            "if(typeof f.requestSubmit===\"function\")f.requestSubmit();else f.submit();" +
            "return \"inviato\";}catch(e){return \"manca\";}})(" + js(utente) + "," + js(password) + ")"

    /** Ascolta l'invio scritto a mano e passa utente e password a `JarvisApp.salvaAccesso`. Una volta per pagina. */
    fun scriptAscoltaInvio(): String =
        "(function(){try{" +
            "var a=document.getElementById(\"utente\"),b=document.getElementById(\"password\");" +
            "var f=a&&a.form;if(!a||!b||!f||f.__jarvisApp)return \"manca\";f.__jarvisApp=true;" +
            "f.addEventListener(\"submit\",function(){try{if(window.JarvisApp)" +
            "window.JarvisApp.salvaAccesso(String(a.value),String(b.value));}catch(e){}},true);" +
            "return \"ascolto\";}catch(e){return \"manca\";}})()"

    /** Il testo finale del dettato alla pagina. */
    fun scriptDettato(testo: String): String = "window.CCDettatoNativo && window.CCDettatoNativo(" + js(testo) + ")"

    /** Il testo provvisorio del dettato (Google), più volte prima del finale. */
    fun scriptParziale(testo: String): String = "window.CCDettatoParziale && window.CCDettatoParziale(" + js(testo) + ")"

    /**
     * Il riconoscitore della pagina con i risultati parziali (identico all'app 1.2.4): prende il posto di quello di
     * ponte.js senza toccare il sito; dettato.js e chiamata.js leggono `window.CCRiconoscimentoNativo` a ogni avvio.
     */
    const val SCRIPT_RICONOSCIMENTO = """(function(){
if(!window.JarvisApp||(window.CCRiconoscimentoNativo&&window.CCRiconoscimentoNativo.parziali))return;
function R(){var r=this,vivo=false,ultimo="";
r.lang="it-IT";r.continuous=false;r.interimResults=false;r.maxAlternatives=1;r.onresult=null;r.onerror=null;r.onend=null;
function ev(t,fin){return{resultIndex:0,results:[Object.assign([{transcript:t,confidence:fin?1:0}],{isFinal:fin})]};}
function parziale(t){if(!vivo)return;t=String(t||"").trim();if(!t||t===ultimo)return;ultimo=t;if(r.interimResults&&r.onresult)r.onresult(ev(t,false));}
function ricevi(t){if(!vivo)return;vivo=false;
if(window.CCDettatoNativo===ricevi)window.CCDettatoNativo=null;
if(window.CCDettatoParziale===parziale)window.CCDettatoParziale=null;
t=String(t||"").trim();
if(t){if(r.onresult)r.onresult(ev(t,true));}
else if(r.onerror){var m="";try{m=JSON.parse(window.JarvisApp.stato()||"{}").motivo||"";}catch(e){}
var e={microfono_negato:"not-allowed",microfono_guasto:"audio-capture",whisper_assente:"service-not-allowed",annullato:"aborted"}[m]||"no-speech";
r.onerror({error:e,motivo:m});}
if(r.onend)r.onend();}
r.start=function(){if(vivo)throw new Error("dettato già avviato");vivo=true;ultimo="";
window.CCDettatoNativo=ricevi;window.CCDettatoParziale=parziale;var ok=false;
try{ok=window.JarvisApp.avviaDettato();}catch(e){ok=false;}
if(!ok)setTimeout(function(){ricevi("");},0);};
r.stop=function(){if(vivo){try{window.JarvisApp.fermaDettato();}catch(e){}}};
r.abort=r.stop;}
R.parziali=true;window.CCRiconoscimentoNativo=R;})();"""

    /**
     * Porta la webapp sul messaggio di una notifica (Boss 08/10: «quando arriva la notifica mi deve riportare a quella
     * notifica»). [fid] = id del Command Center (fili.js lo tiene in `m.fid`). Cerca il messaggio in tutti i fili, apre la
     * chat dove il sito lo mostra (classe «report» → Postino, «avviso» → Jarvis, la stessa regola di app.js), lo porta al
     * centro e lo evidenzia per 4 s. I fili arrivano dal server dopo il caricamento: riprova per 15 s, poi ripiega sul filo.
     */
    fun scriptVaiANotifica(fid: String, filo: String?): String {
        val f = fid.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' }.take(80)
        val k = filo?.filter { it.isLetterOrDigit() || it == '-' }?.take(40).orEmpty()
        return """(function(fid,k){var n=0;
function norm(s){return String(s||"").replace(/[\s*_#>`\[\]()]+/g," ").trim().toLowerCase().slice(0,60);}
function trova(){try{var T=(typeof THREADS==="object"&&THREADS)||{};for(var x in T){var ms=(T[x]&&T[x].messaggi)||[];
for(var i=ms.length-1;i>=0;i--){if(ms[i]&&String(ms[i].fid)===fid)return{m:ms[i],k:x};}}}catch(e){}return null;}
function evidenzia(m){var cerca=norm(m.testo),tit=norm(m.titolo),bolle=document.querySelectorAll(".msg.notifica"),b=null;
for(var i=bolle.length-1;i>=0;i--){var t=norm(bolle[i].innerText);if((tit&&t.indexOf(tit)>=0)||(cerca&&t.indexOf(cerca.slice(0,30))>=0)){b=bolle[i];break;}}
if(!b)return false;b.scrollIntoView({block:"center"});var o=b.style.outline;b.style.outline="2px solid #d97757";b.style.borderRadius="12px";
setTimeout(function(){b.style.outline=o;},4000);return true;}
function giro(){n++;var r=trova();
if(r){var cl=(typeof window.classeNotifica==="function")?window.classeNotifica(r.m,r.k):(r.k==="postino"?"report":"avviso");
try{if(!/^#chat\b/.test(location.hash||""))location.hash="#chat";if(typeof apriChat==="function")apriChat(cl==="report"?"postino":"jarvis");}catch(e){}
var t=0;(function cerca(){if(evidenzia(r.m)||++t>10)return;setTimeout(cerca,300);})();return;}
if(n<30){setTimeout(giro,500);return;}
try{if(k&&window.CCNotifiche&&window.CCNotifiche.speciali.indexOf(k)>=0)window.CCNotifiche.apri(k);else location.hash="#chat";}catch(e){}}
giro();})(""" + js(f) + "," + js(k) + ")"
    }

    enum class Azione { COMPILA_E_INVIA, ASCOLTA_INVIO, NIENTE }

    /**
     * Cosa fare a pagina caricata: fuori dall'accesso niente; credenziali salvate e tentativo libero → compila e invia
     * (UNA volta per processo); tentativo già usato → si ascolta l'invio a mano; senza credenziali si ascolta, salvo «No».
     */
    fun decidi(paginaAccesso: Boolean, credenzialiSalvate: Boolean, tentativoLibero: Boolean, rifiutato: Boolean): Azione =
        when {
            !paginaAccesso -> Azione.NIENTE
            credenzialiSalvate && tentativoLibero -> Azione.COMPILA_E_INVIA
            credenzialiSalvate -> Azione.ASCOLTA_INVIO
            !rifiutato -> Azione.ASCOLTA_INVIO
            else -> Azione.NIENTE
        }

    enum class Candidato { PROPONI, SCARTA, ASPETTA }

    /** Utente e password scritti a mano si propongono da salvare solo se l'accesso è riuscito. */
    fun dopoInvio(url: String?, base: String): Candidato = when {
        ePaginaAccesso(url, base) -> Candidato.SCARTA
        eHostBase(url, base) -> Candidato.PROPONI
        else -> Candidato.ASPETTA
    }

    /** Il JSON di `JarvisApp.stato()` (lo legge ponte.js per il motivo di un dettato vuoto). */
    fun statoJson(
        versione: String, fase: String, motivo: String, motore: String, google: Boolean, whisper: Boolean,
        microfono: Boolean, servizio: Boolean, accessoSalvato: Boolean,
    ): String = "{\"app\":" + js(versione) + ",\"dettato\":" + js(fase) + ",\"motivo\":" + js(motivo) +
        ",\"motore\":" + js(motore) + ",\"google\":" + google + ",\"whisper\":" + whisper + ",\"microfono\":" + microfono +
        ",\"servizio\":" + servizio + ",\"accessoSalvato\":" + accessoSalvato + "}"
}

/** Al massimo UN tentativo automatico di accesso per sessione dell'app (processo). */
class TentativoAutomatico {
    private var usato = false

    val libero: Boolean
        @Synchronized get() = !usato

    /** Vero la prima volta (e da lì il tentativo è consumato), falso tutte le altre. */
    @Synchronized
    fun prendi(): Boolean {
        if (usato) return false
        usato = true
        return true
    }
}
