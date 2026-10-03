#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Makes the iOS app's images, colors and launch screen from the Android app's and Firefox's.

Writes into GeckoTestBrowser/Resources/Assets.xcassets:

- container-<icon>.imageset for the 13 container icons, from Firefox desktop's SVGs in
  browser/components/contextualidentity/content. Each of those holds two drawings, Firefox's old one and its "nova"
  one, picked by a pref only Firefox understands; the script keeps the nova one, which the Android app uses too, and
  paints it black. An icon Firefox does not have is converted from the Android app's dejavu_container_<icon>.xml.
- dejavu-sleep, dejavu-split, dejavu-unsplit, dejavu-container, dejavu-no-container and
  dejavu-temporary-container.imageset, converted from the Android app's dejavu_ic_<name>_24.xml.
  These and the container icons are template images: black on transparent, for the app to tint.
- dejavu-logo.imageset, Dejavu's logo in its own colors, converted from the Android app's dejavu_logo.xml.
- AppIcon.appiconset, the app icon, drawn like the Android launcher icon (mipmap-anydpi/dejavu_launcher.xml): what a
  launcher shows of its layers, as one opaque 1024 px square that iOS rounds itself, and the dark and tinted versions
  of iOS 18, which are the mark alone on a transparent background that iOS fills: in gold, and in white.
- AccentColor.colorset and LaunchBackground.colorset, from the Android app's values/dejavu_colors.xml.

and GeckoTestBrowser/Launch/Base.lproj/LaunchScreen.storyboard: the logo, 96 points wide, centered on LaunchBackground.

The vector drawables are converted to SVG, which Xcode keeps as vectors: paths with their fills, strokes, alpha and
fill type, gradients, groups with their transforms, and clip paths. Each folder the script writes is replaced as a
whole; the other folders of the catalog are left alone. Run it again after any of its sources change:

    python3 mobile/ios/GeckoTestBrowser/tools/dejavu_assets.py

It needs Pillow and NumPy, like the Android icon scripts (pip install pillow numpy).
"""

import json
import math
import re
import shutil
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from pathlib import Path
from xml.sax.saxutils import escape

try:
    import numpy as np
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("dejavu_assets.py needs Pillow and NumPy: pip install pillow numpy")

TOOLS = Path(__file__).resolve().parent
APP = TOOLS.parent / "GeckoTestBrowser"
TREE = TOOLS.parents[3]
SCRIPT = Path(__file__).resolve().relative_to(TREE)
CATALOG = APP / "Resources/Assets.xcassets"
STORYBOARD = APP / "Launch/Base.lproj/LaunchScreen.storyboard"
ANDROID_RES = TREE / "mobile/android/fenix/app/src/main/res"
DRAWABLES = ANDROID_RES / "drawable"
FIREFOX_ICONS = TREE / "browser/components/contextualidentity/content"

# The container icons, named like Firefox's files and like ContainerIcon in Dejavu/Model/Containers.swift.
CONTAINER_ICONS = [
    "fingerprint", "briefcase", "dollar", "cart", "circle", "gift", "vacation", "food", "fruit", "pet", "tree", "chill",
    "fence",
]
# Firefox shows the drawings of this class when its pref browser.nova.enabled is on, and those of the others when not.
FIREFOX_VARIANT = "nova"
FIREFOX_OTHER_VARIANTS = {"proton"}
# The size of the container icons in points, like the Android app's drawables of them; each keeps its own viewBox.
CONTAINER_ICON_SIZE = 24

DEJAVU_ICONS = {
    "dejavu-sleep": "dejavu_ic_sleep_24.xml",
    "dejavu-split": "dejavu_ic_split_24.xml",
    "dejavu-unsplit": "dejavu_ic_unsplit_24.xml",
    "dejavu-container": "dejavu_ic_container_24.xml",
    "dejavu-no-container": "dejavu_ic_container_24.xml".replace("container", "no_container"),
    "dejavu-temporary-container": "dejavu_ic_temporary_container_24.xml",
}
# The logo fills its whole square; dejavu_splash_logo.xml leaves room for the circle Android's splash screen cuts.
LOGO = "dejavu-logo"
LOGO_DRAWABLE = "dejavu_logo.xml"
LAUNCH_LOGO_POINTS = 96

LAUNCHER = ANDROID_RES / "mipmap-anydpi/dejavu_launcher.xml"
APP_ICON_PIXELS = 1024
# The layers of an adaptive icon reach this far past each side of what a launcher shows of them, as a fraction of what
# it shows (AdaptiveIconDrawable.EXTRA_INSET_PERCENTAGE): the middle 72 of 108 units.
EXTRA_INSET = 0.25
# Shapes are drawn this many times larger, then scaled down, for smooth edges.
SUPERSAMPLE = 4

# Colors of the asset catalog, by the names of values/dejavu_colors.xml: (light, dark). They are DejavuColors.primary
# and DejavuColors.surface of Dejavu/UI/Theme.swift.
COLORS = {
    "AccentColor": ("dejavuGold45", "dejavuGold80"),
    "LaunchBackground": ("dejavuWarm5", "dejavuWarm75"),
}
SYSTEM_COLORS = {"transparent": "#00000000", "white": "#FFFFFFFF", "black": "#FF000000"}

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
SVG_NAMESPACE = "http://www.w3.org/2000/svg"
XLINK_NAMESPACE = "http://www.w3.org/1999/xlink"
INFO = {"author": "xcode", "version": 1}
LICENSE = (
    "<!-- This Source Code Form is subject to the terms of the Mozilla Public\n"
    "   - License, v. 2.0. If a copy of the MPL was not distributed with this\n"
    "   - file, You can obtain one at http://mozilla.org/MPL/2.0/. -->"
)
# Elements iOS draws in an SVG image; the script warns about any other in what it writes.
SVG_ELEMENTS = {
    "svg", "g", "path", "circle", "ellipse", "rect", "line", "polyline", "polygon", "defs", "clipPath",
    "linearGradient", "radialGradient", "stop", "title", "desc",
}

warnings = []


def warn(message):
    warnings.append(message)


def num(value):
    """A number as SVG writes it, without trailing zeros: 54, 1.4, 0.28."""
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def attribute(name, value):
    return f'{name}="{escape(str(value), {chr(34): "&quot;"})}"'


def relative(path):
    return Path(path).resolve().relative_to(TREE).as_posix()


# Colors


def parse_color(text):
    """(red, green, blue, alpha) of an Android #RGB, #ARGB, #RRGGBB or #AARRGGBB color, with alpha from 0 to 1."""
    digits = text.strip()[1:]
    if len(digits) in (3, 4):
        digits = "".join(digit * 2 for digit in digits)
    if len(digits) == 6:
        digits = "FF" + digits
    if not re.fullmatch(r"[0-9A-Fa-f]{8}", digits):
        raise ValueError(f"{text!r} is not a color")
    alpha, red, green, blue = (int(digits[index:index + 2], 16) for index in range(0, 8, 2))
    return red, green, blue, alpha / 255


_palette = None


def palette():
    """The color resources of the Android app's res/values, by name."""
    global _palette
    if _palette is None:
        _palette = {}
        for path in sorted((ANDROID_RES / "values").glob("*.xml")):
            if b"<color " not in path.read_bytes():
                continue
            for element in ElementTree.parse(path).getroot().iter("color"):
                if element.get("name") and element.text:
                    _palette[element.get("name")] = element.text.strip()
    return _palette


def color_value(text, template, where, depth=0):
    """The color of a drawable's color attribute, as (red, green, blue, alpha).

    A template image is black and keeps only the alpha of its colors. Its references to color resources or to theme
    attributes stand for the color the app tints it with, so they are opaque black.
    """
    text = text.strip()
    if text == "@android:color/transparent":
        return 0, 0, 0, 0.0
    if text.startswith("#"):
        red, green, blue, alpha = parse_color(text)
        return (0, 0, 0, alpha) if template else (red, green, blue, alpha)
    if template and text[:1] in ("@", "?"):
        return 0, 0, 0, 1.0
    name = text.split("/", 1)[-1]
    if text.startswith("@android:color/") and name in SYSTEM_COLORS:
        return color_value(SYSTEM_COLORS[name], template, where)
    if text.startswith("@color/") and name in palette() and depth < 8:
        return color_value(palette()[name], template, where, depth + 1)
    warn(f"{where}: cannot resolve the color {text}, drew it black")
    return 0, 0, 0, 1.0


def hex_color(color):
    return "#{:02X}{:02X}{:02X}".format(*color[:3])


# Path data


PATH_COMMANDS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
PATH_TOKEN = re.compile(r"[\s,]*([MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)")


def path_segments(data):
    """The segments of SVG or VectorDrawable path data (the two read the same) as (command, numbers).

    A command repeated without its letter is given its letter, and the lines after a move are lines. Raises ValueError
    on anything else, so that broken path data is never written.
    """
    tokens = []
    position = 0
    data = data.rstrip(" \t\r\n,")
    while position < len(data):
        match = PATH_TOKEN.match(data, position)
        if not match:
            raise ValueError(f"unreadable path data at {data[position:position + 24]!r}")
        tokens.append(match.group(1))
        position = match.end()
    segments = []
    index = 0
    while index < len(tokens):
        command = tokens[index]
        index += 1
        count = PATH_COMMANDS.get(command.upper()) if command.isalpha() else None
        if count is None:
            raise ValueError(f"path data has {command!r} where a command should be")
        if not segments and command not in "Mm":
            raise ValueError("path data does not start with a move")
        if count == 0:
            segments.append((command, []))
            continue
        while True:
            numbers = tokens[index:index + count]
            if len(numbers) < count or any(token.isalpha() for token in numbers):
                raise ValueError(f"{command} without its {count} numbers in path data")
            segments.append((command, [float(token) for token in numbers]))
            index += count
            command = {"M": "L", "m": "l"}.get(command, command)
            if index >= len(tokens) or tokens[index].isalpha():
                break
    return segments


# Affine transforms as SVG's matrix(a b c d e f): x' = a x + c y + e, y' = b x + d y + f.


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def multiply(outer, inner):
    """The transform that applies inner, then outer."""
    a1, b1, c1, d1, e1, f1 = outer
    a2, b2, c2, d2, e2, f2 = inner
    return (a1 * a2 + c1 * b2, b1 * a2 + d1 * b2, a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
            a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1)


def invert(matrix):
    a, b, c, d, e, f = matrix
    det = a * d - b * c
    return d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det


def translation(x, y):
    return 1.0, 0.0, 0.0, 1.0, x, y


def scaling(x, y):
    return x, 0.0, 0.0, y, 0.0, 0.0


def rotation(degrees):
    cos, sin = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    return cos, sin, -sin, cos, 0.0, 0.0


def apply(matrix, x, y):
    a, b, c, d, e, f = matrix
    return a * x + c * y + e, b * x + d * y + f


# Vector drawables


@dataclass
class Gradient:
    kind: str
    stops: list  # (offset, color)
    start: tuple = (0.0, 0.0)
    end: tuple = (0.0, 0.0)
    center: tuple = (0.0, 0.0)
    radius: float = 0.0
    tile: str = "clamp"


@dataclass
class Shape:
    data: str
    fill: object = None  # a color, a Gradient, or None for no fill
    fill_alpha: float = 1.0
    even_odd: bool = False
    stroke: object = None
    stroke_alpha: float = 1.0
    stroke_width: float = 0.0
    line_cap: str = "butt"
    line_join: str = "miter"
    miter_limit: float = 4.0


@dataclass
class Clip:
    """Clips what comes after it in its group, as <clip-path> does."""
    data: str


@dataclass
class Group:
    matrix: tuple
    transform: str  # the same transform as an SVG attribute, or "" when there is none
    children: list = field(default_factory=list)


@dataclass
class Vector:
    source: Path
    width: float
    height: float
    viewport: tuple
    alpha: float
    children: list


def android(element, name, default=None):
    return element.get(ANDROID + name, default)


def number(element, name, default):
    value = android(element, name)
    return default if value is None else float(value)


def dimension(text, where):
    match = re.fullmatch(r"\s*([-+]?(?:\d+\.?\d*|\.\d+))\s*(dp|dip|px|sp|pt)?\s*", text or "")
    if not match:
        raise ValueError(f"{where}: {text!r} is not a size")
    if match.group(2) not in (None, "dp", "dip"):
        warn(f"{where}: took its size in {match.group(2)} for points")
    return float(match.group(1))


def paint(element, name, template, where):
    """The paint of a path's fillColor or strokeColor, a color or a Gradient, or None when it draws nothing."""
    for child in element:
        if child.tag == AAPT + "attr" and child.get("name") == "android:" + name:
            gradient = child.find("gradient")
            if gradient is None:
                warn(f"{where}: skipped an inline {name} that is not a gradient")
                return None
            return parse_gradient(gradient, template, where)
    value = android(element, name)
    if value is None:
        return None
    color = color_value(value, template, where)
    return None if color[3] == 0 else color


def parse_gradient(element, template, where):
    items = element.findall("item")
    if items:
        stops = [(float(android(item, "offset", "0")), color_value(android(item, "color", "#00000000"), template, where))
                 for item in items]
    else:
        colors = [android(element, name) for name in ("startColor", "centerColor", "endColor")]
        stops = [(offset, color_value(color, template, where))
                 for offset, color in zip((0.0, 0.5, 1.0), colors) if color is not None]
        if colors[1] is None and len(stops) == 2:
            stops = [(0.0, stops[0][1]), (1.0, stops[1][1])]
    if not stops:
        raise ValueError(f"{where}: a gradient without colors")
    return Gradient(
        kind=android(element, "type", "linear"),
        stops=stops,
        start=(number(element, "startX", 0.0), number(element, "startY", 0.0)),
        end=(number(element, "endX", 0.0), number(element, "endY", 0.0)),
        center=(number(element, "centerX", 0.0), number(element, "centerY", 0.0)),
        radius=number(element, "gradientRadius", 0.0),
        tile=android(element, "tileMode", "clamp"),
    )


def path_data(element, where):
    data = " ".join((android(element, "pathData") or "").split())
    try:
        path_segments(data)
    except ValueError as error:
        raise ValueError(f"{where}: {error}") from None
    return data


def parse_shape(element, template, where):
    shape = Shape(
        data=path_data(element, where),
        fill=paint(element, "fillColor", template, where),
        fill_alpha=number(element, "fillAlpha", 1.0),
        even_odd=android(element, "fillType", "nonZero") == "evenOdd",
        stroke=paint(element, "strokeColor", template, where),
        stroke_alpha=number(element, "strokeAlpha", 1.0),
        stroke_width=number(element, "strokeWidth", 0.0),
        line_cap=android(element, "strokeLineCap", "butt"),
        line_join=android(element, "strokeLineJoin", "miter"),
        miter_limit=number(element, "strokeMiterLimit", 4.0),
    )
    for name, default in (("trimPathStart", 0.0), ("trimPathEnd", 1.0), ("trimPathOffset", 0.0)):
        if number(element, name, default) != default:
            warn(f"{where}: SVG has no {name}, drew the whole path")
    if shape.stroke is not None and shape.stroke_width == 0:
        warn(f"{where}: a stroke without strokeWidth is a hairline on Android, and nothing in SVG")
    return shape


def parse_group(element, template, where):
    pivot_x, pivot_y = number(element, "pivotX", 0.0), number(element, "pivotY", 0.0)
    scale_x, scale_y = number(element, "scaleX", 1.0), number(element, "scaleY", 1.0)
    degrees = number(element, "rotation", 0.0)
    move_x, move_y = number(element, "translateX", 0.0), number(element, "translateY", 0.0)
    # VectorDrawable's order: around the pivot, scale, then rotate, then translate.
    matrix = multiply(translation(move_x + pivot_x, move_y + pivot_y),
                      multiply(rotation(degrees), multiply(scaling(scale_x, scale_y), translation(-pivot_x, -pivot_y))))
    parts = []
    if degrees == 0 and scale_x == 1 and scale_y == 1:
        if move_x or move_y:
            parts.append(f"translate({num(move_x)} {num(move_y)})")
    else:
        if move_x + pivot_x or move_y + pivot_y:
            parts.append(f"translate({num(move_x + pivot_x)} {num(move_y + pivot_y)})")
        if degrees:
            parts.append(f"rotate({num(degrees)})")
        if scale_x != 1 or scale_y != 1:
            parts.append(f"scale({num(scale_x)})" if scale_x == scale_y else f"scale({num(scale_x)} {num(scale_y)})")
        if pivot_x or pivot_y:
            parts.append(f"translate({num(-pivot_x)} {num(-pivot_y)})")
    group = Group(matrix, " ".join(parts), parse_children(element, template, where))
    if abs(scale_x) != abs(scale_y) and any(isinstance(shape, Shape) and shape.stroke for shape in group.children):
        warn(f"{where}: Android does not stretch strokes in a group scaled unevenly, SVG does")
    return group


def parse_children(element, template, where):
    children = []
    for child in element:
        if child.tag == "path":
            children.append(parse_shape(child, template, where))
        elif child.tag == "group":
            children.append(parse_group(child, template, where))
        elif child.tag == "clip-path":
            children.append(Clip(path_data(child, where)))
        elif child.tag != AAPT + "attr" or element.tag not in ("path", "vector"):
            warn(f"{where}: skipped <{child.tag}>")
    return children


def parse_vector(path, template):
    """A vector drawable, its colors made black for a template image."""
    root = ElementTree.parse(path).getroot()
    where = path.name
    if root.tag != "vector":
        raise ValueError(f"{where} is a <{root.tag}>, not a vector drawable")
    if android(root, "tint") and not template:
        warn(f"{where}: ignored its tint")
    if android(root, "autoMirrored") == "true":
        warn(f"{where}: is mirrored right to left on Android, not on iOS")
    return Vector(
        source=path,
        width=dimension(android(root, "width"), where),
        height=dimension(android(root, "height"), where),
        viewport=(float(android(root, "viewportWidth")), float(android(root, "viewportHeight"))),
        alpha=number(root, "alpha", 1.0),
        children=parse_children(root, template, where),
    )


# SVG


def svg_document(width, height, view_box, body, source, extra_root_attributes=()):
    root = [attribute("xmlns", SVG_NAMESPACE), *extra_root_attributes,
            attribute("width", num(width)), attribute("height", num(height)), attribute("viewBox", view_box)]
    return "\n".join([
        '<?xml version="1.0" encoding="UTF-8"?>',
        LICENSE,
        f"<!-- Generated by {SCRIPT.as_posix()} from {source}. Do not edit; run the script. -->",
        f"<svg {' '.join(root)}>",
        *body,
        "</svg>",
        "",
    ])


def svg_from_vector(vector):
    """SVG drawing the same as a vector drawable."""
    where = vector.source.name
    definitions = []
    counter = iter(range(1, 1 << 30))

    def gradient_definition(gradient):
        identifier = f"gradient{next(counter)}"
        units = attribute("gradientUnits", "userSpaceOnUse")
        if gradient.kind == "radial":
            tag = "radialGradient"
            geometry = (f'cx="{num(gradient.center[0])}" cy="{num(gradient.center[1])}" '
                        f'r="{num(gradient.radius)}"')
        else:
            tag = "linearGradient"
            geometry = (f'x1="{num(gradient.start[0])}" y1="{num(gradient.start[1])}" '
                        f'x2="{num(gradient.end[0])}" y2="{num(gradient.end[1])}"')
        spread = {"repeat": ' spreadMethod="repeat"', "mirror": ' spreadMethod="reflect"'}.get(gradient.tile, "")
        definitions.append(f'<{tag} id="{identifier}" {units} {geometry}{spread}>')
        for offset, color in gradient.stops:
            opacity = f' stop-opacity="{num(color[3])}"' if color[3] < 1 else ""
            definitions.append(f'  <stop offset="{num(offset)}" stop-color="{hex_color(color)}"{opacity}/>')
        definitions.append(f"</{tag}>")
        return identifier

    def paint_attributes(name, value, alpha):
        if value is None:
            return [(name, "none")]
        if isinstance(value, Gradient) and value.kind == "sweep":
            warn(f"{where}: SVG has no sweep gradient, painted its first color")
            value = value.stops[0][1]
        if isinstance(value, Gradient):
            result, opacity = [(name, f"url(#{gradient_definition(value)})")], alpha
        else:
            result, opacity = [(name, hex_color(value))], value[3] * alpha
        if opacity < 1:
            result.append((f"{name}-opacity", num(opacity)))
        return result

    def shape_element(shape, pad):
        attributes = paint_attributes("fill", shape.fill, shape.fill_alpha)
        if shape.even_odd and shape.fill is not None:
            attributes.append(("fill-rule", "evenodd"))
        if shape.stroke is not None:
            attributes += paint_attributes("stroke", shape.stroke, shape.stroke_alpha)
            attributes.append(("stroke-width", num(shape.stroke_width)))
            if shape.line_cap != "butt":
                attributes.append(("stroke-linecap", shape.line_cap))
            if shape.line_join != "miter":
                attributes.append(("stroke-linejoin", shape.line_join))
            if shape.miter_limit != 4:
                attributes.append(("stroke-miterlimit", num(shape.miter_limit)))
        attributes.append(("d", shape.data))
        return f"{pad}<path {' '.join(attribute(key, value) for key, value in attributes)}/>"

    def elements(children, depth):
        lines = []
        open_clips = 0
        for child in children:
            pad = "  " * (depth + open_clips)
            if isinstance(child, Clip):
                identifier = f"clip{next(counter)}"
                definitions.append(f'<clipPath id="{identifier}"><path {attribute("d", child.data)}/></clipPath>')
                lines.append(f'{pad}<g clip-path="url(#{identifier})">')
                open_clips += 1
            elif isinstance(child, Group):
                if child.transform:
                    lines.append(f"{pad}<g {attribute('transform', child.transform)}>")
                    lines += elements(child.children, depth + open_clips + 1)
                    lines.append(f"{pad}</g>")
                else:
                    lines += elements(child.children, depth + open_clips)
            else:
                lines.append(shape_element(child, pad))
        for level in reversed(range(open_clips)):
            lines.append("  " * (depth + level) + "</g>")
        return lines

    body = elements(vector.children, 2 if vector.alpha < 1 else 1)
    if vector.alpha < 1:
        body = [f'  <g opacity="{num(vector.alpha)}">', *body, "  </g>"]
    if definitions:
        body = ["  <defs>", *("    " + line for line in definitions), "  </defs>", *body]
    view_box = f"0 0 {num(vector.viewport[0])} {num(vector.viewport[1])}"
    return svg_document(vector.width, vector.height, view_box, body, relative(vector.source))


def local_name(name):
    return name.rsplit("}", 1)[-1]


def svg_from_firefox(path, size):
    """A Firefox icon SVG for iOS: its chosen drawing only, without CSS, painted black instead of context-fill."""
    root = ElementTree.parse(path).getroot()
    where = path.name
    if local_name(root.tag) != "svg":
        raise ValueError(f"{where} is not an SVG")

    def variant(element):
        classes = set((element.get("class") or "").split())
        if classes & FIREFOX_OTHER_VARIANTS:
            return "other"
        return "chosen" if FIREFOX_VARIANT in classes else None

    variants = {variant(element) for element in root.iter()}
    if "other" in variants and "chosen" not in variants:
        raise ValueError(f"{where} has no {FIREFOX_VARIANT} drawing")

    def clean(element):
        kept = []
        for child in list(element):
            if local_name(child.tag) == "style" or variant(child) == "other":
                continue
            clean(child)
            child.attrib.pop("class", None)
            if local_name(child.tag) == "g" and not child.attrib:
                kept.extend(child)
            else:
                kept.append(child)
        element[:] = kept

    clean(root)
    for element in root.iter():
        for key, value in list(element.attrib.items()):
            if value in ("context-fill-opacity", "context-stroke-opacity"):
                del element.attrib[key]
                continue
            value = (value.replace("context-fill-opacity", "1").replace("context-stroke-opacity", "1")
                     .replace("context-fill", "#000000").replace("context-stroke", "#000000"))
            if "context-" in value:
                raise ValueError(f"{where}: cannot replace {value!r}")
            element.set(key, " ".join(value.split()))
        if local_name(element.tag) not in SVG_ELEMENTS:
            warn(f"{where}: kept <{local_name(element.tag)}>, which iOS may not draw")
    if root.get("viewBox") is None:
        raise ValueError(f"{where} has no viewBox")

    uses_xlink = any(key.startswith("{" + XLINK_NAMESPACE + "}") for element in root.iter() for key in element.attrib)

    def name_of(key):
        if key.startswith("{" + XLINK_NAMESPACE + "}"):
            return "xlink:" + local_name(key)
        if key.startswith("{http://www.w3.org/XML/1998/namespace}"):
            return "xml:" + local_name(key)
        return local_name(key)

    def lines_of(element, depth):
        pad = "  " * depth
        tag = local_name(element.tag)
        attributes = "".join(" " + attribute(name_of(key), value) for key, value in element.attrib.items())
        children = list(element)
        text = " ".join((element.text or "").split())
        if not children:
            return [f"{pad}<{tag}{attributes}>{escape(text)}</{tag}>" if text else f"{pad}<{tag}{attributes}/>"]
        return [f"{pad}<{tag}{attributes}>", *(line for child in children for line in lines_of(child, depth + 1)),
                f"{pad}</{tag}>"]

    others = {key: value for key, value in root.attrib.items() if local_name(key) not in ("width", "height", "viewBox")}
    extra = [attribute("xmlns:xlink", XLINK_NAMESPACE)] if uses_xlink else []
    extra += [attribute(name_of(key), value) for key, value in others.items()]
    body = [line for child in root for line in lines_of(child, 1)]
    return svg_document(size, size, root.get("viewBox"), body,
                        f"{relative(path)} (its {FIREFOX_VARIANT} drawing)", extra)


# Drawing vector drawables into pixels, for the app icon


def flatten(data, matrix, step):
    """The subpaths of path data as polygons through matrix, curves cut into pieces about step long after it."""
    scale = math.sqrt(abs(matrix[0] * matrix[3] - matrix[1] * matrix[2]))
    polygons, points = [], []
    x = y = start_x = start_y = 0.0
    cubic = quadratic = None  # the last control point of the previous segment, when it was such a curve

    def pieces(length):
        return max(1, math.ceil(length * scale / step))

    def add(px, py):
        points.append(apply(matrix, px, py))

    for command, values in path_segments(data):
        kind = command.upper()
        dx, dy = (x, y) if command != kind else (0.0, 0.0)
        if kind == "M":
            if len(points) > 1:
                polygons.append(points)
            x, y = start_x, start_y = values[0] + dx, values[1] + dy
            points = [apply(matrix, x, y)]
            cubic = quadratic = None
            continue
        if not points:
            points = [apply(matrix, x, y)]
        if kind == "Z":
            if len(points) > 1:
                polygons.append(points)
            points = []
            x, y = start_x, start_y
            cubic = quadratic = None
            continue
        next_cubic = next_quadratic = None
        if kind == "L":
            nx, ny = values[0] + dx, values[1] + dy
            add(nx, ny)
        elif kind == "H":
            nx, ny = values[0] + dx, y
            add(nx, ny)
        elif kind == "V":
            nx, ny = x, values[0] + dy
            add(nx, ny)
        elif kind in "CS":
            if kind == "C":
                c1x, c1y = values[0] + dx, values[1] + dy
                values = values[2:]
            else:
                c1x, c1y = (2 * x - cubic[0], 2 * y - cubic[1]) if cubic else (x, y)
            c2x, c2y, nx, ny = values[0] + dx, values[1] + dy, values[2] + dx, values[3] + dy
            count = pieces(math.dist((x, y), (c1x, c1y)) + math.dist((c1x, c1y), (c2x, c2y))
                           + math.dist((c2x, c2y), (nx, ny)))
            for index in range(1, count + 1):
                t = index / count
                u = 1 - t
                add(u * u * u * x + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * nx,
                    u * u * u * y + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * ny)
            next_cubic = (c2x, c2y)
        elif kind in "QT":
            if kind == "Q":
                cx, cy, nx, ny = values[0] + dx, values[1] + dy, values[2] + dx, values[3] + dy
            else:
                cx, cy = (2 * x - quadratic[0], 2 * y - quadratic[1]) if quadratic else (x, y)
                nx, ny = values[0] + dx, values[1] + dy
            count = pieces(math.dist((x, y), (cx, cy)) + math.dist((cx, cy), (nx, ny)))
            for index in range(1, count + 1):
                t = index / count
                u = 1 - t
                add(u * u * x + 2 * u * t * cx + t * t * nx, u * u * y + 2 * u * t * cy + t * t * ny)
            next_quadratic = (cx, cy)
        else:
            nx, ny = values[5] + dx, values[6] + dy
            for px, py in arc(x, y, values[0], values[1], values[2], values[3] != 0, values[4] != 0, nx, ny,
                              scale, step):
                add(px, py)
        x, y = nx, ny
        cubic, quadratic = next_cubic, next_quadratic
    if len(points) > 1:
        polygons.append(points)
    return polygons


def arc(x1, y1, rx, ry, degrees, large, sweep, x2, y2, scale, step):
    """The points of an SVG elliptical arc after its start, as in SVG 1.1's implementation notes (F.6.5)."""
    if (x1, y1) == (x2, y2):
        return []
    rx, ry = abs(rx), abs(ry)
    if rx == 0 or ry == 0:
        return [(x2, y2)]
    cos, sin = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
    half_x, half_y = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cos * half_x + sin * half_y, -sin * half_x + cos * half_y
    excess = (x1p / rx) ** 2 + (y1p / ry) ** 2
    if excess > 1:
        rx, ry = rx * math.sqrt(excess), ry * math.sqrt(excess)
    numerator = (rx * ry) ** 2 - (rx * y1p) ** 2 - (ry * x1p) ** 2
    denominator = (rx * y1p) ** 2 + (ry * x1p) ** 2
    factor = math.sqrt(max(0.0, numerator / denominator)) if denominator else 0.0
    if large == sweep:
        factor = -factor
    cxp, cyp = factor * rx * y1p / ry, -factor * ry * x1p / rx
    cx, cy = cos * cxp - sin * cyp + (x1 + x2) / 2, sin * cxp + cos * cyp + (y1 + y2) / 2

    def angle(ux, uy, vx, vy):
        return math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    ux, uy = (x1p - cxp) / rx, (y1p - cyp) / ry
    start = angle(1, 0, ux, uy)
    delta = angle(ux, uy, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi
    count = max(1, math.ceil(abs(delta) * max(rx, ry) * scale / step))
    points = []
    for index in range(1, count + 1):
        theta = start + delta * index / count
        px, py = rx * math.cos(theta), ry * math.sin(theta)
        points.append((cos * px - sin * py + cx, sin * px + cos * py + cy))
    points[-1] = (x2, y2)
    return points


def coverage(data, matrix, pixels, even_odd):
    """How much of each pixel the path covers, from 0 to 1, by the nonzero or the even-odd rule."""
    big = pixels * SUPERSAMPLE
    to_big = multiply(scaling(SUPERSAMPLE, SUPERSAMPLE), matrix)
    winding = np.zeros((big, big), np.int16)
    for polygon in flatten(data, to_big, step=2.0):
        if len(polygon) < 3:
            continue
        mask = Image.new("L", (big, big), 0)
        # Pillow fills the pixels whose corner, not center, is inside.
        ImageDraw.Draw(mask).polygon([(px - 0.5, py - 0.5) for px, py in polygon], fill=1)
        inside = np.asarray(mask, np.int16)
        if even_odd:
            winding ^= inside
        else:
            area = sum(ax * by - bx * ay for (ax, ay), (bx, by) in zip(polygon, polygon[1:] + polygon[:1]))
            winding += inside if area >= 0 else -inside
    filled = (winding != 0).astype(np.float32)
    return filled.reshape(pixels, SUPERSAMPLE, pixels, SUPERSAMPLE).mean(axis=(1, 3))


def paint_pixels(value, inverse, pixels):
    """The color (red, green, blue from 0 to 1) and alpha of a paint at each pixel center."""
    if not isinstance(value, Gradient):
        return np.array(value[:3], np.float32) / 255, np.float32(value[3])
    rows, columns = np.mgrid[0:pixels, 0:pixels].astype(np.float64) + 0.5
    a, b, c, d, e, f = inverse
    x, y = a * columns + c * rows + e, b * columns + d * rows + f
    if value.kind == "linear":
        dx, dy = value.end[0] - value.start[0], value.end[1] - value.start[1]
        length = dx * dx + dy * dy
        t = ((x - value.start[0]) * dx + (y - value.start[1]) * dy) / length if length else np.zeros_like(x)
    elif value.kind == "radial":
        distance = np.hypot(x - value.center[0], y - value.center[1])
        t = distance / value.radius if value.radius else np.ones_like(x)
    else:
        t = (np.arctan2(y - value.center[1], x - value.center[0]) / (2 * math.pi)) % 1.0
    if value.tile == "repeat":
        t = t % 1.0
    elif value.tile == "mirror":
        t = 1 - np.abs(t % 2.0 - 1)
    offsets = [offset for offset, _ in value.stops]
    channels = [np.interp(t, offsets, [color[channel] / 255 for _, color in value.stops]) for channel in range(3)]
    alpha = np.interp(t, offsets, [color[3] for _, color in value.stops])
    return np.stack(channels, axis=-1).astype(np.float32), alpha.astype(np.float32)


def render(vector, pixels, box):
    """A vector drawable drawn in a square of pixels, showing the part box = (x, y, width, height) of its viewport.

    Returns premultiplied red, green, blue and alpha from 0 to 1. Only fills are drawn: the launcher icon has no
    strokes.
    """
    x, y, width, height = box
    to_pixels = (pixels / width, 0.0, 0.0, pixels / height, -x * pixels / width, -y * pixels / height)
    canvas = np.zeros((pixels, pixels, 4), np.float32)

    def draw(children, matrix, clip):
        for child in children:
            if isinstance(child, Clip):
                inside = coverage(child.data, matrix, pixels, even_odd=False)
                clip = inside if clip is None else clip * inside
            elif isinstance(child, Group):
                draw(child.children, multiply(matrix, child.matrix), clip)
            else:
                if child.stroke is not None and child.stroke_width > 0 and child.stroke_alpha > 0:
                    raise NotImplementedError(f"{vector.source.name}: the app icon is drawn without strokes")
                if child.fill is None:
                    continue
                color, paint_alpha = paint_pixels(child.fill, invert(matrix), pixels)
                alpha = coverage(child.data, matrix, pixels, child.even_odd) * child.fill_alpha * paint_alpha
                if clip is not None:
                    alpha = alpha * clip
                alpha = alpha[..., None]
                canvas[..., :3] = color * alpha + canvas[..., :3] * (1 - alpha)
                canvas[..., 3:] = alpha + canvas[..., 3:] * (1 - alpha)

    draw(vector.children, to_pixels, None)
    return canvas * vector.alpha


def adaptive_icon_layers(path):
    """The background, foreground and monochrome vector drawables of an adaptive icon."""
    root = ElementTree.parse(path).getroot()
    layers = {}
    for name in ("background", "foreground", "monochrome"):
        element = root.find(name)
        reference = None if element is None else android(element, "drawable")
        if reference is None:
            continue
        if not reference.startswith("@drawable/"):
            raise ValueError(f"{path.name}: its {name} is {reference}, the script draws only vector drawables")
        layers[name] = parse_vector(DRAWABLES / (reference.split("/", 1)[1] + ".xml"), template=False)
    if "background" not in layers or "foreground" not in layers:
        raise ValueError(f"{path.name} needs a background and a foreground drawable")
    return layers


def app_icon_images():
    """The app icon for each appearance of iOS 18, by file name."""
    layers = adaptive_icon_layers(LAUNCHER)

    def shown(layer):
        width, height = layer.viewport
        inset = EXTRA_INSET / (1 + 2 * EXTRA_INSET)
        box = (width * inset, height * inset, width * (1 - 2 * inset), height * (1 - 2 * inset))
        return render(layer, APP_ICON_PIXELS, box)

    background = shown(layers["background"])
    foreground = shown(layers["foreground"])
    monochrome = shown(layers.get("monochrome", layers["foreground"]))
    if background[..., 3].min() < 0.999:
        raise ValueError("the launcher icon's background has transparent parts; iOS app icons are opaque")
    gold = background[..., :3] / background[..., 3:]
    light = foreground[..., :3] + background[..., :3] * (1 - foreground[..., 3:])
    # The dark icon is the mark alone, in the background's gold; the tinted one is the mark in white, which iOS tints.
    dark = np.dstack([gold, foreground[..., 3]])
    tinted = np.dstack([np.ones_like(gold), monochrome[..., 3]])

    def image(array):
        return Image.fromarray(np.clip(np.round(array * 255), 0, 255).astype(np.uint8))

    return {"AppIcon.png": image(light), "AppIcon-Dark.png": image(dark), "AppIcon-Tinted.png": image(tinted)}


# The asset catalog


def write_json(path, content):
    path.write_text(json.dumps(content, indent=2, sort_keys=True, separators=(",", " : ")) + "\n", encoding="utf-8")


def asset_folder(name):
    """An empty folder of the catalog for an asset, replacing the one the script wrote before."""
    folder = CATALOG / name
    if folder.parent != CATALOG or folder.suffix not in (".imageset", ".appiconset", ".colorset"):
        raise ValueError(f"not an asset folder: {name}")
    if folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True)
    return folder


def image_set(name, svg, template):
    folder = asset_folder(f"{name}.imageset")
    (folder / f"{name}.svg").write_text(svg, encoding="utf-8")
    write_json(folder / "Contents.json", {
        "images": [{"filename": f"{name}.svg", "idiom": "universal"}],
        "info": INFO,
        "properties": {
            "preserves-vector-representation": True,
            "template-rendering-intent": "template" if template else "original",
        },
    })
    return folder


def app_icon_set(images):
    folder = asset_folder("AppIcon.appiconset")
    entries = []
    for filename, image in images.items():
        image.save(folder / filename, optimize=True)
        entry = {"filename": filename, "idiom": "universal", "platform": "ios", "size": "1024x1024"}
        appearance = {"AppIcon-Dark.png": "dark", "AppIcon-Tinted.png": "tinted"}.get(filename)
        if appearance:
            entry["appearances"] = [{"appearance": "luminosity", "value": appearance}]
        entries.append(entry)
    write_json(folder / "Contents.json", {"images": entries, "info": INFO})
    return folder


def color_set(name, light, dark):
    def entry(color, appearance):
        red, green, blue, alpha = color
        item = {
            "color": {
                "color-space": "srgb",
                "components": {"alpha": f"{alpha:.3f}", "blue": f"0x{blue:02X}", "green": f"0x{green:02X}",
                               "red": f"0x{red:02X}"},
            },
            "idiom": "universal",
        }
        if appearance:
            item["appearances"] = [{"appearance": "luminosity", "value": appearance}]
        return item

    folder = asset_folder(f"{name}.colorset")
    write_json(folder / "Contents.json", {"colors": [entry(light, None), entry(dark, "dark")], "info": INFO})
    return folder


def named_color(name):
    if name not in palette():
        raise ValueError(f"values/dejavu_colors.xml has no color {name}")
    return color_value(palette()[name], False, "dejavu_colors.xml")


# The launch screen


def launch_screen(logo, background):
    """A launch screen of the logo centered on the background color, sized LAUNCH_LOGO_POINTS."""
    side = LAUNCH_LOGO_POINTS
    width, height = 375, 667  # Interface Builder's canvas only
    red, green, blue = (f"{channel / 255:.17g}" for channel in background[:3])
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!-- Generated by {SCRIPT.as_posix()}: Dejavu's logo on LaunchBackground. Do not edit; run the script. -->
<document type="com.apple.InterfaceBuilder3.CocoaTouch.Storyboard.XIB" version="3.0" toolsVersion="13122.16" targetRuntime="iOS.CocoaTouch" propertyAccessControl="none" useAutolayout="YES" launchScreen="YES" useTraitCollections="YES" useSafeAreas="YES" colorMatched="YES" initialViewController="01J-lp-oVM">
    <dependencies>
        <plugIn identifier="com.apple.InterfaceBuilder.IBCocoaTouchPlugin" version="13104.12"/>
        <capability name="Named colors" minToolsVersion="9.0"/>
        <capability name="Safe area layout guides" minToolsVersion="9.0"/>
        <capability name="documents saved in the Xcode 8 format" minToolsVersion="8.0"/>
    </dependencies>
    <scenes>
        <!--View Controller-->
        <scene sceneID="EHf-IW-A2E">
            <objects>
                <viewController id="01J-lp-oVM" sceneMemberID="viewController">
                    <view key="view" contentMode="scaleToFill" id="Ze5-6b-2t3">
                        <rect key="frame" x="0.0" y="0.0" width="{width}" height="{height}"/>
                        <autoresizingMask key="autoresizingMask" widthSizable="YES" heightSizable="YES"/>
                        <subviews>
                            <imageView clipsSubviews="YES" userInteractionEnabled="NO" contentMode="scaleAspectFit" horizontalHuggingPriority="251" verticalHuggingPriority="251" image="{LOGO}" translatesAutoresizingMaskIntoConstraints="NO" id="dJv-Lg-Img">
                                <rect key="frame" x="{num((width - side) / 2)}" y="{num((height - side) / 2)}" width="{side}" height="{side}"/>
                                <constraints>
                                    <constraint firstAttribute="width" constant="{side}" id="dJv-Lg-Wdt"/>
                                    <constraint firstAttribute="height" constant="{side}" id="dJv-Lg-Hgt"/>
                                </constraints>
                            </imageView>
                        </subviews>
                        <viewLayoutGuide key="safeArea" id="6Tk-OE-BBY"/>
                        <color key="backgroundColor" name="LaunchBackground"/>
                        <constraints>
                            <constraint firstItem="dJv-Lg-Img" firstAttribute="centerX" secondItem="Ze5-6b-2t3" secondAttribute="centerX" id="dJv-Lg-CnX"/>
                            <constraint firstItem="dJv-Lg-Img" firstAttribute="centerY" secondItem="Ze5-6b-2t3" secondAttribute="centerY" id="dJv-Lg-CnY"/>
                        </constraints>
                    </view>
                </viewController>
                <placeholder placeholderIdentifier="IBFirstResponder" id="iYj-Kq-Ea1" userLabel="First Responder" sceneMemberID="firstResponder"/>
            </objects>
            <point key="canvasLocation" x="53" y="375"/>
        </scene>
    </scenes>
    <resources>
        <image name="{LOGO}" width="{num(logo.width)}" height="{num(logo.height)}"/>
        <namedColor name="LaunchBackground">
            <color red="{red}" green="{green}" blue="{blue}" alpha="1" colorSpace="custom" customColorSpace="sRGB"/>
        </namedColor>
    </resources>
</document>
'''


def main():
    written = []
    for icon in CONTAINER_ICONS:
        name = f"container-{icon}"
        firefox = FIREFOX_ICONS / f"{icon}.svg"
        if firefox.exists():
            svg = svg_from_firefox(firefox, CONTAINER_ICON_SIZE)
        else:
            warn(f"{name}: Firefox has no {icon}.svg, converted dejavu_container_{icon}.xml")
            svg = svg_from_vector(parse_vector(DRAWABLES / f"dejavu_container_{icon}.xml", template=True))
        written.append(image_set(name, svg, template=True))
    for name, drawable in DEJAVU_ICONS.items():
        written.append(image_set(name, svg_from_vector(parse_vector(DRAWABLES / drawable, template=True)), True))
    logo = parse_vector(DRAWABLES / LOGO_DRAWABLE, template=False)
    written.append(image_set(LOGO, svg_from_vector(logo), template=False))
    written.append(app_icon_set(app_icon_images()))
    for name, (light, dark) in COLORS.items():
        written.append(color_set(name, named_color(light), named_color(dark)))
    STORYBOARD.write_text(launch_screen(logo, named_color(COLORS["LaunchBackground"][0])), encoding="utf-8")
    written.append(STORYBOARD)

    for path in written:
        print(f"Wrote {relative(path)}")
    for message in warnings:
        print(f"Warning: {message}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
