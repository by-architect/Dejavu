# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Writes Kaizen's launcher icon, splash logo and in-app logo: an open ring around three rising bars."""

import math
import os

repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../../.."))

def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)

def rrect(x, y, w, h, r):
    return (f"M{x+r:.2f} {y:.2f} H{x+w-r:.2f} A{r} {r} 0 0 1 {x+w:.2f} {y+r:.2f} V{y+h-r:.2f} "
            f"A{r} {r} 0 0 1 {x+w-r:.2f} {y+h:.2f} H{x+r:.2f} A{r} {r} 0 0 1 {x:.2f} {y+h-r:.2f} V{y+r:.2f} "
            f"A{r} {r} 0 0 1 {x+r:.2f} {y:.2f} Z")

cx, cy, R = 54, 54, 22.5
start, end = 118, 118 + 292
sx, sy = polar(cx, cy, R, start)
ex, ey = polar(cx, cy, R, end)
ring = f"M{sx:.2f} {sy:.2f} A{R} {R} 0 1 1 {ex:.2f} {ey:.2f}"
bar_width, bar_gap, base = 5.6, 3.4, 64.5
x0 = cx - (3 * bar_width + 2 * bar_gap) / 2
bars = [rrect(x0 + i * (bar_width + bar_gap), base - h, bar_width, h, bar_width / 2) for i, h in enumerate([9, 14.5, 20])]
dot = polar(cx, cy, R, start - 22)
res = f"{repo}/mobile/android/fenix/app/src/main/res"
LIC = ('<?xml version="1.0" encoding="utf-8"?>\n'
       '<!-- This Source Code Form is subject to the terms of the Mozilla Public\n'
       '   - License, v. 2.0. If a copy of the MPL was not distributed with this\n'
       '   - file, You can obtain one at http://mozilla.org/MPL/2.0/. -->\n')

def glyph(color, indent="    ", dot_alpha="0.75"):
    out = [f'{indent}<path\n{indent}    android:pathData="{ring}"\n{indent}    android:strokeWidth="6.2"\n'
           f'{indent}    android:strokeColor="{color}"\n{indent}    android:strokeLineCap="round" />']
    d = f"M{dot[0]-3.1:.2f} {dot[1]:.2f} A3.1 3.1 0 1 0 {dot[0]+3.1:.2f} {dot[1]:.2f} A3.1 3.1 0 1 0 {dot[0]-3.1:.2f} {dot[1]:.2f} Z"
    out.append(f'{indent}<path\n{indent}    android:pathData="{d}"\n{indent}    android:fillColor="{color}"\n{indent}    android:fillAlpha="{dot_alpha}" />')
    for b in bars:
        out.append(f'{indent}<path\n{indent}    android:pathData="{b}"\n{indent}    android:fillColor="{color}" />')
    return '\n'.join(out)

GRADIENT = '''        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="108"
                android:endY="108">
                <item android:offset="0" android:color="#FFFFE08A" />
                <item android:offset="0.55" android:color="#FFFFC83D" />
                <item android:offset="1" android:color="#FFD99A0B" />
            </gradient>
        </aapt:attr>'''
HIGHLIGHT = '''        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="32"
                android:centerY="24"
                android:gradientRadius="81">
                <item android:offset="0" android:color="#47FFFFFF" />
                <item android:offset="1" android:color="#00FFFFFF" />
            </gradient>
        </aapt:attr>'''

def vector(comment, body, size=108, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ''
    return (LIC + f'<!-- {comment} -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
            f'    android:width="{size}dp"\n    android:height="{size}dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            + body + '\n</vector>\n')

def filled(path, fill):
    return f'    <path android:pathData="{path}">\n{fill}\n    </path>'

# The mark is dark, as white does not stand out on gold.
MARK = "#FF1D1A16"
square = "M0 0 H108 V108 H0 Z"
def disc(r): return f"M{54-r} 54 A{r} {r} 0 1 0 {54+r} 54 A{r} {r} 0 1 0 {54-r} 54 Z"

files = {
    "drawable/kaizen_launcher_background.xml": vector("Background of Kaizen's launcher icon: a golden gradient.",
        filled(square, GRADIENT) + '\n' + filled(square, HIGHLIGHT), aapt=True),
    "drawable/kaizen_launcher_foreground.xml": vector("Kaizen's mark: an open ring around three rising bars, for steady improvement.",
        glyph(MARK)),
    "drawable/kaizen_launcher_monochrome.xml": vector("Kaizen's mark for themed launcher icons.", glyph("#FF000000", dot_alpha="1")),
    # The splash screen shows the icon in a circle two thirds of its size; the logo scales the mark into a gradient disc.
    "drawable/kaizen_logo.xml": vector("Kaizen's logo: its mark on a gradient disc.",
        filled(disc(54), GRADIENT) + '\n' + filled(disc(54), HIGHLIGHT) + '\n'
        + '    <group\n        android:pivotX="54"\n        android:pivotY="54"\n        android:scaleX="1.4"\n        android:scaleY="1.4">\n'
        + glyph(MARK, indent="        ") + '\n    </group>', aapt=True),
    "drawable/kaizen_splash_logo.xml": vector("Kaizen's logo for the splash screen, sized to its visible circle.",
        filled(disc(36), GRADIENT) + '\n'
        + '    <group\n        android:pivotX="54"\n        android:pivotY="54"\n        android:scaleX="0.95"\n        android:scaleY="0.95">\n'
        + glyph(MARK, indent="        ") + '\n    </group>', aapt=True),
    "mipmap-anydpi/kaizen_launcher.xml": LIC + '''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/kaizen_launcher_background" />
    <foreground android:drawable="@drawable/kaizen_launcher_foreground" />
    <monochrome android:drawable="@drawable/kaizen_launcher_monochrome" />
</adaptive-icon>
''',
}
files["mipmap-anydpi/kaizen_launcher_round.xml"] = files["mipmap-anydpi/kaizen_launcher.xml"]
for rel, content in files.items():
    open(f"{res}/{rel}", 'w').write(content)

# Firefox's logo in the app is replaced by Kaizen's, in every build type that has its own.
logo = files["drawable/kaizen_logo.xml"].replace("<!-- Kaizen's logo: its mark on a gradient disc. -->", "<!-- Kaizen: its logo in place of Firefox's. -->")
for variant in ("main", "debug", "beta", "nightly"):
    open(f"{repo}/mobile/android/fenix/app/src/{variant}/res/drawable/ic_firefox.xml", "w").write(logo)
