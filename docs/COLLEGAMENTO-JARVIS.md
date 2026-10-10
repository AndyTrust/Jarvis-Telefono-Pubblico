# Collegamento Jarvis: un'app unica (JBoss 0.6.0)

2026-10-08 · agente jboss-unica · ramo `jboss-unica`

l'utente, 08/10: «devi controllare e fondere senza confondere l'app JBoss con il collegamento a Jarvis; il collegamento con
la webapp collegata a Mac e VPS deve essere un collegamento integrato; JBoss comunica e si tiene in linea; un'app unica,
sennò rischiamo conflitti anche con comunicazioni e notifiche; devo avere attivo solo «Hey Boss» e «Hey JBoss»».

## Prima e dopo

| | Prima (fino alla 0.5.0) | Dalla 0.6.0 |
|---|---|---|
| App sul telefono | JBoss `com.jarvis.telefono` + Jarvis `com.jarvis.app` 1.2.4 (webapp, voce «Hey Jarvis», mani, notifiche) | Solo JBoss. `com.jarvis.app` va disinstallata (pulsante in Impostazioni, decide l'utente) |
| Parola | «Hey Boss», «JBoss», «Hey Jarvis», «Jarvis» | Solo «Hey Boss» e «Hey JBoss» (sei pronunce, tutte `@JBOSS`) |
| Interruttore | «Modulo VPS» | «Collegamento Jarvis»: uno solo per lavori, cervello, notifiche, webapp, memoria |
| Notifiche di Jarvis | nell'app vecchia (WorkManager ogni 15 minuti) | in JBoss, una coda sola con i doppioni fermati |
| Chi risponde | JBoss poteva prendere per sua la risposta della chat del sito | l'Arbitro decide: ogni risposta porta `origine` e `rid` |
| Memoria | nessuna fra telefono e Jarvis | memoria condivisa con la VPS (sotto) |

## Un solo canale

Stesso indirizzo (`vps_url`), stesso token (cassaforte cifrata), stesso ponte `jarvis-agent`:

| Pezzo | Via | Codice |
|---|---|---|
| Lavori lunghi | WebSocket, ruolo «lavori» | `vps/ModuloVps.kt` |
| Cervello per le frasi difficili + mani | WebSocket, ruolo «mani» | `vps/CanaleMani.kt`, `collegamento/Arbitro.kt` |
| Report di Jarvis e del Postino | HTTP `GET /notifiche?dopo=` ogni 15 minuti (AlarmManager inesatto) | `collegamento/CollegamentoJarvis.kt` |
| Webapp (Command Center di Mac, Windows e VPS) | WebView sull'host del sito | `collegamento/WebJarvisActivity.kt` |
| Memoria condivisa | HTTP `/memoria/*` | `collegamento/MemoriaCondivisa.kt`, `jarvis-agent/server/memoriaTelefono.js` |
| Contesti seguiti | lavori con agente `crm` o `patrimonio` | `collegamento/Contesti.kt`, `jarvis-agent/server/lavori.js` |

Modulo spento: nessun collegamento, nessun giro, nessuna notifica di Jarvis (le conferme dei lavori già aperti restano).

## Chi risponde (Arbitro)

Il ponte ha UNA sessione Claude per il telefono e per la chat del sito. Dalla 0.6.0:

- `auth` del telefono porta `app: "jboss"`; `/health` dice `phoneApp` («jboss» o «jarvis-app» = l'app vecchia).
- `user_message` porta `rid` (es. `jb-mg1x2-3`); ogni `assistant_message` torna con `origine` e, per il telefono, `rid`.
- Regole (`Arbitro.risposta`): `sito` → JBoss tace (la risposta è già nella webapp); `sottofondo` → una notifica nella
  coda, mai detta come risposta; `rid` della frase in corso → la dice JBoss; `rid` di una frase annullata → si butta;
  altro `rid` → risposta tardiva, detta una volta. Senza `origine` e `rid` (ponte vecchio) vale il comportamento 0.5.0.
- Gli strumenti (`tool_call`) fuori da una frase di JBoss tornano con un errore: le mani le comanda solo JBoss.

## Una coda di notifiche

Ogni avviso passa da `CollegamentoJarvis.passaDallaCoda` (`CodaNotifiche`):

- stessa chiave (id del ponte, id del lavoro) una volta sola, anche dopo un riavvio (stato nelle preferenze);
- stesso titolo e stesso inizio del testo entro 30 minuti, da qualunque fonte: una notifica sola (il report del Postino
  che arriva come lavoro finito e dal Command Center);
- contesti seguiti (`crm`, `patrimonio`): solo se importanti (`priorita: alta` dal ponte, o un lavoro chiesto dall'utente);
- un canale Android, «Jarvis: report e avvisi»; il tocco apre la webapp sul filo (`?filo=postino#chat`).
Le conferme Invia/Annulla dei lavori restano sul loro canale: sono domande, non avvisi.

## Memoria condivisa: il protocollo

La memoria vera sta sulla VPS. Il telefono chiede e riceve un riassunto; i dati personali restano sulla VPS e nella
cassaforte del telefono (voce «memoria-condivisa», cifrata).

**Cosa.** Fatti = preferenze dell'utente che il telefono conosce: `app_mail`, `account_mail`, `whatsapp_me`,
`chat_se_stesso`, `lingua`, `fine_frase_s`, `ascolto_sempre_acceso`, `filtro_impronta`, `volume_segnali`,
`parole_attivazione`, `trascrittore`. Riassunto = due nodi per tema dal grafo delle regole (`memoria_cerca`: conferme,
voce, posta, segreti, date, patrimonio) + le prime righe delle note della memoria che il grafo non copre («Come si scrive»,
«Chi è l'utente», «Posta - regole del report e PEC») + i contesti seguiti con il loro indirizzo.

**Mai.** Chiavi con password, token, segreto, api_key: il telefono non le manda, la VPS le rifiuta. Valori oltre 200
caratteri. Nei log solo chiavi e conteggi.

**Quando.** All'accensione del collegamento; all'avvio del servizio se l'ultimo giro ha più di 6 ore; a richiesta
(«allinea la memoria», pulsante in Impostazioni); dopo una decisione dell'utente.

**Rotte** (`Authorization: Bearer <token del ponte>`):

```
POST /memoria/allinea  {app, versione, fatti:[{chiave, valore, ts}]}
     → {ora, salvati, uguali, conflitti:[{chiave, telefono:{valore,ts}, vps:{valore,ts,fonte}}], scartati, fatti:[…]}
GET  /memoria/profilo  → {ora, voci:[{tema,id,titolo,data,testo}], contesti:[{id,nome,indirizzo,…}], fatti, conflitti}
GET  /memoria/cerca?q=…&n=3 → {risultati:[{id,titolo,data,testo}]}
POST /memoria/decidi   {chiave, vince:"telefono"|"vps"} → {ok, fatto}
```

File sulla VPS: `/root/jarvis/memoria/telefono-fatti.json` (600), con lo storico delle ultime 200 modifiche.
Jarvis sul Mac o sulla VPS scrive un fatto suo con `node server/memoriaTelefono.js imposta <chiave> <valore>` (fonte «vps»).

**Chi vince.**
1. Fatto nuovo dal telefono → si salva (fonte «telefono»).
2. Stesso valore → niente; un conflitto aperto su quella chiave si chiude.
3. Valore diverso, il vecchio era del telefono e non deciso dall'utente, il nuovo è più recente → vince il più nuovo.
4. Valore diverso e il vecchio è di Jarvis (fonte «vps») o già deciso dall'utente → **conflitto: decide l'utente.** Nessuno
   sovrascrive. JBoss lo mostra in Impostazioni («Tieni il telefono» / «Tieni Jarvis») e a voce («cosa sai di me»).
   Se vince Jarvis, il telefono scrive il valore nella sua configurazione.
5. Jarvis ha un fatto che al telefono manca (vuoto) → il telefono lo prende senza chiedere: riempie un buco.

## Contesti seguiti: CRM di lavoro e Patrimonio (esempi)

- Due contesti di esempio: un CRM di lavoro (database `crm`) e un Patrimonio (database `patrimonio`). Nomi, database e
  indirizzi veri li decide chi installa Jarvis sulla sua VPS: non stanno nell'APK, arrivano con il profilo della memoria.
- Le credenziali stanno solo sulla VPS, nel file dei segreti di Jarvis.
- Una domanda su questi contesti («come va il CRM», «novità sul patrimonio») va SEMPRE alla VPS come lavoro con agente
  `crm` o `patrimonio`, con davanti «SOLA LETTURA, nessuna azione su clienti, preventivi, email o ordini senza il sì
  dell'utente; se serve un'azione, scrivila come proposta».
- Avvisi solo a richiesta o per eventi importanti (la coda lascia in silenzio il resto).
- «apri il CRM», «apri il patrimonio» aprono la pagina nel browser (con il login dell'utente, sola lettura).

## Comandi a voce del collegamento

| Frase | Cosa fa |
|---|---|
| «apri Jarvis», «apri la webapp», «apri il command center» | webapp Jarvis dentro JBoss |
| «apri i report del Postino» | webapp sul filo del Postino |
| «apri il CRM», «apri il patrimonio» | la pagina nel browser |
| «allinea la memoria» | allineamento adesso, dice quante regole e quanti disaccordi |
| «cosa sai di me» | il riassunto della memoria condivisa, a voce |
| «sei collegato a Jarvis?» | stato del collegamento |
| «controlla i report di Jarvis» | un giro delle notifiche adesso |
| «come va il CRM», «novità sul patrimonio» | lavoro sulla VPS in sola lettura |

## La parità con l'app Jarvis 1.2.4 (integra-jarvis, 2026-10-08)

Inventario completo: 73 funzioni (note interne).
Portate dentro JBoss, nel modulo `collegamento/` (nessuna app separata, nessun canale in più):

| Funzione dell'app 1.2.4 | In JBoss |
|---|---|
| Ponte JS `window.JarvisApp` (stesso nome: lo usano ponte.js, dettato.js, chiamata.js, mobile.js del sito) | `WebJarvisActivity.Ponte` |
| Dettato nella chat del sito (Google o Whisper, testo parziale), un microfono solo | `DettatoNativo` + `AccessoWeb.SCRIPT_RICONOSCIMENTO` |
| Voce del sito (`speechSynthesis` → TTS del telefono, «Chiama Jarvis») | `collegamento/VoceWeb.kt` |
| Utente e password del sito salvati, un accesso automatico per processo | `AccessoSalvato` → cassaforte (voce «sito-…», visibile in Sicurezza) |
| Microfono al sito solo sul suo host e col permesso Android; alert/confirm/prompt col titolo JBoss | `WebJarvisActivity.Cromo` |
| Pagina offline con «Riprova» e «Torna a JBoss»; ripresa dopo la morte del motore WebView | `assets/webapp/offline.html` |
| Schermo intero con tasto ⋮ (Ricarica, Voce e telefono, Dimentica l'accesso); user agent `JarvisApp/x JBoss/x` | `AccessoWeb.userAgent` |
| L'icona apriva la webapp | scorciatoia dinamica «Webapp Jarvis» (tocco lungo sull'icona di JBoss) |
| «Cerca aggiornamenti» e controllo ogni 12 ore | `AggiornamentiJBoss`: `/update/apk/jboss-version.json` sul ponte, notifica, il tocco apre l'APK nel browser; a voce «cerca aggiornamenti» |

Non portate, per decisione dell'utente: tasto flottante (JBoss 0.2.0, 07/10: ascolto sempre acceso), «Hey Jarvis» (08/10),
glossario scaricato dal ponte (rotte tolte dall'utente il 26/09: il ponte risponde 404 anche all'app vecchia).
Da unire dopo i rami in lavorazione: installazione dentro l'app (FileProvider + `REQUEST_INSTALL_PACKAGES` nel manifest),
diagnosi del modo tecnico al ponte (`JarvisService.diag`), interruttore «voce sintetica» (pagina Voce di ui-approvata).

Pubblicare una versione di JBoss sul ponte (scrive sulla VPS: solo con il sì dell'utente): copiare l'APK in
`/root/jarvis/jarvis-agent/releases/jboss-<versione>.apk` e scrivere accanto `jboss-version.json`
`{"versionCode":N,"versionName":"x.y.z","apk":"jboss-x.y.z.apk","sha256":"…"}`. Il `version.json` dell'app vecchia non si tocca.

## Banco ADB

```bash
adb shell am broadcast -n com.jarvis.telefono/.mani.ProvaAdbReceiver --es comando '{"action":"collegamento","cosa":"stato"}'
# cosa: stato | giro | allinea | coda_prova | decidi_telefono | decidi_jarvis | accendi | spegni
#       | aggiornamenti | scorciatoia | webapp | accesso_salvato ; esito nel logcat, tag JarvisProva e JarvisCollegamento
```

## Lato VPS (jarvis-agent)

`server/index.js` (origine, rid, app, rotte memoria), `server/memoriaTelefono.js` (+ test), `server/lavori.js` (agenti
`crm` e `patrimonio`). Copie di prima sulla VPS: `server/index.js.prima-unica-20261008`, `server/lavori.js.prima-unica-20261008`.
Ritorno: rimettere le due copie, togliere `memoriaTelefono.js`, `docker compose build && docker compose up -d`.
