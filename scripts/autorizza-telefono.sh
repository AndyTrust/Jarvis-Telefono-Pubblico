#!/usr/bin/env bash
# Concede a Jarvis tutte le autorizzazioni di cui ha bisogno, da un computer con ADB, senza toccare il telefono.
# Serve il telefono collegato (cavo, oppure Debug wireless: Opzioni sviluppatore → Debug wireless).
#
#   bash scripts/autorizza-telefono.sh [serial]        # serial: da `adb devices`, se i telefoni sono più d'uno
#
# Fa, nell'ordine: microfono e notifiche (0.2.0: niente più «Sopra le altre app»), impostazioni con
# restrizioni (necessario per il servizio di accessibilità di un'app installata fuori dal Play Store), servizio di
# accessibilità (solo con ACCESSIBILITA=1), esclusione dal risparmio energetico e dai limiti in background. Ogni passo è ripetibile.
# Il telefono può rifiutarne qualcuno (dipende dal produttore): lo script lo dice e prosegue.
set -u
PKG="${JARVIS_PACKAGE:-com.jarvis.telefono}"
SERVIZIO="$PKG/$PKG.JarvisAccessibilityService"
S=()
[ "${1:-}" ] && S=(-s "$1")
command -v adb >/dev/null || { echo "adb non installato"; exit 1; }
a() { adb ${S[@]+"${S[@]}"} shell "$@" 2>&1 | tr -d '\r'; }
adb ${S[@]+"${S[@]}"} get-state >/dev/null 2>&1 || { echo "Nessun telefono collegato."; exit 1; }
a pm list packages | grep -q "package:$PKG\$" || { echo "L'app $PKG non è installata."; exit 1; }

passo() { printf '%-46s' "$1"; shift; local o; o="$("$@")"; if [ -z "$o" ] || [[ "$o" != *[Ee]xception* && "$o" != *[Ee]rror* && "$o" != *Unknown* ]]; then echo ok; else echo "non concesso: ${o%%$'\n'*}"; fi; }

passo "Microfono"                     a pm grant "$PKG" android.permission.RECORD_AUDIO
passo "Notifiche"                     a pm grant "$PKG" android.permission.POST_NOTIFICATIONS
passo "Impostazioni con restrizioni"  a cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow
passo "In background"                 a cmd appops set "$PKG" RUN_IN_BACKGROUND allow
passo "In background, sempre"         a cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow
passo "Rubrica (1.2.0)"               a pm grant "$PKG" android.permission.READ_CONTACTS
passo "Accesso all'uso (1.2.0)"        a cmd appops set "$PKG" GET_USAGE_STATS allow
passo "Fuori dal risparmio energetico" a dumpsys deviceidle whitelist "+$PKG"

# Servizio di accessibilità: SOLO con ACCESSIBILITA=1. Su Samsung due servizi di accessibilità
# Jarvis non stanno insieme: accendere questo stacca quello dell'app com.jarvis.app (l'utente lo deve sapere).
if [ "${ACCESSIBILITA:-0}" != "1" ]; then
  echo "Accessibilità                                 NON toccata (ACCESSIBILITA=1 per accenderla: stacca l'altro Jarvis)"
else
ATT="$(a settings get secure enabled_accessibility_services)"
case "$ATT" in
  *"$SERVIZIO"*) echo "Accessibilità                                 già acceso" ;;
  null|"")       a settings put secure enabled_accessibility_services "$SERVIZIO"; a settings put secure accessibility_enabled 1; echo "Accessibilità                                 acceso" ;;
  *)             a settings put secure enabled_accessibility_services "$ATT:$SERVIZIO"; a settings put secure accessibility_enabled 1; echo "Accessibilità                                 acceso (insieme agli altri)" ;;
esac
fi

# Perché ADB resti collegabile senza riabbinare ogni settimana (Android 11 e successivi; facoltativo).
a settings put global adb_allowed_connection_time 0 >/dev/null
echo "Fatto. Poi: bash scripts/configura-boss.sh"
