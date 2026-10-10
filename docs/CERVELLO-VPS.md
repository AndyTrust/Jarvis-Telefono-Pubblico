# Cervello della VPS per le frasi che le regole non capiscono (JBoss 0.3.4)

2026-10-07 · agente jboss-cervello · ramo `cervello-vps`

l'utente, 07/10: la voce «funziona malissimo», le regole non bastano. Da 0.3.4 una frase che il cervello a regole non
capisce (o che è «complessa») va al cervello della VPS: `jarvis-agent`, Sonnet sempre vivo, contesto a grafo,
27 strumenti del telefono. La VPS decide e chiede le azioni una alla volta; il telefono le fa con le sue mani,
con la conferma Invia/Annulla imposta dal telefono come prima.

## La catena (`nucleo/CervelloCatena.kt`)

```
frase → regole (millisecondi, sul telefono)
  capita e semplice                         → piano delle regole, nessuna rete (come la 0.3.3)
  NON capita, «complessa» o piano debole     → modulo VPS acceso e rete?
      sì → CervelloVps: user_message → tool_call* (mani del telefono) → assistant_message
           la VPS tace entro 15 s e non ha fatto niente → piano delle regole se c'era, altrimenti «non ho capito» + perché
           la VPS ha già fatto qualcosa e poi tace       → errore onesto (le azioni non si rifanno)
      no → piano delle regole o «Non ho capito» onesto (con «senza rete» se il modulo è acceso)
```

- **Complessa** (`Complessita.complessa`): più passi («e poi», «e dimmi», «e aprimi»…), una risposta da leggere
  («dimmi il primo risultato», «quanto costa», «che tempo fa», «vicino a me», «cosa c'è»), sveglie e promemoria.
- **Piano debole** (`Complessita.pianoDebole`): le regole hanno capito a metà. Un'app con il resto della frase nel
  nome («YouTube e cerca un video…»), una domanda di chiarimento senza azioni («A chi lo mando?»), un «non lo so ancora fare».
- **l'utente parla mentre la VPS lavora**: la richiesta vecchia si ferma; «basta», «stop», «lascia perdere» rispondono
  «Fermato». La risposta tardiva della VPS alla frase vecchia si scarta e i suoi strumenti tornano con un errore.
- Esempio: «cerca su Google quanto costa un biglietto per Londra e dimmi il primo risultato». Le regole aprirebbero
  solo Google. La frase è complessa, quindi va alla VPS: `cerca_google`, `scorri`, `read_screen`, poi la VPS dice il
  prezzo. In 12,5 s, con la prima azione a 2,3 s.

## Il canale «mani» (`vps/CanaleMani.kt`, `vps/ProtocolloMani.kt`, `vps/ManiVps.kt`)

Protocollo dell'app 1.2.x verso `jarvis-agent` (`server/index.js`, `phoneBridge.js`, `phoneTools.js`), invariato lato VPS:

| Verso | Messaggio |
|---|---|
| telefono → VPS | `auth {token, ruolo:"mani", versione:1}` (la VPS tratta come telefono ogni auth senza `ruolo:"lavori"`) |
| telefono → VPS | `user_message {text}` · `tool_result {id, result \| error}` |
| VPS → telefono | `connected` · `tool_call {id, command:{action,…}}` · `assistant_message {text, error?}` · `risposta_conferma {testo}` |

- Stesso interruttore, indirizzo e token del modulo VPS (cassaforte, `ConfigVps`): nessun segreto nel codice o nei log.
- **Batteria**: il canale si apre solo quando una frase va alla VPS, resta aperto 3 minuti per la frase dopo
  (ping ogni 25 s) e poi si chiude. Se cade a riposo non si riapre da solo. Nessun ciclo di riconnessione.
  Modulo spento = canale chiuso subito.
- **Un solo telefono**: prima di collegarsi legge `https://<ponte>/health`. Con `phoneConnected:true` (l'app vecchia
  `com.jarvis.app` collegata) non si collega e lo dice. Se un altro telefono gli prende il posto (chiusura 4000) lo
  dice, senza riprovare da solo.
- Tempi: il primo segno di vita della VPS entro 15 s (strumento o risposta); fra un segno e l'altro 60 s (il tempo
  delle mani non conta); tetto 5 minuti (la bozza aspetta l'utente fino a 2).
- Una `tool_call` fuori da una frase di JBoss (per esempio dalla chat del sito) torna con un errore: JBoss esegue solo
  le azioni delle sue frasi. Una `assistant_message` senza frase si ignora.

## Gli strumenti (`vps/MappaStrumenti.kt`)

I 27 strumenti di `phoneTools.js` arrivano già con l'`action` che `PhoneActionExecutor.handle` conosce: i campi
sono identici e non serve un adattatore. `registro_azioni` arriva come `registro`. Un'azione fuori mappa torna
alla VPS come errore («non esiste su JBoss: non ho fatto niente») e non arriva mai alle mani. `invia_bozza` e
`request_send_confirmation` passano dal pannello Invia/Annulla del telefono come prima. «invia» o «annulla» detti a
voce restano sul telefono (`PhoneActionExecutor.rispostaDiBoss`).

## Bolla e cronologia

- Bolla: «JBoss (VPS): Ci penso…», poi per ogni strumento «JBoss (VPS) → Mani: Apro WhatsApp…», infine
  «JBoss (VPS) → Mani: Fatto».
- Cronologia: «JBoss (VPS) → Mani · ✓ eseguito», cervello `vps`.
- Logcat (`JarvisNucleo`, `JarvisMani`): solo nomi delle azioni e tempi
  (`misura: vps esito=risposta collegamento_ms primo_strumento_ms risposta_ms strumenti`), mai il testo detto.

## Misure sul telefono vero (S24 Ultra, 2026-10-07 23:08-23:20)

| Frase | Prima azione | Risposta | Strumenti |
|---|---|---|---|
| che ore sono (regole) | – | 0,07 s | – |
| cosa c'è in calendario domani | 4,9 s | 29,4 s | 8 (calendario aperto, giorno 8 letto) |
| cerca su Google quanto costa un biglietto per Londra e dimmi il primo risultato | 2,3 s | 12,5 s | cerca_google, scorri, read_screen |
| che tempo fa domani a Cagliari | 2,7 s | 15,6 s | cerca_google |
| quanto manca a Natale | – | 3,5 s | nessuno |
| apri YouTube e cerca un video su come fare la pizza | 2,5 s | 14,2 s | apri_app, cerca_in_app |
| trovami un ristorante di pesce vicino a me e aprimi le indicazioni | circa 3 s | 75,4 s | 21 (Maps, indicazioni aperte) |
| apri la fotocamera e fai un autoscatto | 3,0 s | 11,3 s | apri_app, tocca ×2 (foto salvata) |
| mandami su WhatsApp la lista della spesa | 4,2 s | 11,2 s | componi (errore), cerca_contatto: chiede il numero |
| dimmi quali app ho usato di recente | 2,7 s | 8,7 s | elenca_app |
| apri Chrome, Gazzetta dello Sport, dimmi il titolo principale | 2,6 s | 15,1 s | componi link, attendi, read_screen |

Mediana sulle 10 frasi della VPS: prima azione 2,7 s, risposta 13,4 s. Regole: 0,07-0,09 s. Collegamento a freddo
0,45-0,48 s, poi 0 ms finché il canale resta aperto. In più: meteo + WhatsApp a me stesso con conferma, 38,3 s
compresi 12 s di attesa del «invia»; la bozza è partita e la verifica dice «partita» (unico invio, dichiarato).
Modulo spento: «non ho capito» in 0,09 s, nessun collegamento.

## Limiti noti

- La VPS non manda testo a pezzi: la bolla mostra gli strumenti mentre lavora, la risposta arriva intera alla fine.
  Per il testo a pezzi serve un messaggio nuovo lato VPS (`assistant_partial` da `claude.js`). Oggi non c'è.
- «a me stesso»: la VPS non conosce il numero WhatsApp dell'utente della configurazione del telefono e lo chiede.
- La sessione della VPS è una sola per telefono e sito: una `assistant_message` della chat del sito che arriva mentre
  JBoss aspetta la sua verrebbe presa per sua (raro: il sito e JBoss in contemporanea).
- I risultati degli strumenti finiscono nel log del ponte (`[tool] <-`), come con l'app 1.2.x: anche i nomi della
  rubrica letti da `cerca_contatto`.

## Prove

- JVM: `bash scripts/prove-jvm.sh --tests '*CervelloVpsTest'`. Sono 16 prove con un finto ponte (MockWebServer
  `/health` + `/phone`). Coprono la mappa dei 27 strumenti, il protocollo, la complessità, le regole prima, il modulo
  spento, la mancanza di rete, la VPS con le mani, la frase complessa, il timeout con ritorno alle regole, un solo
  telefono, il posto preso da un altro, il token rifiutato, lo strumento sconosciuto, la conferma, l'utente che cambia
  idea, lo strumento fuori frase e le frasi dell'utente.
- Telefono: `adb shell am broadcast -n com.jarvis.telefono/.mani.ProvaAdbReceiver --es comando '{"action":"voce_prova","testo":"…"}'`
  e `logcat -s JarvisNucleo JarvisMani` per le misure.
