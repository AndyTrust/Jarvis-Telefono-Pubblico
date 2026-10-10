#!/usr/bin/env bash
# Porta Whisper small sul telefono senza farlo scaricare all'app (JBoss 0.3.1+).
#
#   bash scripts/importa-modelli.sh                 # prepara sul Mac (se serve) e spinge
#   bash scripts/importa-modelli.sh --solo-prepara  # solo il Mac, niente adb
#   ADB_SERIAL=<seriale> bash scripts/importa-modelli.sh   # con più telefoni collegati
#
# Cosa fa:
#  1. scarica l'archivio di k2-fsa (639.387.718 byte) in una cartella vuota di
#     ~/modelli-whisper/ (se non c'è già), senza eseguire niente da lì;
#  2. estrae SOLO i tre file che servono e ne controlla dimensione e SHA-256;
#  3. li manda con adb push in /sdcard/Android/data/com.jarvis.telefono/files/modelli/whisper-small/;
#  4. l'app li importa in filesDir/modelli all'avvio del servizio, a ogni apertura di una
#     schermata o col tasto Impostazioni → «Importa da cartella (adb)», e poi li cancella da lì.
# Perché esiste (2026-10-07): i modelli scaricati con l'app vecchia (com.jarvis.app) stanno
# nella SUA cartella privata; JBoss (com.jarvis.telefono) ha la sua e non li vede.
set -euo pipefail

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"
BYTE_ARCHIVIO=639387718
BASE="${JBOSS_CARTELLA:-$HOME/.jboss}/modelli-whisper"
ARCHIVIO_DIR="$BASE/archivio"
FILE_DIR="$BASE/whisper-small"
PKG=com.jarvis.telefono
DEST="/sdcard/Android/data/$PKG/files/modelli/whisper-small"
# nome dimensione sha256 (verificati il 2026-10-07 sull'archivio di k2-fsa)
ATTESI="small-encoder.int8.onnx 112442483 4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9
small-decoder.int8.onnx 262226114 acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee
small-tokens.txt 816730 b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"

ADB=(adb); [ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")

dimensione() { stat -f %z "$1" 2>/dev/null || stat -c %s "$1"; }

file_buoni() {
  local nome byte sha
  while read -r nome byte sha; do
    [ -f "$FILE_DIR/$nome" ] || return 1
    [ "$(dimensione "$FILE_DIR/$nome")" = "$byte" ] || return 1
    [ "$(shasum -a 256 "$FILE_DIR/$nome" | cut -d' ' -f1)" = "$sha" ] || return 1
  done <<< "$ATTESI"
}

# ------------------------------------------------------------------ 1-2. sul Mac
if file_buoni; then
  echo "OK  i tre file sono già in $FILE_DIR e tornano (dimensione e SHA-256)"
else
  mkdir -p "$ARCHIVIO_DIR" "$FILE_DIR"
  A="$ARCHIVIO_DIR/sherpa-onnx-whisper-small.tar.bz2"
  if [ ! -f "$A" ] || [ "$(dimensione "$A")" != "$BYTE_ARCHIVIO" ]; then
    echo "..  scarico l'archivio (640 MB)"
    curl -fL --retry 3 -C - -o "$A" "$URL"
  fi
  [ "$(dimensione "$A")" = "$BYTE_ARCHIVIO" ] || { echo "KO  archivio di $(dimensione "$A") byte invece di $BYTE_ARCHIVIO"; exit 1; }
  echo "..  estraggo i tre file"
  tar -xjf "$A" -C "$FILE_DIR" --strip-components 1 \
    --include 'sherpa-onnx-whisper-small/small-encoder.int8.onnx' \
    --include 'sherpa-onnx-whisper-small/small-decoder.int8.onnx' \
    --include 'sherpa-onnx-whisper-small/small-tokens.txt'
  file_buoni || { echo "KO  i file estratti non tornano con dimensioni e SHA-256 attesi"; exit 1; }
  (cd "$FILE_DIR" && shasum -a 256 small-* > SHA256SUMS)
  echo "OK  file pronti in $FILE_DIR"
fi
[ "${1:-}" = "--solo-prepara" ] && exit 0

# ------------------------------------------------------------------ 3. sul telefono
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "KO  nessun telefono collegato con adb"; exit 2; }
"${ADB[@]}" shell pm path "$PKG" >/dev/null 2>&1 || { echo "KO  $PKG non è installata"; exit 2; }
"${ADB[@]}" shell mkdir -p "$DEST"
while read -r nome byte sha; do
  echo "..  adb push $nome"
  # Si spinge con un nome che l'app non cerca e si rinomina solo a file completo:
  # l'importazione non vede mai un file a metà.
  # </dev/null su ogni adb del ciclo: altrimenti adb si mangia l'elenco del while (1° giro 2026-10-07).
  "${ADB[@]}" push "$FILE_DIR/$nome" "$DEST/.$nome.arrivo" >/dev/null </dev/null
  sul_tel=$("${ADB[@]}" shell stat -c %s "$DEST/.$nome.arrivo" 2>/dev/null </dev/null | tr -d '\r')
  if [ "$sul_tel" != "$byte" ]; then
    echo "KO  $nome sul telefono è di ${sul_tel:-0} byte invece di $byte"; exit 1
  fi
  "${ADB[@]}" shell mv "$DEST/.$nome.arrivo" "$DEST/$nome" </dev/null
done <<< "$ATTESI"
echo "OK  file sul telefono in $DEST"
echo "    Ora apri JBoss (o Impostazioni → «Importa da cartella (adb)»): li copia e li toglie da lì."
echo "    Controllo: adb logcat -d -s JarvisModelli:V JarvisService:V | tail"
