"""Generates packaging/ftcsim.ico: a BIOBUZZ bee on a honeycomb hexagon.

Usage:  python packaging/make_icon.py        (needs Pillow)
The icon is drawn once at 1024 px and downsampled (LANCZOS) to 16/24/32/48/64/128/256 px; the sizes are
stored as PNG images inside the .ico. Small sizes use a simplified drawing (bigger stripes, no outline detail)
so the bee stays readable at 16 px.
"""
import math
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "ftcsim.ico")
SIZES = [16, 24, 32, 48, 64, 128, 256]

HONEY = (255, 179, 0, 255)
HONEY_DARK = (230, 130, 0, 255)
INK = (38, 28, 10, 255)
WING = (190, 230, 255, 255)
WING_EDGE = (90, 140, 190, 255)
CELL = (255, 214, 94, 255)


def hexagon(cx, cy, r, rot=90):
    return [(cx + r * math.cos(math.radians(rot + 60 * i)), cy + r * math.sin(math.radians(rot + 60 * i))) for i in range(6)]


def draw(size, detailed):
    s = size * 4 if size < 128 else size * 2  # supersample for a clean edge
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = s / 2

    # honeycomb hexagon (pointy top) with a dark rim
    outer = hexagon(c, c, s * 0.49)
    d.polygon(outer, fill=INK)
    d.polygon(hexagon(c, c, s * 0.43), fill=HONEY)
    if detailed:  # inner honeycomb cells
        for dx, dy in ((-0.27, 0.12), (0.27, 0.12), (0.0, 0.33)):
            d.polygon(hexagon(c + dx * s, c + dy * s, s * 0.085), fill=CELL)

    # wings
    wing_w, wing_h = s * 0.14, s * 0.19
    for sign in (-1, 1):
        x = c + sign * s * 0.15
        y = c - s * 0.15
        box = [x - wing_w, y - wing_h, x + wing_w, y + wing_h]
        d.ellipse(box, fill=WING, outline=WING_EDGE, width=max(1, int(s * 0.012)))

    # body: rounded ellipse with dark stripes
    bw, bh = s * 0.21, s * 0.27
    body = [c - bw, c - bh * 0.55, c + bw, c + bh * 1.25]
    body_mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(body_mask).ellipse(body, fill=255)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    ld.rectangle([0, 0, s, s], fill=HONEY_DARK if not detailed else (255, 200, 30, 255))
    stripes = 3 if detailed else 2
    top, bottom = body[1], body[3]
    band = (bottom - top) / (stripes * 2 + 1)
    for i in range(stripes):
        y0 = top + band * (1 + 2 * i)
        ld.rectangle([0, y0, s, y0 + band], fill=INK)
    img.paste(layer, (0, 0), body_mask)
    d = ImageDraw.Draw(img)
    d.ellipse(body, outline=INK, width=max(1, int(s * (0.02 if detailed else 0.03))))

    # head with eyes, stinger
    hr = s * 0.13
    hy = body[1] - hr * 0.35
    d.ellipse([c - hr, hy - hr, c + hr, hy + hr], fill=INK)
    if detailed:
        for sign in (-1, 1):
            d.ellipse([c + sign * hr * 0.45 - hr * 0.22, hy - hr * 0.2, c + sign * hr * 0.45 + hr * 0.22, hy + hr * 0.24], fill=(255, 255, 255, 255))
        # antennae
        for sign in (-1, 1):
            d.line([(c + sign * hr * 0.4, hy - hr * 0.8), (c + sign * hr * 1.2, hy - hr * 1.5)], fill=INK, width=max(1, int(s * 0.018)))
    else:
        for sign in (-1, 1):
            d.line([(c + sign * hr * 0.4, hy - hr * 0.8), (c + sign * hr * 1.1, hy - hr * 1.3)], fill=INK, width=max(1, int(s * 0.03)))
    d.polygon([(c - s * 0.04, body[3] - s * 0.01), (c + s * 0.04, body[3] - s * 0.01), (c, body[3] + s * 0.07)], fill=INK)

    return img.resize((size, size), Image.LANCZOS)


def main():
    images = [draw(sz, detailed=sz >= 48) for sz in SIZES]
    largest = images[-1]
    largest.save(OUT, format="ICO", sizes=[(sz, sz) for sz in SIZES], append_images=images[:-1])
    # verify what was written
    with Image.open(OUT) as ico:
        print("wrote", OUT, "sizes:", sorted(ico.info.get("sizes", [])))
    # a preview sheet next to the build scratch dir is handy when tuning the design
    if os.environ.get("FTCSIM_ICON_PREVIEW"):
        sheet = Image.new("RGBA", (sum(i.width for i in images) + 10 * (len(images) + 1), 276), (255, 255, 255, 255))
        x = 10
        for im in images:
            sheet.paste(im, (x, 10), im)
            x += im.width + 10
        sheet.save(os.environ["FTCSIM_ICON_PREVIEW"])


if __name__ == "__main__":
    main()
