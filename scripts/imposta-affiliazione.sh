#!/usr/bin/env bash
# Mette il link di affiliazione della VPS in tutti i documenti.
#
#   bash scripts/imposta-affiliazione.sh            # usa docs/affiliazione.txt
#   bash scripts/imposta-affiliazione.sh --controlla # dice solo dove sta il link, non cambia niente
#
# Il link sta in UN solo file: docs/affiliazione.txt (prima riga). Nei documenti il link sta fra due segni:
#   <!--VPS-->…<!--/VPS-->
# Fra i due segni ci può essere il segnaposto {{LINK_AFFILIAZIONE_VPS}} o un link messo prima: si sostituisce
# tutto con quello del file. Per cambiare link: modifica docs/affiliazione.txt e rilancia lo script.
set -euo pipefail
cd "$(dirname "$0")/.."
FILE=docs/affiliazione.txt
[ -f "$FILE" ] || { echo "Manca $FILE: scrivi lì il link (una riga)."; exit 1; }
LINK="$(head -1 "$FILE" | tr -d '[:space:]')"
case "$LINK" in https://*) ;; *) echo "Il link in $FILE deve cominciare con https://"; exit 1 ;; esac
if [ "${1:-}" = "--controlla" ]; then
  grep -rn --include='*.md' -o '<!--VPS-->[^<]*<!--/VPS-->' . || echo "Nessun segno <!--VPS--> nei documenti."
  exit 0
fi
python3 - "$LINK" <<'PY'
import pathlib, re, sys
link = sys.argv[1]
segno = re.compile(r"<!--VPS-->[^<]*<!--/VPS-->")
n = 0
for p in pathlib.Path(".").rglob("*.md"):
    if any(x in p.parts for x in (".git", "build", "node_modules")):
        continue
    s = p.read_text(encoding="utf-8")
    nuovo, k = segno.subn(f"<!--VPS-->{link}<!--/VPS-->", s)
    if k:
        p.write_text(nuovo, encoding="utf-8")
        print(f"{p}: {k} link")
        n += k
print(f"Fatto: {n} link aggiornati.")
PY
