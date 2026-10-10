package com.jarvis.telefono.collegamento

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** integra-jarvis (2026-10-08): le funzioni dell'app 1.2.4 portate dentro JBoss, parte pura. */
class IntegraJarvisTest {

    @Test fun `il sito riconosce l'app da JarvisApp e vede anche JBoss`() {
        val ua = AccessoWeb.userAgent("Mozilla/5.0 (Linux; Android 16) Chrome/140", "0.6.2")
        assertTrue(Regex("\\bJarvisApp/").containsMatchIn(ua)) // stessa regola di index.html e app.js del sito
        assertTrue(ua.endsWith(" JarvisApp/0.6.2 JBoss/0.6.2"))
        assertTrue(AccessoWeb.userAgent("x", "0.6.2\"<script>").endsWith("JarvisApp/0.6.2script JBoss/0.6.2script"))
    }

    @Test fun `lo stato del ponte JS e JSON valido e senza segreti`() {
        val s = AccessoWeb.statoJson("0.6.2", "pronto", "microfono_negato", "google", true, false, false, true, true)
        val o = JSONObject(s)
        assertEquals("microfono_negato", o.getString("motivo"))
        assertTrue(o.getBoolean("accessoSalvato"))
        assertFalse(s.contains("password"))
    }

    @Test fun `aggiornamenti solo verso l'alto e con un numero sensato`() {
        assertTrue(AggiornamentiJBoss.serveAggiornare(15, 14))
        assertFalse(AggiornamentiJBoss.serveAggiornare(14, 14))
        assertFalse(AggiornamentiJBoss.serveAggiornare(-1, 0))
        assertFalse(AggiornamentiJBoss.serveAggiornare(0, -5))
    }

    @Test fun `il json del ponte si legge e il nome dell'APK non porta percorsi`() {
        val r = AggiornamentiJBoss.leggi("""{"versionCode":15,"versionName":"0.6.3","apk":"jboss-0.6.3.apk","sha256":"ab"}""")!!
        assertEquals(15, r.codice); assertEquals("0.6.3", r.nome); assertEquals("jboss-0.6.3.apk", r.apk)
        assertEquals("jboss-latest.apk", AggiornamentiJBoss.leggi("""{"versionCode":2}""")!!.apk)
        assertNull(AggiornamentiJBoss.leggi("""{"versionCode":15,"apk":"../../etc/passwd.apk"}"""))
        assertNull(AggiornamentiJBoss.leggi("""{"versionCode":15,"apk":"https://altro.it/x.apk"}"""))
        assertNull(AggiornamentiJBoss.leggi("non json"))
        assertEquals(-1, AggiornamentiJBoss.leggi("{}")!!.codice)
    }

    @Test fun `la cartella degli aggiornamenti sta sul ponte`() {
        assertEquals("https://jarvis-agent.example.org/update/apk/", AggiornamentiJBoss.cartella("wss://jarvis-agent.example.org/phone"))
        assertNull(AggiornamentiJBoss.cartella(""))
        assertNull(AggiornamentiJBoss.cartella("ftp://x"))
    }

    @Test fun `cerca aggiornamenti a voce, senza rubare la memoria`() {
        val C = ComandiCollegamento
        assertEquals(ComandiCollegamento.Comando.CercaAggiornamenti, C.riconosci("cerca aggiornamenti"))
        assertEquals(ComandiCollegamento.Comando.CercaAggiornamenti, C.riconosci("Hey JBoss, ci sono aggiornamenti di JBoss?"))
        assertEquals(ComandiCollegamento.Comando.CercaAggiornamenti, C.riconosci("aggiorna JBoss"))
        assertEquals(ComandiCollegamento.Comando.AllineaMemoria, C.riconosci("aggiorna la memoria"))
        assertNull(C.riconosci("aggiorna il calendario"))
    }

    @Test fun `il salto alla notifica porta solo id puliti nella pagina`() {
        val s = AccessoWeb.scriptVaiANotifica("1728390000.12-ab\");alert(1);//", "postino")
        assertTrue(s.endsWith("(\"1728390000.12-abalert1\",\"postino\")"))
        assertFalse(s.contains("alert(1)"))
        assertTrue(AccessoWeb.scriptVaiANotifica("x", null).endsWith("(\"x\",\"\")"))
    }
}
