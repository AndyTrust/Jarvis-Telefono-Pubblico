# JBoss (ex Jarvis Telefono): note tecniche

Note per chi sviluppa, con la storia delle versioni. Per installare e usare l'app: [README](../README.md).

App Android: voce, mani e conferma d'invio sul telefono. Dalla 0.3.0 un modulo VPS spegnibile (spento di fabbrica) manda i
compiti lunghi alla VPS e porta la chat Postino a numeri; spento, l'app non apre nessun collegamento.
Pacchetto `com.jarvis.telefono`, nome visibile «JBoss» dalla 0.2.0 (prima «Jarvis Telefono»; `applicationId`, pacchetti Kotlin e repo restano `jarvis`), icona con Jarvis. Nasce il 2026-10-07 (passo A) dal tag
`v1.2.3-base` dell'app `com.jarvis.app` (repo Jarvis-App-Android), più la modifica «solo Invia e Annulla» e la fine frase
a 1,5 s dell'app 1.2.4. Le due app sono repo separati e non si uniscono.

## Il giro

```
«Jarvis» (sherpa KWS) → VAD Silero → Whisper small offline → glossario integrato
  oppure campo di testo / Detta (Google, Whisper di scorta)
    → Nucleo.elabora
        → bozza in attesa? «invia» / «annulla» (CancelloInvio) → fine
        → Cervello.capisci(frase, contesto) → Piano (azioni JSON + frase da dire)
        → PhoneActionExecutor.handle(JSONObject)   ← lo stesso comando JSON dell'app 1.2.x
        → bolla, segnale, voce di sistema, cronologia (SQLite sul telefono)
```

- `nucleo/Cervello.kt`: contratto `Cervello`, `Contesto`, `Azione`, `Piano`.
- `nucleo/CervelloRegole.kt`: regole in italiano (apri app, cerca su Google/YouTube/Spotify, chiedi a Gemini,
  WhatsApp, mail, SMS, chiama, naviga, indietro, home, scorri, che ore sono). Fuori dalle regole: «Non ho capito».
  Numeri e indirizzi solo dalla frase o dalla rubrica, mai inventati; ogni invio passa da `invia_bozza`.
- `nucleo/Nucleo.kt`: esegue il piano azione per azione e misura i tempi (log `JarvisNucleo`, mai il testo detto).
- `nucleo/Cronologia.kt`: `cronologia.db`, ultime 2000 righe; base della memoria del passo B.
- `nucleo/ConfigPersonale.kt`: preferenze personali da `config-boss.json` (fuori da git e dall'APK).

## 0.2.0: Jarvis capo, agenti, tema Claude (2026-10-07)

Gerarchia: si parla sempre a **Jarvis** (campo, Detta, Parla, la parola «Jarvis»). Jarvis passa il lavoro
all'agente competente con una mappa sola, `agenti/Competenze.kt` (azioni del cervello → agente; frasi → agente per il
cervello cloud futuro). In Home stanno due titolari: **Jarvis** (tutto tranne la posta) e **Postino** (la posta).
Ricercatore, Social, Mani e Scrittore compaiono come chip a fianco di Jarvis solo mentre lavorano.

- `agenti/Agenti.kt` (modello: id, nome, missione, dove gira, autonomia, bloccata, istruzioni di sistema delle schede
  Dots; ordine e fissati restano nel modello ma la Home non li mostra), `ArchivioAgenti.kt` (`agenti.db`), `Avatar.kt`
  (WebP 128/256 in `drawable-nodpi`, cache), `Competenze.kt`.
- `ui/`: `Movimento` (durate dai token, scala di sistema), `AnimaAvatar` (respiro, ascolto, lavoro, luccichio, chip),
  `VistaCronologia` (filtro, ricerca, dettaglio), `ChipLavoro`, `InterruttoreAutonomia`, `Tema`.
- Schermate: `MainActivity` (Home), `AgentiActivity` (menu: scheda Capo + 5 schede), `AgenteChatActivity` (chat di un
  agente; `PostinoActivity.intento()` apre la chat a numeri dalla 0.3.0), `LicenzeActivity`. Lo spazio vuoto
  `PienoSchermoActivity` della 0.2.0 è stato tolto nella 0.3.0: il pieno schermo vero è `vps/TerminaleVpsActivity`.
- Token del tema: `values/tokens.xml` (spazi a 8 dp, raggi, avatar, durate 150-300 ms), colori in `values/colors.xml` e
  `values-night/colors.xml`.
- Cronologia: colonna `agente_esecutore` (database versione 3).
- Tolto il tasto flottante (07/10) e il permesso «sopra le altre app»: bolla e pannello usano la finestra
  dell'accessibilità.
- Avatar: Dots di OpenDots / CopilotKit (MIT, Copyright (c) Atai Barkai), avviso in Impostazioni → Info e licenze.

## 0.3.0: un'app sola, con modulo VPS e Postino a numeri (2026-10-07)

Unione dei rami `modulo-vps` (docs/MODULO-VPS.md, PROTOCOLLO-VPS.md) e `postino-numeri` (docs/POSTINO-NUMERI.md,
PROTOCOLLO-POSTINO.md) in `main`. Un solo pacchetto (`com.jarvis.telefono`), una sola icona, nome «JBoss».

- Pulsante monitor in Home → «Lavori sulla VPS» (`ModuloVpsUi.apri`): In corso / Finiti, Comandi, Ferma, Terminale a
  pieno schermo (Cerca, Copia, Ruota, Pieno, campo per un compito nuovo, pannello Invia/Annulla sopra a tutto).
- Pillola Postino in Home: «N nuove · resoconto delle HH:MM» dall'ultimo resoconto vero (`postino/ConteggioPostino`),
  «◐ in corso sulla VPS» mentre lavora, «collega la VPS» con il modulo spento o senza configurazione.
- Chat Postino (`postino/PostinoActivity`) sul socket del modulo: `vps/CanalePostinoVps.kt` (innesto 2). I lavori del
  Postino entrano nel registro dei lavori come gli altri (notifica a fine lavoro solo se la chat non è aperta).
- Chip «Ricercatore: sulla VPS · …» a fianco di JBoss mentre un lavoro VPS è aperto (tocco = terminale); in cronologia
  «◐ in corso sulla VPS» e «Finito» con l'avatar dell'agente (`agente_esecutore`).
- Instradamento (`vps/Instradamento.kt`): «sulla VPS …» forza la VPS, «qui …» il telefono; Postino e Ricercatore
  automatici (compito lungo: parole da ricerca o 20 elementi), Social sempre VPS, Mani e Scrittore qui. Presa la frase,
  JBoss esce da «Penso» e dice dove è andata.
- Impostazioni → «Collegamento Jarvis» (dalla 0.6.0, prima «Modulo VPS»): interruttore unico, stato, webapp, lavori, memoria. Vedi `docs/COLLEGAMENTO-JARVIS.md`. Indirizzo da `vps_url` della configurazione
  personale, token dal `.env.jarvis` del Mac: `bash scripts/configura-vps.sh` (mai stampato, mai nel repo).
- Parola: «Hey Boss» (riga `▁HE Y ▁BO S S :2.0 #0.2 @JBOSS`, soglia più permissiva) si aggiunge a «JBoss» nelle sue
  pronunce. Dalla 0.6.0 «Jarvis» non sveglia più. Se scatta per sbaglio (al banco 2 falsi su 40, tutti «ok boss …»): `#0.2` → `#0.35` in
  `assets/kws-model/keywords.txt`, ricostruire e reinstallare (al banco 9 su 15 attivazioni, 1 falso su 40).
- Posta per nome (dalla 0.7.1 pubblica, 2026-10-10): «posta», «mail», «email» aprono l'app di posta PREDEFINITA del
  telefono (`AppTelefono.postaPredefinita`), poi la prima installata di `CatalogoApp.POSTA`; «samsung email» apre
  Samsung Email solo se c'è. Su Samsung l'etichetta dell'app è «E-mail».

## Configurazione personale

Il file sta sul Mac in `<cartella privata>/config-boss.json` (fine frase, filtro impronta, ascolto sempre
acceso, volume dei segnali, app mail, indirizzo mail personale, nome della chat con se stessi su WhatsApp, lingua). Nessuna
password. Senza file l'app usa valori di fabbrica neutri.

```bash
bash scripts/autorizza-telefono.sh <seriale>      # permessi; l'accessibilità solo con ACCESSIBILITA=1
bash scripts/configura-boss.sh                    # porta config-boss.json sul telefono (ADB, base64, banco DUMP)
bash scripts/configura-boss.sh --togli            # torna ai valori di fabbrica
```

Dopo aver cambiato la configurazione: Spegni e Accendi nell'app perché la voce usi la fine frase nuova.

## Compilare e provare

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
bash scripts/prove-jvm.sh                         # prove JVM (304 alla 0.2.0)
./gradlew :app:testDebugUnitTest :app:assembleRelease
bash scripts/prove-telefono.sh <seriale>          # prove ADB senza invii (prendere la chiave telefono-adb)
```

`local.properties` (solo `sdk.dir`) e `keystore.properties` stanno nella cartella ma fuori da git. La firma usa la chiave
indicata in `keystore.properties` (`<la tua chiave di firma>`): chi pubblica usa la sua.

Banco ADB (`ProvaAdbReceiver`, solo shell ADB):

```bash
adb shell am broadcast -n com.jarvis.telefono/.mani.ProvaAdbReceiver --es comando '{"action":"frase","testo":"apri Spotify"}'
adb shell am broadcast -n com.jarvis.telefono/.mani.ProvaAdbReceiver --es comando '{"action":"misura"}'
adb logcat -s JarvisNucleo:I JarvisProva:I
```

Valgono anche tutte le azioni delle mani (`apri_app`, `read_screen`, `componi`…), `voce_prova`, `ascolta_prova`,
`risposta_di_boss`, `spegni`.

## Attiva sul telefono e ritorno all'app vecchia (2026-10-07 20:46)

Dal 2026-10-07 sul S24 lavora JBoss (`com.jarvis.telefono` 0.2.0): servizio acceso, accessibilità accesa solo per
JBoss, configurazione dell'utente letta. L'app vecchia `com.jarvis.app` 1.2.4 resta installata e spenta. Per tornare a lei,
due comandi dal Mac (ADB):

```bash
# 1. accessibilità: al posto di JBoss torna quella di com.jarvis.app (gli altri servizi, es. Passbolt, restano)
adb shell 'settings put secure enabled_accessibility_services "$(settings get secure enabled_accessibility_services | sed s#com.jarvis.telefono/com.jarvis.telefono.JarvisAccessibilityService#com.jarvis.app/com.jarvis.app.JarvisAccessibilityService#)"'
# 2. servizio: spegne JBoss e apre l'app vecchia, che riaccende il suo
adb shell am broadcast -n com.jarvis.telefono/.mani.ProvaAdbReceiver --es comando "'{\"action\":\"spegni\"}'" && adb shell am start -n com.jarvis.app/.WebActivity
```

APK 1.2.4 di riserva: `<cartella di backup>/jarvis-1.2.4.apk` (`adb install -r` se servisse).

## Parola di attivazione

«JBoss» in cinque pronunce (`▁JA Y ▁BO S S`, `▁HE Y ▁JA Y ▁BO S S`, `▁JE I ▁BO S S`, `▁G E I ▁BO S S`,
`▁CHI ▁BO S S`, tutte `@JBOSS`) più «Hey Boss» (`▁HE Y ▁BO S S`, #0.2), in `assets/kws-model/keywords.txt`.
Dalla 0.6.0 (08/10: «solo Hey Boss e Hey JBoss») «Hey Jarvis» e «Jarvis» sono tolti. Il KeywordSpotter è a
vocabolario aperto (token BPE di `tokens.txt`, nessun riaddestramento). Banco ripetibile sul Mac con lo stesso modello:
`python3 scripts/banco-parole.py` (voci italiane di macOS + le 320 registrazioni dell'utente in
`<cartella con le tue registrazioni>`). Esito del 08/10 10:45: VERDE, frasi giuste prese 39 su 70 (5 modi di dire «Hey Boss»/«JBoss», 7 voci, 2
velocità); nessuno scatto su «Ok boss lo faccio io», «Ok boss», «Hey Jarvis», «Jarvis» e frasi comuni; nessuno
scatto sulle 320 registrazioni dell'utente. Con la voce vera dell'utente dal vivo non è ancora provato.

## 0.3.4: il cervello della VPS per le frasi non capite (2026-10-07, ramo `cervello-vps`)

Regole prima; se non capiscono (o la frase è «complessa») e il modulo VPS è acceso con la rete, la frase va a
`jarvis-agent` sul ruolo «mani»: la VPS chiede le azioni, le mani del telefono le fanno, conferma d'invio sul telefono.
Canale aperto solo quando serve, chiuso dopo 3 minuti; un solo telefono. Dettagli e misure: [CERVELLO-VPS.md](CERVELLO-VPS.md).

## Due Jarvis sullo stesso telefono

Sul S24 (Android 16) il 2026-10-07 i due servizi di accessibilità (`com.jarvis.app` e `com.jarvis.telefono`) sono rimasti
agganciati insieme per qualche minuto; il 20/09 con un'altra build uno aveva staccato l'altro. Non è garantito: prima di
accendere l'accessibilità di Jarvis Telefono, l'utente lo deve sapere. Con tutti e due i servizi voce accesi, due app
ascoltano «Jarvis» con lo stesso microfono: accenderne uno solo.

## Cosa NON c'è (tolto rispetto all'app 1.2.3)

Tasto flottante (dalla 0.2.0), voce Gemini del ponte, aggiornamenti dal server, notifiche dal server, glossario
scaricato, sito nella WebView (accesso automatico, credenziali cifrate, voce del sito), indirizzi e token nelle Impostazioni,
`pubblica.sh`. Il ponte WebSocket con gli strumenti della VPS è tornato nella 0.3.4, solo per le frasi che le regole non capiscono (CERVELLO-VPS.md).

Checklist di non regressione: [CHECKLIST.md](CHECKLIST.md).
