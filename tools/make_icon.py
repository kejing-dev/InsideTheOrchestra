"""Inside the Orchestra icon: the 512 export for the factory gallery and the legacy launcher PNG, drawn from the
same shapes as the adaptive icon (`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`):
rosewood red tile #B5476B, a white cello (body, neck, scroll, endpin, f-holes and bridge cut in the tile colour)
over three arcs of seat dots (3 / 5 / 7). Coordinates are the vector's 108-unit viewport (group scale 0.82).

    python -I tools/make_icon.py <export-512.png>
"""
import math
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1080
K = S / 108.0
TILE = (0xB5, 0x47, 0x6B, 255)
WHITE = (255, 255, 255, 255)


def g(x, y):
    """Group transform (scale 0.82 about 54,54, then up 2) -> pixels."""
    return ((54 + (x - 54) * 0.82) * K, (54 + (y - 54) * 0.82 - 2) * K)


def cubic(p0, p1, p2, p3, n=24):
    out = []
    for i in range(n + 1):
        t = i / n
        a = (1 - t) ** 3
        b = 3 * (1 - t) ** 2 * t
        c = 3 * (1 - t) * t * t
        d = t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out


def body():
    segs = [
        ((54, 34), (49.5, 34), (47, 36.5), (47.5, 40)),
        ((47.5, 40), (48, 42.5), (49.5, 43.5), (49, 45.5)),
        ((49, 45.5), (48.5, 47.5), (45, 48.5), (45, 53.5)),
        ((45, 53.5), (45, 59), (49, 62), (54, 62)),
        ((54, 62), (59, 62), (63, 59), (63, 53.5)),
        ((63, 53.5), (63, 48.5), (59.5, 47.5), (59, 45.5)),
        ((59, 45.5), (58.5, 43.5), (60, 42.5), (60.5, 40)),
        ((60.5, 40), (61, 36.5), (58.5, 34), (54, 34)),
    ]
    pts = []
    for s in segs:
        pts += cubic(*s)
    return [g(*p) for p in pts]


def draw(size):
    im = Image.new("RGBA", (S, S), TILE)
    d = ImageDraw.Draw(im)
    d.polygon(body(), fill=WHITE)
    w = int(2.6 * 0.82 * K)
    d.line([g(54, 22), g(54, 46)], fill=WHITE, width=w)
    r = 2.6 * 0.82 * K
    x, y = g(54, 21)
    d.ellipse([x - r, y - r, x + r, y + r], fill=WHITE)
    d.line([g(54, 62), g(54, 68)], fill=WHITE, width=int(1.6 * 0.82 * K))
    d.line([g(50.5, 49), g(51, 55)], fill=TILE, width=int(1.1 * 0.82 * K))
    d.line([g(57.5, 49), g(57, 55)], fill=TILE, width=int(1.1 * 0.82 * K))
    for rr, n, span in ((18, 3, 44), (24, 5, 66), (30, 7, 84)):
        for i in range(n):
            a = math.radians(270 - span / 2 + span * i / (n - 1))
            px, py = g(54 + rr * math.cos(a), 100 + rr * math.sin(a))
            q = 1.7 * 0.82 * K
            d.ellipse([px - q, py - q, px + q, py + q], fill=WHITE)
    return im.resize((size, size), Image.LANCZOS)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "icon-512.png")
    draw(512).convert("RGB").save(out)
    draw(192).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    print("icon ->", out)


if __name__ == "__main__":
    main()
