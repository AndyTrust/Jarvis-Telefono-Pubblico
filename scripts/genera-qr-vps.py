#!/usr/bin/env python3
"""Codice QR per abbinare JBoss alla VPS (JBoss 0.4.0, 2026-10-07).

    python3 scripts/genera-qr-vps.py                 # chiede un codice alla VPS e mostra il QR
    python3 scripts/genera-qr-vps.py --url wss://…/phone
    python3 scripts/genera-qr-vps.py --non-aprire    # niente finestra: solo il QR nel terminale
    python3 scripts/genera-qr-vps.py --verifica      # rilegge il PNG con il lettore di macOS (prova)

Cosa fa:
1. legge il token del ponte da ~/.env.jarvis (JARVIS_AGENT__PHONE_TOKEN) SENZA stamparlo e l'indirizzo da
   config-boss.json (chiave «vps_url») o da --url;
2. apre il WebSocket del ponte come «lavori» e chiede un codice monouso (abbina_crea): 8 caratteri, valido
   5 minuti, un solo uso (server/abbina.js in jarvis-agent);
3. mostra il QR con indirizzo e codice (MAI il token) nel terminale e in un PNG temporaneo; sotto, lo stesso
   testo da incollare nell'app se la fotocamera non va.
Nell'app: Configurazione → Cervello → La mia VPS → «Scansiona QR» (o «Incolla»). Il QR si genera con
CoreImage di macOS (swift): nessuna libreria da installare.
"""
import argparse
import asyncio
import json
import os
import struct
import subprocess
import sys
import tempfile
import time
import urllib.parse
import zlib

ENV = os.environ.get("JBOSS_ENV", os.path.expanduser("~/.env.jarvis"))
CONFIG = os.path.join(os.environ.get("JBOSS_CARTELLA", os.path.expanduser("~/.jboss")), "config-boss.json")
SCHEMA = "jboss-vps:1"


def token_ponte() -> str:
    tok = ""
    with open(ENV, encoding="utf-8") as f:
        for riga in f:
            if riga.startswith("JARVIS_AGENT__PHONE_TOKEN="):
                tok = riga.split("=", 1)[1].strip().strip('"').strip("'")
    if len(tok) < 16:
        sys.exit("KO token JARVIS_AGENT__PHONE_TOKEN mancante in ~/.env.jarvis")
    return tok


def indirizzo(da_riga: str) -> str:
    url = da_riga or ""
    if not url and os.path.isfile(CONFIG):
        url = json.load(open(CONFIG, encoding="utf-8")).get("vps_url", "")
    if not url.startswith("wss://"):
        sys.exit("KO serve l'indirizzo wss://…/phone (--url o «vps_url» in config-boss.json)")
    return url


def testo_qr(url: str, codice: str) -> str:
    """Il contenuto del QR: lo legge vps/Abbinamento.kt (leggi). Solo indirizzo e codice."""
    return f"{SCHEMA}?u={urllib.parse.quote(url, safe='')}&c={codice}"


async def chiedi_codice(url: str, token: str) -> dict:
    import websockets  # pip websockets (già sul Mac)

    async with websockets.connect(url, open_timeout=15) as ws:
        await ws.send(json.dumps({"type": "auth", "token": token, "ruolo": "lavori", "versione": 1}))
        token = ""
        fine = time.time() + 15
        while time.time() < fine:
            o = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
            if o.get("type") == "connected":
                await ws.send(json.dumps({"type": "abbina_crea"}))
            elif o.get("type") == "abbina_codice":
                return o
    sys.exit("KO la VPS non ha dato il codice (ponte vecchio senza abbinamento?)")


SWIFT_MATRICE = r"""
import CoreImage
import Foundation
let dati = CommandLine.arguments[1].data(using: .utf8)!
let f = CIFilter(name: "CIQRCodeGenerator")!
f.setValue(dati, forKey: "inputMessage")
f.setValue("M", forKey: "inputCorrectionLevel")
let img = f.outputImage!
let ctx = CIContext()
let r = img.extent
let cg = ctx.createCGImage(img, from: r)!
let w = cg.width, h = cg.height
var px = [UInt8](repeating: 0, count: w * h * 4)
let cs = CGColorSpaceCreateDeviceRGB()
let bm = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: cs,
                   bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
bm.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
for y in 0..<h {
  var s = ""
  for x in 0..<w { s += px[(y * w + x) * 4] < 128 ? "1" : "0" }
  print(s)
}
"""

SWIFT_LEGGI = r"""
import CoreImage
import Foundation
let u = URL(fileURLWithPath: CommandLine.arguments[1])
let img = CIImage(contentsOf: u)!
let d = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])!
for f in d.features(in: img) { if let q = f as? CIQRCodeFeature { print(q.messageString ?? "") } }
"""


def swift(codice_swift: str, *argomenti: str) -> str:
    with tempfile.NamedTemporaryFile("w", suffix=".swift", delete=False) as f:
        f.write(codice_swift)
        nome = f.name
    try:
        r = subprocess.run(["swift", nome, *argomenti], capture_output=True, text=True, timeout=120)
        if r.returncode != 0:
            raise RuntimeError(r.stderr.strip()[-300:])
        return r.stdout
    finally:
        os.unlink(nome)


def matrice(testo: str) -> list:
    righe = [r for r in swift(SWIFT_MATRICE, testo).splitlines() if r]
    return [[c == "1" for c in r] for r in righe]


def stampa(m: list) -> None:
    """Due righe di moduli per riga di terminale, con il bordo bianco (zona di quiete) di 2 moduli."""
    n = len(m)
    bianca = [False] * (n + 4)
    g = [bianca, bianca] + [[False, False] + r + [False, False] for r in m] + [bianca, bianca, bianca]
    for y in range(0, len(g) - 1, 2):
        sopra, sotto = g[y], g[y + 1]
        riga = ""
        for a, b in zip(sopra, sotto):
            # nero = modulo pieno: si disegna in bianco ciò che è chiaro (terminale scuro o chiaro: fondo invertito)
            riga += {(False, False): "█", (True, True): " ", (False, True): "▀", (True, False): "▄"}[(a, b)]
        print("   " + riga)


def png(m: list, percorso: str, scala: int = 12, bordo: int = 4) -> None:
    n = len(m) + 2 * bordo
    lato = n * scala
    grezzo = bytearray()
    for y in range(lato):
        grezzo.append(0)
        my = y // scala - bordo
        for x in range(lato):
            mx = x // scala - bordo
            nero = 0 <= my < len(m) and 0 <= mx < len(m) and m[my][mx]
            grezzo.append(0 if nero else 255)

    def blocco(tipo: bytes, dati: bytes) -> bytes:
        return struct.pack(">I", len(dati)) + tipo + dati + struct.pack(">I", zlib.crc32(tipo + dati) & 0xFFFFFFFF)

    with open(percorso, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(blocco(b"IHDR", struct.pack(">IIBBBBB", lato, lato, 8, 0, 0, 0, 0)))
        f.write(blocco(b"IDAT", zlib.compress(bytes(grezzo), 9)))
        f.write(blocco(b"IEND", b""))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="")
    ap.add_argument("--non-aprire", action="store_true")
    ap.add_argument("--verifica", action="store_true")
    a = ap.parse_args()

    url = indirizzo(a.url)
    o = asyncio.run(chiedi_codice(url, token_ponte()))
    codice = o["codice"]
    scade = time.strftime("%H:%M", time.localtime(o["scade_ts"] / 1000))
    contenuto = testo_qr(url, codice)
    m = matrice(contenuto)

    cartella = tempfile.mkdtemp(prefix="jboss-qr-")
    percorso = os.path.join(cartella, "abbina-jboss.png")
    png(m, percorso)

    print("\nJBoss: inquadra questo codice dall'app (Configurazione → Cervello → La mia VPS → Scansiona QR)\n")
    stampa(m)
    print(f"\nValido fino alle {scade}, una volta sola. Contiene l'indirizzo e un codice, non il token.")
    print(f"Se la fotocamera non va, nell'app tocca «Incolla» e scrivi:\n   indirizzo: {url}\n   codice:    {codice[:4]}-{codice[4:]}")
    print(f"Immagine: {percorso}")
    if a.verifica:
        letto = swift(SWIFT_LEGGI, percorso).strip()
        print("VERIFICA OK: il lettore di macOS legge lo stesso testo" if letto == contenuto else "VERIFICA KO: testo letto diverso")
    if not a.non_aprire:
        subprocess.run(["open", percorso], check=False)


if __name__ == "__main__":
    main()
