# La VPS per JBoss (facoltativa)

JBoss funziona senza VPS. Questa guida serve solo se vuoi il Postino e i lavori lunghi.

## Cosa aggiunge

Una VPS è un computer sempre acceso su internet, affittato a mese. Sopra ci gira **Jarvis** (il progetto per
computer, distribuito a parte, con la sua guida di installazione). Con la VPS collegata JBoss può:

- **Postino**: leggere le tue caselle di posta, darti il resoconto numerato, preparare bozze, cestinare e archiviare.
  Le mail le legge la VPS, non il telefono.
- **Lavori lunghi**: ricerche, riassunti, compiti che durano minuti. Il telefono li manda e riceve i passi; la
  schermata «Lavori sulla VPS» mostra cosa succede.
- **Cervello per le frasi difficili**: quello che le regole del telefono non capiscono va a un modello sulla VPS,
  che risponde con azioni che il telefono esegue (con le stesse conferme).
- **Memoria condivisa** con la webapp Jarvis sul computer.

Ogni invio, cancellazione o comando delicato sulla VPS chiede il tuo sì sul telefono.

## Quanto costa

- La VPS: da pochi euro al mese per un piano piccolo (2 processori e 4-8 GB di memoria bastano). Il prezzo lo decide
  il fornitore e cambia nel tempo: guardalo sulla sua pagina prima di comprare.
- Il modello di intelligenza artificiale: il tuo abbonamento (per esempio Claude Pro o Max con Claude Code) o una
  chiave API a consumo. L'abbonamento è personale: ognuno usa il suo, sulla sua VPS.
- JBoss e Jarvis: gratis.

## Cosa serve

- Una VPS con Linux (Ubuntu 24.04 va bene) e accesso SSH.
- Un nome di dominio o un sottodominio che punti alla VPS (serve per il collegamento sicuro `wss://`).
- Un computer per l'installazione (Mac, Windows o Linux).
- Circa un'ora la prima volta.

## Come prenderla

1. Apri la pagina dei piani VPS. Questo è un **link di affiliazione**: se compri da qui, chi ha scritto JBoss riceve
   una commissione dal fornitore; per te il prezzo è lo stesso. Puoi anche usare qualunque altro fornitore.
   <!--VPS-->https://www.hostinger.com/it/prezzi?REFERRALCODE=ITALOMARZIANO<!--/VPS-->
2. Scegli un piano VPS (non «hosting web»): 2 vCPU, almeno 4 GB di RAM, sistema Ubuntu 24.04.
3. Alla fine del pagamento il pannello ti dà l'indirizzo IP e la password di root (o ti fa caricare una chiave SSH).
   Tienili per te: non scriverli in chat, email o file condivisi.
4. Nel pannello del dominio crea un record `A` (per esempio `jarvis-agent.tuodominio.it`) che punta all'IP della VPS.

## Installare Jarvis sulla VPS

Segui la guida del progetto Jarvis (installazione con Claude Code: fa le domande all'inizio e prepara tutto).
Alla fine sulla VPS gira il servizio `jarvis-agent`, raggiungibile a `wss://jarvis-agent.tuodominio.it/phone`.

## Collegare JBoss

1. Sul computer, nella cartella di questo repository:
   `python3 scripts/genera-qr-vps.py --url wss://jarvis-agent.tuodominio.it/phone`
   Lo script legge il token del ponte dal file dei segreti di Jarvis senza stamparlo, chiede alla VPS un codice
   valido 5 minuti e mostra un codice QR (nel terminale e in un file PNG).
2. Su JBoss: Impostazioni → **Collegamento Jarvis** → «Scansiona QR». Senza servizi Google: «Incolla» e scrivi
   indirizzo e codice che vedi sotto il QR.
3. Il telefono riceve il token e lo mette nella cassaforte cifrata. Accendi «Collegamento Jarvis acceso» e tocca
   «Prova il collegamento».
4. Posta: Impostazioni → Postino e mail → aggiungi una casella (Gmail, Outlook, iCloud, Libero, Aruba, PEC, altro),
   metti la password per app, **Prova**, poi «Invia al Postino». La password va alla VPS solo dopo la tua impronta.

Il QR contiene solo l'indirizzo e un codice usa-e-getta, mai il token. Il token non compare mai sullo schermo
(«Mostra il token» chiede l'impronta).

## Togliere la VPS

Impostazioni → Collegamento Jarvis → spegni l'interruttore: l'app non apre più nessun collegamento. Per cancellare
indirizzo e token: «Togli la VPS da questo telefono». Le caselle mandate alla VPS si tolgono dalla loro schermata.

Dettagli tecnici: [MODULO-VPS.md](MODULO-VPS.md), [COLLEGAMENTO-JARVIS.md](COLLEGAMENTO-JARVIS.md),
[PROTOCOLLO-VPS.md](PROTOCOLLO-VPS.md), [CONFIGURAZIONE.md](CONFIGURAZIONE.md).
