#!/usr/bin/env bash
# Prove JVM dell'app (nessun telefono): :app:testDebugUnitTest con il JDK 17 di brew.
#
#   bash scripts/prove-jvm.sh            # tutte
#   bash scripts/prove-jvm.sh --tests '*GlossarioTest'
#
# Esito 0 solo se tutte le prove passano. In fondo stampa quante sono e quante rotte.
# Linea di base Jarvis Telefono 0.1.0 (passo A): 238 prove, 0 rotte, 1 saltata.
set -u
cd "$(dirname "$0")/.."

JDK=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
if [ -x "$JDK/bin/java" ]; then
  export JAVA_HOME="$JDK"
elif [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  echo "KO: non trovo il JDK 17 in $JDK (brew install openjdk@17)"
  exit 2
fi
echo "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

inizio=$(date +%s)
./gradlew :app:testDebugUnitTest --console=plain "$@"
esito=$?

python3 - <<'PY'
import glob, pathlib, re
tot = rotte = salt = 0
for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    x = pathlib.Path(f).read_text(errors="replace")
    m = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', x)
    if m:
        tot += int(m.group(1)); salt += int(m.group(2)); rotte += int(m.group(3)) + int(m.group(4))
print(f"Prove JVM: {tot} eseguite, {rotte} rotte, {salt} saltate")
PY
echo "Durata: $(( $(date +%s) - inizio )) s"
[ $esito -eq 0 ] && echo "ESITO: OK" || echo "ESITO: KO (gradle esce con $esito)"
exit $esito
