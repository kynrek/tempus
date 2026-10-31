"""Generate the 8-bit cassette tape launcher icon as Android vector drawables.

Usage: python3 tools/generate_launcher_icon.py <outdir>  (needs Pillow for preview.png)
Copy the three ic_launcher_*.xml files from <outdir> into app/src/main/res/drawable/.

The foreground is a 32x32 pixel grid (2dp per pixel, centred in the 66dp safe zone). The
background is a synthwave sunset: banded sky over a neon grid, in 3dp pixels.
"""
import sys

from PIL import Image, ImageDraw

N = 32
OUTLINE, BODY, BODY_LIGHT, SCREW = "#140A24", "#26233D", "#3A3658", "#A9A6C9"
LABEL, INK = "#FFF1E0", "#3B2A5C"
STRIPES = ["#FFD319", "#FF901F", "#FF2975", "#F222FF", "#B31EFF", "#7B2BFF", "#3D5AFE"]
WINDOW, TAPE, REEL, HUB = "#0E0818", "#6B3E26", "#7DF9FF", "#0E0818"
HOLE = "#0E0818"

SKY = ["#1B0B3A", "#2B0B57", "#45107A", "#651B9A", "#8E24AA", "#C2185B", "#FF2975", "#FF6F3C", "#FFB13B"]
GROUND, GRID = "#12052B", "#00E5FF"


def cassette():
    g = [[None] * N for _ in range(N)]

    def fill(x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                g[y][x] = c

    left, top, right, bottom = 1, 7, 30, 25
    fill(left, top, right, bottom, OUTLINE)
    fill(left + 1, top + 1, right - 1, bottom - 1, BODY)
    for x, y in ((left, top), (right, top), (left, bottom), (right, bottom)):
        g[y][x] = None  # rounded corners

    # Label: a cream strip with "handwriting", then sunset stripes.
    fill(4, 9, 27, 10, LABEL)
    for x in (6, 7, 8, 10, 11, 13, 14, 15, 17, 19, 20):
        g[10][x] = INK
    for i, c in enumerate(STRIPES):
        fill(4, 11 + i, 27, 11 + i, c)

    # Tape window with two reels and the tape between them.
    fill(9, 12, 22, 16, WINDOW)
    fill(13, 13, 18, 15, TAPE)
    for cx in (11, 20):
        for y in range(12, 17):
            for x in range(cx - 2, cx + 3):
                d = abs(x - cx) + abs(y - 14)
                if d <= 2:
                    g[y][x] = REEL if d == 2 or (d == 1 and (x - cx) * (y - 14) != 0) else HUB
        g[14][cx] = HUB

    # Bottom guide with its four holes.
    fill(7, 20, 24, 20, BODY_LIGHT)
    fill(8, 21, 23, 24, BODY_LIGHT)
    for x in (10, 13, 18, 21):
        g[22][x] = HOLE

    for x, y in ((3, 8), (28, 8), (3, 23), (28, 23)):
        g[y][x] = SCREW
    return g


grid = cassette()
MONO_GAPS = {WINDOW, HUB, HOLE, INK}


def rect_paths(cells, scale, ox, oy):
    """cells: grid of colour-or-None. Merges horizontal runs per colour into rect subpaths."""
    by_colour = {}
    for y, row in enumerate(cells):
        x = 0
        while x < len(row):
            col = row[x]
            if col is None:
                x += 1
                continue
            run = 1
            while x + run < len(row) and row[x + run] == col:
                run += 1
            by_colour.setdefault(col, []).append(
                f"M{ox + x * scale:g},{oy + y * scale:g}h{run * scale:g}v{scale:g}h{-run * scale:g}z")
            x += run
    return by_colour


def vector(by_colour, comment):
    out = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- {comment} -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for col, ps in by_colour.items():
        out.append(f'    <path\n        android:fillColor="{col}"\n        android:pathData="{"".join(ps)}" />')
    out.append("</vector>\n")
    return "\n".join(out)


def background_cells():
    """36x36 cells of 3dp: sunset bands down to the horizon, then a neon perspective grid."""
    m = 36
    horizon = 21
    cells = [[None] * m for _ in range(m)]
    for y in range(m):
        for x in range(m):
            cells[y][x] = SKY[min(len(SKY) - 1, y * len(SKY) // horizon)] if y < horizon else GROUND
    # Horizontal grid lines get further apart towards the viewer.
    for y in (horizon, horizon + 2, horizon + 5, horizon + 9, horizon + 14):
        if y < m:
            cells[y] = [GRID] * m
    # Lines fanning out from the vanishing point in the middle of the horizon.
    for dx in (-12, -6, -2.5, 2.5, 6, 12):
        for y in range(horizon, m):
            x = round(17.5 + dx * (y - horizon + 1) / 4)
            if 0 <= x < m:
                cells[y][x] = GRID
    return cells


def hex_rgb(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def main(outdir):
    fg = vector(rect_paths(grid, 2, 22, 22), "8-bit cassette tape; generated 32x32 pixel grid.")
    mono_cells = [[None if (c is None or c in MONO_GAPS) else "#FF000000" for c in row] for row in grid]
    mono = vector(rect_paths(mono_cells, 2, 22, 22), "Monochrome cassette silhouette.")
    bg_cells = background_cells()
    bg = vector(rect_paths(bg_cells, 3, 0, 0), "Synthwave sunset and neon grid, 3dp pixels.")
    for name, text in (("ic_launcher_foreground", fg), ("ic_launcher_background", bg),
                       ("ic_launcher_monochrome", mono)):
        with open(f"{outdir}/{name}.xml", "w") as f:
            f.write(text)

    # Preview at 1px per dp, scaled 4x and masked to a circle like a launcher would.
    img = Image.new("RGB", (108, 108))
    px = img.load()
    for y, row in enumerate(bg_cells):
        for x, c in enumerate(row):
            for yy in range(y * 3, y * 3 + 3):
                for xx in range(x * 3, x * 3 + 3):
                    px[xx, yy] = hex_rgb(c)
    for y, row in enumerate(grid):
        for x, c in enumerate(row):
            if c:
                for yy in range(22 + y * 2, 24 + y * 2):
                    for xx in range(22 + x * 2, 24 + x * 2):
                        px[xx, yy] = hex_rgb(c)
    big = img.resize((432, 432), Image.NEAREST)
    mask = Image.new("L", (432, 432), 0)
    ImageDraw.Draw(mask).ellipse((40, 40, 392, 392), fill=255)
    preview = Image.new("RGB", (432, 432), "#202020")
    preview.paste(big, (0, 0), mask)
    preview.save(f"{outdir}/preview.png")


if __name__ == "__main__":
    main(sys.argv[1])
