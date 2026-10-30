"""Textures for the VR concert hall (run before tools/make_hall.py):  python -I tools/make_hall_textures.py <out dir>

All 1024 px JPEG, seamless enough for tiling: honey stage planks, dark wainscot wood, the acoustic shell's light wood
with slats, deep-red wall fabric, patterned carpet, red velvet seats, warm cream plaster with mouldings, coffered
ceiling. Deterministic (fixed seed).
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(11)
N = 1024


def noise(scale, octaves=4):
    acc = np.zeros((N, N))
    amp = 1.0
    for o in range(octaves):
        s = max(2, int(scale * 2 ** o))
        small = rng.random((s, s))
        big = np.array(Image.fromarray((small * 255).astype(np.uint8)).resize((N, N), Image.BICUBIC)) / 255.0
        acc += big * amp
        amp *= 0.5
    acc -= acc.min()
    return acc / acc.max()


def save(name, arr):
    Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).save(os.path.join(OUT, name), quality=88)


def wood(base, dark, planks, grain_scale, name, vertical=True):
    """Planks along v with grain streaks and seams."""
    y, x = np.mgrid[0:N, 0:N] / N
    u = x if vertical else y
    v = y if vertical else x
    idx = np.floor(u * planks)
    tone = (rng.random(planks + 1)[idx.astype(int)] - 0.5) * 0.18
    grain = np.sin((v * grain_scale + noise(6) * 3.0 + idx * 1.7) * np.pi * 2) * 0.5 + 0.5
    g = 0.82 + 0.18 * grain + tone
    img = np.zeros((N, N, 3))
    for c in range(3):
        img[..., c] = base[c] * g + (dark[c] - base[c]) * (grain ** 6) * 0.5
    seam = (np.abs((u * planks) - np.round(u * planks)) < 0.012)
    # staggered butt joints
    joint = (np.abs(((v * 3 + (idx % 3) * 0.37) % 1.0)) < 0.004)
    img[seam | joint] = np.array(dark) * 0.75
    save(name, img)


wood((196, 140, 86), (110, 64, 30), 12, 22, "hall_stage.jpg")
wood((92, 56, 34), (48, 26, 14), 8, 14, "hall_wainscot.jpg", vertical=False)
wood((214, 170, 118), (150, 100, 58), 16, 10, "hall_shell.jpg")

# deep red wall fabric with a faint damask
y, x = np.mgrid[0:N, 0:N] / N
dam = (np.sin(x * 2 * np.pi * 8) * np.sin(y * 2 * np.pi * 8)) ** 2
n = noise(64, 3)
img = np.zeros((N, N, 3))
img[..., 0] = 112 + 26 * dam + 14 * n
img[..., 1] = 26 + 8 * dam + 6 * n
img[..., 2] = 30 + 8 * dam + 6 * n
save("hall_fabric.jpg", img)

# carpet: dark red with a small gold diamond pattern
d = (np.abs(((x * 16) % 1) - 0.5) + np.abs(((y * 16) % 1) - 0.5)) < 0.12
n = noise(128, 2)
img = np.zeros((N, N, 3))
img[..., 0] = 88 + 20 * n
img[..., 1] = 20 + 6 * n
img[..., 2] = 26 + 6 * n
img[d] = [150, 110, 52]
save("hall_carpet.jpg", img)

# velvet seats: red with soft sheen
n = noise(32, 3)
img = np.zeros((N, N, 3))
img[..., 0] = 140 + 40 * n
img[..., 1] = 22 + 12 * n
img[..., 2] = 30 + 12 * n
save("hall_velvet.jpg", img)

# cream plaster with moulding lines (pilasters / balcony fronts)
im = Image.new("RGB", (N, N), (226, 210, 180))
dr = ImageDraw.Draw(im)
for k in range(0, N, 128):
    dr.rectangle([k + 10, 10, k + 118, N - 10], outline=(196, 172, 128), width=6)
    dr.rectangle([k + 24, 24, k + 104, N - 24], outline=(240, 226, 196), width=3)
im = im.filter(ImageFilter.GaussianBlur(1.2))
im.save(os.path.join(OUT, "hall_plaster.jpg"), quality=88)

# coffered ceiling: dark warm grey cells with gilded edges
im = Image.new("RGB", (N, N), (58, 44, 38))
dr = ImageDraw.Draw(im)
cell = N // 4
for i in range(4):
    for j in range(4):
        x0, y0 = i * cell, j * cell
        dr.rectangle([x0, y0, x0 + cell - 1, y0 + cell - 1], outline=(150, 112, 56), width=10)
        dr.rectangle([x0 + 28, y0 + 28, x0 + cell - 29, y0 + cell - 29], fill=(44, 32, 28), outline=(96, 72, 40), width=4)
im = im.filter(ImageFilter.GaussianBlur(1.0))
im.save(os.path.join(OUT, "hall_ceiling.jpg"), quality=88)
print("textures ->", OUT)
