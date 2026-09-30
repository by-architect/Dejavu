# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Writes Dejavu's launcher icon, splash logo, in-app logo and status bar icon: a circle and its faint echo."""

import os

repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../../.."))
res = f"{repo}/mobile/android/fenix/app/src/main/res"
LIC = ('<?xml version="1.0" encoding="utf-8"?>\n'
       '<!-- This Source Code Form is subject to the terms of the Mozilla Public\n'
       '   - License, v. 2.0. If a copy of the MPL was not distributed with this\n'
       '   - file, You can obtain one at http://mozilla.org/MPL/2.0/. -->\n')

# The mark, in the 108 unit space of adaptive icons: a solid circle and, seen once before, its faint echo.
RADIUS = 16.5
SOLID = (47, 60.5)
ECHO = (61, 47.5)
ECHO_ALPHA = "0.28"
# Warm brown: soft on gold, where white does not stand out.
MARK = "#FF5A452A"


def circle(cx, cy, r):
    return f"M{cx - r:g} {cy:g} A{r:g} {r:g} 0 1 0 {cx + r:g} {cy:g} A{r:g} {r:g} 0 1 0 {cx - r:g} {cy:g} Z"


def glyph(color, indent="    ", echo_alpha=ECHO_ALPHA):
    return (f'{indent}<path\n{indent}    android:pathData="{circle(*ECHO, RADIUS)}"\n'
            f'{indent}    android:fillColor="{color}"\n{indent}    android:fillAlpha="{echo_alpha}" />\n'
            f'{indent}<path\n{indent}    android:pathData="{circle(*SOLID, RADIUS)}"\n'
            f'{indent}    android:fillColor="{color}" />')


GRADIENT = '''        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="108"
                android:endY="108">
                <item android:offset="0" android:color="#FFFFEDC2" />
                <item android:offset="0.55" android:color="#FFFFD67A" />
                <item android:offset="1" android:color="#FFF2B84B" />
            </gradient>
        </aapt:attr>'''
HIGHLIGHT = '''        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="32"
                android:centerY="24"
                android:gradientRadius="81">
                <item android:offset="0" android:color="#40FFFFFF" />
                <item android:offset="1" android:color="#00FFFFFF" />
            </gradient>
        </aapt:attr>'''


def vector(comment, body, size=108, viewport=108, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ''
    return (LIC + f'<!-- {comment} -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
            f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
            f'    android:viewportWidth="{viewport}"\n    android:viewportHeight="{viewport}">\n'
            + body + '\n</vector>\n')


def filled(path, fill):
    return f'    <path android:pathData="{path}">\n{fill}\n    </path>'


def scaled(scale, body):
    return ('    <group\n        android:pivotX="54"\n        android:pivotY="54"\n'
            f'        android:scaleX="{scale}"\n        android:scaleY="{scale}">\n' + body + '\n    </group>')


square = "M0 0 H108 V108 H0 Z"
files = {
    "drawable/kaizen_launcher_background.xml": vector("Background of Dejavu's launcher icon: a soft golden gradient.",
        filled(square, GRADIENT) + '\n' + filled(square, HIGHLIGHT), aapt=True),
    "drawable/kaizen_launcher_foreground.xml": vector("Dejavu's mark: a circle and its faint echo, like something seen before.",
        glyph(MARK)),
    "drawable/kaizen_launcher_monochrome.xml": vector("Dejavu's mark for themed launcher icons.",
        glyph("#FF000000", echo_alpha="0.4")),
    # The splash screen shows the icon in a circle two thirds of its size; the logo scales the mark into a gradient disc.
    "drawable/kaizen_logo.xml": vector("Dejavu's logo: its mark on a gradient disc.",
        filled(circle(54, 54, 54), GRADIENT) + '\n' + filled(circle(54, 54, 54), HIGHLIGHT) + '\n'
        + scaled(1.4, glyph(MARK, indent="        ")), aapt=True),
    "drawable/kaizen_splash_logo.xml": vector("Dejavu's logo for the splash screen, sized to its visible circle.",
        filled(circle(54, 54, 36), GRADIENT) + '\n' + scaled(0.95, glyph(MARK, indent="        ")), aapt=True),
    # Status bar icons are drawn in white by the system; the echo keeps some transparency.
    "drawable/ic_status_logo.xml": vector("Dejavu's mark for the status bar.",
        scaled(2.0, glyph("@android:color/white", echo_alpha="0.45")), size=24),
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

# Firefox's logo in the app is replaced by Dejavu's, in every build type that has its own.
logo = files["drawable/kaizen_logo.xml"].replace(
    "<!-- Dejavu's logo: its mark on a gradient disc. -->", "<!-- Dejavu: its logo in place of Firefox's. -->")
for variant in ("main", "debug", "beta", "nightly"):
    open(f"{repo}/mobile/android/fenix/app/src/{variant}/res/drawable/ic_firefox.xml", "w").write(logo)
