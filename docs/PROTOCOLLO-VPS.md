# Protocollo del modulo VPS (lavori lunghi sulla VPS)

Versione 1 · 2026-10-07 · agente telefono-vps · stato: in uso (lato VPS `jarvis-agent/server/lavori.js`, lato app `vps/ModuloVps.kt`)

I compiti lunghi (ricerche, posta in massa, lavori di Claude Code) non girano sul telefono: il telefono li manda
alla VPS, la VPS li esegue con un processo `claude` per lavoro e rimanda in tempo reale i passi, i comandi
eseguiti, il testo e il risultato. Il canale è il WebSocket che esiste già.

## 1. Collegamento

- Indirizzo: `wss://<ponte>/phone`, lo stesso dell'app `com.jarvis.app` (sul telefono arriva dalla configurazione personale, chiave `vps_url`, mai dal repo).
- Primo messaggio entro 5 s, con lo stesso token del ponte e il ruolo:

```json
{"type": "auth", "token": "<token del ponte>", "ruolo": "lavori", "versione": 1}
```

- Risposta: `{"type": "connected", "ruolo": "lavori", "versione": 1}`.
- `ruolo: "lavori"` è obbligatorio per il modulo VPS. Un socket con questo ruolo **non** diventa il telefono
  delle mani (non riceve `tool_call`, non sostituisce né chiude il socket dell'app `com.jarvis.app`, non cambia
  `phoneConnected` in `/health`). Più socket «lavori» possono stare collegati insieme: ognuno riceve gli eventi
  di tutti i lavori.
- Senza `ruolo` (app attuale) tutto resta identico a prima: `user_message`, `tool_call`, `tool_result`,
  `assistant_message`, `risposta_conferma`. I messaggi `job_*` e `conferma` sono accettati anche lì, ma l'app
  attuale non li manda.
- Token sbagliato: chiusura con codice 4001. Il token non va mai nei log né nel repo (sul telefono sta nella
  configurazione del modulo VPS, fuori da git; sulla VPS in `/opt/jarvis-agent/.env`, fonte `.env.jarvis`).

## 2. Telefono → VPS

| Messaggio | Campi | Effetto |
|---|---|---|
| `job_start` | `id` (stringa scelta dal telefono, 8-64 caratteri `[A-Za-z0-9_-]`), `agente` (`ricercatore`, `postino`, `social`, `generico`), `testo`, `opzioni` (facoltativo) | crea il lavoro. Stesso `id` già noto = nessun doppione: la VPS rimanda `job_stato` del lavoro esistente (idempotente, sicuro da ripetere dopo una riconnessione) |
| `job_cancel` | `id` | ferma il lavoro (processo chiuso, conferme in attesa annullate) → `job_done` con `esito: "annullato"` |
| `conferma` | `id`, `azione_id`, `scelta`: `"invia"` o `"annulla"` | sblocca l'azione ferma. Unica strada: un «sì» scritto nel testo del modello non vale mai |
| `job_lista` | `limite` (facoltativo, predefinito 30, massimo 100) | → `job_lista` |
| `job_segui` | `id`, `ultimo_evento` (numero, 0 = tutto) | → gli eventi con `n > ultimo_evento`, poi `job_done` se il lavoro è finito |

`opzioni` (tutte facoltative):

```json
{"modello": "sonnet" | "opus", "max_min": 30, "titolo": "Bandi per la formazione"}
```

- `modello`: predefinito `sonnet`; `opus` solo se chiesto.
- `max_min`: tetto di durata, predefinito 30, massimo 60. Superato il tetto il lavoro si ferma con `esito: "errore"`.

## 3. VPS → telefono

### `job_event`

```json
{"type": "job_event", "id": "…", "n": 12, "kind": "comando", "ts": 1791400000000, "testo": "curl -s https://…", "dati": {}}
```

- `n`: numero progressivo dell'evento dentro il lavoro (1, 2, 3…). Serve alla ripresa.
- `ts`: millisecondi Unix (tempo assoluto: la VPS è su UTC, il telefono su CEST).
- `kind`:

| kind | testo | dati |
|---|---|---|
| `stato` | «in coda», «avviato», «al lavoro», «in attesa del tuo sì», «ripreso» | `{"stato": "coda" \| "avviato" \| "lavoro" \| "attesa" \| …}` |
| `comando` | il comando o lo strumento eseguito, così com'è (non detto dal modello) | `{"strumento": "Bash" \| "WebSearch" \| "Read" \| …, "uso_id": "…"}` |
| `log` | l'uscita del comando (tagliata a 4000 caratteri) | `{"uso_id": "…", "errore": true \| false, "caratteri": 18234}` |
| `testo` | un pezzo di testo del modello (passo, ragionamento breve) | `{}` |
| `conferma` | la domanda in italiano («Posso eseguire sulla VPS: …?») | `{"azione_id", "azione": "invio" \| "scrittura" \| "cancellazione" \| "servizio" \| "rete" \| "altro", "destinatario", "anteprima", "strumento", "scade_ts"}` |
| `risultato` | il testo finale del lavoro | `{"costo_usd", "turni", "durata_ms"}` |
| `errore` | il motivo, in italiano | `{}` |

Una `conferma` chiusa (scelta del telefono, scadenza o annullamento del lavoro) produce un altro evento `stato`
con `dati: {"azione_id", "scelta": "invia" | "annulla" | "scaduta"}`.

### `job_done`

```json
{"type": "job_done", "id": "…", "n": 40, "esito": "ok" | "errore" | "annullato", "riassunto": "…", "ts": 1791400000000}
```

È sempre l'ultimo messaggio di un lavoro e viene salvato come evento (ha un suo `n`).

### `job_stato` (risposta a `job_start` ripetuto o a un errore di richiesta)

```json
{"type": "job_stato", "id": "…", "stato": "coda" | "lavoro" | "attesa" | "finito", "esito": null | "ok" | …, "ultimo_evento": 12}
```

Richiesta non valida (id mancante, testo vuoto, troppi lavori in coda): `{"type": "job_errore", "id": "…", "motivo": "…"}`.

### `job_lista`

```json
{"type": "job_lista", "lavori": [
  {"id", "agente", "titolo", "testo", "stato", "esito", "creato", "finito", "ultimo_evento",
   "ultimo": "ultima riga utile (comando o passo)", "conferma": null | {…dati della conferma in attesa…}}
], "in_corso": 1, "max_paralleli": 2}
```

Ordine: dal più recente. `testo` tagliato a 300 caratteri.

## 4. Conferme

- Un lavoro che deve fare un'azione con effetti fuori dalla sua cartella di lavoro **si ferma**: invio di mail o
  messaggi, scrittura o cancellazione di file sulla VPS, riavvio o fermo di servizi e contenitori, comandi su altre
  macchine (`ssh`, `scp`), richieste web che scrivono (`POST`/`PUT`/`DELETE`, `--data`), installazioni, `git push`.
- Il fermo non dipende dal modello: un hook `PreToolUse` del processo `claude` del lavoro classifica ogni
  strumento (`server/lavoriRegole.js`) e, per le azioni sopra, aspetta la risposta della VPS, che aspetta il
  messaggio `conferma` dal telefono.
- Scadenza: 10 minuti → la conferma vale «annulla», l'azione non parte e il modello lo sa ("l'utente non ha risposto").
- Lettura, ricerca sul web e scrittura nella cartella del lavoro (`/tmp/lavori-telefono/<id>/`) non chiedono niente.
- La guardia dei comandi della VPS (irreversibili) resta accesa sopra a tutto.
- Regola dell'utente: l'invio vero di un messaggio parte dal telefono. La VPS prepara (bozze, testi, elenchi), e per
  questo il suo prompt dice di non inviare; se ci prova lo stesso, si ferma sulla conferma.

## 5. Ripresa dopo una disconnessione

1. Il telefono si ricollega (attese crescenti: 2 s, 5 s, 15 s, 30 s, 60 s, poi ogni 2 minuti, solo con rete).
2. Manda `job_lista`, poi per ogni lavoro non finito che conosce `job_segui {id, ultimo_evento}` con l'ultimo `n`
   che ha salvato.
3. La VPS rimanda gli eventi mancanti nell'ordine giusto e poi continua in tempo reale. Gli eventi con `n` già
   visto si scartano sul telefono (idempotente).
4. Una conferma in attesa si ritrova sia negli eventi sia in `job_lista.conferma`.

## 6. Persistenza e limiti (lato VPS)

- Un file per lavoro: `/root/jarvis/lavori-telefono/<id>.jsonl` (fuori da git, non toccato da `jarvis-repo-sync`).
  Riga 1 = il lavoro (`{"tipo":"lavoro", id, agente, testo, opzioni, creato}`), poi una riga per evento, l'ultima è
  `job_done`. Sopravvive al riavvio del contenitore.
- Al riavvio del server un lavoro rimasto aperto si chiude con `esito: "errore"`, riassunto «interrotto dal
  riavvio del server»: niente lavori fantasma, niente finti «fatto».
- Al massimo 2 lavori in parallelo (`JARVIS_LAVORI_MAX`), gli altri aspettano in coda (al massimo 10).
- Pulizia: all'avvio e ogni 6 ore si cancellano i lavori finiti da più di 30 giorni e quelli oltre i 300 più recenti.
- Ogni lavoro gira in un processo `claude` suo, separato dal processo sempre vivo della chat a voce: un lavoro
  lungo non rallenta le frasi veloci.

## 7. Come si prova

- Lato VPS: `node --test server/lavori.test.js` (regole, persistenza, ripresa, conferme, scadenze) e
  `vps/patch-vps-modulo/finto_lavori.mjs` (finto telefono «lavori», solo se `/health` dice `phoneConnected:false`).
- Lato app: prove JVM `vps/*Test.kt` (protocollo, riconnessione, ripresa, conferme) con `MockWebServer`.

## 8. Account (cassaforte, JBoss 0.3.1, 2026-10-07)

Caselle di posta e token del cervello mandati dall'app alla VPS. Solo da un socket con `ruolo: "lavori"`
(lato VPS `jarvis-agent/server/account.js`, lato app `cassaforte/ProtocolloAccount.kt`).

Dove scrive la VPS (gli stessi posti che usa già il Postino, `strumenti/posta.py`):
- la password in `/root/.env.jarvis` (permessi 600, fonte unica dei segreti) come `POSTA_<ID>_PASSWORD`
  (`<ID>` in maiuscolo, `-` → `_`), riga senza virgolette;
- i parametri senza segreti in `/root/jarvis/strumenti/posta-caselle.json`, con `origine: "jboss"` e
  `password_env` (il nome della variabile). Il Postino vede la casella al giro dopo, senza mani.
- il cervello: `CLAUDE_CODE_OAUTH_TOKEN` (provider `claude-code-vps`) o `OPENAI_API_KEY` (`codex`, `api-openai`)
  in `/root/.env.jarvis`. `api-anthropic` si rifiuta: in Claude Code `ANTHROPIC_API_KEY` passa davanti al token
  dell'abbonamento e farebbe pagare a consumo; la chiave Anthropic resta nella cassaforte del telefono.

| Messaggio | Campi | Risposta |
|---|---|---|
| `account_lista` | — | `account_lista {caselle: [{id, indirizzo, azienda, tipo, imap, smtp, password: bool, da_app: bool}], cervello: {CLAUDE_CODE_OAUTH_TOKEN: bool, OPENAI_API_KEY: bool}}` (mai valori) |
| `account_set` + `anteprima: true` | `richiesta_id`, `tipo` (`casella` \| `cervello`), `id`, `dati` SENZA password/token | `account_anteprima {richiesta_id, tipo, id, azione: "nuova" \| "sostituisce", cosa: [righe], avvisi: [righe]}`. Non scrive niente |
| `account_set` + `conferma: "si"` | come sopra, `dati` CON `password` (casella) o `token` (cervello), `sostituisci: true` se c'è già | `account_esito {richiesta_id, tipo, id, ok, testo, avvisi}` o `account_errore {richiesta_id, motivo}` |
| `account_elimina` + `conferma: "si"` | `richiesta_id`, `tipo: "casella"`, `id` | `account_esito` o `account_errore`. Solo caselle con `origine: "jboss"` |

`dati` di una casella: `indirizzo`, `tipo` (gmail, outlook, icloud, pec, hostinger, libero, aruba, generico),
`azienda`, `imap {host, porta, sicurezza}`, `smtp {host, porta, sicurezza}` (`sicurezza`: `ssl` | `starttls`),
`utente` se diverso dall'indirizzo, `cartelle {inviata, cestino, bozze}` se hanno nomi strani, `password`.

Regole:
- Giro dell'app: anteprima senza password → l'utente legge cosa cambia e conferma con impronta o PIN → set con la
  password e `conferma: "si"`. La password viaggia una volta, solo su `wss://` (l'app rifiuta `ws://` verso un
  indirizzo che non sia il telefono stesso).
- Senza `conferma: "si"` niente si scrive. Una casella o una chiave che c'è già si cambia solo con `sostituisci`.
  Le caselle scritte a mano sulla VPS si aggiornano (con `sostituisci`) ma non si cancellano dall'app.
- Il Postino legge solo con IMAP SSL diretto (993): altrimenti `account_errore`. Spedisce solo con SMTP SSL diretto
  (465): con STARTTLS (587) la casella entra con un avviso «legge ma non invia». Il Postino entra con l'indirizzo:
  un `utente` diverso porta un avviso.
- Prima la password, poi la casella (e al contrario per l'elimina): il Postino non vede mai una casella senza password.
- Scrittura atomica (file temporaneo + rename), permessi del `.env.jarvis` 600. Nei log solo `[account] casella <id>: aggiunta|aggiornata|tolta`.
- Il cervello scritto in `.env.jarvis` vale per chi legge quel file; il ponte `jarvis-agent` lo vede dopo
  `env_sync.py applica` e il riavvio del contenitore (lo decide l'utente, non l'app).
- La copia del Mac (`~/.env.jarvis`) si riallinea da sola entro 10 minuti (`sincro_env_da_vps.sh`).

Prove: `node --test server/account.test.js` (14 prove, anche `posta.py` vero che legge la casella finta) e
`vps/patch-cassaforte/prova_account.mjs` contro l'istanza di prova (porta 8793, file in `/tmp/jarvis-prova-account`).

## 9. Abbinamento con codice monouso (JBoss 0.4.0, 2026-10-07)

Un telefono senza token si abbina senza che il token passi da uno schermo o da un QR (lato VPS `server/abbina.js`,
lato app `configura/Abbinamento.kt`, sul Mac `scripts/genera-qr-vps.py`).

| Da | Messaggio | Risposta |
|---|---|---|
| socket «lavori» autenticato (il Mac) | `{"type":"abbina_crea"}` | `{"type":"abbina_codice","codice":"ABCD2345","scade_ts":…}` |
| socket NON autenticato, primo messaggio | `{"type":"abbina","codice":"ABCD2345"}` | `{"type":"abbinato","token":"…","versione":1}` e chiusura 1000, oppure `{"type":"abbina_errore","motivo":"…"}` e chiusura 4003 |

- Codice: 8 caratteri dall'alfabeto `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (niente 0/O/1/I), maiuscole/minuscole, spazi e
  trattini non contano; valido 5 minuti; un solo uso; un codice nuovo annulla il vecchio.
- Dopo un tentativo sbagliato il successivo si accetta solo dopo 3 s (per tutti i socket insieme); dopo 5 sbagliati il
  codice si annulla e l'abbinamento resta chiuso finché il Mac non chiede un codice nuovo.
- QR: `jboss-vps:1?u=<indirizzo urlencoded>&c=<codice>`. Mai il token.
- Log: solo «[abbina] codice nuovo / tentativo sbagliato (n su 5) / telefono abbinato». `/health` → `abbina: {attivo, bloccato, errori}`.
- Prove: `node --test server/abbina.test.js` (9) e `<repo Jarvis>/vps/patch-qr/prova_abbina.mjs` (istanza di prova, 9 controlli);
  lato app `AbbinamentoTest` (11, con WebSocket finto).
