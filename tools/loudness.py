"""Loudness evidence (B20 / ui_gates loudness) for an app with short feedback sounds only (2026-11-05 rule, 交警手势口径):
renders the sounds of a typical stretch of play at the default volume (gain 1.0, the SoundPlayer top step) in the app's
order, with the app's +-5 % rate variation, keeps only the voiced parts (|x| above -40 dBFS, gaps trimmed to 40 ms),
and prints RMS / peak of that voiced track. Then the D51 burst check: the same event 5 times 80 ms apart with the app's
one-instance rule (a new play stops the previous one). Rendered, not recorded: kj-mbp-m5 has no audio loopback.

  python3 tools/loudness.py OUT.wav
"""
import math, os, random, struct, sys, wave
import numpy as np

SND = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "sounds")
SR = 22050


def load(name):
    with wave.open(os.path.join(SND, name + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def variants(name):
    return [n for n in (name, name + "_2", name + "_3") if os.path.exists(os.path.join(SND, n + ".wav"))]


def rate(x, r):
    idx = (np.arange(int(len(x) / r)) * r).astype(int)
    return x[np.minimum(idx, len(x) - 1)]


def voiced(x, thr=10 ** (-40 / 20), gap=0.04):
    out, quiet = [], 0
    for v in x:
        if abs(v) < thr:
            quiet += 1
            if quiet > gap * SR:
                continue
        else:
            quiet = 0
        out.append(v)
    return out


def main(out):
    rnd = random.Random(5)
    step = ["turn", "drop"]
    order = step * 4 + ["reject"] + step * 3 + ["hole", "take"] + step * 4 + ["take", "drop"] + step * 3 + ["lid", "solved"]
    seq = []
    for name in order:
        x = load(rnd.choice(variants(name)))
        if name != "solved":
            x = rate(x, 0.95 + rnd.random() * 0.10)
        seq += voiced(x)
    seq = np.array(seq)
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(seq, -1, 1) * 32767).astype("<i2").tobytes())
    rms = math.sqrt(float((seq ** 2).mean())); pk = float(np.abs(seq).max())
    print("voiced %.1f s  RMS %.1f dBFS  peak %.1f dBFS  (rendered, default volume)" % (len(seq) / SR, 20 * math.log10(rms), 20 * math.log10(pk)))
    for ev in ("drop", "turn", "reject", "take"):
        mix = np.zeros(int(1.5 * SR))
        for k in range(5):
            x = load(variants(ev)[k % len(variants(ev))])
            s = int(k * 0.08 * SR)
            end = s + len(x) if k == 4 else min(s + len(x), int((k + 1) * 0.08 * SR))  # one instance: the next stops it
            mix[s:end] += x[:end - s]
        print("burst x5 %-5s peak %.1f dBFS" % (ev, 20 * math.log10(np.abs(mix).max())))


main(sys.argv[1])
