# JBoss 0.7.1: prima versione pubblica

Data: 2026-10-10 · versionCode 20

## Cosa c'è di nuovo per chi la installa

- **Ogni Android, non solo Samsung.** La posta apre l'app predefinita del telefono (Samsung Email solo se c'è e se
  la scegli). L'elenco delle app riconosciute usa quelle installate davvero.
- **Un APK per architettura** più uno universale: `arm64-v8a` (quasi tutti), `armeabi-v7a` (telefoni a 32 bit),
  `x86_64` (emulatori, Chromebook), `universal` (tutti, circa 100 MB).
- **Guida batteria per marca** (Samsung, Pixel, Xiaomi, OnePlus/OPPO, Motorola, Huawei/Honor) con il link a
  dontkillmyapp.com; guida accessibilità con le parole della tua marca.
- **Senza servizi Google**: niente crash. Il QR dice di usare «Incolla»; Parla dice di scaricare la voce senza rete;
  «apri la posta» senza VPS apre l'app di posta del telefono.
- **Postino: una sola conferma.** Il tocco su Invia sotto la bozza, con il testo davanti, è la conferma. Il
  riquadro Invia/Annulla compare solo se la VPS sta per spedire un testo diverso, o se il comando è scritto o detto.
  «Elimina» confermato sul telefono vale anche per i blocchi grandi. Con la chat di JBoss davanti niente pannello
  doppio sopra.
- **Postino: tre pulsanti** verso JBoss: Scrivi a JBoss, Parla a JBoss, Chiama JBoss.
- **La stessa barra in tutte le chat** (JBoss, Postino, Ricercatore web, Social, Mani, Scrittore), come la webapp:
  campo con gli esempi dell'agente, Parla (detti e parte), Chiama (conversazione a mani libere, «basta» per
  chiudere), Invia. In alto avatar, missione e stato dell'agente.
- Una frase scritta nella chat di un agente che ha bisogno della VPS resta nella sua chat (prima finiva in quella di JBoss).
- Prova guidata: con la voce in pausa la riga «JBoss in ascolto» non è più verde e «Accendi» la riprende davvero.
- Licenza: permesso aggiuntivo GPLv3 §7 per le librerie Google (Play services, ML Kit, Firebase).
- Contesti di esempio neutri («CRM di lavoro», «Patrimonio»), testi senza riferimenti personali.

## File

| File | Per chi |
|---|---|
| `JBoss-0.7.1-arm64-v8a.apk` | quasi tutti i telefoni |
| `JBoss-0.7.1-armeabi-v7a.apk` | telefoni vecchi a 32 bit (non provato) |
| `JBoss-0.7.1-x86_64.apk` | emulatori su PC, Chromebook (non provato) |
| `JBoss-0.7.1-universal.apk` | tutti, più pesante |
| `SHA256SUMS` | per controllare i file: `shasum -a 256 -c SHA256SUMS` |

Gli APK della release sono firmati con la chiave dell'autore. Impronta SHA-256 del certificato:
`08ad413410bbf589b3f8a70f68009ca466fd4872dde585681bd5241cfc9a43b7`
(controllo: `apksigner verify --print-certs JBoss-0.7.1-arm64-v8a.apk`). Gli APK vanno allegati alla Release di
GitHub, non nel repository.

## Come si prepara la release firmata

```bash
# keystore.properties FUORI dal repo (storeFile, storePassword, keyAlias, keyPassword; storeFile relativo = accanto)
JBOSS_KEYSTORE_PROPERTIES=/percorso/keystore.properties ./gradlew clean test :app:assembleRelease
cd app/build/outputs/apk/release
for a in arm64-v8a armeabi-v7a x86_64 universal; do cp app-$a-release.apk JBoss-0.7.1-$a.apk; done
shasum -a 256 JBoss-0.7.1-*.apk > SHA256SUMS
```

## Provato

- 716 prove sulla JVM, 0 fallite (2 saltate: servono un archivio vero dei modelli).
- Emulatore Pixel 7, Android 14 (con Google): avvio, permessi guidati, guida batteria, «che ore sono», «apri
  impostazioni», bozza SMS annullata (niente inviato), mail nell'app predefinita, voce pronta, tema chiaro e scuro.
- Emulatore Android 9 senza servizi Google: avvio senza crash, QR con messaggio chiaro, Parla con messaggio chiaro,
  «apri la posta» nell'app di sistema, voce pronta, Postino in modalità prova con una conferma per invio e una per
  cancellazione.

- Barra degli agenti su emulatore Android 14: per ogni agente campo, invio, Parla (dettato con il riconoscimento
  del telefono), Chiama (aperta e chiusa), tema scuro, rotazione; su Android 9 senza Google messaggio chiaro su
  Parla e Chiama con l'APK firmato.

## Non provato

- Telefoni Xiaomi, OnePlus, Motorola, Huawei veri.
- APK `armeabi-v7a` e `x86_64` su un dispositivo.
- Il Postino con una VPS vera dopo la modifica della conferma unica (provato con la modalità prova e con le prove JVM).
- Una conversazione vera a voce con Chiama: sull'emulatore il microfono è virtuale (il riconoscimento ha scritto parole a caso dal rumore, e l'app le ha mandate e ha risposto «non ho capito»).
