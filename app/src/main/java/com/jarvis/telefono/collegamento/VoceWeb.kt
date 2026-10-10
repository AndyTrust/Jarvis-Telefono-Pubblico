package com.jarvis.telefono.collegamento

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * La voce del sito dentro l'app (1.1.2, l'utente 2026-10-04: «nella chiamata Jarvis non parla»).
 * La WebView di Android non ha `speechSynthesis`: chiamata.js, senza, salta la lettura in silenzio.
 * Qui c'è il sintetizzatore vero del telefono (TextToSpeech) e, in [POLYFILL], un `speechSynthesis`
 * che gli passa le frasi. La pagina non cambia: stessi eventi (start, boundary, end, error).
 *
 * [emetti] riceve il codice JavaScript da eseguire nella pagina (thread principale a cura del chiamante).
 */
class VoceWeb(private val context: Context, private val emetti: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var pronto = false
    private val inAttesa = ArrayDeque<() -> Unit>()

    private fun motore(): TextToSpeech {
        tts?.let { return it }
        val nuovo = TextToSpeech(context.applicationContext) { esito ->
            val t = tts
            if (esito == TextToSpeech.SUCCESS && t != null) {
                t.language = Locale.ITALIAN
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                pronto = true
                while (inAttesa.isNotEmpty()) inAttesa.removeFirst().invoke()
            } else {
                // Niente sintetizzatore: chi aspettava riceve un errore, la pagina non resta appesa.
                while (inAttesa.isNotEmpty()) inAttesa.removeFirst().invoke()
            }
        }
        nuovo.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = evento(utteranceId, "start", false)
            override fun onDone(utteranceId: String?) = evento(utteranceId, "end", true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = evento(utteranceId, "error", true)
            override fun onError(utteranceId: String?, errorCode: Int) = evento(utteranceId, "error", true)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = evento(utteranceId, "error", true)
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val p = Id.leggi(utteranceId) ?: return
                emetti("window.__jarvisTts&&window.__jarvisTts(${Id.js(p.id)},'boundary',${p.offset + start},${end - start})")
            }
        })
        tts = nuovo
        return nuovo
    }

    /** Un id di frase lunga si divide in pezzi: «id|k|n|offset». `fine` vale solo sull'ultimo pezzo. */
    private fun evento(utteranceId: String?, tipo: String, fine: Boolean) {
        val p = Id.leggi(utteranceId) ?: return
        if (tipo == "start" && p.k != 0) return
        if (fine && tipo == "end" && p.k != p.n - 1) return
        // un errore su un pezzo chiude tutta la frase, una sola volta
        emetti("window.__jarvisTts&&window.__jarvisTts(${Id.js(p.id)},'$tipo',0,0)")
    }

    fun parla(id: String, testo: String, rate: Float) {
        val lavoro = {
            val t = motore()
            if (!pronto) {
                emetti("window.__jarvisTts&&window.__jarvisTts(${Id.js(id)},'error',0,0)")
            } else {
                t.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
                val pezzi = pezzi(testo)
                var offset = 0
                pezzi.forEachIndexed { k, p ->
                    val codice = Id.scrivi(id, k, pezzi.size, offset)
                    t.speak(p, TextToSpeech.QUEUE_ADD, null, codice)
                    offset += p.length
                }
                if (pezzi.isEmpty()) emetti("window.__jarvisTts&&window.__jarvisTts(${Id.js(id)},'end',0,0)")
            }
        }
        if (pronto) lavoro() else {
            inAttesa.addLast(lavoro)
            motore() // se già in avvio torna quello che c'è; il lavoro parte a motore pronto
        }
    }

    fun taci() {
        tts?.stop()
    }

    fun staParlando(): Boolean = tts?.isSpeaking == true

    fun rilascia() {
        inAttesa.clear()
        tts?.stop()
        tts?.shutdown()
        tts = null
        pronto = false
    }

    class Id(val id: String, val k: Int, val n: Int, val offset: Int) {
        companion object {
            fun scrivi(id: String, k: Int, n: Int, offset: Int) = "$id|$k|$n|$offset"
            fun leggi(s: String?): Id? {
                val v = s?.split("|") ?: return null
                if (v.size != 4) return null
                return Id(v[0], v[1].toIntOrNull() ?: return null, v[2].toIntOrNull() ?: return null, v[3].toIntOrNull() ?: return null)
            }
            fun js(s: String) = "'" + s.filter { it.isLetterOrDigit() || it == '_' } + "'"
        }
    }

    companion object {
        private const val MAX = 3000

        /** Un testo diviso in pezzi sotto il limite del sintetizzatore, a fine frase quando si può. */
        fun pezzi(testo: String, max: Int = MAX): List<String> {
            val t = testo.trim()
            if (t.isEmpty()) return emptyList()
            val fuori = ArrayList<String>()
            var resto = t
            while (resto.length > max) {
                val finestra = resto.substring(0, max)
                var taglio = maxOf(finestra.lastIndexOf(". "), finestra.lastIndexOf("\n"), finestra.lastIndexOf("? "), finestra.lastIndexOf("! "))
                if (taglio < max / 2) taglio = finestra.lastIndexOf(' ')
                if (taglio <= 0) taglio = max - 1
                fuori.add(resto.substring(0, taglio + 1))
                resto = resto.substring(taglio + 1)
            }
            if (resto.isNotBlank()) fuori.add(resto)
            return fuori
        }

        /** Gira nella pagina: un speechSynthesis che passa le frasi all'app. Idempotente. */
        const val POLYFILL = """(function(){
if(!window.JarvisApp||window.__jarvisTtsOk)return;window.__jarvisTtsOk=1;
var seq=0,map={};
function U(t){this.text=String(t==null?'':t);this.lang='';this.rate=1;this.pitch=1;this.volume=1;this.voice=null;
this.onstart=null;this.onend=null;this.onerror=null;this.onboundary=null;}
var voce={name:'Italiano (telefono)',lang:'it-IT',default:true,localService:true,voiceURI:'android-it'};
var ss={speaking:false,pending:false,paused:false,onvoiceschanged:null,
getVoices:function(){return [voce];},
speak:function(u){var id='u'+(++seq);map[id]=u;ss.speaking=true;
try{window.JarvisApp.parla(id,u.text,Number(u.rate)||1);}catch(e){window.__jarvisTts(id,'error',0,0);}},
cancel:function(){try{window.JarvisApp.taci();}catch(e){}},
pause:function(){},resume:function(){},addEventListener:function(){},removeEventListener:function(){}};
window.__jarvisTts=function(id,tipo,n,l){var u=map[id];if(!u)return;
var ev={type:tipo,charIndex:n||0,charLength:l||0,utterance:u,name:'word'};
try{
if(tipo==='start'){if(u.onstart)u.onstart(ev);}
else if(tipo==='boundary'){if(u.onboundary)u.onboundary(ev);}
else{delete map[id];ss.speaking=Object.keys(map).length>0;var f=tipo==='end'?u.onend:u.onerror;if(f)f(ev);}
}catch(e){}};
try{Object.defineProperty(window,'speechSynthesis',{value:ss,configurable:true,writable:true});}catch(e){window.speechSynthesis=ss;}
window.SpeechSynthesisUtterance=U;
})();"""
    }
}
