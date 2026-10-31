"""Generate the 8-bit vinyl record launcher icon as Android vector drawables.

Usage: python3 tools/generate_launcher_icon.py <outdir>  (needs Pillow for preview.png)
Copy the three ic_launcher_*.xml files from <outdir> into app/src/main/res/drawable/.
"""
import math, sys
from PIL import Image

N = 32                      # pixel grid
C = (N - 1) / 2.0           # centre in pixel-centre coordinates
OUTLINE, VINYL, GROOVE, SHINE, SHINE2 = "#0B0B10", "#1C1C26", "#34344A", "#B9B9D6", "#6E6E90"
LABEL, LABEL_DARK, LABEL_DOT = "#E8453C", "#A82B25", "#FFD9A0"
BG, BG_DOT = "#F2C14E", "#E5AE34"

def pixel(x, y):
    r = math.hypot(x - C, y - C)
    a = math.degrees(math.atan2(C - y, x - C))  # 0 = right, 90 = up
    if r > 15.6: return None
    if r > 14.6: return OUTLINE
    if r <= 1.3: return None                    # spindle hole
    if r <= 2.3: return LABEL_DARK
    if r <= 6.0:
        if 3.2 < r <= 4.2 and 100 < a < 170: return LABEL_DOT   # little label highlight
        return LABEL
    if r <= 7.0: return LABEL_DARK
    # glint: two diagonal arcs, top-left and bottom-right
    for centre in (135, -45):
        d = abs((a - centre + 180) % 360 - 180)
        if 8.5 < r <= 13.5 and d < 9: return SHINE if d < 5 else SHINE2
    if any(abs(r - g) < 0.5 for g in (9.0, 11.5, 13.5)): return GROOVE
    return VINYL

MONO_GAPS = (GROOVE, SHINE, SHINE2, LABEL_DARK)
grid = [[pixel(x, y) for x in range(N)] for y in range(N)]

def paths(scale, ox, oy, mono=False):
    by_colour = {}
    for y in range(N):
        x = 0
        while x < N:
            col = grid[y][x]
            if col is None: x += 1; continue
            key = "#FF000000" if mono else col
            # monochrome: a filled disc, with grooves, shine and the label ring cut out as detail
            if mono and col in MONO_GAPS: x += 1; continue
            run = 1
            while x + run < N and grid[y][x + run] is not None and (("#FF000000" if mono else grid[y][x+run]) == key) and not (mono and grid[y][x+run] in MONO_GAPS):
                run += 1
            by_colour.setdefault(key, []).append(
                f"M{ox + x*scale:g},{oy + y*scale:g}h{run*scale:g}v{scale:g}h{-run*scale:g}z")
            x += run
    return by_colour

def vector(by_colour):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- 8-bit vinyl record; generated pixel grid (32x32), each pixel is one square path. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for col, ps in by_colour.items():
        out.append(f'    <path\n        android:fillColor="{col}"\n        android:pathData="{"".join(ps)}" />')
    out.append('</vector>\n')
    return "\n".join(out)

# Foreground: 32 px * 2 = 64 dp, centred in the 66 dp safe zone of the 108 dp canvas.
fg = vector(paths(2, 22, 22))
mono = vector(paths(2, 22, 22, mono=True))
# Background: flat colour with a sparse 8-bit dot pattern (6 dp pixels).
bgp = []
for gy in range(18):
    for gx in range(18):
        if (gx + gy * 3) % 7 == 0:
            bgp.append(f"M{gx*6},{gy*6}h6v6h-6z")
bg = "\n".join(['<?xml version="1.0" encoding="utf-8"?>',
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
    '    android:width="108dp"', '    android:height="108dp"',
    '    android:viewportWidth="108"', '    android:viewportHeight="108">',
    f'    <path\n        android:fillColor="{BG}"\n        android:pathData="M0,0h108v108h-108z" />',
    f'    <path\n        android:fillColor="{BG_DOT}"\n        android:pathData="{"".join(bgp)}" />',
    '</vector>\n'])

outdir = sys.argv[1]
open(f"{outdir}/ic_launcher_foreground.xml", "w").write(fg)
open(f"{outdir}/ic_launcher_background.xml", "w").write(bg)
open(f"{outdir}/ic_launcher_monochrome.xml", "w").write(mono)

# Preview: 108 px canvas = 1 px per dp, scaled up 4x, masked to a circle like a launcher would.
img = Image.new("RGB", (108, 108), BG)
px = img.load()
for gy in range(18):
    for gx in range(18):
        if (gx + gy * 3) % 7 == 0:
            for yy in range(gy*6, gy*6+6):
                for xx in range(gx*6, gx*6+6): px[xx, yy] = tuple(int(BG_DOT[i:i+2], 16) for i in (1,3,5))
for y in range(N):
    for x in range(N):
        col = grid[y][x]
        if col:
            rgb = tuple(int(col[i:i+2], 16) for i in (1, 3, 5))
            for yy in range(22 + y*2, 24 + y*2):
                for xx in range(22 + x*2, 24 + x*2): px[xx, yy] = rgb
big = img.resize((432, 432), Image.NEAREST)
mask = Image.new("L", (432, 432), 0)
from PIL import ImageDraw
ImageDraw.Draw(mask).ellipse((40, 40, 392, 392), fill=255)  # ~ 88 dp visible of 108
preview = Image.new("RGB", (432, 432), "#202020"); preview.paste(big, (0, 0), mask)
preview.save(f"{outdir}/preview.png")
