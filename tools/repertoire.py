"""The app's repertoire (v0.1.5, user 2026-11-05: "可以选的曲子多几个"): six public-domain orchestral pieces, each
arranged by us for the app's 15 parts. Imported by tools/make_music.py; pure Python, no audio here.

A piece builder gets an `Arr` and writes notes into it pass by pass. Notes are
(t0 seconds, duration seconds, midi, velocity 0..1, articulation) where articulation is one of
  arco   bowed strings / sustained winds (section patch)
  leg    slow, legato strings (slow section patch)
  pizz   plucked strings
  tongue winds and brass, normal attack
  hit    timpani
  bd     bass drum
  cym    cymbals
"""

PARTS = ["V1", "V2", "VA", "VC", "CB", "FL", "OB", "CL", "BSN", "HN", "TPT", "TBN", "TUBA", "TIMP", "BD"]
STRINGS = ("V1", "V2", "VA", "VC", "CB")

NAMES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def midi(name):
    """'F#4' -> 66, 'Bb3' -> 58, 'Db5' -> 73."""
    n = NAMES[name[0]]
    i = 1
    while i < len(name) and name[i] in "#b":
        n += 1 if name[i] == "#" else -1
        i += 1
    return n + 12 * (int(name[i:]) + 1)


def mel(text, default=1.0):
    """'F#4 G4:.5 r:1 A4:2' -> [(66, 1.0), (67, .5), (None, 1.0), (69, 2.0)] (beats). '|' is ignored."""
    out = []
    for tok in text.replace("|", " ").split():
        name, _, d = tok.partition(":")
        beats = float(d) if d else default
        out.append((None if name == "r" else midi(name), beats))
    return out


QUAL = {"": (0, 4, 7), "m": (0, 3, 7), "7": (0, 4, 7, 10), "m7": (0, 3, 7, 10), "maj7": (0, 4, 7, 11),
        "dim": (0, 3, 6), "sus4": (0, 5, 7)}


def chord_pcs(sym):
    """'F#m' -> (root pc, [pcs]); 'A7' -> (9, [9, 1, 4, 7])."""
    r = NAMES[sym[0]]
    i = 1
    while i < len(sym) and sym[i] in "#b":
        r += 1 if sym[i] == "#" else -1
        i += 1
    q = sym[i:]
    return r % 12, [(r + k) % 12 for k in QUAL[q]]


def chords(text, default=4.0):
    """'D:2 A:2 Bm F#m' -> [('D', 2), ('A', 2), ('Bm', 4), ('F#m', 4)] (beats)."""
    out = []
    for tok in text.replace("|", " ").split():
        s, _, d = tok.partition(":")
        out.append((s, float(d) if d else default))
    return out


def tone_near(pcs, target):
    """The chord tone (midi) closest to [target]."""
    best = None
    for m in range(target - 7, target + 8):
        if m % 12 in pcs and (best is None or abs(m - target) < abs(best - target)):
            best = m
    return best


class Arr:
    """Note sink for one piece plus its pass table."""

    def __init__(self):
        self.notes = {p: [] for p in PARTS}
        self.passes = []

    # ---- passes ------------------------------------------------------------------------------
    def mark(self, t, bpm, zh=None, en=None, focus=None):
        self.passes.append({"startS": round(t, 3), "bpm": bpm, "captionZh": zh, "captionEn": en, "focus": focus})

    # ---- lines -------------------------------------------------------------------------------
    def line(self, part, seq, t0, spb, shift=0, vel=0.7, art="arco", legato=0.95, swell=None):
        """Plays [seq] ((midi|None, beats) pairs) from t0; returns the end time. [swell](i, n) -> velocity factor."""
        t = t0
        for i, (m, beats) in enumerate(seq):
            d = beats * spb
            if m is not None:
                v = vel * (swell(i, len(seq)) if swell else 1.0)
                dur = spb * 0.45 if art == "pizz" else d * legato
                self.notes[part].append((t, dur, m + shift, max(0.05, min(1.0, v)), art))
            t += d
        return t

    def hold(self, part, t0, dur, m, vel, art="arco"):
        self.notes[part].append((t0, dur, m, vel, art))

    # ---- accompaniment from a chord list -----------------------------------------------------
    def bass(self, part, chs, t0, spb, low, vel, art="arco", every=None, fifth=False):
        """Chord roots in [low, low + 11]; [every] beats repeats them (None = held per chord)."""
        t = t0
        for sym, beats in chs:
            root, pcs = chord_pcs(sym)
            m = low + ((root - low) % 12)
            if every is None:
                self.notes[part].append((t, beats * spb * 0.97, m, vel, art))
            else:
                k = 0
                b = 0.0
                while b < beats - 1e-6:
                    mm = m + 7 if (fifth and k % 2 == 1) else m
                    if mm - m == 7 and mm > low + 14:
                        mm -= 12
                    dur = spb * (0.45 if art == "pizz" else every * 0.9)
                    self.notes[part].append((t + b * spb, dur, mm, vel, art))
                    b += every
                    k += 1
            t += beats * spb
        return t

    def pad(self, part, chs, t0, spb, center, vel, art="arco", every=None, offset=0.0, length=None, which=0):
        """One chord tone per chord near [center] (which = 0 nearest, 1 the next one up, -1 the next one down)."""
        t = t0
        prev = center
        for sym, beats in chs:
            _, pcs = chord_pcs(sym)
            m = tone_near(pcs, prev if abs(prev - center) < 5 else center)
            if which:
                step = 1 if which > 0 else -1
                n = m + step
                while n % 12 not in pcs:
                    n += step
                m = n
            prev = m
            if every is None:
                self.notes[part].append((t + offset * spb, (length or beats * 0.97) * spb, m, vel, art))
            else:
                b = offset
                while b < beats - 1e-6:
                    self.notes[part].append((t + b * spb, (length or every * 0.9) * spb, m, vel, art))
                    b += every
            t += beats * spb
        return t

    def arp(self, part, chs, t0, spb, center, vel, sub=0.5, art="arco", shape=(0, 1, 2, 1)):
        """Broken chord: [sub]-beat notes walking the chord tones around [center] in [shape] order."""
        t = t0
        for sym, beats in chs:
            _, pcs = chord_pcs(sym)
            base = tone_near(pcs, center - 4)
            tones = [base]
            while len(tones) < 4:
                n = tones[-1] + 1
                while n % 12 not in pcs:
                    n += 1
                tones.append(n)
            k = 0
            b = 0.0
            while b < beats - 1e-6:
                self.notes[part].append((t + b * spb, sub * spb * 0.9, tones[shape[k % len(shape)]], vel, art))
                b += sub
                k += 1
            t += beats * spb
        return t

    def roll(self, part, t0, dur, m, v0, v1, step=0.07):
        n = max(1, int(dur / step))
        for k in range(n):
            self.notes[part].append((t0 + k * step, 0.25, m, v0 + (v1 - v0) * k / n, "hit"))

    def end(self, t):
        """Cut every note that would ring past [t] (a clean stop for the coda)."""
        for p in PARTS:
            self.notes[p] = [(a, min(d, max(0.05, t - a)), m, v, art) for (a, d, m, v, art) in self.notes[p] if a < t]


def beats_of(seq):
    return sum(b for _, b in seq)


def chords_beats(chs):
    return sum(b for _, b in chs)


# ===============================================================================================
# 1. Grieg, In the Hall of the Mountain King (Peer Gynt, 1875)
# ===============================================================================================
def mountain_king(a):
    B, Cs, D, E, Fs, F, C, A, Bh = 0, 2, 3, 5, 7, 6, 1, 10, 12
    phrase_a = [B, Cs, D, E, Fs, D, Fs, Fs, F, Cs, F, F, E, C, E, E,
                B, Cs, D, E, Fs, D, Fs, Bh, A, Fs, D, Fs, A, A, A, A]
    phrase_b = phrase_a[:24] + [A, Fs, D, Fs, Fs, Fs, Fs, Fs]
    theme = phrase_a + phrase_b
    roots = [0, 0, 0, 0, 2, 2, 1, 1, 0, 0, 0, 0, 10, 10, 10, 10,
             0, 0, 0, 0, 2, 2, 1, 1, 0, 0, 0, 0, 7, 7, 7, 7]
    passes = [(2, 84, 0.18), (8, 84, 0.25), (8, 88, 0.33), (8, 96, 0.42), (8, 104, 0.52), (8, 114, 0.62),
              (8, 124, 0.72), (8, 136, 0.82), (8, 148, 0.90), (8, 160, 1.00), (6, 170, 1.00)]
    caps = {
        0: ("圆号先吹出一个低低的长音，像山洞里的回声", "The horns hold one low note, like an echo in a cave", "br"),
        1: ("主题来了：大管和大提琴、低音提琴用手指拨弦，一步一步往前走", "The theme: bassoons with plucked cellos and basses, tiptoeing forward", "str"),
        3: ("旋律爬高了：小提琴、中提琴接过来，双簧管和单簧管也加入", "Higher now: violins and violas take over, oboe and clarinet join", "ww"),
        5: ("弦乐全部拿起弓来拉，长笛在最上面闪着光", "All strings switch to the bow; the flute sparkles on top", "str"),
        7: ("铜管和定音鼓进来了，山魔王们开始追了", "Brass and timpani arrive: the trolls give chase", "br"),
        9: ("最后一遍：大鼓和镲一起砸下来，全团最快最响", "Last time round: bass drum and cymbals crash in, everyone at full speed", "perc"),
        10: ("尾声：全团重击和弦，最后一下镲", "Coda: hammered chords and one last crash", None),
    }
    N = a.notes

    def theme_notes(part, t0, spb, octave, vel, art):
        e = spb / 2
        i = 0
        while i < len(theme):
            n = theme[i]
            j = i + 1
            while j < len(theme) and theme[j] == n and art != "pizz" and (j % 8) != 0:
                j += 1
            dur = e if art == "pizz" else (j - i) * e
            N[part].append((t0 + i * e, dur * 0.92, octave + n, vel, art))
            i = j

    def roots_notes(part, t0, spb, octave, vel, art):
        for b in range(32):
            N[part].append((t0 + b * spb, spb * (0.4 if art == "pizz" else 0.9), octave + roots[b], vel, art))

    t = 0.0
    for idx, (bars, bpm, v) in enumerate(passes):
        spb = 60.0 / bpm
        c = caps.get(idx)
        a.mark(t, bpm, *(c if c else (None, None, None)))
        beats = bars * 4
        L = beats * spb
        if idx == 0:
            a.hold("HN", t + 0.1, L - 0.2, 54, 0.30, "tongue")
        elif idx in (1, 2):
            theme_notes("VC", t, spb, 47, v + 0.2, "pizz")
            theme_notes("CB", t, spb, 35, v + 0.2, "pizz")
            theme_notes("BSN", t, spb, 47, v * 0.9 + 0.15, "tongue")
            if idx == 2:
                a.hold("HN", t, L, 54, 0.30, "tongue")
        elif idx in (3, 4):
            theme_notes("V2", t, spb, 59, v + 0.15, "pizz")
            theme_notes("VA", t, spb, 47, v + 0.15, "pizz")
            theme_notes("OB", t, spb, 71, v * 0.8 + 0.1, "tongue")
            theme_notes("CL", t, spb, 59, v * 0.8 + 0.1, "tongue")
            roots_notes("VC", t, spb, 47, v * 0.8 + 0.1, "pizz")
            roots_notes("CB", t, spb, 35, v * 0.8 + 0.1, "pizz")
            theme_notes("BSN", t, spb, 47, v * 0.6 + 0.1, "tongue")
            a.hold("HN", t, L, 54, 0.32, "tongue")
        else:
            theme_notes("V1", t, spb, 71, v, "arco")
            theme_notes("V2", t, spb, 59, v, "arco")
            theme_notes("VA", t, spb, 59, v * 0.9, "arco")
            theme_notes("VC", t, spb, 47, v, "arco")
            roots_notes("CB", t, spb, 35, v, "arco")
            theme_notes("FL", t, spb, 83, v * 0.8, "tongue")
            if idx >= 6:
                theme_notes("OB", t, spb, 71, v * 0.8, "tongue")
                theme_notes("CL", t, spb, 59, v * 0.8, "tongue")
                theme_notes("BSN", t, spb, 47, v * 0.8, "tongue")
                for b in range(0, beats, 4):
                    a.hold("HN", t + b * spb, spb * 3.8, 54, v * 0.7, "tongue")
            if 7 <= idx < 10:
                theme_notes("TPT", t, spb, 71, v * 0.9, "tongue")
                theme_notes("TBN", t, spb, 47, v * 0.9, "tongue")
                for b in range(0, beats, 2):
                    N["TIMP"].append((t + b * spb, spb * 1.5, 47 if roots[b % 32] in (0, 10) else 42, v, "hit"))
            if 8 <= idx < 10:
                theme_notes("TUBA", t, spb, 35, v * 0.9, "tongue")
            if idx == 9:
                for b in range(0, beats, 4):
                    N["BD"].append((t + b * spb, 1.6, 36, v, "bd"))
                    N["BD"].append((t + b * spb, 2.2, 57, v * 0.8, "cym"))
            if idx == 10:
                for p in ("V1", "V2", "VA", "VC", "CB", "FL", "OB", "CL", "BSN", "HN", "TPT", "TBN", "TUBA"):
                    N[p] = [n for n in N[p] if n[0] < t]
                chord = {"V1": 71, "V2": 66, "VA": 62, "VC": 47, "CB": 35, "FL": 83, "OB": 78, "CL": 74, "BSN": 47,
                         "HN": 62, "TPT": 71, "TBN": 54, "TUBA": 35}
                for p, m in chord.items():
                    art = "arco" if p in STRINGS else "tongue"
                    for b in range(0, 16, 2):
                        N[p].append((t + b * spb, spb * 0.6, m, 1.0, art))
                    N[p].append((t + 16 * spb, spb * 7.0, m, 1.0, art))
                a.roll("TIMP", t, 16 * spb, 47, 0.45, 0.85)
                N["TIMP"].append((t + 16 * spb, 2.5, 47, 1.0, "hit"))
                N["BD"].append((t, 1.6, 36, 1.0, "bd"))
                N["BD"].append((t + 16 * spb, 2.5, 36, 1.0, "bd"))
                N["BD"].append((t + 16 * spb, 4.0, 57, 1.0, "cym"))
        t += L
    return t + 3.0


# ===============================================================================================
# 2. Beethoven, Symphony No. 9, finale: the "Ode to Joy" theme (1824)
#    Like Beethoven: the tune first in the low strings alone, then violas and cellos with a bassoon line,
#    then the violins, then the whole orchestra with brass and timpani.
# ===============================================================================================
ODE = mel(
    "F#4 F#4 G4 A4 | A4 G4 F#4 E4 | D4 D4 E4 F#4 | F#4:1.5 E4:.5 E4:2 |"
    "F#4 F#4 G4 A4 | A4 G4 F#4 E4 | D4 D4 E4 F#4 | E4:1.5 D4:.5 D4:2 |"
    "E4 E4 F#4 D4 | E4 F#4:.5 G4:.5 F#4 D4 | E4 F#4:.5 G4:.5 F#4 E4 | D4 E4 A3:2 |"
    "F#4 F#4 G4 A4 | A4 G4 F#4 E4 | D4 D4 E4 F#4 | E4:1.5 D4:.5 D4:2")
ODE_CH = chords(
    "D:2 D:2 | A:2 A:2 | D:2 G:2 | D:2 A:2 |"
    "D:2 D:2 | A:2 A:2 | D:2 G:2 | A7:2 D:2 |"
    "A:2 D:2 | A:2 D:2 | A:2 Bm:2 | E7:2 A:2 |"
    "D:2 D:2 | A:2 A:2 | D:2 G:2 | A7:2 D:2")
# a simple running counter-line for the bassoon (pass 2) and violas (pass 3): quarters on chord tones
ODE_COUNTER = mel(
    "D3 E3 F#3 D3 | C#3 E3 A3 G3 | F#3 A3 B3 G3 | F#3 D3 E3 C#3 |"
    "D3 E3 F#3 D3 | C#3 E3 A3 G3 | F#3 A3 B3 G3 | E3 C#3 D3:2 |"
    "C#3 A2 D3 F#3 | E3 C#3 D3 A2 | C#3 E3 D3 B2 | G#2 B2 A2:2 |"
    "D3 E3 F#3 D3 | C#3 E3 A3 G3 | F#3 A3 B3 G3 | E3 C#3 D3:2")


def ode_to_joy(a):
    bpm = 104
    spb = 60.0 / bpm
    t = 0.0
    # pass 0: the low strings alone, quiet (Beethoven's cellos and basses)
    a.mark(t, bpm, "大提琴和低音提琴先轻轻地、慢慢地唱出这段旋律，别的人都在听",
           "Cellos and basses sing the tune quietly on their own; everyone else listens", "str")
    a.line("VC", ODE, t, spb, -12, 0.55, "leg")
    t = a.line("CB", ODE, t, spb, -24, 0.45, "leg")
    # pass 1: violas take the tune with the cellos; the bassoon adds a walking line
    a.mark(t, bpm, "中提琴接过旋律，大管在下面走出一条新的线", "Violas take the tune; the bassoon walks a new line underneath", "ww")
    a.line("VA", ODE, t, spb, 0, 0.6, "arco")
    a.line("VC", ODE, t, spb, -12, 0.55, "arco")
    a.line("BSN", ODE_COUNTER, t, spb, 0, 0.6, "tongue", 0.85)
    t = a.bass("CB", ODE_CH, t, spb, 38, 0.5, "pizz", every=1)
    # pass 2: the violins sing it an octave up, horns hold the harmony
    a.mark(t, bpm, "小提琴在高处唱，圆号在后面托着和声", "The violins sing it up high; the horns hold the harmony", "str")
    a.line("V1", ODE, t, spb, 12, 0.7, "arco")
    a.line("V2", ODE, t, spb, 0, 0.6, "arco")
    a.line("VA", ODE_COUNTER, t, spb, 12, 0.55, "arco", 0.9)
    a.bass("VC", ODE_CH, t, spb, 50, 0.55, "arco")
    a.bass("CB", ODE_CH, t, spb, 38, 0.6, "pizz", every=1)
    a.pad("HN", ODE_CH, t, spb, 62, 0.45, "tongue")
    t = a.pad("CL", ODE_CH, t, spb, 69, 0.4, "tongue", which=1)
    # pass 3: everyone, brass and timpani, full voice
    a.mark(t, bpm, "全团一起：小号和长号加进来，定音鼓敲着拍子，越来越响",
           "The whole orchestra: trumpets and trombones join, timpani on the beat", "br")
    a.line("V1", ODE, t, spb, 12, 0.95, "arco")
    a.line("V2", ODE, t, spb, 0, 0.85, "arco")
    a.line("FL", ODE, t, spb, 12, 0.8, "tongue")
    a.line("OB", ODE, t, spb, 12, 0.75, "tongue")
    a.line("TPT", ODE, t, spb, 0, 0.85, "tongue")
    a.line("CL", ODE, t, spb, 0, 0.7, "tongue")
    a.line("VA", ODE_COUNTER, t, spb, 12, 0.75, "arco", 0.9)
    a.line("BSN", ODE_COUNTER, t, spb, 0, 0.7, "tongue", 0.85)
    a.bass("VC", ODE_CH, t, spb, 50, 0.85, "arco", every=1)
    a.bass("CB", ODE_CH, t, spb, 38, 0.9, "arco", every=1)
    a.pad("HN", ODE_CH, t, spb, 62, 0.75, "tongue")
    a.pad("TBN", ODE_CH, t, spb, 55, 0.7, "tongue")
    a.bass("TUBA", ODE_CH, t, spb, 38, 0.7, "tongue")
    tt = t
    for sym, beats in ODE_CH:
        root, _ = chord_pcs(sym)
        a.notes["TIMP"].append((tt, spb * 0.9, 45 if root in (9, 4) else 50, 0.75, "hit"))
        tt += beats * spb
    t += beats_of(ODE) * spb
    # coda: two long D major chords
    a.mark(t, bpm, "尾声：全团一个长长的大和弦", "Coda: one long, full chord", None)
    chord = {"V1": 74, "V2": 66, "VA": 62, "VC": 50, "CB": 38, "FL": 86, "OB": 78, "CL": 69, "BSN": 50,
             "HN": 62, "TPT": 74, "TBN": 57, "TUBA": 38}
    for p, m in chord.items():
        art = "arco" if p in STRINGS else "tongue"
        a.hold(p, t, spb * 1.0, m, 0.9, art)
        a.hold(p, t + 2 * spb, spb * 5.5, m, 1.0, art)
    a.notes["TIMP"].append((t, 0.5, 50, 0.9, "hit"))
    a.roll("TIMP", t + 2 * spb, spb * 5.0, 50, 0.5, 0.9)
    a.notes["BD"].append((t + 2 * spb, 3.0, 57, 0.7, "cym"))
    t += 8 * spb
    a.end(t)
    return t + 3.0


# ===============================================================================================
# 3. Pachelbel, Canon in D (c. 1690): the ground bass under three violin lines in canon
# ===============================================================================================
CANON_BASS = mel("D3:2 A2:2 B2:2 F#2:2 G2:2 D2:2 G2:2 A2:2")
CANON_CH = chords("D:2 A:2 Bm:2 F#m:2 G:2 D:2 G:2 A:2")
CANON_LINES = [
    mel("F#5:2 E5:2 D5:2 C#5:2 B4:2 A4:2 B4:2 C#5:2"),
    mel("D5:2 C#5:2 B4:2 A4:2 G4:2 F#4:2 G4:2 E4:2"),
    mel("D4 F#4 A4 G4 F#4 D4 F#4 E4 D4 B3 D4 A4 G4 B4 A4 G4"),
    mel("F#4:.5 D4:.5 E4:.5 F#4:.5 A4:.5 E4:.5 F#4:.5 G4:.5 F#4:.5 D4:.5 E4:.5 F#4:.5 D4:.5 B3:.5 C#4:.5 D4:.5 "
        "G4:.5 B4:.5 A4:.5 G4:.5 F#4:.5 D4:.5 E4:.5 F#4:.5 G4:.5 E4:.5 D4:.5 E4:.5 A4:.5 G4:.5 F#4:.5 E4:.5"),
    mel("A5:.5 F#5:.5 G5:.5 A5:.5 F#5:.5 G5:.5 A5:.5 A4:.5 B4:.5 C#5:.5 D5:.5 E5:.5 F#5:.5 G5:.5 F#5:.5 D5:.5 "
        "E5:.5 F#5:.5 G5:.5 E5:.5 D5:.5 C#5:.5 B4:.5 A4:.5 B4:.5 C#5:.5 D5:.5 E5:.5 F#5:.5 G5:.5 A5:.5 G5:.5"),
    mel("F#5:1.5 F#5:.5 E5:1.5 E5:.5 D5:1.5 D5:.5 C#5:1.5 C#5:.5 B4:1.5 B4:.5 A4:1.5 A4:.5 B4:1.5 B4:.5 C#5:1.5 C#5:.5"),
]


def canon(a):
    bpm = 66
    spb = 60.0 / bpm
    cyc = beats_of(CANON_BASS) * spb
    n_cycles = 8
    caps = {
        0: ("大提琴和低音提琴先拉出一组八个音的低音，整首曲子它都不变", "Cellos and basses lay down eight bass notes; they repeat the whole piece", "str"),
        1: ("第一小提琴进来；后面每一组，第二小提琴都会照着它拉一遍", "First violins enter; each line they play, the seconds will copy one round later", "str"),
        3: ("中提琴也跟进，三条线像轮唱一样追着走", "The violas follow: three lines chasing each other like a round", "str"),
        5: ("长笛和双簧管加进来，圆号托着长音", "Flute and oboe join; the horns hold long notes", "ww"),
        7: ("最后一轮：全团一起，慢慢收住", "Last round: everyone together, slowing to the end", None),
    }
    t = 0.0
    for c in range(n_cycles):
        cap = caps.get(c)
        a.mark(t, bpm, *(cap if cap else (None, None, None)))
        sw = 0.45 + 0.06 * c
        a.line("VC", CANON_BASS, t, spb, 12, sw, "leg", 0.97)
        a.line("CB", CANON_BASS, t, spb, 0, sw * 0.9, "leg" if c < 5 else "arco", 0.97)
        if c >= 1:
            a.line("V1", CANON_LINES[min(c - 1, len(CANON_LINES) - 1)], t, spb, 0, 0.5 + 0.05 * c, "leg", 0.95)
        if c >= 2:
            a.line("V2", CANON_LINES[min(c - 2, len(CANON_LINES) - 1)], t, spb, 0, 0.45 + 0.05 * c, "leg", 0.95)
        if c >= 3:
            a.line("VA", CANON_LINES[min(c - 3, len(CANON_LINES) - 1)], t, spb, -12, 0.45 + 0.05 * c, "leg", 0.95)
        if c >= 5:
            a.line("FL", CANON_LINES[0], t, spb, 12, 0.5, "tongue", 0.95)
            a.pad("HN", CANON_CH, t, spb, 62, 0.45, "tongue")
        if c >= 6:
            a.line("OB", CANON_LINES[1], t, spb, 0, 0.5, "tongue", 0.95)
            a.line("BSN", CANON_BASS, t, spb, 12, 0.45, "tongue", 0.9)
        if c == 7:
            a.line("CL", CANON_LINES[2], t, spb, 0, 0.45, "tongue", 0.9)
            a.pad("TBN", CANON_CH, t, spb, 55, 0.35, "tongue")
        t += cyc
    a.mark(t, bpm, "尾声：一个 D 大调的长和弦", "Coda: one long D major chord", None)
    chord = {"V1": 74, "V2": 66, "VA": 57, "VC": 50, "CB": 38, "FL": 78, "OB": 74, "CL": 69, "BSN": 50,
             "HN": 62, "TBN": 54}
    for p, m in chord.items():
        a.hold(p, t, spb * 5.0, m, 0.7, "leg" if p in STRINGS else "tongue")
    t += 6 * spb
    a.end(t)
    return t + 3.0


# ===============================================================================================
# 4. Johann Strauss II, The Blue Danube (An der schönen blauen Donau, 1867): the first waltz
# ===============================================================================================
def _danube_group(x, y, z, a1, b1):
    """| x x y | z - - | r a a | r b b |"""
    return [(x, 1), (x, 1), (y, 1), (z, 3), (None, 1), (a1, 1), (a1, 1), (None, 1), (b1, 1), (b1, 1)]


m = midi
DANUBE = (_danube_group(m("D4"), m("F#4"), m("A4"), m("A5"), m("F#5"))
          + _danube_group(m("D4"), m("F#4"), m("A4"), m("A5"), m("G5"))
          + _danube_group(m("C#4"), m("E4"), m("B4"), m("B5"), m("G5"))
          + _danube_group(m("C#4"), m("E4"), m("B4"), m("B5"), m("F#5"))
          + _danube_group(m("D4"), m("F#4"), m("A4"), m("D6"), m("A5"))
          + _danube_group(m("D4"), m("F#4"), m("A4"), m("D6"), m("B5"))
          + mel("E4 E4 G4 | B4:3 | r B4 G#4 | A4:3 | F#5:2 D5 | D5:2 A4 | E5:3 | D5:3"))
DANUBE_CH = chords(
    "D:3 D:3 D:3 D:3 | D:3 D:3 A7:3 A7:3 | A7:3 A7:3 A7:3 A7:3 | A7:3 A7:3 D:3 D:3 |"
    "D:3 D:3 D:3 D:3 | D:3 D:3 G:3 G:3 | Em:3 E7:3 E7:3 A:3 | D:3 D:3 A7:3 D:3")


def danube(a):
    bpm = 168
    spb = 60.0 / bpm
    t = 0.0
    # intro: tremolo-like held D in the violins, horns call the opening D - F# - A
    a.mark(t, bpm, "开头：小提琴轻轻抖着一个长音，圆号吹出多瑙河的开头三个音",
           "Intro: the violins shimmer on one note; the horn plays the river's first three notes", "br")
    a.hold("V1", t, 12 * spb, m("A5"), 0.35, "leg")
    a.hold("V2", t, 12 * spb, m("D5"), 0.3, "leg")
    a.line("HN", mel("D4 F#4 A4 A4:3 r:3 A4:3 F#4:3"), t + 3 * spb, spb, 0, 0.55, "tongue", 0.95)
    t += 15 * spb
    waltz_beats = beats_of(DANUBE)
    rounds = [
        ("圆舞曲来了：小提琴唱旋律，低音提琴每小节第一拍「嗯」，中提琴和第二小提琴「嚓嚓」",
         "The waltz: violins sing; basses on beat one, violas and second violins on two and three", "str"),
        ("这一遍旋律交给长笛和单簧管，圆号在下面接话", "This time flute and clarinet sing it; the horns answer underneath", "ww"),
        ("最后一遍全团一起转起来，小号、长号也加入", "Last time everyone spins together; trumpets and trombones join", "br"),
    ]
    for r, (zh, en, fam) in enumerate(rounds):
        a.mark(t, bpm, zh, en, fam)
        v = 0.55 + 0.15 * r
        if r == 0:
            a.line("V1", DANUBE, t, spb, 0, v, "arco", 0.9)
        elif r == 1:
            a.line("FL", DANUBE, t, spb, 12, v, "tongue", 0.9)
            a.line("CL", DANUBE, t, spb, 0, v * 0.9, "tongue", 0.9)
            a.line("V1", DANUBE, t, spb, 0, v * 0.5, "arco", 0.9)
        else:
            a.line("V1", DANUBE, t, spb, 12, v, "arco", 0.9)
            a.line("V2", DANUBE, t, spb, 0, v * 0.9, "arco", 0.9)
            a.line("FL", DANUBE, t, spb, 12, v * 0.8, "tongue", 0.9)
            a.line("OB", DANUBE, t, spb, 0, v * 0.8, "tongue", 0.9)
            a.line("TPT", DANUBE, t, spb, 0, v * 0.7, "tongue", 0.9)
        # oom-pah-pah: bass on one, chords on two and three
        a.bass("CB", DANUBE_CH, t, spb, 38, v, "pizz" if r == 0 else "arco", every=3)
        a.bass("VC", DANUBE_CH, t, spb, 50, v * 0.9, "pizz" if r == 0 else "arco", every=3)
        if r < 2:
            a.pad("V2", DANUBE_CH, t, spb, 66, v * 0.75, "pizz" if r == 0 else "arco", every=3, offset=1, length=0.5)
            a.pad("V2", DANUBE_CH, t, spb, 66, v * 0.75, "pizz" if r == 0 else "arco", every=3, offset=2, length=0.5)
        a.pad("VA", DANUBE_CH, t, spb, 62, v * 0.75, "pizz" if r == 0 else "arco", every=3, offset=1, length=0.5)
        a.pad("VA", DANUBE_CH, t, spb, 62, v * 0.75, "pizz" if r == 0 else "arco", every=3, offset=2, length=0.5)
        if r >= 1:
            a.pad("HN", DANUBE_CH, t, spb, 62, v * 0.7, "tongue", every=3, offset=1, length=1.6)
            a.bass("BSN", DANUBE_CH, t, spb, 50, v * 0.7, "tongue", every=3)
        if r == 2:
            a.pad("TBN", DANUBE_CH, t, spb, 55, v * 0.6, "tongue", every=3, offset=1, length=1.6)
            a.bass("TUBA", DANUBE_CH, t, spb, 38, v * 0.7, "tongue", every=3)
            tt = t
            for k, (sym, beats) in enumerate(DANUBE_CH):
                root, _ = chord_pcs(sym)
                if k % 2 == 0:
                    a.notes["TIMP"].append((tt, spb * 2, 45 if root in (9, 4) else 50, 0.6, "hit"))
                tt += beats * spb
        t += waltz_beats * spb
    a.mark(t, bpm, "收尾：三下干脆的和弦，最后一下镲", "Ending: three crisp chords and one cymbal", "perc")
    chord = {"V1": 74, "V2": 66, "VA": 62, "VC": 50, "CB": 38, "FL": 86, "OB": 78, "CL": 69, "BSN": 50,
             "HN": 62, "TPT": 74, "TBN": 57, "TUBA": 38}
    for p, mm in chord.items():
        art = "arco" if p in STRINGS else "tongue"
        for k in range(2):
            a.hold(p, t + k * 3 * spb, spb * 0.8, mm, 0.9, art)
        a.hold(p, t + 6 * spb, spb * 4.0, mm, 1.0, art)
    for k in range(2):
        a.notes["TIMP"].append((t + k * 3 * spb, 0.6, 50, 0.85, "hit"))
    a.notes["TIMP"].append((t + 6 * spb, 1.5, 50, 1.0, "hit"))
    a.notes["BD"].append((t + 6 * spb, 2.0, 36, 0.8, "bd"))
    a.notes["BD"].append((t + 6 * spb, 3.0, 57, 0.9, "cym"))
    t += 11 * spb
    a.end(t)
    return t + 3.0


# ===============================================================================================
# 5. Dvořák, Symphony No. 9 "From the New World", Largo (1893): the cor anglais tune
#    (no cor anglais in our orchestra: the oboe plays it, the card says so)
# ===============================================================================================
LARGO = mel(
    "F4:1.5 Ab4:.5 Ab4:2 | F4:1.5 Eb4:.5 Db4:2 | Eb4 F4 Ab4 F4 | Eb4:4 |"
    "F4:1.5 Ab4:.5 Ab4:2 | F4:1.5 Eb4:.5 Db4:2 | Eb4 F4 Eb4 Db4 | Db4:4")
LARGO_CH = chords("Db:4 | Db:2 Ab:2 | Gb:2 Db:2 | Ab:4 | Db:4 | Db:2 Gb:2 | Ab7:2 Ab7:2 | Db:4")
LARGO_INTRO = chords("Db:4 Gb:4 Db:2 Ab:2 Db:4")


def largo(a):
    bpm = 52
    spb = 60.0 / bpm
    t = 0.0
    a.mark(t, bpm, "开头：长号、大号和圆号吹出几个又低又暖的和弦", "Opening: trombones, tuba and horns breathe a few low, warm chords", "br")
    a.pad("TBN", LARGO_INTRO, t, spb, 56, 0.5, "tongue")
    a.pad("HN", LARGO_INTRO, t, spb, 63, 0.45, "tongue")
    a.pad("HN", LARGO_INTRO, t, spb, 60, 0.4, "tongue", which=-1)
    a.bass("TUBA", LARGO_INTRO, t, spb, 37, 0.5, "tongue")
    a.bass("BSN", LARGO_INTRO, t, spb, 49, 0.4, "tongue")
    t += chords_beats(LARGO_INTRO) * spb
    a.mark(t, bpm, "英国管的那段旋律（这里由双簧管吹），弦乐轻轻垫在下面",
           "The famous cor anglais tune (our oboe plays it) over soft, muted strings", "ww")
    a.line("OB", LARGO, t, spb, 0, 0.7, "tongue", 0.97)
    a.pad("V1", LARGO_CH, t, spb, 68, 0.3, "leg")
    a.pad("V2", LARGO_CH, t, spb, 65, 0.3, "leg")
    a.pad("VA", LARGO_CH, t, spb, 60, 0.3, "leg")
    a.bass("VC", LARGO_CH, t, spb, 49, 0.32, "leg")
    t = a.bass("CB", LARGO_CH, t, spb, 37, 0.3, "leg")
    a.mark(t, bpm, "单簧管接着唱一遍，圆号轻轻应和", "The clarinet sings it once more; a horn answers softly", "ww")
    a.line("CL", LARGO, t, spb, 0, 0.65, "tongue", 0.97)
    a.line("HN", mel("r:4 r:4 Ab3:2 Db4:2 Eb4:4 r:4 r:4 Ab3:2 Gb3:2 F3:4"), t, spb, 0, 0.4, "tongue")
    a.pad("V2", LARGO_CH, t, spb, 65, 0.3, "leg")
    a.pad("VA", LARGO_CH, t, spb, 60, 0.3, "leg")
    a.bass("VC", LARGO_CH, t, spb, 49, 0.32, "leg")
    t = a.bass("CB", LARGO_CH, t, spb, 37, 0.3, "leg")
    a.mark(t, bpm, "弦乐全体唱这段旋律，长笛在上面飘着", "All the strings sing it together; the flute floats above", "str")
    a.line("V1", LARGO, t, spb, 12, 0.6, "leg", 0.97)
    a.line("V2", LARGO, t, spb, 0, 0.55, "leg", 0.97)
    a.line("FL", LARGO, t, spb, 12, 0.4, "tongue", 0.97)
    a.pad("VA", LARGO_CH, t, spb, 60, 0.45, "leg")
    a.bass("VC", LARGO_CH, t, spb, 49, 0.5, "leg")
    a.bass("CB", LARGO_CH, t, spb, 37, 0.45, "leg")
    t = a.pad("HN", LARGO_CH, t, spb, 63, 0.35, "tongue")
    a.mark(t, bpm, "最后又回到开头那几个铜管和弦，安静地结束", "The brass chords from the start return, and it fades to silence", "br")
    a.pad("TBN", LARGO_INTRO, t, spb, 56, 0.4, "tongue")
    a.pad("HN", LARGO_INTRO, t, spb, 63, 0.35, "tongue")
    a.bass("TUBA", LARGO_INTRO, t, spb, 37, 0.4, "tongue")
    a.bass("CB", LARGO_INTRO, t, spb, 37, 0.3, "leg")
    a.notes["TIMP"].append((t + chords_beats(LARGO_INTRO) * spb - 4 * spb, 2.5, 49, 0.35, "hit"))
    t += chords_beats(LARGO_INTRO) * spb
    a.end(t)
    return t + 3.5


# ===============================================================================================
# 6. Grieg, Morning Mood (Peer Gynt Suite No. 1, 1875): flute and oboe trade the sunrise tune, then the strings
# ===============================================================================================
# E major pentatonic, lilting 6/8 (quarter-eighth pairs), six bars per statement
MORNING = mel("B4:1 G#4:.5 F#4:1 E4:.5 | F#4:1 G#4:.5 B4:1 G#4:.5 | F#4:.5 E4:.5 F#4:.5 G#4:.5 F#4:.5 G#4:.5 |"
              "B4:.5 G#4:.5 B4:.5 C#5:.5 G#4:.5 C#5:.5 | B4:1 G#4:.5 F#4:1 G#4:.5 | E4:3")
MORNING_CH = chords("E:3 | E:3 | B:3 | A:3 | E:1.5 B:1.5 | E:3")


def morning(a):
    bpm = 90   # quarters (6/8 at a dotted quarter of 60); a bar = 3 of our beats
    spb = 60.0 / bpm
    t = 0.0
    beats = beats_of(MORNING)
    a.mark(t, bpm, "长笛吹出日出的旋律，弦乐轻轻铺着", "The flute plays the sunrise tune over soft strings", "ww")
    a.line("FL", MORNING, t, spb, 12, 0.65, "tongue", 0.95)
    a.pad("VA", MORNING_CH, t, spb, 64, 0.3, "leg")
    a.bass("VC", MORNING_CH, t, spb, 52, 0.3, "leg")
    t += beats * spb
    a.mark(t, bpm, "双簧管接过来，同样的旋律再唱一遍", "The oboe answers with the same tune", "ww")
    a.line("OB", MORNING, t, spb, 12, 0.65, "tongue", 0.95)
    a.pad("VA", MORNING_CH, t, spb, 64, 0.3, "leg")
    a.bass("VC", MORNING_CH, t, spb, 52, 0.3, "leg")
    t += beats * spb
    a.mark(t, bpm, "长笛和双簧管又一来一回，太阳升高了一点", "Flute and oboe trade it again; the sun climbs a little", "ww")
    a.line("FL", MORNING, t, spb, 12, 0.7, "tongue", 0.95)
    a.pad("V2", MORNING_CH, t, spb, 68, 0.3, "leg")
    a.pad("VA", MORNING_CH, t, spb, 64, 0.35, "leg")
    a.bass("VC", MORNING_CH, t, spb, 52, 0.35, "leg")
    t += beats * spb
    a.line("OB", MORNING, t, spb, 12, 0.7, "tongue", 0.95)
    a.line("CL", MORNING, t, spb, 0, 0.45, "tongue", 0.95)
    a.pad("V2", MORNING_CH, t, spb, 68, 0.35, "leg")
    a.pad("VA", MORNING_CH, t, spb, 64, 0.35, "leg")
    a.bass("VC", MORNING_CH, t, spb, 52, 0.4, "leg")
    a.bass("CB", MORNING_CH, t, spb, 40, 0.3, "leg")
    t += beats * spb
    a.mark(t, bpm, "弦乐全体把旋律唱得又宽又亮，圆号和大管托着", "All the strings sing it broad and bright; horns and bassoons underneath", "str")
    for k in range(2):
        a.line("V1", MORNING, t, spb, 12, 0.7 + 0.1 * k, "arco", 0.95)
        a.line("V2", MORNING, t, spb, 0, 0.6 + 0.1 * k, "arco", 0.95)
        a.pad("VA", MORNING_CH, t, spb, 64, 0.5, "arco")
        a.bass("VC", MORNING_CH, t, spb, 52, 0.55, "arco")
        a.bass("CB", MORNING_CH, t, spb, 40, 0.55, "arco")
        a.pad("HN", MORNING_CH, t, spb, 63, 0.45 + 0.1 * k, "tongue")
        a.bass("BSN", MORNING_CH, t, spb, 52, 0.45, "tongue")
        if k == 1:
            a.line("FL", MORNING, t, spb, 24, 0.5, "tongue", 0.95)
            a.pad("TBN", MORNING_CH, t, spb, 56, 0.4, "tongue")
            a.notes["TIMP"].append((t, 1.5, 52, 0.4, "hit"))
        t += beats * spb
    a.mark(t, bpm, "最后长笛轻轻再吹一句，像早晨安静下来", "At the end the flute plays it once more, quietly", "ww")
    a.line("FL", MORNING[:6], t, spb, 12, 0.5, "tongue", 0.95)
    a.hold("V1", t, 9 * spb, m("G#5"), 0.3, "leg")
    a.hold("VA", t, 9 * spb, m("B4"), 0.3, "leg")
    a.hold("VC", t, 9 * spb, m("E3"), 0.3, "leg")
    a.hold("HN", t + 3 * spb, 6 * spb, m("E4"), 0.3, "tongue")
    t += 9 * spb
    a.end(t)
    return t + 3.0




# id, builder, title, composer, year, what each family does in this piece (the instrument card)
PIECES = [
    ("mountain-king", mountain_king, ("山魔王的大厅", "In the Hall of the Mountain King"), ("格里格", "Grieg"), 1875, {
        "str": ("开头大提琴和低音提琴用手指拨弦，迈出山魔王的脚步；后来全体拿起弓来拉。", "Cellos and basses pluck the opening steps; later every string player picks up the bow."),
        "ww": ("大管第一个吹出主题，后来双簧管、单簧管和长笛一层层加上去。", "The bassoon plays the theme first; oboe, clarinet and flute stack on top later."),
        "br": ("开头圆号的一个长音像山洞里的回声，后半段小号和长号带着全团冲。", "A horn's long note opens like a cave echo; later trumpets and trombones drive the chase."),
        "perc": ("定音鼓追着拍子越敲越快，最后一遍大鼓和镲一起砸下来。", "Timpani chase the beat faster and faster; in the last round bass drum and cymbals crash in."),
    }),
    ("ode-to-joy", ode_to_joy, ("欢乐颂", "Ode to Joy"), ("贝多芬", "Beethoven"), 1824, {
        "str": ("大提琴和低音提琴最先唱出旋律，接着中提琴、小提琴一层层接上去。", "Cellos and basses sing the tune first; violas, then violins take it higher."),
        "ww": ("大管在旋律下面走出另一条线，后来长笛、双簧管跟着一起唱。", "The bassoon walks a second line under the tune; later flute and oboe sing along."),
        "br": ("圆号托着和声，最后一遍小号吹旋律、长号和大号垫在下面。", "Horns hold the harmony; in the last round the trumpet plays the tune over trombones and tuba."),
        "perc": ("最后一遍定音鼓每两拍敲一下，结尾一阵滚奏。", "In the last round the timpani strike every two beats, then roll at the end."),
    }),
    ("canon", canon, ("D 大调卡农", "Canon in D"), ("帕赫贝尔", "Pachelbel"), 1690, {
        "str": ("低音一直重复同一组八个音；三组小提琴、中提琴照着同一条线一前一后地追。", "The bass repeats the same eight notes; violins and violas play one line, each a round behind."),
        "ww": ("后半段长笛和双簧管加进来，跟着弦乐的旋律走。", "In the second half flute and oboe join the string lines."),
        "br": ("圆号只吹长音，把和声垫厚。", "The horns only hold long notes, thickening the harmony."),
        "perc": ("这首没有打击乐，打击乐手在休息。", "No percussion in this one: the percussionists are resting."),
    }),
    ("blue-danube", danube, ("蓝色多瑙河", "The Blue Danube"), ("小约翰·施特劳斯", "J. Strauss II"), 1867, {
        "str": ("圆舞曲的「嗯嚓嚓」：低音提琴敲第一拍，中提琴和第二小提琴敲后两拍，第一小提琴唱旋律。", "The waltz's oom-pah-pah: basses on one, violas and seconds on two and three, firsts on the tune."),
        "ww": ("第二遍旋律交给长笛和单簧管，大管跟着低音走。", "In the second round flute and clarinet take the tune; the bassoon follows the bass."),
        "br": ("开头圆号吹出最有名的那三个音，后面接话、垫和弦。", "A horn plays the famous first three notes, then answers and fills in chords."),
        "perc": ("最后一遍定音鼓加进来，收尾一下镲。", "Timpani join in the last round; one cymbal crash ends it."),
    }),
    ("new-world-largo", largo, ("自新大陆·广板", "New World Symphony, Largo"), ("德沃夏克", "Dvořák"), 1893, {
        "str": ("开始只轻轻垫着和声，第三遍全体弦乐一起唱这段旋律。", "They softly pad the harmony at first; in the third round all the strings sing the tune."),
        "ww": ("这段旋律本来是英国管吹的，这里由双簧管吹，单簧管再唱一遍。", "The tune belongs to the cor anglais; here the oboe plays it, then the clarinet."),
        "br": ("开头和结尾那几个又低又暖的和弦，是长号、大号和圆号吹的。", "The low, warm chords at the start and end are trombones, tuba and horns."),
        "perc": ("只在结尾敲一下很轻的定音鼓。", "Just one very soft timpani stroke near the end."),
    }),
    ("morning-mood", morning, ("晨景", "Morning Mood"), ("格里格", "Grieg"), 1875, {
        "str": ("先轻轻铺着长音，后来全体弦乐把旋律唱得又宽又亮。", "They lay soft long notes first; later all the strings sing the tune broad and bright."),
        "ww": ("长笛和双簧管一来一回地吹日出的旋律。", "Flute and oboe trade the sunrise tune back and forth."),
        "br": ("圆号在后半段托着和声，像太阳完全升起来。", "Horns hold the harmony in the second half, as the sun fully rises."),
        "perc": ("只有一下很轻的定音鼓。", "Only one soft timpani stroke."),
    }),
]
