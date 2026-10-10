#!/usr/bin/env bash
# Raccoglie marca, modello, versione di Android, architettura e versione di JBoss, per una segnalazione.
# Niente dati personali: nessun nome, numero, account, contatto o messaggio. Serve un cavo USB e il «Debug USB».
#
#   bash scripts/info-telefono.sh            # il telefono collegato
#   bash scripts/info-telefono.sh <seriale>  # se ne hai più di uno
set -eu
S="${1:-${ANDROID_SERIAL:-}}"
A="adb"; [ -n "$S" ] && A="adb -s $S"
command -v adb >/dev/null || { echo "Serve adb (Android platform-tools)."; exit 1; }
p() { $A shell getprop "$1" 2>/dev/null | tr -d '\r'; }
v="$($A shell dumpsys package com.jarvis.telefono 2>/dev/null | tr -d '\r' | grep -m1 versionName | sed 's/.*versionName=//')"
cat <<TXT
Marca: $(p ro.product.manufacturer) ($(p ro.product.brand))
Modello: $(p ro.product.model)
Android: $(p ro.build.version.release) (API $(p ro.build.version.sdk))
Interfaccia: $(p ro.build.version.oneui)$(p ro.miui.ui.version.name)$(p ro.build.version.opporom)
Architettura: $(p ro.product.cpu.abi) (supportate: $(p ro.product.cpu.abilist))
Servizi Google: $($A shell pm list packages com.google.android.gms 2>/dev/null | grep -q gms && echo sì || echo no)
JBoss: ${v:-non installata}
TXT
