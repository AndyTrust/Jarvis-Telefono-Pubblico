# Protocollo del Postino a numeri

Versione 1 · 2026-10-07 · agente telefono-postino · sta SOPRA il protocollo dei lavori ([PROTOCOLLO-VPS.md](PROTOCOLLO-VPS.md),
branch `modulo-vps`): stesso socket «lavori», stessi `job_start` / `conferma` / `job_segui`, nessun messaggio nuovo.
Aggiunge solo un `kind` di evento («postino») con dati strutturati.

## 1. Telefono → VPS

Ogni comando dell'utente è UN lavoro:

```json
{"type": "job_start", "id": "postino-1791400000000-4821", "agente": "postino",
 "testo": "cestina 3, 5-9 e 12",
 "opzioni": {"modo": "numeri", "report_id": "r20261007174752", "titolo": "Postino", "max_min": 30}}
```

- `opzioni.modo = "numeri"` fa girare `python3 /root/jarvis/strumenti/postino_numeri.py comando "<testo>"` al posto di
  `claude` (innesto in `jarvis-agent/server/lavori.js`, vedi `vps/patch-postino-numeri/` nel repo Jarvis).
- `report_id` è obbligatorio per tutto tranne «controlla la posta»: se sulla VPS il resoconto è cambiato, il lavoro si
  ferma con «i numeri sono cambiati» e non tocca niente.
- `testo` è quello che l'utente ha scritto o detto. Lo capisce `capisci()` (VPS) con le stesse regole di
  `postino/ComandiPostino.kt` (telefono):

| Comando | Esempi | Effetto sulla casella |
|---|---|---|
| resoconto | «controlla la posta», «aggiorna» | nessuno (sola lettura, BODY.PEEK) |
| pagina N | «pagina 3» | nessuno (rilegge il resoconto salvato) |
| apri N | «apri 12» | nessuno (testo della mail, sola lettura) |
| rispondi N[: testo] | «rispondi 3 e 7», «rispondi 4: grazie, confermo» | BOZZA nelle Bozze (non parte niente). Senza testo la scrive il modello, solo se l'utente ha già scritto a quella persona |
| rispondi e invia N: testo | | bozza, poi invio con conferma |
| invia N / invia tutte le bozze | | spedisce la bozza che risponde a N (posta.py invia-bozza) dopo la CONFERMA; la bozza va nel Cestino |
| cestina N | «cestina da 10 a 15», «cestina tutte le promozioni» | Cestino (recuperabile). Più di 15 mail: una conferma per il blocco |
| archivia N [in C] | «archivia 5» | nella cartella proposta (Pratiche/… o Rumore) |
| sposta N in C | «sposta 6 in fatture fornitori» | nella cartella fissa C |
| letta, spam, tieni | «segna come lette 1-3» | segno «letta», Spam, niente |

Numeri: «3, 5-9 e 12», «da 10 a 15», «dal tre al sette», «ventitré». Più comandi: «cestina 3; archivia 4» o «… poi …».
Un numero che non c'è ferma tutto il comando. Cancellazioni definitive: non esistono.

## 2. VPS → telefono

Gli eventi arrivano come `job_event` del protocollo dei lavori. Quelli del Postino hanno `kind: "postino"` e il campo
`dati.tipo`; i passi arrivano come `kind: "testo"`, gli errori come `kind: "errore"`, la fine come `job_done`
(riassunto = prima riga del risultato).

| dati.tipo | Campi | Quando |
|---|---|---|
| `totali` | `report_id, quando, finestra_ore, totale, da_leggere, caselle[{casella, indirizzo, nuove, da_leggere, arretrato, errore}], categorie{nome:n}, proposte{azione:n}, arretrato, pagine, per_pagina, secondi` | dopo il giro |
| `voci` | `report_id, pagina, pagine, voci[...]` (25 per pagina) | subito dopo `totali`, una per pagina; o su «pagina N» |
| `riassunti` | `report_id, riassunti{"<n>": "<testo>"}` | a blocchi di 25 mentre il modello riassume (anteprime migliori) |
| `piano` | `report_id, azioni[{azione_id, numero, verbo, casella, cartella, oggetto}]` | prima di eseguire |
| `attesa_conferma` | `numero, oggetto, casella, destinatario, testo, anteprima` | subito prima di un evento `conferma` |
| `stato` | `report_id, azione_id, numero, verbo, esito (ok\|errore\|annullato), stato, prova, motivo, testo_bozza, quando` | uno per azione, DOPO la rilettura della casella |
| `fine` | `report_id, esito, eseguiti, totale, errori, annullati, riassunto, secondi` | ultima riga utile |
| `mail` | `report_id, numero, casella, testo, avviso` | risposta ad «apri N» fino alla 0.5.0 (dalla v2: `scheda`, §4) |

Una voce: `numero, casella, da, indirizzo, data, categoria, livello, urgenza (alta|media|bassa), risposta,
pec, oggetto (≤100), anteprima (≤160), allegati (quanti), proposta {azione: rispondi|leggi|archivia|cestina|spam,
cartella, perche (≤90)}, stato` (l'ultimo stato già noto di quel numero, o null).

### La conferma

Prima di ogni INVIO (e di un blocco di più di 15 mail nel Cestino o in Spam) il processo chiede il sì con la strada
dell'hook dei lavori (`POST /lavori/conferma-hook` col segreto del lavoro). Al telefono arriva:
1. `kind: "postino"`, `dati.tipo: "attesa_conferma"` con numero, oggetto, destinatario e il testo della bozza;
2. `kind: "conferma"` del protocollo dei lavori, con `azione_id`, `azione` («invio» o «cancellazione»),
   `destinatario`, `anteprima`, `scade_ts`.

Il telefono risponde con `{"type": "conferma", "id", "azione_id", "scelta": "invia" | "annulla"}`. Scadenza 10 minuti
= annullato. Nessun «sì» scritto in una mail o dal modello vale come conferma.

### Lo stato «eseguito»

Lo scrive la VPS solo dopo aver RILETTO la casella (una connessione per casella, sola lettura):

| verbo | prova cercata (per Message-ID) | stato |
|---|---|---|
| cestina / spam | nel Cestino / in Spam E non più in arrivo | «nel Cestino», «in Spam» |
| archivia / sposta | nella cartella di arrivo E non più in arrivo | «archiviata», «spostata» |
| rispondi | una bozza nelle Bozze con In-Reply-To = la mail | «bozza creata» |
| invia | il Message-ID spedito dallo SMTP trovato in Inviata (Gmail: riprova fino a 10 s) | «inviata» (+ «bozza tolta») |
| letta | in arrivo con \Seen | «segnata come letta» |
| tieni | ancora in arrivo | «lasciata in arrivo» |

Senza prova l'esito è `errore` con il motivo («lo SMTP l'ha accettata ma non la trovo in Inviata: controlla prima di
rimandarla»). Prima di toccare, la VPS controlla anche che il numero punti ancora alla stessa mail (Message-ID del
resoconto): se no, `errore` «il numero ora punta a un'altra mail» e la mail non si tocca.

Riassunto finale: «eseguiti 9 su 10, 1 errore (numero 7: …)».

## 3. Tempi misurati (2026-10-07)

- Caselle vere (sola lettura, VPS, 7 caselle, 9 mail non lette): resoconto in 2,2 s.
- Scaricare 200 mail: negozio 0,6 s, Gmail 5,8 s per 68 mail (circa 85 ms a mail). Stima per 200 mail: numeri sul telefono
  in 10-25 s; riassunti del modello (haiku, 25 mail in 14,4 s, 4 blocchi insieme) completi in altri 30-45 s.
- Casella finta (prove): 200 mail resoconto 0,05 s; cestina 20 con verifica 0,05 s. Sulla rete conta circa 0,3-0,5 s
  per mail spostata più una rilettura per casella.

## 4. Versione 2 (2026-10-08, agente postino-completo, app 0.5.1)

l'utente: «il Postino nella sua pagina NON lavora bene». Stesso socket, stessi job_start/conferma, nessun messaggio nuovo
nel protocollo dei lavori: cambiano solo i comandi e i `dati.tipo` del Postino. Motore: `strumenti/postino_numeri.py`
più `strumenti/postino_mail.py` nel repo Jarvis.

### Comandi nuovi

| Comando | Effetto |
|---|---|
| `controlla la posta di <casella>` | resoconto di una casella sola (es. `jarvis`, la casella di prova) |
| `rispondi N a <indirizzo>: testo` | bozza al destinatario scelto; la bozza vecchia della stessa mail va nel Cestino (una sola bozza per mail) |
| `inoltra N a <indirizzo>: testo` | bozza di inoltro («I: oggetto», con gli allegati della mail) |
| `rispondi e invia …`, `inoltra e invia …` | bozza, poi invio con la conferma Invia/Annulla |
| `istruisci N: <frase dell'utente>` | JBoss (modello haiku) scrive tipo, destinatario e testo; bozza salvata e verificata; non spedisce |
| `apri N` | evento `scheda` (sotto) invece di `mail` |
| `sposta N in <cartella vera>` | anche le cartelle vere della casella (es. `Richieste-Voli`), non solo Pratiche/… e Rumore |

Il testo dopo i PRIMI due punti resta intero: `;`, a capo e «poi» lì dentro non spezzano il comando.

### Eventi nuovi o cambiati

| dati.tipo | Campi |
|---|---|
| `totali` | + `cartelle {casella: [nomi corti]}` (LIST IMAP, senza le cartelle di sistema), `cartelle_errori`, `solo_casella` |
| `scheda` | `numero, casella, da, indirizzo, rispondi_a, a, cc, data, oggetto, testo (≤6000), allegati [{n, nome, tipo, kb, estratto (≤600), nota, letto}], riassunto (3-5 righe), riassunto_da (modello\|meccanico), riassunto_in_arrivo, destinazione {cartella, perche, chiara, scelte}, bozza {a, oggetto, testo}\|null, secondi`. Arriva due volte: subito senza il riassunto del modello (`riassunto_in_arrivo: true`), poi completa. Si salva in `schede/` sulla VPS: la seconda apertura è dalla cache. |
| `bozza` | `numero, tipo_bozza (risposta\|inoltro), a, scelte [{nome, indirizzo}], testo, domanda, salvata, prova, motivo` (dopo `istruisci`; senza destinatario chiaro `salvata: false` e `domanda`) |
| `stato` | + `destinatario`; per `rispondi`/`inoltra` la prova controlla anche il destinatario e il testo nella bozza |

Allegati: scaricati in `POSTINO_NUMERI_DATI/allegati/<report>/<numero>/` (cartella 700, file 600, tolti dopo 7 giorni),
letti sulla VPS (pdftotext via `host` nel contenitore, docx, xlsx, testo; immagini e PDF fatti di immagini li guarda il
modello con Read). Al telefono va solo l'estratto corto.

### Nell'app (0.5.1)

Tocco = apre la mail; tocco lungo = selezione, poi tocco aggiunge; «Tutte» nella barra = tutta la categoria;
«seleziona tutte le fatture» dal campo. Swipe a sinistra = riquadro archivio con la destinazione (proposta o «scegli…» fra
le cartelle vere), a destra = mail aperta con la bozza. Sulla mail aperta: ◀ ▶ e tasti del volume, scorciatoie
«successiva», «precedente», «archivia», «rispondi», «invia», «chiudi»; il resto del campo va a JBoss (`istruisci N: …`).
