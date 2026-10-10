# Postino a numeri: come è fatto e come si innesta

Branch `postino-numeri` (2026-10-07, agente telefono-postino). Idea dell'utente: le mail non si leggono dal telefono.
Le legge la VPS con un solo giro, il resoconto numerato arriva nella chat Postino, l'utente decide per numeri, la VPS
esegue con `posta.py` e rilegge la casella: «eseguito» lo dice la casella, non il modello.

## I pezzi

| Dove | File | Cosa |
|---|---|---|
| repo Jarvis | `strumenti/postino_numeri.py` | il motore: giro (posta.digest in sola lettura), numeri, proposte, parser, esecutore, conferme, verifica |
| repo Jarvis | `strumenti/postino_finto.py` | IMAP/SMTP finto con 200 mail di esempio (example.com), per prove e demo |
| repo Jarvis | `strumenti/prova_postino_numeri.py` | 19 prove Python + tempi |
| repo Jarvis | `jarvis-agent/server/postinoNumeri.js` (+ `.test.js`) | il pezzo del ponte: opzioni, comando, righe |
| repo Jarvis | `vps/patch-postino-numeri/lavori.js.patch` | l'innesto in `lavori.js` (4 punti, nessuno cambia i lavori claude) |
| questo repo | `app/.../postino/` | chat Postino: `PostinoActivity`, `ComandiPostino`, `StatoPostino`, `SessionePostino`, `CanalePostino`, `CanaleDemo` |
| questo repo | `docs/PROTOCOLLO-POSTINO.md` | i dati degli eventi |

La numerazione del telefono sta in `~/.cache/postino-numeri/` (sulla VPS `/root/.cache/postino-numeri/`): non tocca
`posta-ultimo-report.json` né `posta-digest-stato.json`, quindi la scheda Postino del Command Center e i giri automatici
restano identici. Le azioni finiscono nello stesso `posta-azioni.log`.

## Innesto 1: lavori.js (VPS)

```bash
cd <repo Jarvis>/jarvis-agent && patch -p1 < ../vps/patch-postino-numeri/lavori.js.patch
node --test server/postinoNumeri.test.js server/lavori.test.js
```

`postinoNumeri.test.js` applica la patch a una copia se `lavori.js` non è ancora innestato: se la patch non si applica
più (telefono-vps ha cambiato quelle righe) la prova lo dice. Poi copia in `/opt/jarvis-agent/server/` di `lavori.js` e
`postinoNumeri.js`, `docker compose build && docker compose up -d` (solo jarvis-agent).

## Innesto 2: il canale nell'app (FATTO nella 0.3.0: `vps/CanalePostinoVps.kt`, registrato all'apertura della chat)

La chat non apre socket sue. Un adattatore su `vps/ModuloVps.kt` implementa `CanalePostino` e si registra all'avvio:

```kotlin
FornitoreCanale.fabbrica = { ctx -> CanaleModuloVps(ModuloVps.di(ctx)) }

class CanaleModuloVps(private val m: ModuloVps) : CanalePostino {
    override val collegato get() = m.collegato
    override var ascoltatore: ((JSONObject) -> Unit)? = null
    override var suCollegamento: ((Boolean) -> Unit)? = null
    init { m.ascolta { msg -> ascoltatore?.invoke(msg) }; m.suStato { su -> suCollegamento?.invoke(su) } }
    override fun avvia(idLavoro: String, testo: String, opzioni: JSONObject) =
        m.manda(JSONObject().put("type", "job_start").put("id", idLavoro).put("agente", "postino").put("testo", testo).put("opzioni", opzioni))
    override fun conferma(idLavoro: String, azioneId: String, scelta: String) =
        m.manda(JSONObject().put("type", "conferma").put("id", idLavoro).put("azione_id", azioneId).put("scelta", scelta))
    override fun segui(idLavoro: String, ultimoEvento: Int) =
        m.manda(JSONObject().put("type", "job_segui").put("id", idLavoro).put("ultimo_evento", ultimoEvento))
}
```

(i nomi `di`, `ascolta`, `suStato`, `manda` sono quelli che ModuloVps avrà: si adattano lì). L'ascoltatore deve
arrivare sul thread principale.

## Innesto 3: la schermata Agenti (FATTO nella 0.3.0: Home e Agenti aprono la chat a numeri, nessuna icona sua)

Il riquadro del Postino apre `startActivity(PostinoActivity.intento(this))`. Poi, se si vuole una sola icona nel
launcher, si toglie l'`intent-filter` LAUNCHER di `.postino.PostinoActivity` nel manifest. L'avatar: la chat usa da sola
`avatar_postino_128` di telefono-ui se c'è (`getIdentifier`), altrimenti la busta `postino_avatar.xml`. Le misure stanno
in `values/postino.xml` con gli stessi valori dei token di telefono-ui (prefisso `postino_`, niente conflitti).

## Merge

```bash
git -C <cartella del repo> merge postino-numeri
```

Righe toccate fuori dalla cartella `postino/`: il blocco nuovo in fondo al manifest, il blocco `androidComponents` in fondo
a `app/build.gradle.kts` (versione dell'APK di prova da riga di comando, neutro senza `-PversioneProva`). Il resto sono
file nuovi.

## Provare

```bash
python3 <repo Jarvis>/strumenti/prova_postino_numeri.py                      # 19 prove + tempi, caselle finte
cd <repo Jarvis>/jarvis-agent && node --test server/postinoNumeri.test.js    # giro completo dentro lavori.js
bash scripts/prove-jvm.sh --tests 'com.jarvis.telefono.postino.*'       # parser, stato dai messaggi veri del ponte
adb shell am start -n com.jarvis.telefono/.postino.PostinoActivity --ez demo true   # chat con dati finti (DUMP: solo ADB)
python3 <repo Jarvis>/strumenti/postino_numeri.py comando "controlla la posta" --finto /tmp/p/server.json
```

`app/src/test/resources/postino/giro-vps.jsonl` è registrato dal test del ponte (`POSTINO_REGISTRA=<file>`): se cambia
il formato della VPS, si rigenera e `StatoPostinoTest` dice cosa si è rotto nell'app.

## Rischi noti

- `HEADER Message-ID` in SEARCH: provato su IMAP finto; Gmail e Dovecot lo supportano, la PEC (sicurezzapostale) va
  provata col primo giro vero. Se una casella non lo supporta lo stato esce `errore` (mai un falso «fatto»).
- Gmail scrive Inviata da solo: la verifica riprova per 10 s; oltre dice errore «controlla prima di rimandarla».
- La bozza automatica (senza testo) usa `claude -p --model sonnet` con il tono di `posta.py tono`; solo per chi l'utente ha
  già scritto, come la regola del 27/09.
