# Su quali telefoni funziona JBoss

Versione 0.7.1 · prove del 2026-10-10. Qui c'è solo quello che è stato provato davvero, e come.

## Versioni di Android

| Android | Stato | Come è stato provato |
|---|---|---|
| 8.0 e 8.1 (API 26-27) | dovrebbe funzionare | è il minimo dichiarato (minSdk 26); non provato |
| 9 (API 28) | **funziona** | emulatore senza servizi Google (immagine AOSP arm64): app avviata, permessi, «che ore sono», «apri impostazioni», «apri la posta» (app di sistema), conferma Invia/Annulla del Postino in modalità prova, voce «Hey Boss» pronta (motore caricato) |
| 10-13 (API 29-33) | dovrebbe funzionare | non provato |
| 14 (API 34) | **funziona** | emulatore Pixel 7 con Google: le stesse prove più bozza SMS con Annulla (niente inviato), mail nell'app predefinita, guida batteria, tema chiaro e scuro |
| 15-16 | funziona su Samsung | telefono vero Samsung Galaxy S24 (One UI, Android 16), provato da chi sviluppa l'app nelle versioni precedenti |

## Architetture (ABI)

| APK | Per chi | Stato |
|---|---|---|
| `arm64-v8a` (circa 45 MB) | quasi tutti i telefoni dal 2017 | **provato** (S24 vero, emulatori Android 9 e 14) |
| `armeabi-v7a` (circa 35 MB) | telefoni vecchi o economici a 32 bit (Android Go) | costruito, **non provato**: i Mac con chip Apple non fanno girare codice a 32 bit, quindi niente emulatore |
| `x86_64` (circa 49 MB) | emulatore su PC Intel o AMD, alcuni Chromebook | costruito, non provato |
| `universal` (circa 100 MB) | va su tutti | **provato** sull'emulatore Android 14 |

Le librerie della voce (sherpa-onnx, ONNX Runtime) esistono per tutte e quattro le architetture. x86 a 32 bit non si
costruisce: nessun telefono in commercio lo usa.

## Marche

| Marca | Stato | Note |
|---|---|---|
| Samsung (One UI) | **provato** su Galaxy S24 vero | accessibilità in «App installate»; batteria in «App mai in sospensione» |
| Google Pixel / Android puro | **provato** su emulatore (Pixel 7, Android 14) | la guida batteria riconosce la marca da sola |
| Telefoni senza Google (AOSP) | **provato** su emulatore Android 9 | niente crash; vedi sotto cosa manca |
| Xiaomi, Redmi, POCO | dovrebbe funzionare, **non provato su hardware** | serve «Avvio automatico» e il lucchetto nelle app recenti; segnalaci com'è andata |
| OnePlus, OPPO, Realme | dovrebbe funzionare, **non provato su hardware** | batteria «Non ottimizzare» e attività in background |
| Motorola | dovrebbe funzionare, **non provato su hardware** | batteria «Senza restrizioni» |
| Huawei, Honor senza Google | dovrebbe funzionare, **non provato su hardware** | come AOSP; batteria in «Avvio app» |

Il codice dell'app è lo stesso per tutte le marche. Cambiano solo i testi di aiuto (che riconoscono la marca da
`Build.MANUFACTURER`) e i nomi delle app di sistema: per la posta, i messaggi e il telefono JBoss usa l'app
predefinita o la prima installata, non quella di una marca.

## Cosa richiede i servizi Google e cosa no

| Funzione | Senza servizi Google |
|---|---|
| Comandi scritti, regole, mani, conferma Invia/Annulla | funziona |
| «Hey Boss» (parola di attivazione) | funziona: modello sherpa-onnx dentro l'app |
| Dettato e voce | con il riconoscimento di Google se c'è; altrimenti il modello Whisper nel telefono (va scaricato, 415 MB). Senza tutti e due JBoss lo dice e si può scrivere |
| Voce che risponde | usa la sintesi vocale del telefono (quella di sistema) |
| «Scansiona QR» per la VPS | serve Google Play Services; senza, JBoss lo dice e si usa «Incolla» (stesso risultato) |
| Sveglia della VPS (notifica push) | serve Firebase (Google) e un file di configurazione che non è nel repository: nell'APK pubblico è spenta. La VPS parla col telefono quando l'app è collegata |
| «chiedi a Gemini», Maps, YouTube | servono quelle app; se mancano JBoss dice che non sono installate |

## Come segnalare un problema

1. Apri una Issue con il modello **«Problema su un telefono»**.
2. Marca, modello e versioni: `bash scripts/info-telefono.sh` dal computer (cavo USB e «Debug USB» attivo), oppure
   copiali da Impostazioni del telefono → Info telefono. Lo script non legge nomi, numeri, account o messaggi.
3. Scrivi i passi e cosa è successo. Una foto dello schermo aiuta, senza dati personali.
