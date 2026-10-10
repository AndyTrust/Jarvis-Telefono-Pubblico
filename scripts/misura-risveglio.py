#!/usr/bin/env python3
"""Risveglio di JBoss: 10 richieste alla VPS, ognuna dopo Doze profondo forzato, batteria simulata scollegata.
Misura: collegamento_ms, risposta_ms dal log di JBoss; consumo stimato di JBoss (batterystats) prima/dopo.
Rimette sempre batteria e Doze come prima."""
import json, re, subprocess, sys, time

DEV = __import__("os").environ.get("ADB_TELEFONO", "192.168.1.119:44853")
FRASE = "sulla VPS dimmi solo l'ora del server, in tre parole"  # va al cervello della VPS, nessuna azione sul telefono


def adb(*a, t=60):
    return subprocess.run(["adb", "-s", DEV, *a], capture_output=True, text=True, timeout=t).stdout


def sh(c):
    return adb("shell", c)


def consumo():
    out = sh("dumpsys batterystats com.jarvis.telefono")
    m = re.search(r"Uid u0a643: ([\d.]+)", out) or re.search(r"UID u0a643: ([\d.]+)", out)
    cpu = re.search(r"Total cpu time: u=(\S+) s=(\S+)", out)
    return (float(m.group(1)) if m else None), (cpu.groups() if cpu else None)


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    frase = sys.argv[2] if len(sys.argv) > 2 else None
    righe = []
    try:
        sh("dumpsys battery unplug")
        prima = consumo()
        import os
        for i in range(n):
            # Solo a schermo spento (nessuno lo usa) e dopo il riposo del canale mani (3 minuti): risveglio vero.
            while "mWakefulness=Awake" in sh("dumpsys power | grep mWakefulness="):
                sh("dumpsys deviceidle unforce"); sh("dumpsys battery reset")
                print(json.dumps({"attesa": "il telefono è in uso"}), flush=True)
                time.sleep(30)
                sh("dumpsys battery unplug")
            sh("dumpsys deviceidle force-idle")
            time.sleep(200)
            if "mWakefulness=Awake" in sh("dumpsys power | grep mWakefulness="):
                print(json.dumps({"saltata": "schermo acceso durante l'attesa"}), flush=True)
                sh("dumpsys deviceidle unforce"); continue
            stato = re.search(r"mState=(\w+)", sh("dumpsys deviceidle | grep mState=")).group(1)
            t = sh("date '+%m-%d %H:%M:%S.000'").strip()
            t0 = time.time()
            # La frase come la manderebbe la voce dopo la trascrizione: passa da Instradamento («sulla VPS» → lavori)
            # oppure dal cervello mani. Qui: frase al nucleo.
            j = json.dumps({"action": "frase", "testo": frase or f"prova di risveglio JBoss numero {i + 1}: rispondi solo ok"}, ensure_ascii=False)
            sh("am broadcast -n com.jarvis.telefono/.mani.ProvaAdbReceiver --es comando '" + j + "'")
            mis = None
            while time.time() - t0 < 60:
                time.sleep(1)
                log = adb("logcat", "-d", "-T", t)
                m = re.findall(r"JarvisNucleo: misura: (vps .*)", log)
                if m:
                    mis = m[-1]
                    break
            riga = {"n": i + 1, "doze": stato, "misura": mis, "s": round(time.time() - t0, 1)}
            righe.append(riga)
            print(json.dumps(riga, ensure_ascii=False), flush=True)
            sh("dumpsys deviceidle unforce")
            time.sleep(5)
        dopo = consumo()
        print(json.dumps({"consumo_prima_mAh": prima[0], "consumo_dopo_mAh": dopo[0], "cpu_prima": prima[1], "cpu_dopo": dopo[1]}), flush=True)
    finally:
        sh("dumpsys deviceidle unforce")
        sh("dumpsys battery reset")


if __name__ == "__main__":
    main()
