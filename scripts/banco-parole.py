#!/usr/bin/env python3
"""Banco della parola di attivazione di JBoss (0.6.0, 08/10: «solo Hey Boss e Hey JBoss»).

Passa al KeywordSpotter (lo stesso modello e lo stesso keywords.txt dell'APK) tre gruppi di frasi:
  DEVE SCATTARE   «Hey Boss», «Hey JBoss», «JBoss» dette dalle voci italiane del Mac (say)
  NON DEVE        «Ok boss lo faccio io», «Hey Jarvis», «Jarvis», frasi comuni (voci del Mac)
  NON DEVE        le tue registrazioni vere in $BANCO_REGISTRAZIONI (di base ~/registrazioni-voce) (parlato di tutti i giorni)
Esce con 1 se una frase NON DEVE scatta o se le giuste prese sono sotto la soglia.

Uso:  python3 scripts/banco-parole.py [--keywords file] [--min 0.7]
Serve sherpa_onnx (c'è nel venv di backtalk). I file audio si fanno in una cartella temporanea e si buttano.
"""
import argparse
import glob
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np
import sherpa_onnx

QUI = os.path.dirname(os.path.abspath(__file__))
K = os.path.join(QUI, "..", "app", "src", "main", "assets", "kws-model")
VOCI = ["Alice", "Eddy (Italiano (Italia))", "Flo (Italiano (Italia))", "Reed (Italiano (Italia))",
        "Rocko (Italiano (Italia))", "Sandy (Italiano (Italia))", "Shelley (Italiano (Italia))"]
# «JBoss» scritto così le voci del Mac lo leggono lettera per lettera: si scrive come si pronuncia.
SI = ["Hey Boss", "Ehi Boss", "Hey Jey Boss", "Jey Boss", "Hey Gei Boss"]
NO = ["Ok boss lo faccio io", "Ok boss", "Hey Jarvis", "Jarvis", "Ehi Jarvis", "Jarvis apri la posta",
      "Il boss arriva domani", "Che bella giornata di sole", "Dammi il conto per favore", "Ciao a tutti come stai oggi",
      "Vorrei una pizza margherita", "Ho visto Marco alla stazione", "Il servizio è pronto per la cena"]


def spotter(keywords):
    return sherpa_onnx.KeywordSpotter(
        tokens=f"{K}/tokens.txt", encoder=f"{K}/encoder.onnx", decoder=f"{K}/decoder.onnx", joiner=f"{K}/joiner.onnx",
        keywords_file=keywords, num_threads=2, provider="cpu", keywords_score=1.5, keywords_threshold=0.25)


def leggi(f):
    w = wave.open(f)
    if w.getnchannels() != 1:
        return None, None
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    return x, w.getframerate()


def scatta(sp, x, sr):
    s = sp.create_stream()
    s.accept_waveform(sr, np.zeros(int(sr * 0.3), dtype=np.float32))
    s.accept_waveform(sr, x)
    s.accept_waveform(sr, np.zeros(int(sr * 0.8), dtype=np.float32))
    s.input_finished()
    trovata = ""
    while sp.is_ready(s):
        sp.decode_stream(s)
        r = sp.get_result(s)
        if r:
            trovata = trovata or r
            sp.reset_stream(s)
    return trovata


def sintetizza(cartella, voce, frase, velocita):
    nome = os.path.join(cartella, f"{voce.split()[0]}_{frase.replace(' ', '_')}_{velocita}.wav")
    subprocess.run(["say", "-v", voce, "-r", str(velocita), "--file-format=WAVE", "--data-format=LEI16@16000",
                    "-o", nome, frase], check=True, capture_output=True)
    return nome


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--keywords", default=f"{K}/keywords.txt")
    a.add_argument("--min", type=float, default=0.5, help="quota minima di frasi giuste prese (0-1)")
    a.add_argument("--senza-boss", action="store_true", help="salta le tue registrazioni vere")
    o = a.parse_args()
    sp = spotter(o.keywords)
    falsi, prese, totale_si = [], 0, 0
    with tempfile.TemporaryDirectory() as tmp:
        for voce in VOCI:
            for vel in (150, 185):
                for frase in SI + NO:
                    try:
                        f = sintetizza(tmp, voce, frase, vel)
                    except subprocess.CalledProcessError:
                        continue
                    x, sr = leggi(f)
                    if x is None:
                        continue
                    r = scatta(sp, x, sr)
                    if frase in SI:
                        totale_si += 1
                        prese += bool(r)
                    elif r:
                        falsi.append(f"{voce.split()[0]} r{vel} «{frase}» → {r}")
    boss = [] if o.senza_boss else sorted(glob.glob(os.path.join(os.environ.get("BANCO_REGISTRAZIONI", os.path.expanduser("~/registrazioni-voce")), "*.wav")))
    falsi_boss = []
    for f in boss:
        x, sr = leggi(f)
        if x is None:
            continue
        r = scatta(sp, x, sr)
        if r:
            falsi_boss.append(f"{os.path.basename(f)} → {r}")
    quota = prese / totale_si if totale_si else 0
    print(f"giuste prese: {prese} su {totale_si} ({quota:.0%})")
    print(f"scatti sbagliati (voci del Mac): {len(falsi)}")
    for r in falsi:
        print("   ", r)
    print(f"scatti sbagliati (registrazioni vere, {len(boss)}): {len(falsi_boss)}")
    for r in falsi_boss[:20]:
        print("   ", r)
    print("NOTA: le registrazioni vere possono contenere davvero «Boss»/«JBoss»: ogni riga sopra va ascoltata.")
    ok = not falsi and quota >= o.min
    print("ESITO:", "VERDE" if ok else "ROSSO")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
