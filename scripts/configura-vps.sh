#!/usr/bin/env bash
# Porta sul telefono la configurazione del MODULO VPS (indirizzo del ponte + token) di JBoss.
#
#   bash scripts/configura-vps.sh [seriale]            # configura e accende il modulo
#   bash scripts/configura-vps.sh --togli [seriale]    # toglie indirizzo e token dal telefono
#   bash scripts/configura-vps.sh --stato [seriale]    # stato del modulo (senza token)
#
# Niente nel repo né nell'APK:
# - l'indirizzo sta nella configurazione personale ${JBOSS_CARTELLA:-~/.jboss}/config-boss.json, chiave «vps_url»;
# - il token sta SOLO in ~/.env.jarvis (JARVIS_AGENT__PHONE_TOKEN, fonte unica la VPS): si legge, non si stampa.
# Arriva all'app in base64 col banco ConfiguraVpsReceiver (protetto da DUMP: solo la shell di ADB) e finisce in
# filesDir/modulo-vps.json (cartella privata, allowBackup=false).
# Regole del telefono: mai adb kill-server, mai disconnect, mai scansioni di porte.
set -eu
PKG="${JARVIS_PACKAGE:-com.jarvis.telefono}"
CONFIG="${JBOSS_CARTELLA:-$HOME/.jboss}/config-boss.json"
ENVF="$HOME/.env.jarvis"
AZIONE=configura
case "${1:-}" in --togli) AZIONE=togli; shift ;; --stato) AZIONE=stato; shift ;; esac
SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
  ELENCO="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  SERIAL="$(printf '%s\n' "$ELENCO" | grep -E ':|_adb-tls' | head -1)"
  [ -z "$SERIAL" ] && SERIAL="$(printf '%s\n' "$ELENCO" | head -1)"
fi
[ -z "$SERIAL" ] && { echo "KO nessun telefono in stato «device»"; exit 2; }
adb -s "$SERIAL" shell pm path "$PKG" >/dev/null 2>&1 || { echo "KO $PKG non installata su $SERIAL"; exit 2; }
RX="$PKG/com.jarvis.telefono.vps.ConfiguraVpsReceiver"
T="$(adb -s "$SERIAL" shell 'date +%s' | tr -d '\r')"
if [ "$AZIONE" = configura ]; then
  URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("vps_url",""))' "$CONFIG")"
  case "$URL" in wss://*|ws://*) ;; *) echo "KO manca «vps_url» (wss://…/phone) in $CONFIG"; exit 2 ;; esac
  # Il token non passa mai dalla riga di comando del Mac né dallo schermo: python lo legge e lo mette nel JSON.
  B64="$(python3 - "$ENVF" "$URL" <<'PY'
import base64, json, sys
tok = ""
for riga in open(sys.argv[1], encoding="utf-8"):
    if riga.startswith("JARVIS_AGENT__PHONE_TOKEN="):
        tok = riga.split("=", 1)[1].strip().strip('"').strip("'")
if len(tok) < 16:
    sys.exit("KO token JARVIS_AGENT__PHONE_TOKEN mancante in .env.jarvis")
print(base64.b64encode(json.dumps({"url": sys.argv[2], "token": tok}).encode()).decode())
PY
)"
  adb -s "$SERIAL" shell "am broadcast -n $RX --es base64 '$B64'" >/dev/null
  B64=""
  adb -s "$SERIAL" shell "am broadcast -n $RX --es azione accendi" >/dev/null
else
  adb -s "$SERIAL" shell "am broadcast -n $RX --es azione $AZIONE" >/dev/null
fi
sleep 1.5
adb -s "$SERIAL" logcat -d -T "$T.000" -s JarvisProva:I 2>/dev/null | grep -E "modulo VPS" | tail -3
