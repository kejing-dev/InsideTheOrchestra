"""Renders the app's repertoire (tools/repertoire.py, six public-domain pieces) with sampled instruments.

Run:  python tools/make_music.py <GeneralUser-GS.sf2> [qa dir]
      needs numpy, soundfile (OGG Vorbis) and tinysoundfont (pip); matplotlib for the QA spectrograms

v0.1.5 (user 2026-11-05: "音色也要调整更自然好听"): the notes are played through a sampled General MIDI bank
(GeneralUser GS v2.0.3 by S. Christian Collins, free for commercial use, see app/src/main/assets/credits.txt)
instead of the old additive / noise synthesis. Every part gets its own sampled instrument: string section,
pizzicato, flute, oboe, clarinet, bassoon, French horns, trumpet, trombone, tuba, timpani, and the GS orchestral
kit for bass drum and cymbals. A small room reverb is added here (the bank itself is dry).

Outputs (per piece <id>)
  app/src/main/assets/audio/<id>/<stem>.ogg   10 mono stems, 32 kHz, each scaled to the same loudness of its loud part
  app/src/main/assets/pieces/<id>/parts.bin   15 parts x N frames at 20 Hz, one byte each: bit 7 = pizzicato,
                                              bits 0-6 = envelope 0..127 (header 'ORCP', u16 parts, u16 frames, u16 rate)
  app/src/main/assets/pieces/<id>/piece.json  title, composer, duration, passes (start, bpm, caption, focus), rests,
                                              what each family does in this piece
  app/src/main/assets/pieces/index.json       the list, in menu order
  [qa dir]/<id>/<stem>-10s.wav + -last15s.wav + spectrogram png; [qa dir]/<id>-mix.wav
"""
import json
import math
import os
import struct
import sys

import numpy as np
import soundfile as sf
import tinysoundfont
from scipy.signal import lfilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import repertoire  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SR = 32000
PARTS = repertoire.PARTS
STEMS = {
    "vln": ["V1", "V2"], "vla": ["VA"], "vc": ["VC"], "cb": ["CB"], "ww": ["FL", "OB", "CL", "BSN"],
    "hn": ["HN"], "tpt": ["TPT"], "lbr": ["TBN", "TUBA"], "timp": ["TIMP"], "bd": ["BD"],
}
# General MIDI programs (bank 0) per part and articulation; the drums use the GS "Orchestral" kit (bank 128, 48)
PROGRAM = {"V1": 48, "V2": 48, "VA": 48, "VC": 48, "CB": 43, "FL": 73, "OB": 68, "CL": 71, "BSN": 70,
           "HN": 60, "TPT": 56, "TBN": 57, "TUBA": 58, "TIMP": 47}
SLOW_STRINGS = 49
PIZZICATO = 45
DRUM_KIT = 48
# the cello section gets a solo-cello layer on top of the section patch (body and bow noise in the low register)
LAYER = {"VC": (42, 0.55), "CB": (48, 0.5)}

SF2 = open(sys.argv[1], "rb").read()


def velocity(v):
    return int(max(1, min(127, round(28 + 99 * v))))


def render_part(part, notes, n):
    """Plays [notes] through the bank on up to four channels; returns mono float64 of [n] samples."""
    if not notes:
        return np.zeros(n)
    syn = tinysoundfont.Synth(samplerate=SR, gain=-6)
    sfid = syn.sfload(SF2)
    if part == "BD":
        syn.program_select(9, sfid, 128, DRUM_KIT, True)
    else:
        syn.program_select(0, sfid, 0, PROGRAM[part])
        syn.program_select(1, sfid, 0, PIZZICATO)
        syn.program_select(2, sfid, 0, SLOW_STRINGS if part in repertoire.STRINGS else PROGRAM[part])
        if part in LAYER:
            syn.program_select(3, sfid, 0, LAYER[part][0])
    events = []  # (sample, order, kind, chan, key, vel); offs before ons at the same sample
    last_off = {}
    for (t0, dur, m, v, art) in sorted(notes, key=lambda x: x[0]):
        if part == "BD":
            chans = [9]
        elif art == "pizz":
            chans = [1]
        elif art == "leg":
            chans = [2] + ([3] if part in LAYER else [])
        else:
            chans = [0] + ([3] if part in LAYER else [])
        s0 = int(t0 * SR)
        s1 = max(s0 + 1, int((t0 + dur) * SR))
        for ch in chans:
            vel = velocity(v * (LAYER[part][1] if ch == 3 else 1.0))
            key = (ch, m)
            # a repeated key: the earlier note ends where the new one starts
            if key in last_off and last_off[key][0] > s0:
                events.remove(last_off[key][1])
                ev = (s0, 0, "off", ch, m, 0)
                events.append(ev)
            events.append((s0, 1, "on", ch, m, vel))
            off = (s1, 0, "off", ch, m, 0)
            events.append(off)
            last_off[key] = (s1, off)
    events.sort(key=lambda e: (e[0], e[1]))
    out = np.zeros((n, 2), dtype=np.float32)
    pos = 0
    for (s, _, kind, ch, key, vel) in events:
        s = min(s, n)
        if s > pos:
            out[pos:s] = np.frombuffer(syn.generate(s - pos), dtype=np.float32).reshape(-1, 2)
            pos = s
        if kind == "on":
            syn.noteon(ch, key, vel)
        else:
            syn.noteoff(ch, key)
    if pos < n:
        out[pos:n] = np.frombuffer(syn.generate(n - pos), dtype=np.float32).reshape(-1, 2)
    return out.mean(axis=1).astype(np.float64)


rng = np.random.default_rng(7)


def reverb(x, wet=0.18, t60=1.4):
    n = int(SR * t60)
    t = np.arange(n) / SR
    ir = rng.standard_normal(n) * np.exp(-6.9 * t / t60)
    ir[: int(SR * 0.015)] = 0
    ir = lfilter([0.35], [1.0, -0.65], ir)   # darker tail: one-pole low-pass on the impulse response
    ir /= np.sqrt(np.sum(ir ** 2))
    L = len(x) + n
    nf = 1 << (L - 1).bit_length()
    y = np.fft.irfft(np.fft.rfft(x, nf) * np.fft.rfft(ir, nf), nf)[: len(x)]
    return x + wet * y


def fade_tail(y, dur_s, fade_s=0.8):
    """Silence after the piece's end and a short fade before it: no sample tail or loop seam rings past the end."""
    end = min(len(y), int(dur_s * SR))
    f = int(fade_s * SR)
    y[end:] = 0
    y[max(0, end - f):end] *= np.linspace(1, 0, end - max(0, end - f))
    return y


# loudness (profile B: "所有有声音的应用都要足够响"): the whole piece's loud passages sit at -6 dBFS RMS as a mix
# (v0.1.6 tried -10: the emulator's loopback stayed at RMS -8.7 / peak -0.1 dBFS, its output path normalises, so the
# headset keeps the louder render); one gain for all ten stems keeps the arrangement's balance; each stem is soft-limited to -1 dBFS
LOUD_RMS = 0.50
STEM_SHARE = 1.0
KNEE = 0.70
CEIL = 0.89   # -1 dBFS


def loudness_scale(y):
    w = int(SR * 0.4)
    k = len(y) // w
    if k == 0:
        return 0.0
    r = np.sqrt(np.mean(y[: k * w].reshape(k, w) ** 2, axis=1))
    loud = np.sort(r)[int(k * 0.7):]
    ref = float(np.sqrt(np.mean(loud ** 2)))
    return 0.0 if ref < 1e-5 else LOUD_RMS / ref


def limit(y):
    a = np.abs(y)
    return np.where(a < KNEE, y, np.sign(y) * (KNEE + (CEIL - KNEE) * np.tanh((a - KNEE) / (CEIL - KNEE))))


def envelopes(notes, duration):
    rate = 20
    frames = int(math.ceil(duration * rate))
    env = np.zeros((len(PARTS), frames))
    pizz = np.zeros((len(PARTS), frames), dtype=bool)
    for pi, p in enumerate(PARTS):
        for (t0, dur, m, vel, art) in notes[p]:
            decay = 0.25 if art in ("pizz", "hit") else (0.5 if art in ("bd", "cym") else None)
            f0 = int(t0 * rate)
            span = int((dur if decay is None else max(dur, decay * 2)) * rate) + 1
            for k in range(span):
                fi = f0 + k
                if fi >= frames:
                    break
                tt = k / rate
                e = vel * (math.exp(-tt / decay) if decay else (1.0 if tt < dur else 0.0))
                if e > env[pi, fi]:
                    env[pi, fi] = e
                if art == "pizz":
                    pizz[pi, fi] = True
    return rate, frames, np.clip(env, 0, 1), pizz


def rests_of(env, idx, rate, frames):
    silent = np.all(env[idx] < 0.02, axis=0)
    rs, i = [], 0
    while i < frames:
        if silent[i]:
            j = i
            while j < frames and silent[j]:
                j += 1
            if (j - i) / rate >= 0.3:
                rs.append([round(i / rate, 2), round(j / rate, 2)])
            i = j
        else:
            i += 1
    return rs


def spectrogram(y, path, title):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(figsize=(8, 3), dpi=100)
    ax.specgram(y + 1e-9, NFFT=1024, Fs=SR, noverlap=512, cmap="magma", vmin=-120)
    ax.set_ylim(0, 8000)
    ax.set_title(title)
    ax.set_xlabel("s")
    ax.set_ylabel("Hz")
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def write_wav(path, y):
    sf.write(path, np.clip(y, -1, 1).astype(np.float32), SR, subtype="PCM_16")


def write_ogg(path, y):
    """libsndfile crashes on one big Vorbis write; feed it in blocks."""
    with sf.SoundFile(path, "w", SR, 1, format="OGG", subtype="VORBIS", compression_level=0.55) as f:
        for i in range(0, len(y), 8192):
            f.write(y[i:i + 8192].astype(np.float32))


def main():
    qa = sys.argv[2] if len(sys.argv) > 2 else None
    only = set(sys.argv[3].split(",")) if len(sys.argv) > 3 else None
    index = []
    for (pid, build, title, composer, year, fam_notes) in repertoire.PIECES:
        a = repertoire.Arr()
        duration = build(a)
        index.append({"id": pid, "titleZh": title[0], "titleEn": title[1], "composerZh": composer[0],
                      "composerEn": composer[1], "year": year, "durationS": round(duration, 2)})
        if only and pid not in only:
            continue
        n = int(SR * duration)
        part_audio = {p: render_part(p, a.notes[p], n) for p in PARTS}
        rate, frames, env, pizz = envelopes(a.notes, duration)
        pdir = os.path.join(ASSETS, "pieces", pid)
        adir = os.path.join(ASSETS, "audio", pid)
        os.makedirs(pdir, exist_ok=True)
        os.makedirs(adir, exist_ok=True)
        with open(os.path.join(pdir, "parts.bin"), "wb") as fh:
            fh.write(b"ORCP" + struct.pack("<HHH", len(PARTS), frames, rate))
            for pi in range(len(PARTS)):
                fh.write((np.round(env[pi] * 127).astype(np.uint8) | (pizz[pi].astype(np.uint8) << 7)).tobytes())
        rests = {}
        # one gain for the whole piece (v0.1.5): the arrangement's balance stays as written, so a single soft
        # timpani stroke is not blown up to the level of a stem that plays all the time
        stems = {s: fade_tail(reverb(sum(part_audio[p] for p in ps)), duration - 0.2) for s, ps in STEMS.items()}
        mix = sum(stems.values())
        k = loudness_scale(mix) * STEM_SHARE
        mix = np.zeros(n)
        for s, ps in STEMS.items():
            y = limit(stems[s] * k)
            mix += y
            write_ogg(os.path.join(adir, s + ".ogg"), y)
            rests[s] = rests_of(env, [PARTS.index(p) for p in ps], rate, frames)
            pk = float(np.max(np.abs(y))) if len(y) else 0.0
            lim = float(np.mean(np.abs(stems[s] * k) > KNEE)) * 100
            print(f"  {pid}/{s}: peak {20 * math.log10(pk + 1e-9):.1f} dBFS limited {lim:.2f}% rests {len(rests[s])}", flush=True)
            if qa and pk > 1e-4:
                qd = os.path.join(qa, pid)
                os.makedirs(qd, exist_ok=True)
                # 10 s sample from the loudest stretch of this stem, and the last 15 s of the piece
                w = SR
                c = np.concatenate([[0.0], np.cumsum(y ** 2)])
                r = (c[w:] - c[:-w]) / w
                i0 = int(np.argmax(r)) - 5 * SR
                i0 = max(0, min(len(y) - 10 * SR, i0))
                write_wav(os.path.join(qd, f"{s}-10s.wav"), y[i0:i0 + 10 * SR])
                spectrogram(y[i0:i0 + 10 * SR], os.path.join(qd, f"{s}-10s.png"), f"{pid} {s} (10 s from {i0 / SR:.0f} s)")
                write_wav(os.path.join(qd, f"{s}-last15s.wav"), y[-15 * SR:])
        ml = loudness_scale(mix)
        print(f"  {pid}: gain {k:.2f}, mix loud-RMS {20 * math.log10(LOUD_RMS / ml) if ml else -99:.1f} dBFS, mix peak {20 * math.log10(np.max(np.abs(mix)) + 1e-9):.1f} dBFS", flush=True)
        if qa:
            mm = mix / max(1e-9, np.max(np.abs(mix))) * 0.89
            write_wav(os.path.join(qa, f"{pid}-mix.wav"), mm)
            spectrogram(mm[-15 * SR:], os.path.join(qa, f"{pid}-mix-last15s.png"), f"{pid} mix, last 15 s")
        piece = {
            "id": pid, "titleZh": title[0], "titleEn": title[1], "composerZh": composer[0], "composerEn": composer[1],
            "year": year, "durationS": round(duration, 2), "parts": PARTS, "envelopeRate": rate,
            "passes": [dict(p, **{"pass": i}) for i, p in enumerate(a.passes)],
            "rests": rests,
            "families": {f: {"zh": zh, "en": en} for f, (zh, en) in fam_notes.items()},
        }
        json.dump(piece, open(os.path.join(pdir, "piece.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(pid, "duration", round(duration, 1), "passes", len(a.passes), flush=True)
    json.dump(index, open(os.path.join(ASSETS, "pieces", "index.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
