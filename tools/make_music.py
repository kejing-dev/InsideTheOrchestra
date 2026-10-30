"""Arranges and renders Grieg's "In the Hall of the Mountain King" (1875, public domain) for the app.

Run:  python -I tools/make_music.py <out wav dir>
      (numpy only; the WAVs are then encoded to OGG by tools/encode_ogg.py inside Blender)

The arrangement is our own and table driven (design package piece.arrangement): the 32-beat theme is written
once, every pass says which parts play it (or an accompaniment figure), at what octave, loudness and tempo.
Sound is synthesised here (additive / plucked / noise models with a small room reverb) rather than taken from a
SoundFont: no FluidSynth or SoundFont is available offline on the build machine (recorded as a deviation).

Outputs
  <out>/<stem>.wav           10 mono stems, 32 kHz 16 bit, each scaled to the same loudness of its loud part
  app/src/main/assets/parts.bin   15 parts x N frames at 20 Hz, one byte each: bit 7 = pizzicato, bits 0-6 =
                                  envelope 0..127 (header: magic 'ORCP', u16 parts, u16 frames, u16 rate)
  app/src/main/assets/piece.json  duration, pass starts and bpm, captions, per-stem rests (for seeks)
"""
import json
import math
import os
import struct
import sys
import wave

import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SR = 32000
PARTS = ["V1", "V2", "VA", "VC", "CB", "FL", "OB", "CL", "BSN", "HN", "TPT", "TBN", "TUBA", "TIMP", "BD"]
STEMS = {
    "vln": ["V1", "V2"], "vla": ["VA"], "vc": ["VC"], "cb": ["CB"], "ww": ["FL", "OB", "CL", "BSN"],
    "hn": ["HN"], "tpt": ["TPT"], "lbr": ["TBN", "TUBA"], "timp": ["TIMP"], "bd": ["BD"],
}

# ---------------------------------------------------------------------------------------------
# the theme: 64 eighth notes (32 beats), semitones from B3 (MIDI 59); None = rest
# ---------------------------------------------------------------------------------------------
B, Cs, D, E, Fs, F, C, A, Bh = 0, 2, 3, 5, 7, 6, 1, 10, 12
PHRASE_A = [B, Cs, D, E, Fs, D, Fs, Fs,
            F, Cs, F, F, E, C, E, E,
            B, Cs, D, E, Fs, D, Fs, Bh,
            A, Fs, D, Fs, A, A, A, A]
PHRASE_B = PHRASE_A[:24] + [A, Fs, D, Fs, Fs - 12 + 12, Fs, Fs, Fs]
THEME = PHRASE_A + PHRASE_B
# held notes: an eighth equal to the previous one is a tie
# chord roots per beat (32 beats) for the accompaniment, semitones from B
ROOTS = [0, 0, 0, 0, 2, 2, 1, 1, 0, 0, 0, 0, 10, 10, 10, 10,
         0, 0, 0, 0, 2, 2, 1, 1, 0, 0, 0, 0, 7, 7, 7, 7]

# passes: (bars, bpm, dynamic 0..1, caption, focus) as in the design package
PASSES = [
    (2, 84, 0.18), (8, 84, 0.25), (8, 88, 0.33), (8, 96, 0.42), (8, 104, 0.52), (8, 114, 0.62),
    (8, 124, 0.72), (8, 136, 0.82), (8, 148, 0.90), (8, 160, 1.00), (6, 170, 1.00),
]
CAPTIONS = {
    0: ("圆号先吹出一个低低的长音，像山洞里的回声", "The horns hold one low note, like an echo in a cave", "br"),
    1: ("主题来了：大管和大提琴、低音提琴用手指拨弦，一步一步往前走", "The theme: bassoons with plucked cellos and basses, tiptoeing forward", "str"),
    3: ("旋律爬高了：小提琴、中提琴接过来，双簧管和单簧管也加入", "Higher now: violins and violas take over, oboe and clarinet join", "ww"),
    5: ("弦乐全部拿起弓来拉，长笛在最上面闪着光", "All strings switch to the bow; the flute sparkles on top", "str"),
    7: ("铜管和定音鼓进来了，山魔王们开始追了", "Brass and timpani arrive: the trolls give chase", "br"),
    9: ("最后一遍：大鼓和镲一起砸下来，全团最快最响", "Last time round: bass drum and cymbals crash in, everyone at full speed", "perc"),
    10: ("尾声：全团重击和弦，最后一下镲", "Coda: hammered chords and one last crash", None),
}

# per part: list of notes (t0 seconds, dur seconds, midi, velocity 0..1, articulation)
notes = {p: [] for p in PARTS}


def theme_notes(part, t0, spb, octave, vel, art, every=1):
    """The theme from t0 (seconds), eighth = spb / 2; ties merged; `octave` = MIDI of B."""
    e = spb / 2
    i = 0
    while i < len(THEME):
        n = THEME[i]
        j = i + 1
        while j < len(THEME) and THEME[j] == n and art != "pizz" and (j % 8) != 0:
            j += 1
        dur = (j - i) * e
        if art == "pizz":
            dur = e
        if i % every == 0:
            notes[part].append((t0 + i * e, dur * 0.92, octave + n, vel, art))
        i = j


def roots_notes(part, t0, spb, octave, vel, art, beats=32, step=1):
    for b in range(0, beats, step):
        notes[part].append((t0 + b * spb, spb * step * (0.4 if art == "pizz" else 0.9), octave + ROOTS[b % 32], vel, art))


def hold(part, t0, dur, midi, vel, art="sus"):
    notes[part].append((t0, dur, midi, vel, art))


t = 0.0
starts = []
for idx, (bars, bpm, dyn) in enumerate(PASSES):
    spb = 60.0 / bpm
    starts.append(round(t, 3))
    beats = bars * 4
    L = beats * spb
    v = dyn
    if idx == 0:
        hold("HN", t + 0.1, L - 0.2, 54, 0.30)                         # F#3 pp
    elif idx in (1, 2):
        theme_notes("VC", t, spb, 47, v, "pizz")                       # B2
        theme_notes("CB", t, spb, 35, v, "pizz")                       # B1
        theme_notes("BSN", t, spb, 47, v * 0.9, "tongue")
        if idx == 2:
            hold("HN", t, L, 54, 0.30)
    elif idx in (3, 4):
        theme_notes("V2", t, spb, 59, v, "pizz")                       # B3
        theme_notes("VA", t, spb, 59 - 12, v, "pizz")
        theme_notes("OB", t, spb, 71, v * 0.8, "tongue")
        theme_notes("CL", t, spb, 59, v * 0.8, "tongue")
        roots_notes("VC", t, spb, 47, v * 0.8, "pizz")
        roots_notes("CB", t, spb, 35, v * 0.8, "pizz")
        theme_notes("BSN", t, spb, 47, v * 0.6, "tongue")
        hold("HN", t, L, 54, 0.32)
    else:
        a = "arco"
        theme_notes("V1", t, spb, 71, v, a)                            # B4
        theme_notes("V2", t, spb, 59, v, a)
        theme_notes("VA", t, spb, 59, v * 0.9, a)
        theme_notes("VC", t, spb, 47, v, a)
        roots_notes("CB", t, spb, 35, v, a)
        theme_notes("FL", t, spb, 83, v * 0.8, "tongue")               # B5
        if idx >= 6:
            theme_notes("OB", t, spb, 71, v * 0.8, "tongue")
            theme_notes("CL", t, spb, 59, v * 0.8, "tongue")
            theme_notes("BSN", t, spb, 47, v * 0.8, "tongue")
            for b in range(0, beats, 4):
                hold("HN", t + b * spb, spb * 3.8, 54, v * 0.7)
        if idx >= 7 and idx < 10:
            theme_notes("TPT", t, spb, 71, v * 0.9, "tongue")
            theme_notes("TBN", t, spb, 47, v * 0.9, "tongue")
            for b in range(0, beats, 2):
                notes["TIMP"].append((t + b * spb, spb * 1.5, 47 if ROOTS[b % 32] in (0, 10) else 42, v, "hit"))
        if idx >= 8 and idx < 10:
            theme_notes("TUBA", t, spb, 35, v * 0.9, "tongue")
        if idx == 9:
            for b in range(0, beats, 4):
                notes["BD"].append((t + b * spb, 1.6, 30, v, "bd"))
                notes["BD"].append((t + b * spb, 2.2, 0, v * 0.8, "cym"))
        if idx == 10:
            # coda: hammered B minor chords on the beat, then a held final chord; timpani roll; last crash
            for p in ("V1", "V2", "VA", "VC", "CB", "FL", "OB", "CL", "BSN", "HN", "TPT", "TBN", "TUBA"):
                notes[p] = [n for n in notes[p] if n[0] < t]
            chord = {"V1": 71, "V2": 66, "VA": 62, "VC": 47, "CB": 35, "FL": 83, "OB": 78, "CL": 74, "BSN": 47,
                     "HN": 62, "TPT": 71, "TBN": 54, "TUBA": 35}
            for p, m in chord.items():
                art = "arco" if p in ("V1", "V2", "VA", "VC", "CB") else "tongue"
                for b in range(0, 16, 2):
                    notes[p].append((t + b * spb, spb * 0.6, m, 1.0, art))
                notes[p].append((t + 16 * spb, spb * 7.0, m, 1.0, art))
            for k in range(int(16 * spb / 0.07)):
                notes["TIMP"].append((t + k * 0.07, 0.25, 47, 0.45 + 0.4 * k / (16 * spb / 0.07), "hit"))
            notes["TIMP"].append((t + 16 * spb, 2.5, 47, 1.0, "hit"))
            notes["BD"].append((t, 1.6, 30, 1.0, "bd"))
            notes["BD"].append((t + 16 * spb, 2.5, 30, 1.0, "bd"))
            notes["BD"].append((t + 16 * spb, 4.0, 0, 1.0, "cym"))
    t += L
DURATION = t + 3.0

# ---------------------------------------------------------------------------------------------
# instruments
# ---------------------------------------------------------------------------------------------
rng = np.random.default_rng(7)


def mfreq(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def adsr(n, sr, a, d, s, r, dur):
    t = np.arange(n) / sr
    env = np.where(t < a, t / max(a, 1e-4), s + (1 - s) * np.exp(-(t - a) / max(d, 1e-4)))
    rel = np.clip((t - dur) / max(r, 1e-4), 0, 1)
    return env * (1 - rel) ** 2


HARM = {
    "str": [1, .7, .5, .42, .33, .28, .22, .18, .14, .11, .08, .06],
    "FL": [1, .25, .08, .03],
    "OB": [.5, 1, .8, .6, .45, .3, .2, .12],
    "CL": [1, .05, .55, .04, .32, .03, .18, .02, .1],
    "BSN": [.7, 1, .7, .55, .4, .3, .2, .12, .08],
    "brass": [1, .8, .65, .55, .45, .35, .28, .2, .14, .1],
}


def additive(f, n, harm, vel, vib=0.0, bright=1.0):
    t = np.arange(n) / SR
    ph = 2 * np.pi * f * t
    if vib:
        ph = ph + (vib * f / 5.5) * np.sin(2 * np.pi * 5.5 * t) * np.clip(t / 0.3, 0, 1)
    y = np.zeros(n)
    for k, a in enumerate(harm, 1):
        if f * k > SR * 0.45:
            break
        y += a * (bright ** (k - 1)) * np.sin(k * ph + 0.3 * k)
    return y


def render_note(part, dur, m, vel, art):
    f = mfreq(m)
    if art == "pizz":
        n = int(SR * 0.9)
        t = np.arange(n) / SR
        y = np.zeros(n)
        for k in range(1, 9):
            y += (1.0 / k) * np.exp(-t * (3.0 + 2.5 * k) * (1 + f / 300)) * np.sin(2 * np.pi * f * k * t)
        return y * vel * 0.9
    if art == "hit":  # timpani: pitched modes, fast decay
        n = int(SR * (dur + 0.6))
        t = np.arange(n) / SR
        y = sum(a * np.exp(-t / tau) * np.sin(2 * np.pi * f * r * t) for r, a, tau in
                [(1, 1, .9), (1.5, .5, .5), (1.98, .35, .35), (2.44, .2, .25)])
        y += 0.3 * rng.standard_normal(n) * np.exp(-t / 0.02)
        return y * vel
    if art == "bd":
        n = int(SR * dur)
        t = np.arange(n) / SR
        y = np.sin(2 * np.pi * (48 + 30 * np.exp(-t / 0.05)) * t) * np.exp(-t / 0.5)
        y += 0.25 * rng.standard_normal(n) * np.exp(-t / 0.03)
        return y * vel * 1.3
    if art == "cym":
        n = int(SR * dur)
        t = np.arange(n) / SR
        w = rng.standard_normal(n)
        w = np.diff(np.concatenate([[0], w]))  # brighten
        return w * np.exp(-t / 0.9) * vel * 0.5
    fam = "str" if part in ("V1", "V2", "VA", "VC", "CB") else ("brass" if part in ("HN", "TPT", "TBN", "TUBA") else part)
    rel = 0.12 if fam == "str" else 0.08
    n = int(SR * (dur + rel))
    if fam == "str":
        y = additive(f, n, HARM["str"], vel, vib=0.006, bright=0.85 + 0.1 * vel)
        y += 0.03 * rng.standard_normal(n)
        env = adsr(n, SR, 0.05, 0.3, 0.85, rel, dur)
    elif fam == "brass":
        bright = 0.55 + 0.4 * vel
        if part == "HN":
            bright *= 0.7
        y = additive(f, n, HARM["brass"], vel, vib=0.002, bright=bright)
        env = adsr(n, SR, 0.04, 0.2, 0.8, rel, dur)
    else:
        y = additive(f, n, HARM[fam], vel, vib=0.004 if fam == "FL" else 0.002)
        if fam == "FL":
            y += 0.05 * rng.standard_normal(n)
        env = adsr(n, SR, 0.03, 0.25, 0.85, rel, dur)
    return y * env * vel


def lowpass(x, fc):
    # gentle 2nd-order-like roll-off applied in the frequency domain (the whole part at once)
    X = np.fft.rfft(x)
    fr = np.fft.rfftfreq(len(x), 1 / SR)
    X *= 1 / np.sqrt(1 + (fr / fc) ** 4)
    return np.fft.irfft(X, len(x))


def reverb(x, wet=0.25, t60=1.6):
    n = int(SR * t60)
    t = np.arange(n) / SR
    ir = rng.standard_normal(n) * np.exp(-6.9 * t / t60)
    ir[: int(SR * 0.012)] = 0
    ir /= np.sqrt(np.sum(ir ** 2))
    L = len(x) + n
    nf = 1 << (L - 1).bit_length()
    y = np.fft.irfft(np.fft.rfft(x, nf) * np.fft.rfft(ir, nf), nf)[: len(x)]
    return x + wet * y


N = int(SR * DURATION)
part_audio = {}
LP = {"V1": 7000, "V2": 6500, "VA": 5000, "VC": 4000, "CB": 2500, "FL": 8000, "OB": 7000, "CL": 6000,
      "BSN": 4000, "HN": 2500, "TPT": 7000, "TBN": 4500, "TUBA": 2000, "TIMP": 4000, "BD": 9000}
for p in PARTS:
    buf = np.zeros(N)
    for (t0, dur, m, vel, art) in notes[p]:
        y = render_note(p, dur, m, vel, art)
        i0 = int(t0 * SR)
        i1 = min(N, i0 + len(y))
        buf[i0:i1] += y[: i1 - i0]
    part_audio[p] = lowpass(buf, LP[p])
    print("part", p, "notes", len(notes[p]), flush=True)

# ---------------------------------------------------------------------------------------------
# envelopes (20 Hz) from the note lists, not from the mix: what each player is doing right now
# ---------------------------------------------------------------------------------------------
RATE = 20
F = int(math.ceil(DURATION * RATE))
env = np.zeros((len(PARTS), F))
pizz = np.zeros((len(PARTS), F), dtype=bool)
for pi, p in enumerate(PARTS):
    for (t0, dur, m, vel, art) in notes[p]:
        decay = 0.25 if art in ("pizz", "hit") else (0.5 if art in ("bd", "cym") else None)
        f0 = int(t0 * RATE)
        span = int((dur if decay is None else max(dur, decay * 2)) * RATE) + 1
        for k in range(span):
            fi = f0 + k
            if fi >= F:
                break
            tt = k / RATE
            e = vel * (math.exp(-tt / decay) if decay else (1.0 if tt < dur else 0.0))
            if e > env[pi, fi]:
                env[pi, fi] = e
            if art == "pizz":
                pizz[pi, fi] = True
env = np.clip(env, 0, 1)
with open(os.path.join(ASSETS, "parts.bin"), "wb") as fh:
    fh.write(b"ORCP" + struct.pack("<HHH", len(PARTS), F, RATE))
    for pi in range(len(PARTS)):
        row = (np.round(env[pi] * 127).astype(np.uint8) | (pizz[pi].astype(np.uint8) << 7))
        fh.write(row.tobytes())

# ---------------------------------------------------------------------------------------------
# stems: mix parts, reverb, same loudness (RMS of the loudest 30 % of 400 ms windows)
# ---------------------------------------------------------------------------------------------
out = sys.argv[1]
os.makedirs(out, exist_ok=True)
LOUD_RMS = 0.30
KNEE = 0.70
CEIL = 0.97
rests = {}
for s, ps in STEMS.items():
    y = sum(part_audio[p] for p in ps)
    y = reverb(y, 0.25)
    w = int(SR * 0.4)
    k = len(y) // w
    r = np.sqrt(np.mean(y[: k * w].reshape(k, w) ** 2, axis=1))
    loud = np.sort(r)[int(k * 0.7):]
    ref = np.sqrt(np.mean(loud ** 2)) + 1e-9
    # v0.1.4 (user: "声音太小了 ... 至少大 3-5 倍"): loud-part RMS 0.30 (-10.5 dBFS, was 0.12 / -18.4 dBFS), peaks
    # through a soft limiter (tanh knee) instead of scaling the whole stem down
    y = y * (LOUD_RMS / ref)
    y = np.where(np.abs(y) < KNEE, y, np.sign(y) * (KNEE + (CEIL - KNEE) * np.tanh((np.abs(y) - KNEE) / (CEIL - KNEE))))
    pk = np.max(np.abs(y))
    pcm = (np.clip(y, -1, 1) * 32767).astype("<i2")
    with wave.open(os.path.join(out, s + ".wav"), "wb") as wf:
        wf.setnchannels(1); wf.setsampwidth(2); wf.setframerate(SR); wf.writeframes(pcm.tobytes())
    # rests: >= 0.3 s where every part of this stem is silent (envelope frames)
    idx = [PARTS.index(p) for p in ps]
    silent = np.all(env[idx] < 0.02, axis=0)
    rs, i = [], 0
    while i < F:
        if silent[i]:
            j = i
            while j < F and silent[j]:
                j += 1
            if (j - i) / RATE >= 0.3:
                rs.append([round(i / RATE, 2), round(j / RATE, 2)])
            i = j
        else:
            i += 1
    rests[s] = rs
    print("stem", s, "peak", round(float(pk), 3), "rests", len(rs), flush=True)

piece = {
    "id": "mountain-king",
    "durationS": round(DURATION, 2),
    "parts": PARTS,
    "envelopeRate": RATE,
    "passes": [{"pass": i, "startS": starts[i], "bpm": PASSES[i][1],
                "captionZh": CAPTIONS.get(i, (None,))[0], "captionEn": CAPTIONS.get(i, (None, None))[1],
                "focus": CAPTIONS.get(i, (None, None, None))[2]} for i in range(len(PASSES))],
    "rests": rests,
}
json.dump(piece, open(os.path.join(ASSETS, "piece.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("DURATION", round(DURATION, 2), "starts", starts)
