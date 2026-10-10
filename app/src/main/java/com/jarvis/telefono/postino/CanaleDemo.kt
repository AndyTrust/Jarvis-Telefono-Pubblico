package com.jarvis.telefono.postino

import org.json.JSONArray
import org.json.JSONObject

/**
 * La prova con DATI FINTI della chat Postino: risponde come il modulo VPS (stessi messaggi,
 * stessi campi), ma con 30 mail inventate su example.com e nessuna casella vera. Serve a provare
 * la schermata sul telefono quando la VPS non è collegata. Si apre solo con l'extra «demo» (ADB) e
 * la schermata lo dice in alto: «prova con dati finti».
 *
 * Ogni stato «fatto» qui dice nella prova che è finto: anche per gioco non si dichiara un
 * eseguito che nessuna casella ha confermato.
 */
class CanaleDemo(
    private val pianifica: (Long, () -> Unit) -> Unit,
) : CanalePostino {

    override val collegato = true
    override var ascoltatore: ((JSONObject) -> Unit)? = null
    override var suCollegamento: ((Boolean) -> Unit)? = null

    private val mail = MAIL_FINTE.mapIndexed { i, (cat, da, ogg) -> Triple(i + 1, cat, da to ogg) }
    private var reportId = "r20261007120000"
    private val bozze = mutableSetOf<Int>()
    private val fatte = mutableMapOf<Int, String>()
    private val inAttesa = mutableMapOf<String, () -> Unit>()
    private val n = mutableMapOf<String, Int>()

    private fun manda(m: JSONObject) { ascoltatore?.invoke(m) }

    private fun evento(id: String, kind: String, testo: String, dati: JSONObject = JSONObject()) {
        val k = (n[id] ?: 0) + 1
        n[id] = k
        manda(JSONObject().put("type", "job_event").put("id", id).put("n", k).put("kind", kind)
            .put("ts", System.currentTimeMillis()).put("testo", testo).put("dati", dati))
    }

    private fun fine(id: String, esito: String, riassunto: String) {
        val k = (n[id] ?: 0) + 1
        n[id] = k
        manda(JSONObject().put("type", "job_done").put("id", id).put("n", k).put("esito", esito).put("riassunto", riassunto))
    }

    override fun avvia(idLavoro: String, testo: String, opzioni: JSONObject) {
        val richieste = try {
            ComandiPostino.capisci(testo)
        } catch (e: ComandiPostino.Errore) {
            pianifica(200) { evento(idLavoro, "errore", e.message ?: "errore"); fine(idLavoro, "errore", e.message ?: "errore") }
            return
        }
        if (richieste.all { it.verbo == "resoconto" }) { resoconto(idLavoro); return }
        if (richieste.size == 1 && richieste[0].verbo == "apri") {
            val v = mail.firstOrNull { it.first == richieste[0].numeri[0] }
            pianifica(300) {
                evento(idLavoro, "postino", "mail", JSONObject().put("tipo", "mail").put("numero", richieste[0].numeri[0])
                    .put("testo", "Da: ${v?.third?.first}\nOggetto: ${v?.third?.second}\n\n(Testo finto della prova: nessuna casella vera.)"))
                fine(idLavoro, "ok", "testo della mail ${richieste[0].numeri[0]}")
            }
            return
        }
        esegui(idLavoro, richieste)
    }

    private fun resoconto(id: String) {
        reportId = "r2026100712" + (1000..5959).random().toString().padStart(4, '0')
        fatte.clear()
        bozze.clear()
        val passi = listOf("leggo prova-a", "leggo prova-b", "leggo prova-g")
        passi.forEachIndexed { i, p -> pianifica(250L * (i + 1)) { evento(id, "testo", p) } }
        pianifica(1000) {
            evento(id, "postino", "${mail.size} mail", JSONObject().put("tipo", "totali").put("report_id", reportId)
                .put("quando", "2026-10-07T12:00:00+02:00").put("totale", mail.size).put("pagine", 2).put("per_pagina", 25)
                .put("caselle", JSONArray().put(casella("prova-a", 12)).put(casella("prova-b", 10)).put(casella("prova-g", 8))))
            for (pagina in 1..2) {
                val arr = JSONArray()
                mail.filter { (it.first - 1) / 25 + 1 == pagina }.forEach { arr.put(voce(it)) }
                evento(id, "postino", "pagina $pagina di 2", JSONObject().put("tipo", "voci").put("report_id", reportId)
                    .put("pagina", pagina).put("pagine", 2).put("voci", arr))
            }
            fine(id, "ok", "${mail.size} mail numerate da 1 a ${mail.size} (2 pagine)")
        }
    }

    private fun casella(nome: String, nuove: Int) = JSONObject().put("casella", nome).put("nuove", nuove).put("errore", JSONObject.NULL)

    private fun voce(m: Triple<Int, String, Pair<String, String>>): JSONObject {
        val (num, cat, dv) = m
        val azione = when (cat) {
            "Promozioni" -> "cestina"
            "Fatture fornitori" -> "archivia"
            "Da leggere" -> if (num % 2 == 0) "rispondi" else "leggi"
            else -> "leggi"
        }
        return JSONObject().put("numero", num).put("casella", listOf("prova-a", "prova-b", "prova-g")[num % 3])
            .put("da", dv.first).put("indirizzo", "mittente$num@example.com").put("oggetto", dv.second)
            .put("data", "07/10 ${8 + num % 10}:${(num * 7 % 60).toString().padStart(2, '0')}")
            .put("categoria", cat).put("livello", 2).put("urgenza", if (cat == "Da agire") "alta" else "media")
            .put("proposta", JSONObject().put("azione", azione).put("cartella", if (azione == "archivia") "Fatture-Fornitori" else JSONObject.NULL)
                .put("perche", "regola di prova"))
            .put("anteprima", "Testo finto di prova numero $num: nessuna casella vera.").put("allegati", if (cat.startsWith("Fatture")) 1 else 0)
    }

    private fun esegui(id: String, richieste: List<ComandiPostino.Richiesta>) {
        val azioni = mutableListOf<Pair<Int, String>>()
        for (r in richieste) {
            val numeri = r.numeri.toMutableList()
            when (r.selettore) {
                "bozze" -> numeri.addAll(bozze)
                "cat:5,6" -> numeri.addAll(mail.filter { it.second == "Promozioni" }.map { it.first })
                "tutte" -> numeri.addAll(mail.map { it.first })
            }
            val fuori = numeri.filter { it < 1 || it > mail.size }
            if (fuori.isNotEmpty()) {
                pianifica(200) {
                    val t = "nel resoconto non c'è il numero ${fuori.joinToString()}: vanno da 1 a ${mail.size}. Non ho toccato niente."
                    evento(id, "errore", t); fine(id, "errore", t)
                }
                return
            }
            numeri.distinct().sorted().forEach { azioni.add(it to r.verbo); if (r.invia) azioni.add(it to "invia") }
        }
        pianifica(300) {
            val arr = JSONArray()
            azioni.forEach { (num, v) -> arr.put(JSONObject().put("numero", num).put("verbo", v)) }
            evento(id, "postino", "${azioni.size} azioni", JSONObject().put("tipo", "piano").put("report_id", reportId).put("azioni", arr))
            prossima(id, azioni, 0, 0, mutableListOf())
        }
    }

    private fun prossima(id: String, azioni: List<Pair<Int, String>>, i: Int, ok: Int, errori: MutableList<String>) {
        if (i >= azioni.size) {
            var frase = "eseguiti $ok su ${azioni.size}"
            if (errori.isNotEmpty()) frase += ", ${errori.size} ${if (errori.size == 1) "errore" else "errori"} (${errori.joinToString("; ")})"
            evento(id, "postino", frase, JSONObject().put("tipo", "fine").put("report_id", reportId).put("riassunto", frase)
                .put("eseguiti", ok).put("totale", azioni.size).put("errori", errori.size))
            fine(id, if (errori.isEmpty()) "ok" else "errore", frase)
            return
        }
        val (num, verbo) = azioni[i]
        if (verbo == "invia") {
            if (num !in bozze) {
                stato(id, num, verbo, "errore", null, "nessuna bozza risponde a questa mail: prima «rispondi $num: …»")
                pianifica(250) { prossima(id, azioni, i + 1, ok, errori.apply { add("numero $num: nessuna bozza") }) }
                return
            }
            val az = "demo${System.nanoTime() % 1_000_000}"
            val a = mail[num - 1].third
            evento(id, "postino", "conferma", JSONObject().put("tipo", "attesa_conferma").put("numero", num).put("oggetto", a.second)
                .put("testo", "Invio la risposta al numero $num a mittente$num@example.com?"))
            evento(id, "conferma", "Posso procedere con: invio?", JSONObject().put("azione_id", az).put("azione", "invio")
                .put("destinatario", "mittente$num@example.com").put("anteprima", "Grazie, ricevuto. Ci sentiamo lunedì.\n\n(bozza finta della prova)")
                .put("scade_ts", System.currentTimeMillis() + 600_000))
            inAttesa[az] = {
                pianifica(700) {
                    bozze.remove(num)
                    stato(id, num, verbo, "ok", "dati finti: trovata in Inviata della casella di prova", null)
                    prossima(id, azioni, i + 1, ok + 1, errori)
                }
            }
            inAttesa["$az-no"] = {
                stato(id, num, verbo, "annullato", null, "annullata da Boss")
                prossima(id, azioni, i + 1, ok, errori)
            }
            return
        }
        pianifica(450) {
            if (verbo == "rispondi") bozze.add(num)
            val prova = when (verbo) {
                "cestina" -> "dati finti: trovata nel Cestino della casella di prova"
                "rispondi" -> "dati finti: bozza trovata nelle Bozze della casella di prova"
                "archivia", "sposta" -> "dati finti: trovata nella cartella di arrivo"
                else -> "dati finti"
            }
            stato(id, num, verbo, "ok", prova, null, if (verbo == "rispondi") "Grazie, ricevuto. Ci sentiamo lunedì." else null)
            fatte[num] = verbo
            prossima(id, azioni, i + 1, ok + 1, errori)
        }
    }

    private fun stato(id: String, num: Int, verbo: String, esito: String, prova: String?, motivo: String?, bozza: String? = null) {
        val etichetta = mapOf("cestina" to "nel Cestino", "spam" to "in Spam", "archivia" to "archiviata", "sposta" to "spostata",
            "letta" to "segnata come letta", "rispondi" to "bozza creata", "invia" to "inviata", "tieni" to "lasciata in arrivo")
        evento(id, "postino", "$num", JSONObject().put("tipo", "stato").put("report_id", reportId).put("numero", num).put("verbo", verbo)
            .put("esito", esito).put("stato", if (esito == "ok") etichetta[verbo] ?: verbo else esito)
            .put("prova", prova ?: JSONObject.NULL).put("motivo", motivo ?: JSONObject.NULL).put("testo_bozza", bozza ?: JSONObject.NULL))
    }

    override fun conferma(idLavoro: String, azioneId: String, scelta: String) {
        val f = if (scelta == "invia") inAttesa.remove(azioneId) else inAttesa.remove("$azioneId-no")
        inAttesa.remove(azioneId)
        inAttesa.remove("$azioneId-no")
        evento(idLavoro, "stato", if (scelta == "invia") "Boss ha detto sì: procedo" else "Boss ha detto no",
            JSONObject().put("azione_id", azioneId).put("scelta", scelta))
        f?.invoke()
    }

    override fun segui(idLavoro: String, ultimoEvento: Int) {}

    companion object {
        val MAIL_FINTE = listOf(
            Triple("Da agire", "Studio Esempio", "Scadenza documenti entro venerdì"),
            Triple("Da leggere", "Marco Esempio", "Re: preventivo per il sito nuovo"),
            Triple("Da leggere", "Giulia Esempio", "Richiesta disponibilità 12 ottobre"),
            Triple("Fatture fornitori", "Fornitore Esempio", "Fattura n. 118 del 05/10"),
            Triple("Promozioni", "Negozio Esempio", "Sconto del 30% solo per oggi"),
            Triple("Promozioni", "Negozio Esempio", "La newsletter di ottobre"),
        ).let { base -> (0 until 30).map { base[it % base.size].let { (c, d, o) -> Triple(c, d, "$o #${it + 1}") } } }
    }
}
