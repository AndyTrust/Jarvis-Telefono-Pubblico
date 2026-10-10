#!/usr/bin/env bash
# Prove di NON REGRESSIONE di Jarvis Telefono (com.jarvis.telefono, senza VPS) sul telefono vero, dal Mac via ADB.
#
#   bash scripts/prove-telefono.sh                 # sceglie il telefono da `adb devices`
#   bash scripts/prove-telefono.sh 192.168.1.119:5555
#
# Fa solo prove AUTOMATICHE e SENZA EFFETTI: legge stato, versione, permessi e log, e usa il banco
# ProvaAdbReceiver (stesso JSON che manda il cervello del telefono, risposta nel logcat col tag JarvisProva).
# Non tocca mai lo schermo con coordinate e non preme mai Invia. L'unica bozza è una mail verso
# prova@example.invalid (dominio che non esiste: anche nel caso peggiore non arriva a nessuno),
# proposta col pannello di conferma e ANNULLATA con «annulla»; poi BACK e HOME.
#
# Regole: mai `adb kill-server`, mai `adb disconnect`, mai scansioni di porte. Chi usa il telefono
# prende prima la chiave: python3 <repo Jarvis>/strumenti/lavori.py prendo ... --risorse telefono-adb
#
# Ogni prova stampa: OK | KO | SALTATA, il nome, i millisecondi e il motivo. Le righe INFO non
# contano. Esito 0 solo se nessun KO. In fondo l'elenco delle prove da fare a mano.
set -u

PKG="${JARVIS_PACKAGE:-com.jarvis.telefono}"
VERSIONE_ATTESA="${VERSIONE_ATTESA:-}"   # vuota = si legge da app/build.gradle.kts
CARTELLA="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/prove-telefono.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

command -v adb >/dev/null || { echo "KO adb non installato"; exit 2; }
command -v python3 >/dev/null || { echo "KO python3 non installato"; exit 2; }

# ------------------------------------------------------------------ scelta del telefono
SERIAL="${1:-${ANDROID_SERIAL:-}}"
if [ -z "$SERIAL" ]; then
  ELENCO="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
  N="$(printf '%s\n' "$ELENCO" | grep -c . || true)"
  if [ "$N" = "0" ]; then echo "KO nessun telefono in stato «device» (adb devices)"; exit 2; fi
  if [ "$N" = "1" ]; then SERIAL="$ELENCO"
  else
    # Più dispositivi: quello wireless (ip:porta o _adb-tls-connect)
    SERIAL="$(printf '%s\n' "$ELENCO" | grep -E ':|_adb-tls' | head -1)"
    [ -z "$SERIAL" ] && SERIAL="$(printf '%s\n' "$ELENCO" | head -1)"
  fi
fi
A() { adb -s "$SERIAL" "$@"; }
S() { adb -s "$SERIAL" shell "$@" 2>&1 | tr -d '\r'; }

ora_ms() { python3 -c 'import time;print(int(time.time()*1000))'; }

# ------------------------------------------------------------------ esiti
NOK=0; NKO=0; NSALT=0
ESITI="$TMP/esiti.txt"; : > "$ESITI"
riga() { # esito nome ms motivo
  printf '%-8s %-34s %7s ms  %s\n' "$1" "$2" "$3" "$4" | tee -a "$ESITI"
  case "$1" in OK) NOK=$((NOK+1));; KO) NKO=$((NKO+1));; SALTATA) NSALT=$((NSALT+1));; esac
}
info() { printf '%-8s %-34s %7s     %s\n' "INFO" "$1" "" "$2" | tee -a "$ESITI"; }

# ------------------------------------------------------------------ logcat dal telefono
# Ora del telefono in secondi con decimali (logcat -v epoch usa la stessa scala).
ora_tel() {
  local t; t="$(S 'date +%s.%N')"
  case "$t" in *N*|"") t="$(S 'date +%s')"; sleep 1;; esac
  printf '%s' "$t"
}

# Parser dei log: messaggi dopo l'istante T per i tag dati, nel formato «TAG: testo».
cat > "$TMP/log.py" <<'PY'
import re, sys
t0 = float(sys.argv[1]); tags = set(sys.argv[2].split(","))
rx = re.compile(r'^\s*(\d+\.\d+)\s+\d+\s+\d+\s+[VDIWEF]\s+(\S+?)\s*: ?(.*)$')
for line in sys.stdin:
    m = rx.match(line.rstrip("\r\n"))
    if m and float(m.group(1)) > t0 and m.group(2) in tags:
        print(f"{m.group(2)}: {m.group(3)}")
PY
log_dopo() { # T tag1,tag2
  A logcat -d -v epoch -s $(printf '%s:V ' ${2//,/ }) 2>/dev/null | python3 "$TMP/log.py" "$1" "$2"
}

# Primo tool_result del banco dopo T (i pezzi da 3000 caratteri si ricuciono). Stampa il JSON.
cat > "$TMP/risultato.py" <<'PY'
import json, sys
testo = "".join(l[len("JarvisProva: "):].rstrip("\n") for l in sys.stdin if l.startswith("JarvisProva: "))
i = 0
while True:
    i = testo.find('{"type":"tool_result"', i)
    if i < 0: sys.exit(1)
    try:
        obj, fine = json.JSONDecoder().raw_decode(testo[i:])
        print(json.dumps(obj, ensure_ascii=False)); sys.exit(0)
    except Exception:
        i += 1
PY

# banco '<json>' attesa_s  → RIS (JSON tool_result) e ritorno 0 se arriva in tempo
RIS=""
banco() {
  local json="$1" attesa="${2:-10}" t fine
  t="$(ora_tel)"; T_ULTIMO="$t"
  S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '$json'" >/dev/null
  fine=$(( $(date +%s) + attesa ))
  while [ "$(date +%s)" -le "$fine" ]; do
    RIS="$(log_dopo "$t" JarvisProva | python3 "$TMP/risultato.py")" && return 0
    sleep 0.5
  done
  RIS=""; return 1
}
campo() { printf '%s' "$RIS" | python3 -c "import json,sys;d=json.load(sys.stdin);v=d.get('$1');print('' if v is None else (json.dumps(v,ensure_ascii=False) if not isinstance(v,str) else v))"; }
ha_errore() { [ -n "$(campo error)" ]; }
breve() { printf '%s' "$1" | tr '\n' ' ' | cut -c1-110; }

# prova NOME '<json>' attesa [controllo_python_su_result]
# Il controllo è un'espressione Python su r (il result) che deve essere vera.
prova_banco() {
  local nome="$1" json="$2" attesa="$3" controllo="${4:-True}" t0 ms
  t0=$(ora_ms)
  if ! banco "$json" "$attesa"; then
    riga KO "$nome" $(( $(ora_ms) - t0 )) "nessuna risposta del banco in ${attesa} s"; return 1
  fi
  ms=$(( $(ora_ms) - t0 ))
  if ha_errore; then riga KO "$nome" "$ms" "errore: $(breve "$(campo error)")"; return 1; fi
  if printf '%s' "$RIS" | python3 -c "import json,sys;r=json.load(sys.stdin).get('result');sys.exit(0 if ($controllo) else 1)" 2>/dev/null; then
    riga OK "$nome" "$ms" "$(breve "$(campo result)")"; return 0
  fi
  riga KO "$nome" "$ms" "risposta inattesa: $(breve "$(campo result)")"; return 1
}

echo "Prove di non regressione Jarvis · $(date '+%Y-%m-%d %H:%M') · telefono $SERIAL"
echo "------------------------------------------------------------------------------------------"
T_INIZIO="$(ora_tel)"

# ------------------------------------------------------------------ 1. collegamento e versione
t0=$(ora_ms)
if [ "$(A get-state 2>/dev/null)" = "device" ]; then riga OK "adb collegato" $(( $(ora_ms)-t0 )) "$SERIAL"
else riga KO "adb collegato" $(( $(ora_ms)-t0 )) "stato $(A get-state 2>&1)"; exit 1; fi

[ -z "$VERSIONE_ATTESA" ] && VERSIONE_ATTESA="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$CARTELLA/app/build.gradle.kts" | head -1)"
t0=$(ora_ms)
V="$(S dumpsys package "$PKG" | sed -n 's/.*versionName=//p' | head -1)"
C="$(S dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)"
if [ -z "$V" ]; then riga KO "versione installata" $(( $(ora_ms)-t0 )) "$PKG non installata"; exit 1
elif [ "$V" = "$VERSIONE_ATTESA" ]; then riga OK "versione installata" $(( $(ora_ms)-t0 )) "$V (codice $C)"
else riga KO "versione installata" $(( $(ora_ms)-t0 )) "$V (codice $C), attesa $VERSIONE_ATTESA"; fi

# ------------------------------------------------------------------ 2. servizi
t0=$(ora_ms)
SERVIZIO_ON=0
if S dumpsys activity services "$PKG" | grep -qE "$PKG/(\.|$PKG\.)JarvisService\b"; then SERVIZIO_ON=1; riga OK "servizio JarvisService" $(( $(ora_ms)-t0 )) "attivo"
else riga KO "servizio JarvisService" $(( $(ora_ms)-t0 )) "non in esecuzione (voce, bolla e ascolto non possono passare)"; fi

t0=$(ora_ms)
ACC="$(S settings get secure enabled_accessibility_services)"
LEGATO="$(S dumpsys accessibility | grep -c "JarvisAccessibilityService")"
if [[ "$ACC" == *"$PKG/"*JarvisAccessibilityService* ]] && [ "${LEGATO:-0}" -gt 0 ]; then
  riga OK "servizio accessibilità" $(( $(ora_ms)-t0 )) "acceso e agganciato"
elif [[ "$ACC" == *"$PKG/"*JarvisAccessibilityService* ]]; then
  riga KO "servizio accessibilità" $(( $(ora_ms)-t0 )) "nelle impostazioni ma non agganciato (Samsung: un altro Jarvis lo ha preso?)"
else riga KO "servizio accessibilità" $(( $(ora_ms)-t0 )) "spento"; fi

# ------------------------------------------------------------------ 3. permessi
t0=$(ora_ms)
DP="$(S dumpsys package "$PKG")"
mancano=""; info_perm=""
for p in RECORD_AUDIO POST_NOTIFICATIONS; do
  printf '%s' "$DP" | grep -q "android.permission.$p: granted=true" || mancano="$mancano $p"
done
printf '%s' "$DP" | grep -q "android.permission.READ_CONTACTS: granted=true" || info_perm="$info_perm READ_CONTACTS"
[[ "$(S cmd appops get "$PKG" GET_USAGE_STATS)" == *allow* ]] || info_perm="$info_perm GET_USAGE_STATS"
S dumpsys deviceidle whitelist | grep -q "$PKG" || info_perm="$info_perm fuori-risparmio-energetico"
if [ -z "$mancano" ]; then riga OK "permessi essenziali" $(( $(ora_ms)-t0 )) "microfono, notifiche${info_perm:+; mancano facoltativi:$info_perm}"
else riga KO "permessi essenziali" $(( $(ora_ms)-t0 )) "mancano:$mancano (bash scripts/autorizza-telefono.sh)"; fi

# ------------------------------------------------------------------ 4. altro Jarvis sullo stesso telefono (solo informazione)
# Su Samsung due servizi di accessibilità Jarvis non stanno insieme: accenderne uno stacca l'altro.
ALTRI="$(S pm list packages | grep -E 'com\.jarvis\.' | grep -v "$PKG\$" | sed 's/package://' | tr '\n' ' ')"
info "altri Jarvis installati" "${ALTRI:-nessuno}"

# ------------------------------------------------------------------ 5. schermo
WAKE="$(S dumpsys power | sed -n 's/.*mWakefulness=//p' | head -1)"
[ "$WAKE" != "Awake" ] && S input keyevent KEYCODE_WAKEUP >/dev/null && sleep 1
FOCUS="$(S dumpsys window | grep -m1 mCurrentFocus)"
SCHERMO_OK=1
if printf '%s' "$FOCUS" | grep -qiE 'Keyguard|NotificationShade|Bouncer'; then
  SCHERMO_OK=0; info "schermo" "bloccato ($FOCUS): le prove che aprono app si saltano"
else info "schermo" "sbloccato · $(printf '%s' "$FOCUS" | sed 's/.*mCurrentFocus=//' | cut -c1-80)"; fi

# ------------------------------------------------------------------ 6. banco: mani in sola lettura
prova_banco "banco stato_tecnico (M18)" '{"action":"stato_tecnico"}' 8 'r is not None'
# Se qualcuno ha lasciato le mani ferme, si rimettono in moto prima di provare.
S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"sblocca_mani\"}'" >/dev/null; sleep 0.5

prova_banco "elenca_app (M02)" '{"action":"elenca_app","max":5}' 10 'isinstance(r,dict) and r.get("totale",0) > 200'
prova_banco "registro azioni (M16)" '{"action":"registro","righe":5}' 8 'r is not None'

if [ $SCHERMO_OK -eq 1 ]; then
  for app in WhatsApp "Samsung Email" "E-mail" posta Chrome Maps Impostazioni; do
    prova_banco "apri_app $app (M01)" "{\"action\":\"apri_app\",\"nome\":\"$app\"}" 15 'isinstance(r,dict) and r.get("aperto") is True and "[1]" in r.get("schermo","")'
  done
  prova_banco "read_screen (M08)" '{"action":"read_screen"}' 8 'isinstance(r,str) and "[1]" in r'
  prova_banco "key HOME (M12)" '{"action":"key","name":"HOME"}' 8 'r is True'
  prova_banco "cerca_google (M04)" '{"action":"cerca_google","domanda":"meteo Olbia"}' 15 'isinstance(r,dict) and r.get("aperto") is True'
  banco '{"action":"key","name":"HOME"}' 6 >/dev/null

  # ---------------------------------------------------------------- 7. bozza senza invio, annullata
  prova_banco "componi mail, bozza (M03)" '{"action":"componi","tipo":"mail","app":"samsung","a":"prova@example.invalid","oggetto":"prova non regressione","corpo":"prova automatica, da non inviare"}' 15 'isinstance(r,dict) and r.get("aperto") is True'
  sleep 1.5
  t0=$(ora_ms); tb="$(ora_tel)"
  S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"invia_bozza\",\"app\":\"Samsung Email\",\"destinatario\":\"prova@example.invalid\",\"bozza\":\"prova\"}'" >/dev/null
  sleep 3
  if log_dopo "$tb" JarvisProva | grep -q '"tool_result"'; then
    # Risposta immediata = niente pannello (pulsante non trovato): nessun rischio, ma la prova è KO.
    RIS="$(log_dopo "$tb" JarvisProva | python3 "$TMP/risultato.py")"
    riga KO "pannello di conferma (S02)" $(( $(ora_ms)-t0 )) "$(breve "$(campo error)")"
  else
    riga OK "pannello di conferma (S02)" $(( $(ora_ms)-t0 )) "bozza in attesa dell'utente, nessun invio"
    t0=$(ora_ms); ta="$(ora_tel)"
    S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"risposta_di_boss\",\"testo\":\"annulla\"}'" >/dev/null
    sleep 2
    L="$(log_dopo "$ta" JarvisProva)"
    if printf '%s' "$L" | grep -q 'risposta_di_boss gestita=true' && printf '%s' "$L" | grep -q 'Boss ha annullato'; then
      riga OK "annulla a voce (S03)" $(( $(ora_ms)-t0 )) "bozza annullata, niente inviato"
    else riga KO "annulla a voce (S03)" $(( $(ora_ms)-t0 )) "$(breve "$L")"; fi
  fi
  # Si esce dalla composizione senza toccare Invia.
  banco '{"action":"key","name":"BACK"}' 5 >/dev/null; sleep 0.5
  banco '{"action":"key","name":"BACK"}' 5 >/dev/null; sleep 0.5
  banco '{"action":"key","name":"HOME"}' 5 >/dev/null
else
  for n in "apri_app x5 (M01)" "read_screen (M08)" "key HOME (M12)" "cerca_google (M04)" "componi mail, bozza (M03)" "pannello di conferma (S02)" "annulla a voce (S03)"; do
    riga SALTATA "$n" 0 "schermo bloccato"
  done
fi

# ------------------------------------------------------------------ 8. «Ferma» e ripartenza (S06)
t0=$(ora_ms)
if banco '{"action":"emergenza"}' 8 && ! ha_errore; then
  # Dalla 1.2.4 «Ferma» è «Annulla»: il rifiuto dice «Boss ha annullato…» (prima «fermato»).
  if banco '{"action":"read_screen"}' 8 && [[ "$(campo error)" == *"fermato"* || "$(campo error)" == *"annullato"* ]]; then
    S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"sblocca_mani\"}'" >/dev/null; sleep 0.7
    if banco '{"action":"read_screen"}' 8 && ! ha_errore; then
      riga OK "Ferma e ripartenza (S06)" $(( $(ora_ms)-t0 )) "fermato: azioni rifiutate; dopo lo sblocco rispondono"
    else riga KO "Ferma e ripartenza (S06)" $(( $(ora_ms)-t0 )) "dopo lo sblocco: $(breve "$(campo error)")"; fi
  else riga KO "Ferma e ripartenza (S06)" $(( $(ora_ms)-t0 )) "dopo Ferma l'azione non è stata rifiutata: $(breve "$RIS")"; fi
else riga KO "Ferma e ripartenza (S06)" $(( $(ora_ms)-t0 )) "emergenza senza risposta"; fi
S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"sblocca_mani\"}'" >/dev/null

# ------------------------------------------------------------------ 9. ascolto vero: 10 s di silenzio (V03)
if [ $SERVIZIO_ON -eq 0 ]; then
  riga KO "ascolta_prova, silenzio (V03)" 0 "JarvisService spento"
else
t0=$(ora_ms); ta="$(ora_tel)"
S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"ascolta_prova\"}'" >/dev/null
esito=""; fine=$(( $(date +%s) + 25 ))
while [ "$(date +%s)" -le "$fine" ]; do
  L="$(log_dopo "$ta" JarvisService,JarvisBolla)"
  if printf '%s' "$L" | grep -q 'Non ho sentito niente'; then esito=ok; break; fi
  if printf '%s' "$L" | grep -q 'la voce non è pronta'; then esito=nonpronta; break; fi
  sleep 1
done
case "$esito" in
  ok) riga OK "ascolta_prova, silenzio (V03)" $(( $(ora_ms)-t0 )) "«Non ho sentito niente» e ritorno in attesa" ;;
  nonpronta) riga KO "ascolta_prova, silenzio (V03)" $(( $(ora_ms)-t0 )) "la voce non è pronta (microfono o modelli)" ;;
  *) L2="$(printf '%s' "$L" | grep -E 'bolla:' | tail -1)"
     riga KO "ascolta_prova, silenzio (V03)" $(( $(ora_ms)-t0 )) "niente «Non ho sentito» in 25 s (rumore in stanza?) ${L2:+· $L2}" ;;
esac
fi

# ------------------------------------------------------------------ 10. bolla e suoni con voce_prova (V12, U07)
if [ $SERVIZIO_ON -eq 0 ]; then
  riga KO "voce_prova, bolla e suoni" 0 "JarvisService spento"
else
  sleep 2
  t0=$(ora_ms); tv="$(ora_tel)"
  S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"voce_prova\",\"testo\":\"che ore sono\"}'" >/dev/null
  sleep 6
  L="$(log_dopo "$tv" JarvisProva,JarvisBolla,JarvisSuoni)"
  if ! printf '%s' "$L" | grep -q 'voce_prova: servizio acceso'; then
    riga KO "voce_prova, bolla e suoni" $(( $(ora_ms)-t0 )) "servizio spento"
  else
    nb="$(printf '%s\n' "$L" | grep -c '^JarvisBolla: bolla:')"; ns="$(printf '%s\n' "$L" | grep -c '^JarvisSuoni: suono')"
    ultima="$(printf '%s\n' "$L" | grep '^JarvisBolla: bolla:' | tail -1 | sed 's/^JarvisBolla: //')"
    if [ "$nb" -gt 0 ] && [ "$ns" -gt 0 ]; then riga OK "voce_prova, bolla e suoni" $(( $(ora_ms)-t0 )) "$nb righe bolla, $ns suoni · $ultima"
    else riga KO "voce_prova, bolla e suoni" $(( $(ora_ms)-t0 )) "bolla $nb, suoni $ns · $ultima"; fi
  fi
fi

# ------------------------------------------------------------------ 10b. cervello a regole: «non ho capito» e misura (senza toccare app)
t0=$(ora_ms); tf="$(ora_tel)"
S "am broadcast -n $PKG/.mani.ProvaAdbReceiver --es comando '{\"action\":\"frase\",\"testo\":\"frase senza senso per la prova\"}'" >/dev/null
sleep 2
L="$(log_dopo "$tf" JarvisNucleo)"
if printf '%s' "$L" | grep -q 'esito=non capito'; then riga OK "non ho capito (cervello regole)" $(( $(ora_ms)-t0 )) "$(printf '%s' "$L" | grep misura | tail -1 | sed 's/.*misura: //')"
else riga KO "non ho capito (cervello regole)" $(( $(ora_ms)-t0 )) "$(breve "$L")"; fi

# ------------------------------------------------------------------ 11. crash
t0=$(ora_ms)
CR="$(A logcat -b crash -d -v epoch 2>/dev/null | python3 -c "
import sys,re
t0=float('$T_INIZIO'); out=[]
for l in sys.stdin:
    m=re.match(r'^\s*(\d+\.\d+)',l)
    if m and float(m.group(1))>t0 and '$PKG' in l: out.append(l.strip())
print(len(out))")"
if [ "${CR:-0}" = "0" ]; then riga OK "nessun crash durante le prove" $(( $(ora_ms)-t0 )) "logcat -b crash pulito per $PKG"
else riga KO "nessun crash durante le prove" $(( $(ora_ms)-t0 )) "$CR righe di crash: adb logcat -b crash -d"; fi

# ------------------------------------------------------------------ riepilogo
echo "------------------------------------------------------------------------------------------"
echo "Totale: $NOK OK · $NKO KO · $NSALT saltate"
cat <<'MANO'

Da fare a mano (voce vera o tocco dell'utente; checklist completa in docs/CHECKLIST.md):
  V01  «Hey Jarvis» e «Jarvis», 3 volte ciascuna: due note e bolla «Ti ascolto»
  V05  una frase di 5 s trascritta giusta, bolla «Ho sentito «…»»
  V07  voce di un altro: passa (filtro impronta spento)
  V08  due frasi di fila senza toccare il telefono
  V09  dettato della chat: testo parziale; con rete spenta ripiego Whisper
  V10  risposta parlata con la voce di sistema
  V12  si sentono i tre segnali e vibra (qui si controlla solo il log)
  U-chat  campo di testo + Detta (Google, parziali): la frase va al cervello, la cronologia si aggiorna
  V16  durante il dettato la parola non scatta, dopo riparte
  M03  componi per whatsapp, sms, telegram, mappe, calendario, Samsung Email (bozze vere: a mano)
  M05/M06/M07  gemini_chiedi, cerca_in_app, cerca_contatto (toccano dati o account)
  M09/M10/M11  tocca, scorri, scrivi (schermata scelta dall'utente, screenshot prima)
  M14/M15  screenshot non nero; compila_accesso senza password nel log
  S01  «Invia» senza conferma rifiutato (BLOCCATO DAL TELEFONO): rischioso da automatizzare
  S02  pannello: Invia manda (solo a te stesso)
  S05  bozza che scade a 2 minuti
  S09  logcat senza token, password, frasi dette
  U06-U11  Home (Jarvis e Postino, chip, cronologia con filtri), menu Agenti e chat, bolla trascinata, assistente, tema, notifica
  I02-I06  permessi su telefono pulito, modelli, riavvio; configurazione personale (scripts/configura-boss.sh)
  R01  «Jarvis, apri Spotify» e «manda un WhatsApp a me stesso: prova» (bozza, poi «annulla»)
MANO
[ $NKO -eq 0 ] && exit 0 || exit 1
