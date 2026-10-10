# Configurare JBoss da zero

Versione 0.4.0 · 2026-10-07 · agenti jboss-cassaforte (0.3.1) e jboss-configura (0.4.0) · stato: cassaforte, prova
delle caselle, protocollo VPS, schermate di configurazione e abbinamento con codice QR nel codice (paragrafi 8 e 9).

Fino alla 0.3.0 l'app prendeva tutto da file personali portati con ADB (`scripts/configura-boss.sh`,
`scripts/configura-vps.sh`). Dalla 0.3.1 i dati di accesso stanno nella **Cassaforte** dell'app, cifrati, e chi
riceve l'app li inserisce da sé. Gli script ADB restano per l'utente e per le prove.

## 1. I passi per chi riceve l'app

1. **Installa l'APK** (link o file). Android chiede di consentire l'installazione da quella fonte: si consente una volta.
2. **Blocco schermo**: serve un PIN o un'impronta sul telefono. Senza, l'app non mostra password e token.
3. **Permessi** (procedura guidata al primo avvio, passo 1): microfono, notifiche, accessibilità (le «mani»), rubrica, batteria.
4. **VPS** (facoltativa, passo 3): sul Mac `python3 scripts/genera-qr-vps.py`, nell'app «Scansiona QR» (o «Incolla»).
   Il QR porta indirizzo e un codice monouso, mai il token (paragrafo 9). Senza VPS l'app lavora solo sul telefono.
5. **Cervello**: uno dei tre casi del paragrafo 4.
6. **Caselle di posta**: per ognuna si sceglie il gestore (preset), si mette la password (o la password per app),
   si tocca **Prova** e, se la prova riesce, **Manda alla VPS** (il Postino la leggerà da lì).
7. **Google**: niente da fare per le ricerche e le app Google (paragrafo 5).

## 2. Dove stanno i segreti

| Cosa | Dove | Come |
|---|---|---|
| Password delle caselle, token e chiavi del cervello, token VPS, numero WhatsApp | telefono, `filesDir/cassaforte.bin` | AES-256-GCM; la chiave sta nell'Android Keystore (non esportabile, nel chip sicuro se c'è) |
| Preferenze della voce (fine frase, impronta, volume) | telefono, `filesDir/config-boss.json` | in chiaro, nessun segreto (il file rifiuta chiavi «password», «token»…) |
| Password delle caselle lette dal Postino | VPS, `/root/.env.jarvis` (600) | riga `POSTA_<ID>_PASSWORD`, fonte unica dei segreti; copia sul Mac in sola lettura |
| Parametri delle caselle (senza segreti) | VPS, `/root/jarvis/strumenti/posta-caselle.json` | indirizzo, server, `password_env`, `origine: "jboss"` |
| Token Claude Code della VPS | VPS, `/root/.env.jarvis` | `CLAUDE_CODE_OAUTH_TOKEN` |

Protezioni sul telefono:
- `android:allowBackup="false"` e regole di esclusione (`res/xml/estrazione_dati.xml`, `backup_escluso.xml`):
  niente backup nel cloud e niente trasferimento da telefono a telefono. La cassaforte comunque non si apre su un
  altro telefono: la chiave non esce dal Keystore.
- Niente segreti nei log: `toString()` delle voci mostra «•••• (N)»; i log dicono solo generi e conteggi.
- Le schermate con i segreti chiameranno `Sblocco.proteggiSchermo()` (FLAG_SECURE: niente screenshot né anteprima
  nelle app recenti) e `Sblocco.chiedi()` (impronta o PIN, valido 60 secondi) prima di mostrare una password.
- Niente Room, niente SharedPreferences con segreti.

**Migrazione dalla 0.3.0**: al primo avvio della 0.3.1 l'app porta nella cassaforte il token e l'indirizzo della VPS
(e cancella il vecchio `modulo-vps.json` in chiaro), l'account di posta e il numero WhatsApp di `config-boss.json`
(il file resta com'è). Una volta sola; da ADB si rifà con l'azione `cassaforte_migra` del banco di prova e si
controlla con `cassaforte_stato` (solo conteggi).

**Cambio di telefono**: «Esporta» crea un testo cifrato con una frase scelta da te (PBKDF2 210 000 giri + AES-GCM);
sul telefono nuovo «Importa» con la stessa frase. Senza la frase il file non si apre.

## 3. Come si cancella

- Una voce: Elimina nella sua schermata (dopo impronta o PIN). Una casella mandata alla VPS si toglie anche lì
  (`account_elimina`: password e casella via). Le caselle scritte a mano sulla VPS non si tolgono dall'app.
- Tutto: «Svuota la cassaforte» (cancella il file) e disinstallare l'app (Android cancella la chiave del Keystore).
- Una password per app di Gmail o iCloud si revoca dalla pagina del gestore: l'accesso cade subito.

## 4. Il cervello: tre casi

1. **Claude Code sulla VPS con il proprio abbonamento (Pro o Max)**. Sul proprio computer:
   `claude setup-token` → accesso con il proprio account → token `sk-ant-oat01-…`. Si incolla nell'app (Cervello,
   «Claude Code sulla VPS»), che lo tiene nella cassaforte e, dopo la conferma, lo manda alla VPS
   (`account_set tipo: cervello`): la VPS lo scrive in `/root/.env.jarvis` come `CLAUDE_CODE_OAUTH_TOKEN`. Per il
   ponte serve poi `python3 strumenti/env_sync.py applica` e il riavvio del contenitore.
   **Regole di Anthropic** (lette il 05/10/2026): l'abbonamento è personale. Non si condivide con altre persone e il
   token non si usa dentro app o prodotti di terzi. Chi riceve JBoss usa il **proprio** abbonamento sulla **propria**
   VPS, oppure una chiave API a consumo. L'app lo scrive nella guida (`TestiGuida.CERVELLO_CLAUDE_CODE`).
2. **Chiave API** Anthropic (`sk-ant-api…`, console.anthropic.com) o OpenAI (`sk-…`, platform.openai.com): resta
   nella cassaforte del telefono e serve al cervello in cloud (passo B). «Prova la chiave» chiede l'elenco dei
   modelli (`GET /v1/models`): non consuma crediti. Sulla VPS la chiave Anthropic non va: in Claude Code
   `ANTHROPIC_API_KEY` passerebbe davanti al token dell'abbonamento.
3. **Codex (OpenAI) sulla VPS**: `codex login` sulla VPS (abbonamento ChatGPT), oppure una chiave API OpenAI che
   l'app manda alla VPS come `OPENAI_API_KEY`.

Codice: `cassaforte/ConfiguraCervello.kt` (`controllaForma`, `prova`), testi in `cassaforte/TestiGuida.kt`.

## 5. Google

**Raccomandazione (una strada, per l'utente e per chi riceve l'app):** niente accesso OAuth a Google in questo giro.
- **Ricerche e app Google** (Ricerca, Gmail, Maps, Calendar, Drive): JBoss usa l'account Google già presente sul
  telefono, aprendo le app e leggendo lo schermo con l'accessibilità. Non serve nessun permesso Google in più.
- **Gmail per il Postino della VPS**: IMAP con la **password per app** (myaccount.google.com/apppasswords, serve la
  verifica in due passaggi). È la stessa strada che l'utente usa già per `gmail` e `jarvis` sulla VPS.
- **Calendar e Drive dalla VPS**: se servono, si fanno lato VPS con il progetto Google Cloud che l'utente ha già
  (le chiavi OAuth stanno in `.env.jarvis`), non dall'app.

Perché non OAuth nell'app (Credential Manager / Sign in with Google + API Gmail, Calendar, Drive):
- serve un progetto Google Cloud, la schermata di consenso, un client ID Android legato al pacchetto
  `com.jarvis.telefono` e all'impronta SHA-1 del certificato di firma, gli scope scelti uno per uno;
- in modalità **Testing**: al massimo 100 utenti di prova e autorizzazioni che scadono dopo **7 giorni** (si rifà
  l'accesso ogni settimana);
- in **produzione** senza verifica: schermata «app non verificata» e tetto di 100 utenti per gli scope sensibili;
- con la verifica: gli scope sensibili (Calendar, Drive) chiedono la revisione di Google; quelli «restricted»
  (lettura di Gmail, Drive intero) chiedono anche una valutazione di sicurezza annuale da un laboratorio esterno,
  a pagamento. Per un'app privata che passa di mano in mano il costo non torna.

Se un giorno servirà: progetto Google Cloud, schermata di consenso (tipo «Esterno», stato Testing per iniziare),
client ID OAuth di tipo Android con pacchetto e SHA-1 della firma (`apksigner verify --print-certs`), gli scope
minimi. Non è stato creato niente.

## 6. Cosa viaggia sulla rete

| Da → a | Cosa | Canale |
|---|---|---|
| telefono → server IMAP del gestore | utente e password (solo durante «Prova») | TLS (993) con controllo del certificato, o STARTTLS; mai in chiaro (in chiaro solo verso 127.0.0.1 nelle prove) |
| telefono → VPS | anteprima senza password; poi password o token una volta, dopo la conferma | `wss://` autenticato col token del ponte (l'app rifiuta `ws://` per i segreti) |
| telefono → api.anthropic.com / api.openai.com | la chiave, solo per «Prova la chiave» (`GET /v1/models`) | HTTPS |
| VPS → telefono | elenco caselle con «password sì/no», esiti, avvisi | `wss://`; mai valori |

Niente passa da GitHub o da cartelle sincronizzate nel cloud.

## 7. Le caselle: preset e prova

Preset (`cassaforte/PresetPosta.kt`): Gmail (imap.gmail.com:993, smtp.gmail.com:465, password per app), Outlook /
Hotmail / Live e Microsoft 365 (la password per IMAP non è più accettata dal 16/09/2024: serve OAuth, l'app lo dice),
iCloud (imap.mail.me.com:993, smtp.mail.me.com:587, password per app), Hostinger, Libero, Virgilio, Aruba,
PEC Aruba, PEC Legalmail (utente = USERID InfoCert), PEC Sicurezza Postale, PEC Register (server a mano),
Samsung Email (app del telefono: nessun server, nessuna password), Altro gestore.
`verificato = true` sui preset controllati su una fonte; gli altri li conferma la prova.

La prova (`cassaforte/ProvaImap.kt`): socket TLS + CAPABILITY, LOGIN, LIST, LOGOUT (nessuna libreria di posta:
qualche KB contro i ~700 KB di Jakarta Mail). Non legge né tocca mail. Dice in italiano: password sbagliata,
password per app richiesta, IMAP spento, accesso da confermare nel browser, OAuth richiesto, server inesistente,
rete o porta, TLS sbagliato, server che non parla IMAP. Trova anche le cartelle speciali (Inviata, Cestino, Bozze).

Limiti del Postino della VPS (`posta.py`, non toccato): legge solo con IMAP SSL (993), spedisce solo con SMTP SSL
(465), entra con l'indirizzo (non con un utente diverso). La VPS rifiuta o avvisa in questi casi.

## 8. Le schermate (0.4.0)

Codice in `app/src/main/java/com/jarvis/telefono/configura/`. Ogni pannello vive sia nella procedura guidata sia nella
sua pagina delle Impostazioni (scheda «Configurazione» in cima: Configurazione guidata, Cervello, Account mail, Google,
VPS, Sicurezza). Viste scritte in codice con i colori del tema Claude chiaro/scuro (`Mattoni.kt`), tocchi da 48 dp,
descrizioni per TalkBack, animazioni da 150-300 ms con `ViewPropertyAnimator` che si spengono con «Rimuovi animazioni»
(`Movimento.attive`). FLAG_SECURE su Cervello, VPS, Sicurezza e casella (spento solo nel pacchetto di prova `.prova`).
Le password: niente suggerimenti né apprendimento della tastiera (`IME_FLAG_NO_PERSONALIZED_LEARNING`), ma
`autofillHints` per il gestore di password (Passbolt). Girando il telefono la schermata non si ricrea (i campi restano).

**Procedura guidata** (`ConfigurazioneActivity`), cinque passi tutti saltabili, in alto «2/5», cinque segmenti
(verde fatto, terracotta adesso, grigio da fare) e «Chiudi»; in basso «Salta» e «Avanti» («Fine» all'ultimo).
- Parte da sola al primo avvio solo se la cassaforte è vuota e nessun passo è mai stato verde (`Passi.decidiAvvio`);
  se i dati vengono dalla migrazione di `config-boss.json` parte «ripresa» dal primo passo da fare, con i verdi già verdi.
  «Fine» o «Chiudi» = non riparte più da sola. Si riapre da Impostazioni → Configurazione guidata.
1. **Benvenuto**: JBoss grande, poi Microfono, Notifiche, Accessibilità «Guida →», Rubrica, Batteria «Escludi» (e Blocco
   schermo se manca). La guida dell'accessibilità dice cosa premere (Android 14+: «App installate» su Samsung o «Servizi
   installati», poi «JBoss», interruttore, «Consenti»; con «Impostazione con restrizioni»: Informazioni app → ⋮ →
   «Consenti impostazioni con restrizioni») e apre la pagina del servizio di JBoss (Android 13+) o quella generale.
   Al ritorno la riga si ricontrolla da sola. Verde = microfono, notifiche e accessibilità.
2. **Modelli vocali**: Whisper e impronta ✓/○, barra di avanzamento, «Scarica» (lo stesso scarico delle Impostazioni,
   `ImpostazioniActivity.scaricaDaFuori`, solo in Wi-Fi) e «Importa da cartella» (`Inventario.importaModelli`).
3. **Cervello** «Come pensa JBoss?»: (•) La mia VPS (codice QR) · ( ) Chiave API · ( ) Codex / OpenAI.
   VPS: stato dell'abbinamento, «Scansiona QR» / «Incolla», se la VPS ha già il token di Claude Code (`account_lista`),
   la guida di `claude setup-token`, il campo del token con «Prova» (forma) e «Invia alla VPS» (anteprima, conferma,
   impronta; poi la frase «il riavvio lo decidi tu»: l'app non riavvia niente). Chiave API: Anthropic/OpenAI, «Prova la
   chiave» (`GET /v1/models`), «Salva nel telefono». Codex: guida `codex login`, chiave OpenAI facoltativa verso la VPS.
   In fondo: «Il tuo abbonamento Claude non si condivide con altri».
4. **Account mail**: «+ Aggiungi casella», le caselle con ✓ (la legge il Postino) o ○ (solo nel telefono) e ⋮ (Modifica,
   Prova il collegamento, Invia al Postino sulla VPS, Elimina), la riga dei preset, quante caselle legge il Postino, Google.
5. **Prova guidata**: «JBoss in ascolto» (Accendi), poi «Hey Boss, che ore sono», «apri Spotify», «manda un WhatsApp a me
   con scritto prova» (Annulla): ognuna ○ → «● ti ascolto» → ✓ leggendo la cronologia (`ProvaGuidata`).

**Aggiungi casella** (`CasellaActivity`): 1 gestore (preset, scelto anche dal dominio dell'indirizzo) con la sua nota;
2 indirizzo e password (per Gmail e iCloud «Password per app» con la guida breve e il pulsante della pagina giusta);
3 server già compilati (IMAP con SSL/STARTTLS/nessuna, SMTP, utente); 4 «Prova il collegamento» (esito in italiano,
cartelle speciali trovate), «Salva», «Invia al Postino sulla VPS» (anteprima senza password → conferma → impronta o PIN
→ la password parte una volta).

**Google** (`PannelloGoogle`): detto onestamente, niente OAuth e nessuna password di Google. L'account del telefono si
sceglie con la finestra di Android (a JBoss arriva solo il nome), «Vedi gli account del telefono», i pulsanti Gmail,
Calendar, Drive, Google per le cose già presenti, «Aggiungi Gmail con la password per app».

**VPS** (`PannelloVps`): indirizzo, token mascherato («Mostra il token» con impronta), stato del collegamento in tempo
reale, interruttore del modulo, «Prova il collegamento» (quante caselle legge il Postino, token Claude Code e chiave
OpenAI presenti sì/no), «Scansiona QR» / «Incolla», «Togli la VPS da questo telefono».

**Sicurezza** (`PannelloSicurezza`): blocco schermo, l'elenco dei segreti (nome e genere, «segreto presente», mai il
valore) con «Mostra» (impronta o PIN, 30 secondi, poi si nasconde) e «Cancella», «Esporta cifrato» (frase due volte,
file scelto da te), «Importa», «Svuota la cassaforte», registro degli accessi (`RegistroAccessi`: data, azione, nome
della voce; mai valori; ultime 200 righe in `filesDir/registro-accessi.json`).

## 9. Abbinamento con codice QR

1. Sul Mac: `python3 scripts/genera-qr-vps.py` (`--verifica` rilegge il PNG col lettore di macOS). Legge il token da
   `~/.env.jarvis` senza stamparlo e l'indirizzo da `config-boss.json` (`vps_url`) o `--url`; chiede alla VPS un codice
   (`abbina_crea`) e mostra il QR `jboss-vps:1?u=<wss://…/phone>&c=<codice>` nel terminale e in un PNG, più il testo per «Incolla».
2. Nell'app: «Scansiona QR» usa il lettore di Google Play Services (`play-services-code-scanner`): nessun permesso
   fotocamera per JBoss, la prima volta Play Services può scaricare il lettore («riprova tra un minuto»). «Incolla» accetta
   il testo del QR o indirizzo e codice (anche «abcd-2345»).
3. L'app apre `/phone`, manda `{type:"abbina", codice}`, riceve il token del ponte e lo mette nella cassaforte; il modulo
   VPS si accende. Codice: 8 caratteri, 5 minuti, un uso, un tentativo sbagliato ogni 3 s, chiuso dopo 5 errori.
   Lato VPS `jarvis-agent/server/abbina.js` (rollback in `<repo Jarvis>/vps/patch-qr/LEGGIMI.md`); protocollo in `PROTOCOLLO-VPS.md` §9.

## 10. Pacchetto di prova

`./gradlew :app:assembleProva` fa `com.jarvis.telefono.prova` («JBoss prova», FLAG_SECURE spento per gli screenshot):
si installa accanto a JBoss vero con una cassaforte sua, vuota. Su Samsung due accessibilità non stanno insieme: quella
del pacchetto di prova NON si accende, e il microfono non si concede (una sola app in ascolto).

## 11. Screenshot di riferimento

Descritti qui, i file restano fuori dal repo (scratchpad dell'agente).

Giro del 2026-10-07 sera sull'emulatore Android 16 (`jp36`, pacchetto `.prova`, cassaforte vuota) e sul pacchetto vero:
- `e02` Benvenuto 1/5: JBoss grande, sei righe ◯ con Concedi / Guida → / Escludi / Imposta; avanzamento col primo segmento terracotta.
- `e05` dopo «Concedi» le notifiche: ✓ verde con un piccolo scatto, pulsante «Fatto» spento.
- `e07` la guida dell'accessibilità (due schermate, impostazione con restrizioni); `e09` la pagina di Android che si apre.
- `e11` Modelli 2/5: le due righe, barra, Scarica / Importa da cartella, esito rosso «nella cartella non ci sono file…».
- `e12` Cervello 3/5: le tre scelte; `e13` il lettore QR di Play Services aperto; `e23` dopo «Incolla» con un codice vero:
  «Abbinata a jarvis-agent…», ✓ VPS e «La tua VPS ha già il token di Claude Code».
- `e25` token di forma sbagliata: «Così non va: …»; `e27` anteprima della VPS «Sostituire il token sulla VPS?» (annullata, `.env.jarvis` non toccato).
- `e29` Account mail 4/5 con «Il Postino sulla VPS legge 7 caselle»; `e30` Aggiungi casella (chip dei gestori, guida password per app).
- `e36` prova con SSL su un server in chiaro: messaggio TLS; `e37` password sbagliata: «Indirizzo o password sbagliati»;
  `e39` password giusta: «Salvata nel telefono, collegamento provato»; `e41` la VPS rifiuta l'anteprima: «manca il server SMTP».
- `e43` Google; `e44` la scelta dell'account di Android. `e46` Prova guidata 5/5 con «Fine».
- `e50` Impostazioni con la scheda Configurazione; `e52` VPS: «Collegata. Sulla VPS: 7 caselle…, token di Claude Code presente».
- `e56` Sicurezza (righe a due piani); `e58` «Mostra» dopo il PIN (password finta, si chiude in 30 s).
- `e63` tema scuro; `e64` orizzontale. `v03` pacchetto vero sul passo Cervello: schermo nero (FLAG_SECURE), `v04` passo Modelli visibile.
