# Usare JBoss tutti i giorni

JBoss è l'app sul telefono. Jarvis è la webapp sul computer (e sulla VPS): sono due cose diverse che possono
parlarsi, ma JBoss funziona anche da sola.

## Come si dà un comando

Ogni chat (JBoss, Postino, Ricercatore web, Social, Mani, Scrittore) ha in basso la stessa barra:

```
╭──────────────────────────────────────────╮
│ Scrivi a <agente>: «esempio», «esempio»…  │  (🎤)  (📞)  (↑)
╰──────────────────────────────────────────╯  Parla Chiama Invia
```

1. **Scrivi** nel campo e tocca **↑** (o il tasto Invio della tastiera). Gli esempi nel campo cambiano con l'agente.
2. **🎤 Parla**: il microfono diventa rame e nel campo compare «Ti ascolto…». Quello che dici si scrive nel campo e
   parte da solo quando smetti di parlare. Tocca di nuovo il microfono per fermare.
3. **📞 Chiama**: una conversazione a mani libere con quell'agente. Il telefono diventa verde pieno; dici una frase,
   l'agente risponde, JBoss riascolta. Di' «basta» (o «chiudi», «riattacca») o tocca di nuovo il telefono per chiudere.
   Dopo due silenzi di fila la chiamata si chiude da sola.
4. **«Hey Boss»** (o «Hey JBoss»): con l'ascolto acceso (Impostazioni → Voce → «Ascolta Hey Boss sempre») basta dirlo,
   anche a schermo spento.

Ogni risposta compare nella chat con un segno: ✓ fatto, «annullato, niente inviato», oppure il motivo dell'errore.
In alto ci sono l'avatar, la missione dell'agente e il suo stato (pronto, al lavoro, Collegamento Jarvis spento…).

## I comandi, con esempi

Funzionano senza internet (le regole sono nel telefono). Le parole tra parentesi si possono cambiare.

| Cosa | Esempi |
|---|---|
| Ora e data | «che ore sono», «che giorno è oggi» |
| Aprire un'app | «apri impostazioni», «apri WhatsApp», «apri la fotocamera», «apri la posta» |
| Cercare | «cerca (meteo domani) su Google», «cerca (ricette) su YouTube», «metti (musica jazz) su Spotify» |
| Messaggi | «manda un WhatsApp a (Mario) che (arrivo tra dieci minuti)», «manda un SMS a (Mario): (ci vediamo)» |
| Mail | «scrivi una mail a (Mario): (grazie del preventivo)», «scrivi una mail con Gmail a …» |
| Chiamare | «chiama (Mario)», «chiama (333 1234567)» |
| Strada | «portami a (Piazza Duomo, Milano)», «come arrivo a (Stazione Centrale)» |
| Sveglia e timer | «metti una sveglia alle (6 e 45)», «metti un timer di (10 minuti)» |
| Telefono | «accendi la torcia», «attiva il Wi-Fi», «torna indietro», «vai alla home», «app recenti», «scorri giù» |
| Gemini (se installata) | «chiedi a Gemini (come si dice grazie in giapponese)» |
| Bozza in attesa | «invia» o «annulla» (a voce), oppure i due pulsanti |

Regole che non cambiano:
- Numeri e indirizzi non si inventano: vengono dalla frase o dalla rubrica. Se in rubrica ci sono due «Marco»,
  JBoss chiede quale.
- Ogni invio (messaggio, mail, pagamento) aspetta **Invia**. Senza risposta, dopo 2 minuti la bozza scade.
- «apri la posta» apre l'app di posta predefinita del telefono. Si cambia in Impostazioni → Voce → «App della
  posta» (Quella del telefono, Gmail, Samsung Email).

Le frasi che le regole non capiscono vanno al «cervello» (la VPS o una chiave API), se l'hai configurato.
Altrimenti JBoss risponde «Non ho capito».

## Il Postino (con la VPS)

Il Postino legge le tue caselle sulla VPS e ti dà un resoconto numerato.

- «controlla la posta» o «resoconto»: le mail da fare, numerate.
- «apri 4», «leggi la prossima», «avanti», «indietro».
- «rispondi 4: grazie, confermo»: prepara la bozza nelle Bozze. Non parte niente.
- Sulla mail aperta tocca **Invia** sotto la bozza: è la tua conferma, la mail parte così com'è. Se la VPS sta per
  spedire un testo diverso da quello che vedi, JBoss ti mostra il riquadro Invia/Annulla con il testo vero.
- «cestina 3, 5-9», «archivia 5», «sposta 6 in fatture fornitori»: cestinare chiede **Elimina** una volta, poi va.
- In fondo alla pagina la barra del Postino (Parla, Chiama, Invia parlano col Postino) e tre pulsanti per tornare a
  JBoss: **Scrivi a JBoss**, **Parla a JBoss**, **Chiama JBoss**.

## Problemi frequenti

Il link della tua marca su [dontkillmyapp.com](https://dontkillmyapp.com) spiega il risparmio batteria con le foto.
JBoss lo apre da Permessi → Batteria → «Guida online».

### Tutte le marche

- **L'interruttore dell'accessibilità è grigio** («Impostazione con restrizioni»): Impostazioni → App → JBoss →
  tre puntini in alto → «Consenti impostazioni con restrizioni». Poi riprova.
- **«Hey Boss» non risponde dopo un po'**: manca l'esclusione dalla batteria. Permessi → Batteria → «Escludi ora».
- **Parla non parte e dice che manca il riconoscimento di Google**: scarica la voce senza rete in Impostazioni →
  Voce → Modelli vocali (415 MB, una volta, col Wi-Fi), oppure scrivi.
- **Scansiona QR dice che manca il lettore di Google**: usa «Incolla» con indirizzo e codice.

### Samsung (One UI)

- Accessibilità: Impostazioni → Accessibilità → **App installate** → JBoss.
- Batteria: Impostazioni → Batteria → Limiti utilizzo in background → **App mai in sospensione** → aggiungi JBoss.
- Con due app di assistente accese (per esempio una vecchia versione) le accessibilità si possono staccare a vicenda:
  tienine accesa una sola.

### Google Pixel

- Accessibilità: Impostazioni → Accessibilità → **App scaricate** (o Servizi installati) → JBoss.
- Batteria: di solito basta «Escludi ora». Se la voce si ferma: Impostazioni → App → JBoss → Utilizzo batteria app →
  **Senza restrizioni**.

### Xiaomi, Redmi, POCO (MIUI / HyperOS)

- Accessibilità: Impostazioni → Impostazioni aggiuntive → Accessibilità → **App scaricate** → JBoss.
- Impostazioni → App → Gestisci app → JBoss → **Avvio automatico** acceso.
- Nella stessa pagina: Risparmio batteria → **Nessuna restrizione**.
- Nelle app recenti tieni premuta JBoss e tocca il **lucchetto**.
- Se l'accessibilità si spegne da sola dopo un riavvio: controlla di nuovo l'Avvio automatico.

### OnePlus (OxygenOS), OPPO, Realme

- Batteria: Impostazioni → Batteria → Ottimizzazione batteria → JBoss → **Non ottimizzare**.
- Impostazioni → App → JBoss → Utilizzo batteria → **Consenti attività in background** e **Avvio automatico**.

### Motorola

- Batteria: Impostazioni → App → JBoss → Batteria app → **Senza restrizioni**.

### Huawei, Honor (senza servizi Google)

- Batteria: Impostazioni → Batteria → Avvio app → JBoss → gestione manuale, accendi i tre interruttori.
- Senza Google: la voce usa il modello senza rete (va scaricato), il QR si sostituisce con «Incolla», la sveglia
  della VPS (notifiche push) non c'è: la VPS raggiunge il telefono quando l'app è collegata.

Se il problema resta, apri una segnalazione con il modello «Problema su un telefono» (vedi
[COMPATIBILITA.md](COMPATIBILITA.md#come-segnalare-un-problema)).
