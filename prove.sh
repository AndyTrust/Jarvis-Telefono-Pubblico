#!/usr/bin/env bash
# Le prove di Jarvis Telefono. Girano sulla JVM: niente telefono,
# niente emulatore, pochi secondi.
#
#   bash <cartella del repo>/prove.sh
#
# Esiste per una ragione sola: su alcuni computer `java` non è nel PATH, e senza
# JAVA_HOME Gradle muore con «Unable to locate a Java Runtime», che non dice
# a nessuno cosa fare. Il JDK c'è, installato con brew, ed è la stessa
# versione che usa la CI (temurin 17).
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  for c in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
           /opt/homebrew/Cellar/openjdk@17/*/libexec/openjdk.jdk/Contents/Home \
           /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    [ -x "$c/bin/java" ] && JAVA_HOME="$c" && break
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "🔴 Non trovo un JDK 17. Si installa con:  brew install openjdk@17"
  exit 1
fi
export JAVA_HOME
echo "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

./gradlew test --console=plain "$@"

# Il riassunto che Gradle non stampa: quante sono andate e quante no.
python3 - <<'PY'
import glob, pathlib, re
tot = rotte = 0
nomi = []
for f in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    x = pathlib.Path(f).read_text()
    m = re.search(r'tests="(\d+)".*?failures="(\d+)".*?errors="(\d+)"', x)
    if m:
        tot += int(m.group(1)); rotte += int(m.group(2)) + int(m.group(3))
    nomi += re.findall(r'testcase name="([^"]+)"', x)
for n in sorted(nomi):
    print("  ok", n)
print(f"\n{tot - rotte}/{tot} prove passate")
PY
