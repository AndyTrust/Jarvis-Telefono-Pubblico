#!/usr/bin/env bash
# Porta sul telefono la configurazione personale dell'utente (JBoss, ex Jarvis Telefono).
#
#   bash scripts/configura-boss.sh                       # file di default, telefono da `adb devices`
#   bash scripts/configura-boss.sh percorso/config.json [seriale]
#   bash scripts/configura-boss.sh --togli [seriale]     # torna ai valori di fabbrica
#
# Il file NON sta nel repo né nell'APK: di default è ${JBOSS_CARTELLA:-~/.jboss}/config-boss.json.
# Arriva all'app con il banco di prova (ProvaAdbReceiver, protetto da DUMP: solo la shell di ADB),
# in base64, e l'app lo scrive nella sua cartella privata (filesDir/config-boss.json).
# Nessuna password e nessun token: l'app ignora chiavi con quei nomi.
# Chiavi utili: app_mail, account_mail, whatsapp_me (il numero per «manda un WhatsApp a me stesso», solo cifre col prefisso).
# Regole del telefono: mai adb kill-server, mai disconnect, mai scansioni di porte.
set -eu
PKG="${JARVIS_PACKAGE:-com.jarvis.telefono}"
FILE="${JBOSS_CARTELLA:-$HOME/.jboss}/config-boss.json"
TOGLI=0
if [ "${1:-}" = "--togli" ]; then TOGLI=1; shift; elif [ -n "${1:-}" ] && [ -f "$1" ]; then FILE="$1"; shift; fi
SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
  ELENCO="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  SERIAL="$(printf '%s\n' "$ELENCO" | grep -E ':|_adb-tls' | head -1)"
  [ -z "$SERIAL" ] && SERIAL="$(printf '%s\n' "$ELENCO" | head -1)"
fi
[ -z "$SERIAL" ] && { echo "KO nessun telefono in stato «device»"; exit 2; }
adb -s "$SERIAL" shell pm path "$PKG" >/dev/null 2>&1 || { echo "KO $PKG non installata su $SERIAL"; exit 2; }

if [ $TOGLI -eq 1 ]; then
  COMANDO='{"action":"configura_togli"}'
else
  [ -f "$FILE" ] || { echo "KO manca $FILE"; exit 2; }
  python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$FILE" || { echo "KO $FILE non è JSON valido"; exit 2; }
  if grep -qiE '"(password|token|segreto|secret|api_key)"' "$FILE"; then echo "KO il file contiene un segreto: toglilo"; exit 2; fi
  B64="$(base64 < "$FILE" | tr -d '\n')"
  COMANDO="{\"action\":\"configura\",\"base64\":\"$B64\"}"
fi
T="$(adb -s "$SERIAL" shell 'date +%s' | tr -d '\r')"
adb -s "$SERIAL" shell "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '$COMANDO'" >/dev/null
sleep 1.5
adb -s "$SERIAL" logcat -d -T "$T.000" -s JarvisProva:I 2>/dev/null | grep -E "configurazione|riaccendi" | tail -3
