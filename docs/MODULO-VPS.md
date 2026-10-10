# Modulo VPS di JBoss (0.3.0, unito in `main` il 2026-10-07)

2026-10-07 · agente telefono-vps · protocollo: [PROTOCOLLO-VPS.md](PROTOCOLLO-VPS.md)

I compiti lunghi (ricerche, posta in massa, lavori pesanti) non girano sul telefono: il telefono li manda alla VPS,
la VPS li esegue con un processo `claude` per lavoro e rimanda passi, comandi eseguiti, uscite e risultato. Le azioni
delicate (invii, scritture e cancellazioni sulla VPS, servizi, ssh, POST) si fermano finché l'utente non tocca Invia.
Il modulo è spegnibile e parte spento: spento, l'app non apre nessun collegamento.

## I pezzi (tutti in `app/src/main/java/com/jarvis/telefono/vps/`)

| File | Cosa fa | Provato |
|---|---|---|
| `ProtocolloVps.kt` | messaggi JSON del protocollo (auth con `ruolo: "lavori"`, job_*, conferma) | JVM |
| `Instradamento.kt` | locale o VPS: «sulla VPS»/«qui», scelta per agente, compito lungo (parole o 20 elementi), invii sempre qui, modulo spento o senza rete detto chiaro | JVM |
| `NucleoVps.kt` | logica senza Android: lavori, ripresa da `ultimo_evento`, eventi idempotenti, conferme, coda senza rete | JVM |
| `ClientVps.kt` | WebSocket OkHttp, attese crescenti 2-5-15-30-60-120 s, stop su token rifiutato, ping 30 s, byte contati | JVM (MockWebServer) |
| `RegistroLavori.kt` | SQLite `lavori-vps.db` (lavori + eventi), ultimi 300 lavori | telefono |
| `ConfigVps.kt` | `filesDir/modulo-vps.json` (indirizzo e token, fuori da git e dall'APK) + interruttore e scelte per agente | telefono |
| `ModuloVps.kt` | il modulo in app: collega solo se acceso + rete + qualcosa da seguire; notifiche; cronologia; `instrada()` | telefono |
| `NotificheVps.kt` | canali «Lavori sulla VPS» e «Conferme della VPS» (Invia/Annulla nella notifica); banco ADB `ConfiguraVpsReceiver` (DUMP) | telefono |
| `LavoriActivity.kt` | «Lavori sulla VPS»: interruttore, In corso / Finiti, Comandi, Ferma, Ritenta, Terminale | telefono |
| `TerminaleVpsActivity.kt` | terminale a PIENO SCHERMO: log nativo, Cerca, Copia, Ruota, Pieno, Ferma, campo per un compito nuovo, pannello di conferma sopra a tutto | telefono |
| `ModuloVpsUi.kt` | punti di aggancio: `apri()`, `schedaInCorso()`, `aggiungiSezione()` (Impostazioni) | telefono |

Fuori da `vps/` il branch tocca solo: `AndroidManifest.xml` (2 activity, 2 receiver), `ImpostazioniActivity.kt` (1 riga),
`nucleo/Nucleo.kt` (1 riga: `ModuloVps.instrada`), `app/build.gradle.kts` (versione 0.3.0), `scripts/configura-vps.sh`.
All'unione il buildType di prova `vpsprova` (pacchetto `.vps`, nome «Jarvis VPS») è stato tolto: c'è un'app sola.

## Perché il terminale è nativo (e non la WebView del sito)

Il terminale del sito (`#computer/terminale`) è ttyd dietro cc-ponte con login + TOTP e `forward_auth` di Caddy: nella
WebView servirebbe il TOTP a ogni sessione (o un cookie lungo salvato nel telefono), xterm.js con la tastiera di
Android è scomodo, e soprattutto sarebbe una shell root della VPS in mano al telefono, fuori dalle conferme. La vista
nativa degli eventi è più leggera (niente pagina, solo testo), riprende da sola dopo una caduta di rete e ogni azione
delicata passa dal pannello Invia/Annulla. Per scrivere alla VPS c'è il campo in basso: diventa un lavoro.

## Configurare e provare

```bash
# indirizzo: «vps_url» in <cartella privata>/config-boss.json; token: solo ~/.env.jarvis
bash scripts/configura-vps.sh                                      # app vera (dopo il merge)
bash scripts/prove-jvm.sh --tests 'com.jarvis.telefono.vps.*'
```

Sull'APK di prova NON accendere l'accessibilità (Samsung: due servizi Jarvis non stanno insieme). Senza
accessibilità la conferma compare nel pannello della schermata Terminale e nella notifica; con l'accessibilità
accesa (app vera) e l'app in secondo piano compare anche il pannello di sistema delle bozze.

## Come si fa il merge (FATTO il 2026-10-07, ramo `unione`; resta per memoria)

```bash
cd <cartella del repo>            # main con il blocco 1 già committato
git merge --no-ff modulo-vps
```

Conflitti attesi, tutti piccoli:
- `app/build.gradle.kts`: fatto con `versionCode = 4`, `versionName = "0.3.0"`, senza `vpsprova`.
- `AndroidManifest.xml`: tenere le activity del blocco 1 (`AgentiActivity`, `LicenzeActivity`, `PienoSchermoActivity`) e le
  4 righe `.vps.*` di questo branch.
- `ImpostazioniActivity.kt`, `nucleo/Nucleo.kt`: tenere le modifiche di tutti e due (le mie sono una riga ciascuna).

Poi tre agganci nel codice del blocco 1:
1. `MainActivity.avvisoVps()` → `com.jarvis.telefono.vps.ModuloVpsUi.apri(this)` (il pulsante monitor apre i lavori).
2. La `PienoSchermoActivity` vuota del blocco 1 non serve più: il pieno schermo è `vps/TerminaleVpsActivity`
   (la sua prova del pannello si può tenere, oppure togliere la classe e la riga del Manifest).
3. Chat: per ogni lavoro aperto `ModuloVpsUi.schedaInCorso(context, lavoro)` (oggi monogramma; con gli avatar del blocco 1
   basta sostituire `monogramma()` con l'immagine dell'agente). L'elenco: `ModuloVps.registro(ctx).aperti()`, aggiornato
   con `ModuloVps.nucleo(ctx).ascolta { cambiato(id) }`. La scelta per agente della scheda Agenti: `ConfigVps.setPreferenza`.

Dopo il merge: `bash scripts/prove-jvm.sh` (tutte verdi), `./gradlew :app:assembleRelease`, configura-vps.sh, prova
sul telefono della checklist qui sotto.

## Checklist sul telefono

- Impostazioni → Modulo VPS: interruttore spento di fabbrica; acceso dice «collegata» entro pochi secondi.
- Chat: «sulla VPS cerca …» → in cronologia «[VPS · Ricercatore web] In corso sulla VPS», notifica a fine lavoro.
- Lavori → Terminale: log in tempo reale, Cerca, Copia, Ruota (il log resta), Pieno, tastiera che spinge su il campo.
- Conferma (es. «sulla VPS scrivi ok nel file /root/prova.txt»): pannello sopra il log anche a pieno schermo e in
  orizzontale; Annulla → il file non c'è; notifica con Invia/Annulla ad app chiusa.
- Modo aereo durante un lavoro: al ritorno della rete il log riprende senza buchi né doppioni.

## Come si torna indietro

Il branch non tocca `main`: basta non unirlo. Dopo il merge: `ModuloVps` spento (interruttore) = nessun collegamento;
per togliere il codice `git revert -m 1 <commit del merge>`. Lato VPS: vedi `<repo Jarvis>/vps/patch-vps-modulo/LEGGIMI.md`.
