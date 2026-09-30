# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Writes the icons of icons.py as vector drawables that replace the android-components icons of the same names.

Run with python3 from anywhere; it reads the originals to keep their sizes and mirroring.
"""

import sys, os, re
sys.path.insert(0, os.path.dirname(__file__))
from icons import ICONS
repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "../../../../.."))
ac = f"{repo}/mobile/android/android-components/components/ui/icons/src/main/res/drawable"
out = f"{repo}/mobile/android/fenix/app/src/main/res/drawable"
STROKE = {24: 1.75, 20: 1.9, 16: 2.1}
HEADER = ('<?xml version="1.0" encoding="utf-8"?>\n'
          '<!-- This Source Code Form is subject to the terms of the Mozilla Public\n'
          '   - License, v. 2.0. If a copy of the MPL was not distributed with this\n'
          '   - file, You can obtain one at http://mozilla.org/MPL/2.0/. -->\n'
          '<!-- Dejavu\'s version of the android-components icon with this name. -->\n')
COLOR = "@color/mozac_ui_icons_fill"

def body(parts, sw, indent="    "):
    lines = []
    for mode, d in parts:
        if mode == 'flip':
            lines.append(f'{indent}<group\n{indent}    android:pivotY="12"\n{indent}    android:scaleY="-1">')
            lines += body(d, sw, indent + "    ")
            lines.append(f'{indent}</group>')
        elif mode == 'stroke':
            lines.append(f'{indent}<path\n{indent}    android:pathData="{d}"\n{indent}    android:strokeWidth="{sw}"\n'
                         f'{indent}    android:strokeColor="{COLOR}"\n{indent}    android:strokeLineCap="round"\n'
                         f'{indent}    android:strokeLineJoin="round" />')
        elif mode in ('fill', 'fillEvenOdd', 'tonal'):
            extra = ''
            if mode == 'fillEvenOdd': extra = f'\n{indent}    android:fillType="evenOdd"'
            if mode == 'tonal': extra = f'\n{indent}    android:fillAlpha="0.28"'
            lines.append(f'{indent}<path\n{indent}    android:pathData="{d}"\n{indent}    android:fillColor="{COLOR}"{extra} />')
        else:
            raise ValueError(mode)
    return lines

written = []
for name, parts in ICONS.items():
    for size in (16, 20, 24):
        src = f"{ac}/mozac_ic_{name}_{size}.xml"
        if not os.path.exists(src):
            continue
        original = open(src).read()
        if COLOR not in original:
            continue
        mirrored = 'android:autoMirrored="true"' in original
        attrs = (f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
                 + ('    android:autoMirrored="true"\n' if mirrored else '')
                 + '    android:viewportWidth="24"\n    android:viewportHeight="24">')
        xml = HEADER + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n' + attrs + '\n' \
            + '\n'.join(body(parts, STROKE[size])) + '\n</vector>\n'
        path = f"{out}/mozac_ic_{name}_{size}.xml"
        open(path, 'w').write(xml)
        written.append(os.path.basename(path))
print(len(written), 'icons written')
missing = [n for n in ICONS if not any(os.path.exists(f"{ac}/mozac_ic_{n}_{s}.xml") for s in (16, 20, 24))]
print('drawn but not in a-c:', missing)
