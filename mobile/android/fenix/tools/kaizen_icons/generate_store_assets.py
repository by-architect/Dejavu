# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Draws Dejavu's store graphics: the 512 px icon and the 1024x500 feature graphic.

The mark and colors are the ones of generate_launcher.py. Usage: generate_store_assets.py OUTPUT_DIR [FONT]
"""

import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

SUPERSAMPLE = 4
STOPS = [(0.0, (0xFF, 0xED, 0xC2)), (0.55, (0xFF, 0xD6, 0x7A)), (1.0, (0xF2, 0xB8, 0x4B))]
MARK = (0x5A, 0x45, 0x2A)
ECHO_ALPHA = 0.28
# In the 108 unit space of the launcher icon.
RADIUS, SOLID, ECHO = 16.5, (47, 60.5), (61, 47.5)
DEFAULT_FONT = "InterVariable.ttf"


def gradient(width, height):
    """The diagonal gold gradient with the soft highlight of the launcher icon, as an RGB array."""
    y, x = np.mgrid[0:height, 0:width].astype(np.float64)
    t = (x / width + y / height) / 2
    rgb = np.zeros((height, width, 3))
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        part = np.clip((t - t0) / (t1 - t0), 0, 1)[..., None]
        inside = ((t >= t0) & (t <= t1))[..., None]
        rgb = np.where(inside, np.array(c0) + (np.array(c1) - np.array(c0)) * part, rgb)
    size = min(width, height)
    distance = np.hypot(x - width * 32 / 108, y - height * 24 / 108) / (size * 81 / 108)
    light = np.clip(1 - distance, 0, 1)[..., None] * (0x40 / 255)
    return rgb * (1 - light) + 255 * light


def draw_mark(image, center, unit):
    """Draws the mark centered on [center], [unit] pixels per launcher icon unit."""
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    for (cx, cy), alpha in ((ECHO, ECHO_ALPHA), (SOLID, 1.0)):
        x = center[0] + (cx - 54) * unit
        y = center[1] + (cy - 54) * unit
        r = RADIUS * unit
        draw.ellipse((x - r, y - r, x + r, y + r), fill=MARK + (round(255 * alpha),))
    image.alpha_composite(layer)


def icon(size):
    big = size * SUPERSAMPLE
    image = Image.fromarray(gradient(big, big).astype(np.uint8)).convert("RGBA")
    draw_mark(image, (big / 2, big / 2), big / 108)
    return image.resize((size, size), Image.LANCZOS)


def feature_graphic(font_path):
    width, height = 1024 * SUPERSAMPLE, 500 * SUPERSAMPLE
    image = Image.fromarray(gradient(width, height).astype(np.uint8)).convert("RGBA")
    draw_mark(image, (width * 0.27, height / 2), height / 108 * 0.9)
    draw = ImageDraw.Draw(image)
    title = ImageFont.truetype(font_path, 120 * SUPERSAMPLE)
    subtitle = ImageFont.truetype(font_path, 44 * SUPERSAMPLE)
    for font, weight in ((title, 650), (subtitle, 450)):
        try:
            font.set_variation_by_axes([weight])
        except (OSError, ValueError):
            pass
    left = width * 0.47
    draw.text((left, height * 0.36), "Dejavu", font=title, fill=MARK + (255,), anchor="lm")
    draw.text((left, height * 0.62), "Browser", font=subtitle, fill=MARK + (190,), anchor="lm")
    return image.resize((1024, 500), Image.LANCZOS).convert("RGB")


def main():
    out = sys.argv[1]
    font = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_FONT
    os.makedirs(out, exist_ok=True)
    icon(512).save(os.path.join(out, "dejavu-icon-512.png"))
    feature_graphic(font).save(os.path.join(out, "dejavu-feature-graphic-1024x500.png"))


if __name__ == "__main__":
    main()
