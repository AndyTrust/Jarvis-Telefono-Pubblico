# Checklist di non regressione: JBoss (ex Jarvis Telefono)

Ricavata da «Elenco funzioni app Android 1.2.3» (note interne, 2026-10-07) tenendo solo le funzioni locali. Le righe del ponte, del
sito, delle notifiche e degli aggiornamenti dal server non esistono in questa app. A ogni versione: una spunta per riga, sul
S24 vero; in cima data, versione, esito. JVM = `bash scripts/prove-jvm.sh`; ADB = `bash scripts/prove-telefono.sh` o il banco;
gesto = a mano. Una riga rossa blocca la versione.

Ultimo giro: 0.6.3 (codice 15, app unica: ui-approvata + sveglia-fcm + postino-completo + integra-jarvis fino a 6389fcc),
2026-10-08 13:45, JVM 554 eseguite, 0 rotte, 2 saltate; `:app:assembleRelease` verde, APK firmato con la chiave di sempre.
Sul telefono non provata come 0.6.3: c'era già la prova 0.6.5-integra (codice 17, stesso codice salvo il numero di versione),
installare il codice 15 sopra il 17 sarebbe un ritorno indietro. Le righe sotto restano da spuntare sul S24.
VPS (`/opt/jarvis-agent`, carico 80-89): sveglia 15/15, ponte sveglia 3/3, account 14/14; lavori 7 OK + 10 annullati
nel giro intero, uno per uno 9 su 10 verdi («coda e tetto» rosso per tempi sotto carico); sul Mac con gli stessi file 53/53.

Giro prima: 0.3.0 (codice 4, JBoss con modulo VPS e Postino a numeri), 2026-10-07 21:10, JVM 341 eseguite, 0 rotte,
1 saltata; `prove-telefono.sh` 26 OK, 0 KO (vedi in fondo). Giro prima: 0.2.0 (codice 3), JVM 313, 22 OK e 2 KO. Giro precedente: 0.1.1 (codice 2), JVM 245/245, provate R01, R06, R07, M06.

| Esito | ID | Cosa deve succedere | Come |
|---|---|---|---|
| [ ] | V01 | 0.6.0: «Hey Boss» e «Hey JBoss» attivano (due note + bolla «Ti ascolto»); «Hey Jarvis» e «Jarvis» NON attivano | gesto, 3 prove ciascuna; banco `scripts/banco-parole.py` verde |
| [ ] | V02 | A telefono fermo la batteria non scende più del normale | `dumpsys batterystats` dopo 30 min |
| [ ] | V03 | 10 s di silenzio dopo la parola: «non ho sentito niente», di nuovo in attesa | ADB `ascolta_prova` |
| [ ] | V05 | Una frase di 5 s trascritta giusta, bolla «Ho sentito «…»» | gesto |
| [ ] | V06 | Un termine del glossario integrato viene corretto | JVM `GlossarioTest` |
| [ ] | V07 | Filtro impronta spento: la voce di un altro passa | gesto + JVM `ImprontaTest` |
| [ ] | V08 | Due frasi di fila senza toccare il telefono | gesto |
| [ ] | V09 | Detta nel campo di testo: testo parziale mentre si parla; con rete spenta ripiego Whisper | gesto |
| [ ] | V10 | La risposta si sente con la voce di sistema in italiano | gesto |
| [ ] | V11 | La voce non legge comandi né percorsi | JVM `PerLaVoceTest` |
| [ ] | V12 | Si sentono i tre segnali e vibra; volume dei segnali dalla configurazione | gesto |
| [ ] | V16 | Un solo microfono: durante Detta la parola non scatta, dopo riparte | gesto |
| [ ] | V17 | Fine frase 1,5 s (2,5 s dopo 4 s di parlato) | JVM `MotoreTest` + gesto |
| [ ] | M01 | `apri_app` apre WhatsApp, Gmail, Chrome, Maps, Impostazioni | ADB |
| [ ] | M02 | `elenca_app` torna più di 200 app | ADB |
| [ ] | M03 | `componi` apre la bozza per whatsapp, mail, sms, mappe, calendario, link (nessun invio) | ADB |
| [ ] | M04/M05 | `cerca_google` e `gemini_chiedi` tornano uno schermo con la risposta | ADB |
| [ ] | M06 | `cerca_in_app` in WhatsApp, Gmail, Maps, YouTube | ADB |
| [ ] | M07 | `cerca_contatto` trova un contatto noto | ADB |
| [ ] | M08 | `read_screen` numera gli elementi su 5 app | ADB |
| [ ] | M09/M10/M12 | tocca per numero, scorri, HOME e BACK | ADB |
| [ ] | M11 | `scrivi` in un campo di Keep o nelle note | ADB |
| [ ] | M14 | `screenshot` torna JPEG non nero | ADB |
| [ ] | M15 | `compila_accesso` senza password nel log | ADB + `grep` logcat |
| [ ] | M16 | `registro` mostra le ultime azioni senza testi sensibili | ADB |
| [ ] | M19 | Gmail in composizione espone il campo di scrittura (`isAccessibilityTool`) | ADB |
| [ ] | S01 | «Invia» senza conferma rifiutato: «BLOCCATO DAL TELEFONO» | ADB + JVM `ManiTest` |
| [ ] | S02 | Pannello con SOLO «Annulla» e «Invia»; notifica con gli stessi due; Annulla non manda | gesto + ADB |
| [ ] | S03 | «annulla» e «invia» a voce risolvono la bozza | ADB `risposta_di_boss` |
| [ ] | S05 | Bozza non confermata: scade a 2 minuti, Jarvis lo dice | gesto |
| [ ] | S06 | Annulla (o il riquadro della tendina) rifiuta le azioni di quella richiesta, la richiesta dopo riparte | gesto + ADB `emergenza` |
| [ ] | S09 | Nel logcat niente frasi dette, nomi dei contatti, password | `adb logcat -d \| grep` |
| [ ] | R00 | Frase fuori dalle regole: «Non ho capito» a voce e nella bolla | ADB `frase` |
| [ ] | R01 | «Jarvis, apri Spotify»: Spotify davanti, «Ho aperto Spotify» | ADB `voce_prova` o `frase` + `misura` |
| [ ] | R02 | «Manda un WhatsApp a <contatto>: prova» → bozza, pannello, «annulla», niente inviato | gesto |
| [ ] | R03 | Nome ambiguo o senza numero: nessuna azione, Jarvis lo dice | gesto |
| [ ] | R04 | Rubrica non concessa: Jarvis chiede il permesso, non inventa | gesto |
| [ ] | R05 | Le regole sulle 50 frasi di prova | JVM `CervelloRegoleTest` |
| [ ] | R07 | «Manda una mail a me stesso con oggetto X: testo» → Samsung Email (Gmail solo se detto), corpo una volta sola, pannello | ADB `frase` + gesto |
| [ ] | R06 | «Manda un WhatsApp a me stesso: prova» → chat con se stessi aperta col numero `whatsapp_me` della configurazione, bozza, pannello; senza numero Jarvis lo dice | ADB `frase` + gesto |
| [ ] | U06 | Home 0.2.0: Jarvis e Postino titolari, cronologia grande con filtri Tutto · Jarvis · Postino e ricerca, campo, Detta, Parla, Invia; Spegni nei dettagli dello stato | gesto |
| [ ] | U07 | La bolla appare, si sposta, ricorda la posizione, sparisce 3 s dopo la voce | gesto + JVM `BollaTest` |
| [ ] | U08 | (0.2.0) Niente tasto flottante né permesso «sopra le altre app»; «Jarvis» sempre in ascolto | JVM `HomeTest` + ADB |
| [ ] | U09 | Tasto laterale lungo apre Jarvis come assistente | gesto |
| [ ] | U10 | Tema esistente su tutte le pagine native | gesto |
| [ ] | U11 | Notifica del servizio con Ascolta e Spegni | gesto |
| [ ] | U13 | La cronologia resta dopo il riavvio dell'app (anche dopo l'aggiornamento a 0.2.0: colonna `agente_esecutore`) | gesto |
| [ ] | A01 | Chip «Mani: …» a fianco di Jarvis solo mentre un agente lavora, poi esce in dissolvenza | screenrecord + JVM `HomeTest` |
| [ ] | A02 | Bolla «Jarvis → agente» con i due avatar; Jarvis da solo quando agisce lui | ADB `frase` + JVM `CompetenzeTest` |
| [ ] | A03 | Cronologia: «Jarvis → agente · ora», esito, tocco = dettaglio; filtri e ricerca | gesto + JVM `HomeTest` |
| [ ] | A04 | Menu Agenti: scheda Capo in cima, 5 schede, autonomia «vai da solo» bloccata per Mani e Social | gesto + JVM `AgentiTest` |
| [ ] | A05 | Tocco su una scheda (o sul Postino in Home) apre la chat dell'agente; avviso onesto in cima | gesto |
| [ ] | A06 | Avatar animati (respiro, ascolto, lavoro, luccichio); fermi con «Rimuovi animazioni» e a schermo spento | gesto |
| [ ] | A07 | Tema chiaro e scuro (segue il sistema), contrasto AA | gesto + JVM `TemaTest` |
| [x] | A08 | Monitor → «Lavori sulla VPS» → Terminale: pieno schermo, rotazione, tastiera, conferma Invia/Annulla sopra | gesto |
| [x] | P01 | Postino: «controlla la posta» in sola lettura, resoconto numerato, filtri, pillola «N nuove» in Home | gesto |
| [ ] | P02 | Postino: azioni per numero solo su caselle finte o `jarvis`; un invio vero solo all'utente stesso, dichiarato | gesto + `prova_postino_numeri.py` |
| [x] | L01 | Lavoro lungo dall'app («sulla VPS …»): chip, cronologia «in corso», eventi dal vivo, notifica a fine lavoro | gesto |
| [x] | L02 | Modulo spento e riacceso durante un lavoro: il log riprende senza buchi | ADB `ConfiguraVpsReceiver` spegni/accendi |
| [ ] | V01b | «Hey Boss» e «Hey JBoss» 3 volte ciascuna; «Ok boss lo faccio io» non deve scattare | gesto (l'utente) |
| [ ] | A09 | Icona Jarvis nel launcher e nei Recenti (adattiva, monocromatica) | screenshot |
| [ ] | I02 | Su telefono pulito i permessi si chiedono in fila; prima apertura accende Jarvis | gesto |
| [ ] | I03 | Modelli: scarica, riprende, controlla la dimensione | gesto (Wi-Fi) |
| [ ] | I06 | Dopo il riavvio arriva la notifica o parte il servizio | gesto |
| [ ] | I09 | `configura-boss.sh` scrive la configurazione; Impostazioni la mostrano; `--togli` torna di fabbrica | ADB + gesto |
| [ ] | C01 | Telefono pulito (o `.prova`): la procedura guidata parte da sola; con dati migrati parte «ripresa» con i verdi; dopo «Fine»/«Chiudi» non riparte | gesto |
| [ ] | C02 | Passo 1: ogni «Concedi» apre la richiesta giusta; «Guida →» spiega le due schermate e apre l'accessibilità; al ritorno ✓ | gesto |
| [ ] | C03 | Passo 2: «Scarica» muove la barra (solo Wi-Fi), «Importa da cartella» dice l'esito in italiano | gesto |
| [ ] | C04 | Abbinamento: `genera-qr-vps.py`, «Scansiona QR» (o «Incolla») → «Abbinata», modulo VPS acceso, token mai a schermo | gesto + `prova_abbina.mjs` |
| [ ] | C05 | Casella: preset, prova contro un server sbagliato e uno giusto (messaggi italiani), «Invia al Postino» solo dopo anteprima e impronta | gesto |
| [ ] | C06 | Sicurezza: «Mostra» chiede impronta o PIN e si nasconde in 30 s; esporta/importa con la frase; registro senza valori | gesto |
| [ ] | C07 | Schermate con segreti: screenshot nero (FLAG_SECURE) nell'APK vero | `adb exec-out screencap` |
| [ ] | C08 | Tema scuro, rotazione e tastiera: nessun campo coperto né tagliato; «Rimuovi animazioni» = niente animazioni | gesto |
| [ ] | C09 | JVM: `AbbinamentoTest`, `PassiTest`, `CampiTest`, `ManifestTest` | JVM |
| [ ] | TUTTO | Prove JVM verdi e `adb logcat -b crash -d` vuoto dopo 10 minuti d'uso | comando |

## Esito delle prove sul telefono del 2026-10-07

- Installata accanto a com.jarvis.app 1.2.4, permessi da script, configurazione dell'utente scritta.
- R00: «frase senza senso» → non capito in 19 ms. «che ore sono» → risposta in 207 ms.
- R01 / M01: «Jarvis, apri Spotify» (banco `frase`, senza microfono né Whisper) → Spotify in primo piano, 2,18 s dalla frase
  alla fine dell'azione (lettura dello schermo compresa).
- WhatsApp a se stessi: fallito senza effetti (il campo di ricerca di WhatsApp non è stato trovato, niente scritto né inviato).
  Da rifare con un contatto della rubrica o con il numero dell'utente nella configurazione.
- Nessun crash. Accessibilità rimessa com'era alla fine.

## Esito delle prove sul telefono del 2026-10-07, 0.1.1

- Causa del fallimento della 0.1.0: «a me stesso» passava da `cerca_in_app` con il nome della chat. WhatsApp si apriva
  sull'ultima chat con la tastiera emoji aperta, e il testo finiva nella barra «cerca emoji e sticker» (`#search_bar`);
  la chat con se stessi poi non si trovava (la ricerca di WhatsApp non la elenca nemmeno per nome).
- 0.1.1: «a me stesso / a me / sulla mia chat / mandami un WhatsApp» aprono `wa.me/<whatsapp_me>?text=…` (numero solo nella
  configurazione personale fuori da git). `cerca_in_app` dentro una chat torna alla lista prima di cercare (provato).
- R06: frase → pannello 2,35 / 2,25 / 2,57 s (mediana 2,35 s), tre volte ANNULLA, niente inviato.
  Riferimento «apri Spotify»: 4,34 (a freddo) / 1,19 / 1,35 s (mediana 1,35 s).
- Invio reale a se stessi con il tocco su «Invia» del pannello: «Prova Jarvis Telefono» partito, campo svuotato.
- Nessun crash (`logcat -b crash`), nel log dell'app né il numero né il testo.
- R07 (Samsung Email, posta predefinita dal 07/10; Gmail solo se nominato): frase → pannello 1,73 / 1,80 / 1,85 s
  (mediana 1,80 s), tre volte ANNULLA. Invio reale a se stessi col tocco su «Invia»: «Prova Jarvis Telefono» in Inviate alle 19:17.
- Trappole Samsung Email: il corpo arrivava due volte (mailto e EXTRA_TEXT: ora solo mailto); «Destinatario già aggiunto»
  (innocuo); uscire con HOME salva la bozza da sola (va chiusa con X → Scarta); `uiautomator dump` stacca il servizio di
  accessibilità e fa sparire il pannello: durante le prove si usa `read_screen` dell'app e lo screenshot, mai uiautomator.

## Esito delle prove sul telefono del 2026-10-07, 0.2.0

- `prove-telefono.sh` (20:46, build finale «JBoss»): 22 OK, 2 KO; al giro delle 20:30 erano 21 OK e 3 KO:
  - M01 «Samsung Email»: sul S24 l'app si chiama «E-mail»; la prova è entrata con la 0.1.1, la risoluzione dei nomi
    (`AppTelefono`, `CatalogoApp`) non è cambiata. Da decidere: alias «Samsung Email» → `com.samsung.android.email.provider`.
  - S06: l'app rifiuta davvero l'azione dopo «Ferma» («l'utente ha annullato…»); lo script cercava «fermato» (parola della
    1.2.3). Script corretto per accettare anche «annullato».
  - V03: alle 20:08 «manca il modello della voce» (Whisper non scaricato sul telefono); alle 20:46 dopo «Ti ascolto»
    nessun evento in 25 s (rumore continuo in stanza). Il codice dell'ascolto non è cambiato; resta da riprovare in silenzio.
- Visto a schermo (screenshot nello scratchpad `ui02/`): Home chiara e scura, chip «Ricercatore: Cerco su Google…» a fianco
  di Jarvis, bolla «Jarvis → Ricercatore» con i due avatar, menu Agenti (scheda Capo), chat del Postino, spazio VPS a pieno
  schermo con «Prova la conferma» (Invia e Annulla cliccabili sopra, log `JarvisPienoSchermo`), rotazione, tastiera,
  icona nel cassetto e nei Recenti. Respiro di Jarvis: registrazione dello schermo, variazione periodica ogni 2,4 s.
- Misure: APK 43.202.401 → 43.545.856 byte (+343.455, +0,79%); avvio della Home 95-157 ms (0.1.1: 69-246 ms, processo già
  vivo per il servizio); frame persi sulla Home 0,20% su 986 fotogrammi; PSS 168-170 MB contro 147,8 della 0.1.1
  (Java heap +9 MB, causa non cercata).
- Trovato e corretto durante il giro: `AgenteChatActivity` mancava nel Manifest (crash al tocco); ora `ManifestTest`
  controlla ogni Activity.

## Esito delle prove sul telefono del 2026-10-07, 0.3.0

- `prove-telefono.sh` (21:10): 26 OK, 0 KO. M01 ora prova «Samsung Email», «E-mail» e «posta»: tutte e tre aprono
  `com.samsung.android.email.provider`. V03 in silenzio: «Non ho sentito niente» in 11 s.
- V03, la causa vera del KO della 0.2.0: con suono in stanza il VAD sente parlato, la frase si chiude al silenzio (19,6 s
  alle 21:07) e poi «frase catturata ma Whisper non è scaricato: non la trascrivo» → bolla «manca il modello della voce».
  Il comando a voce (dopo la parola) passa SOLO da Whisper: il dettato di Google serve al tasto Detta, non alla voce.
  Whisper (circa 415 MB) si scarica da Impostazioni → Modelli, in Wi-Fi.
- Modulo VPS in produzione dal telefono: resoconto Postino reale (sola lettura, 7 caselle, 7 mail) numeri sul telefono
  in 3,2 s, riassunti completi in 29,8 s; ricerca «Node.js LTS» sulla VPS 48 s con modulo spento a 15 s e riacceso a 27 s,
  log completo fino a FINE, notifica a fine lavoro; pieno schermo, rotazione, tastiera; conferma sopra il pieno schermo
  (in orizzontale) accettata due volte con un tocco a mano dal telefono (file `/root/jarvis/prova-jboss-030-*.txt`).
- Misure: APK 43.666.230 byte (+120.374 sulla 0.2.0); frame persi 1,48% su 3.784 fotogrammi (gfxinfo dopo le prove).
