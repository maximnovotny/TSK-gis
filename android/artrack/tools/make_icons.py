#!/usr/bin/env python3
"""
Rebuilds the launcher icon from docs/icon-source.png.

The icon is adaptive with a TRANSPARENT background, so the mascot floats on the
home screen instead of sitting on a tile. That makes the safe size the binding
constraint: a launcher mask cuts whatever sticks out, and with nothing behind it
the cut is plainly visible. 70 of the 108 dp canvas is the largest size at which
the squircle mask takes nothing at all and the circle mask only grazes the two
outermost tentacle tips (~0.6 % of the artwork).

    python3 tools/make_icons.py        # needs pillow

Writes mipmap-*/ic_launcher_foreground.png, mipmap-*/ic_launcher_monochrome.png,
docs/icon-512.png (opaque, for stores) and docs/icon-preview.png.
"""

import os

from PIL import Image, ImageChops, ImageDraw

SOURCE = "docs/icon-source.png"
RES = "app/src/main/res"
SAFE_DP = 70
CANVAS_DP = 108
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
STORE_BACKGROUND = (0x0E, 0x27, 0x33, 255)
MONO_LUMA_THRESHOLD = 110


def fit(canvas_px, image):
    """Centres the artwork inside the safe zone of a canvas_px square."""
    target = int(round(canvas_px * SAFE_DP / CANVAS_DP))
    width, height = image.size
    scale = target / max(width, height)
    resized = image.resize(
        (max(1, round(width * scale)), max(1, round(height * scale))), Image.LANCZOS
    )
    out = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    out.paste(
        resized,
        ((canvas_px - resized.size[0]) // 2, (canvas_px - resized.size[1]) // 2),
        resized,
    )
    return out


def monochrome(art):
    """Themed-icon layer: keep the bright parts so the sunglasses stay as holes."""
    luma = art.convert("RGB").convert("L")
    bright = luma.point(lambda v: 255 if v > MONO_LUMA_THRESHOLD else 0)
    out = Image.new("RGBA", art.size, (255, 255, 255, 0))
    out.putalpha(ImageChops.multiply(art.getchannel("A"), bright))
    return out


def mask(image, shape):
    size = image.size[0]
    inset = int(size * 18 / CANVAS_DP)
    m = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(m)
    box = [inset, inset, size - inset, size - inset]
    if shape == "circle":
        draw.ellipse(box, fill=255)
    else:
        draw.rounded_rectangle(box, radius=int(size * 0.22), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(image, (0, 0), m)
    return out


def clipped_pixels(layer, shape):
    opaque = sum(1 for v in layer.getchannel("A").getdata() if v > 8)
    kept = sum(1 for v in mask(layer, shape).getchannel("A").getdata() if v > 8)
    return opaque - kept


def main():
    art = Image.open(SOURCE).convert("RGBA")
    art = art.crop(art.getchannel("A").getbbox())
    mono = monochrome(art)

    for name, px in DENSITIES.items():
        directory = f"{RES}/mipmap-{name}"
        os.makedirs(directory, exist_ok=True)
        fit(px, art).save(f"{directory}/ic_launcher_foreground.png")
        fit(px, mono).save(f"{directory}/ic_launcher_monochrome.png")
        print(f"  mipmap-{name}: {px} px")

    big = fit(432, art)
    for shape in ("circle", "squircle"):
        print(f"  {shape} mask would cut {clipped_pixels(big, shape)} px of the artwork")

    store = Image.new("RGBA", (512, 512), STORE_BACKGROUND)
    store.alpha_composite(fit(512, art))
    store.convert("RGB").save("docs/icon-512.png")

    tiles = [mask(big, "circle"), mask(big, "squircle")]
    themed = Image.new("RGBA", big.size, (0x25, 0x31, 0x38, 255))
    tint = Image.new("RGBA", big.size, (0xBF, 0xD6, 0xE2, 255))
    tint.putalpha(fit(432, mono).getchannel("A"))
    themed.alpha_composite(tint)
    tiles.append(mask(themed, "circle"))

    pad, size = 28, big.size[0]
    sheet = Image.new("RGB", (len(tiles) * size + (len(tiles) + 1) * pad, size + 2 * pad), (0, 0, 0))
    for i, tile in enumerate(tiles):
        sheet.paste(tile, (pad + i * (size + pad), pad), tile)
    sheet.resize((sheet.size[0] // 2, sheet.size[1] // 2), Image.LANCZOS).save(
        "docs/icon-preview.png"
    )
    print("  docs/icon-512.png, docs/icon-preview.png")


if __name__ == "__main__":
    main()
