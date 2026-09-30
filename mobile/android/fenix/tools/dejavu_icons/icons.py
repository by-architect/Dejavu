# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Dejavu's icon set, drawn on a 24 unit grid with round 1.75 unit strokes."""

import math

SW = 1.75

def S(d): return ('stroke', d)
def F(d): return ('fill', d)
def FE(d): return ('fillEvenOdd', d)

def circle(cx, cy, r):
    return f"M{cx-r:.2f} {cy:.2f} A{r} {r} 0 1 0 {cx+r:.2f} {cy:.2f} A{r} {r} 0 1 0 {cx-r:.2f} {cy:.2f} Z"

def rrect(x, y, w, h, r):
    return (f"M{x+r:.2f} {y:.2f} H{x+w-r:.2f} A{r} {r} 0 0 1 {x+w:.2f} {y+r:.2f} V{y+h-r:.2f} "
            f"A{r} {r} 0 0 1 {x+w-r:.2f} {y+h:.2f} H{x+r:.2f} A{r} {r} 0 0 1 {x:.2f} {y+h-r:.2f} V{y+r:.2f} "
            f"A{r} {r} 0 0 1 {x+r:.2f} {y:.2f} Z")

def star(cx, cy, R, r, pts=5):
    out = []
    for i in range(pts * 2):
        ang = -math.pi / 2 + i * math.pi / pts
        rad = R if i % 2 == 0 else r
        out.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
    return "M" + " L".join(f"{x:.2f} {y:.2f}" for x, y in out) + " Z"

def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)

def arrow_arc(cx, cy, r, start, sweep_deg, clockwise=True, head=3.0):
    """An arc from angle start sweeping sweep_deg degrees, with an arrowhead at its end."""
    end = start + (sweep_deg if clockwise else -sweep_deg)
    sx, sy = polar(cx, cy, r, start)
    ex, ey = polar(cx, cy, r, end)
    large = 1 if sweep_deg > 180 else 0
    sweep = 1 if clockwise else 0
    arc = f"M{sx:.2f} {sy:.2f} A{r} {r} 0 {large} {sweep} {ex:.2f} {ey:.2f}"
    a = math.radians(end)
    tx, ty = (-math.sin(a), math.cos(a)) if clockwise else (math.sin(a), -math.cos(a))
    bx, by = -tx, -ty
    def rot(vx, vy, deg):
        c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
        return vx * c - vy * s, vx * s + vy * c
    p1 = rot(bx, by, 42); p2 = rot(bx, by, -42)
    tipx, tipy = ex + tx * 0.4, ey + ty * 0.4
    headp = (f"M{tipx + p1[0]*head:.2f} {tipy + p1[1]*head:.2f} L{tipx:.2f} {tipy:.2f} "
             f"L{tipx + p2[0]*head:.2f} {tipy + p2[1]*head:.2f}")
    return [S(arc), S(headp)]

PAGE = "M13.5 3.5 H7.5 A2 2 0 0 0 5.5 5.5 V18.5 A2 2 0 0 0 7.5 20.5 H16.5 A2 2 0 0 0 18.5 18.5 V8.5 Z"
PAGE_FOLD = "M13.5 3.5 V7 A1.5 1.5 0 0 0 15 8.5 H18.5"
FOLDER = "M4 7.5 A2 2 0 0 1 6 5.5 H9.4 A1.6 1.6 0 0 1 10.6 6 L11.8 7.5 H18 A2 2 0 0 1 20 9.5 V17 A2 2 0 0 1 18 19 H6 A2 2 0 0 1 4 17 Z"
SHIELD = "M12 3.6 L18.4 6.1 A0.9 0.9 0 0 1 19 7 V11.4 C19 15.3 16.2 18.7 12 20.4 C7.8 18.7 5 15.3 5 11.4 V7 A0.9 0.9 0 0 1 5.6 6.1 Z"
MASK = ("M4 9.2 C4 7.6 5.5 6.5 7.6 6.5 C9.6 6.5 10.6 7.4 12 7.4 C13.4 7.4 14.4 6.5 16.4 6.5 C18.5 6.5 20 7.6 20 9.2 "
        "C20 13.4 18.4 16.8 15.6 16.8 C13.8 16.8 13.1 15.2 12 15.2 C10.9 15.2 10.2 16.8 8.4 16.8 C5.6 16.8 4 13.4 4 9.2 Z")
PIN = "M9.5 4 H14.5 M10.4 4 V9.2 L7.6 12.6 A0.8 0.8 0 0 0 8.2 13.9 H15.8 A0.8 0.8 0 0 0 16.4 12.6 L13.6 9.2 V4 M12 13.9 V20"
PIN_BODY = "M10.4 4 V9.2 L7.6 12.6 A0.8 0.8 0 0 0 8.2 13.9 H15.8 A0.8 0.8 0 0 0 16.4 12.6 L13.6 9.2 V4 Z"
TRAY = "M4.5 14.5 V17.5 A2 2 0 0 0 6.5 19.5 H17.5 A2 2 0 0 0 19.5 17.5 V14.5"
BOX_OPEN = "M18.5 13.5 V17.5 A2 2 0 0 1 16.5 19.5 H6.5 A2 2 0 0 1 4.5 17.5 V7.5 A2 2 0 0 1 6.5 5.5 H10.5"

ICONS = {
    # navigation
    "back": [S("M10.5 6.5 L5 12 L10.5 17.5"), S("M5.6 12 H19")],
    "forward": [S("M13.5 6.5 L19 12 L13.5 17.5"), S("M18.4 12 H5")],
    "arrow_clockwise": arrow_arc(12, 12.3, 7.2, -30, 300, True),
    "arrow_counter_clockwise": arrow_arc(12, 12.3, 7.2, 210, 300, False),
    "cross": [S("M7 7 L17 17"), S("M17 7 L7 17")],
    "plus": [S("M12 5.5 V18.5"), S("M5.5 12 H18.5")],
    "checkmark": [S("M5.2 12.6 L9.6 17 L18.8 7.8")],
    "chevron_down": [S("M6.5 9.5 L12 15 L17.5 9.5")],
    "chevron_up": [S("M6.5 14.5 L12 9 L17.5 14.5")],
    "chevron_right": [S("M9.5 6.5 L15 12 L9.5 17.5")],
    "search": [S(circle(10.8, 10.8, 6.3)), S("M15.5 15.5 L19.5 19.5")],
    "home": [S("M4.5 11 L12 4.8 L19.5 11"), S("M6.5 9.6 V17.5 A2 2 0 0 0 8.5 19.5 H15.5 A2 2 0 0 0 17.5 17.5 V9.6"),
             S("M10.2 19.5 V16.2 A1.8 1.8 0 0 1 13.8 16.2 V19.5")],
    "settings": [S("M4.5 7.5 H9.6"), S("M14.4 7.5 H19.5"), S(circle(12, 7.5, 2.4)),
                 S("M4.5 16.5 H5.6"), S("M10.4 16.5 H19.5"), S(circle(8, 16.5, 2.4))],
    "ellipsis_vertical": [F(circle(12, 5.8, 1.45)), F(circle(12, 12, 1.45)), F(circle(12, 18.2, 1.45))],
    "avatar_circle": [S(circle(12, 12, 8.5)), S(circle(12, 10.2, 2.9)),
                      S("M6.9 17.9 C8.1 16 9.9 15.1 12 15.1 C14.1 15.1 15.9 16 17.1 17.9")],
    "share_android": [S(TRAY.replace("14.5", "12.5")), S("M12 15 V4.5"), S("M8 8.5 L12 4.5 L16 8.5")],
    "download": [S("M12 4.5 V14"), S("M8 10 L12 14 L16 10"), S(TRAY)],
    "external_link": [S(BOX_OPEN), S("M13.5 4.5 H19.5 V10.5"), S("M19 5 L11.5 12.5")],
    # pages & files
    "find_in_page": [S("M11 20.5 H7.5 A2 2 0 0 1 5.5 18.5 V5.5 A2 2 0 0 1 7.5 3.5 H13.5 L18.5 8.5 V10.5"),
                     S(PAGE_FOLD), S(circle(15.3, 15.3, 2.9)), S("M17.4 17.4 L19.8 19.8")],
    "save_file": [S(PAGE), S(PAGE_FOLD), S("M12 11 V16.8"), S("M9.6 14.4 L12 16.8 L14.4 14.4")],
    "print": [S("M7.5 8.5 V5.5 A1 1 0 0 1 8.5 4.5 H15.5 A1 1 0 0 1 16.5 5.5 V8.5"),
              S("M7.5 16.5 H5.5 A2 2 0 0 1 3.5 14.5 V10.5 A2 2 0 0 1 5.5 8.5 H18.5 A2 2 0 0 1 20.5 10.5 V14.5 A2 2 0 0 1 18.5 16.5 H16.5"),
              S("M7.5 13.5 H16.5 V19 A1 1 0 0 1 15.5 20 H8.5 A1 1 0 0 1 7.5 19 Z"), F(circle(17, 11.4, 0.85))],
    "copy": [S(rrect(8.5, 8.5, 11, 11, 2.5)),
             S("M15.5 8.5 V6.5 A2 2 0 0 0 13.5 4.5 H6.5 A2 2 0 0 0 4.5 6.5 V13.5 A2 2 0 0 0 6.5 15.5 H8.5")],
    "link": [S("M10.3 13.7 A3.9 3.9 0 0 0 16.1 14.1 L18.4 11.8 A3.9 3.9 0 0 0 12.9 6.3 L11.6 7.6"),
             S("M13.7 10.3 A3.9 3.9 0 0 0 7.9 9.9 L5.6 12.2 A3.9 3.9 0 0 0 11.1 17.7 L12.4 16.4")],
    "edit": [S("M5 19 L5.7 15.4 L15.3 5.8 A1.9 1.9 0 0 1 18 5.8 L18.2 6 A1.9 1.9 0 0 1 18.2 8.7 L8.6 18.3 Z"),
             S("M13.6 7.5 L16.5 10.4")],
    "delete": [S("M4.5 7 H19.5"), S("M9.5 7 V5.5 A1.5 1.5 0 0 1 11 4 H13 A1.5 1.5 0 0 1 14.5 5.5 V7"),
               S("M6.5 7 L7.4 18.2 A2 2 0 0 0 9.4 20 H14.6 A2 2 0 0 0 16.6 18.2 L17.5 7"),
               S("M10.2 10.8 V16.2"), S("M13.8 10.8 V16.2")],
    "folder": [S(FOLDER)],
    "folder_add": [S(FOLDER), S("M12 10.4 V16.1"), S("M9.15 13.25 H14.85")],
    "folder_arrow_right": [S(FOLDER), S("M8.8 13.25 H15"), S("M12.6 10.85 L15 13.25 L12.6 15.65")],
    # library
    "bookmark": [S(star(12, 12.6, 8.2, 3.9))],
    "bookmark_fill": [F(star(12, 12.6, 8.2, 3.9)), S(star(12, 12.6, 8.2, 3.9))],
    "bookmark_tray": [S(rrect(4, 4, 16, 16, 4)), S(star(12, 12.4, 4.6, 2.2))],
    "history": [S("M4.6 13.5 A7.6 7.6 0 1 0 5.9 7.3"), S("M4.4 4.9 V8.4 H7.9"), S("M12 8.3 V12 L14.7 13.6")],
    "login": [S(circle(8.2, 12, 3.6)), S("M11.8 12 H20"), S("M17 12 V15"), S("M20 12 V14.2")],
    "extension": [S("M5 9.6 A1.6 1.6 0 0 1 6.6 8 H9.2 A2.4 2.4 0 1 1 13.8 8 H16.4 A1.6 1.6 0 0 1 18 9.6 V12.2 "
                    "A2.4 2.4 0 1 1 18 16.8 V18.4 A1.6 1.6 0 0 1 16.4 20 H6.6 A1.6 1.6 0 0 1 5 18.4 Z")],
    # page tools
    "device_desktop": [S(rrect(3.5, 5, 17, 11.5, 2.2)), S("M9 20 H15"), S("M12 16.5 V20")],
    "device_mobile": [S(rrect(7, 3.5, 10, 17, 2.6)), S("M11 17.4 H13")],
    "translate": [S("M4 6.2 H12"), S("M8 4.4 V6.2"), S("M10.6 6.2 C9.8 9.8 7.6 12.4 4.4 13.8"), S("M6.4 8.8 C7.3 10.7 8.7 12.1 10.6 13"),
                  S("M12.2 20 L15.9 11.2 L19.6 20"), S("M13.5 17 H18.3")],
    "sparkle": [S("M12 4 C12.6 9.3 14.7 11.4 20 12 C14.7 12.6 12.6 14.7 12 20 C11.4 14.7 9.3 12.6 4 12 C9.3 11.4 11.4 9.3 12 4 Z")],
    "lightbulb": [S("M9.3 16.9 V15.3 C7.1 14.1 5.9 11.9 6.3 9.4 C6.8 6.5 9.2 4.4 12 4.4 C14.8 4.4 17.2 6.5 17.7 9.4 C18.1 11.9 16.9 14.1 14.7 15.3 V16.9 Z"),
                  S("M10.2 20 H13.8")],
    "lightning": [S("M13.2 3.6 L5.6 13.2 A0.6 0.6 0 0 0 6.1 14.2 H11.4 L10.6 20.4 L18.4 10.8 A0.6 0.6 0 0 0 17.9 9.8 H12.6 Z")],
    "pin": [S(PIN)],
    "pin_fill": [F(PIN_BODY), S(PIN)],
    "pin_slash": [S(PIN), S("M4.5 4.5 L19.5 19.5")],
    "grid_add": [S(rrect(4, 4, 6.5, 6.5, 2)), S(rrect(13.5, 4, 6.5, 6.5, 2)), S(rrect(4, 13.5, 6.5, 6.5, 2)),
                 S("M16.75 13.8 V19.7"), S("M13.8 16.75 H19.7")],
    "tab_ungroup": [S(rrect(4, 4, 6.5, 6.5, 2)), S(rrect(13.5, 4, 6.5, 6.5, 2)), S(rrect(4, 13.5, 6.5, 6.5, 2)),
                    S("M13.8 16.75 H19.7")],
    "select_all": [S(rrect(4, 4, 16, 16, 4.2)), S("M8.3 12.3 L10.9 14.9 L15.8 9.6")],
    "tab": [S(rrect(3.5, 5, 17, 14, 3)), S("M3.5 9.2 H20.5")],
    # privacy & security
    "private_mode": [S(MASK), S(circle(8.6, 11, 1.5)), S(circle(15.4, 11, 1.5))],
    "private_mode_fill": [FE(MASK + " " + circle(8.6, 11, 1.5) + " " + circle(15.4, 11, 1.5))],
    "globe": [S(circle(12, 12, 8.5)), S("M12 3.5 C9.3 6.2 9.3 17.8 12 20.5 C14.7 17.8 14.7 6.2 12 3.5 Z"), S("M3.6 12 H20.4")],
    "shield_checkmark": [S(SHIELD), S("M9 12 L11.2 14.2 L15.1 10.2")],
    "shield_slash": [S(SHIELD), S("M4.5 4.5 L19.5 19.5")],
    "shield_cross": [S(SHIELD), S("M9.6 9.6 L14.4 14.4"), S("M14.4 9.6 L9.6 14.4")],
    "lock": [S(rrect(5.5, 10.5, 13, 9.5, 2.6)), S("M8.5 10.5 V8 A3.5 3.5 0 0 1 15.5 8 V10.5")],
    "microphone": [S(rrect(9, 3.5, 6, 10.5, 3)), S("M5.8 11.6 A6.2 6.2 0 0 0 18.2 11.6"), S("M12 17.8 V20.5")],
}

TABS = rrect(3.5, 5, 17, 14, 3)
BELL = "M6.4 16.4 V11 A5.6 5.6 0 0 1 17.6 11 V16.4 L19 17.8 H5 Z"
EYE = "M3.5 12 C5.4 8.3 8.4 6.4 12 6.4 C15.6 6.4 18.6 8.3 20.5 12 C18.6 15.7 15.6 17.6 12 17.6 C8.4 17.6 5.4 15.7 3.5 12 Z"
COOKIE = ("M19.8 12.6 A7.9 7.9 0 1 1 11.4 4.2 A2.6 2.6 0 0 0 14.6 7.6 A2.6 2.6 0 0 0 17.4 10.4 A2.2 2.2 0 0 0 19.8 12.6 Z")
TRIANGLE = "M10.3 5.3 A2 2 0 0 1 13.7 5.3 L20.1 16.8 A2 2 0 0 1 18.4 19.8 H5.6 A2 2 0 0 1 3.9 16.8 Z"
THUMB = ("M8 10.5 L11.3 4.8 A1.6 1.6 0 0 1 14.2 6.1 L13.4 9.5 H18 A2 2 0 0 1 19.9 12 L18.6 17.7 A2 2 0 0 1 16.7 19.3 H8 Z")
READER = [S(rrect(4, 4.5, 16, 15, 3)), S("M8 9 H16"), S("M8 12.2 H16"), S("M8 15.4 H12.6")]
STORAGE = [S(rrect(4.5, 4.5, 15, 6.2, 2)), S(rrect(4.5, 13.3, 15, 6.2, 2)), F(circle(8, 7.6, 0.9)), F(circle(8, 16.4, 0.9))]
PLAY = "M8.5 6.3 A1 1 0 0 1 10 5.4 L18.3 11.1 A1 1 0 0 1 18.3 12.9 L10 18.6 A1 1 0 0 1 8.5 17.7 Z"

ICONS.update({
    "ellipsis_horizontal": [F(circle(5.8, 12, 1.45)), F(circle(12, 12, 1.45)), F(circle(18.2, 12, 1.45))],
    "more_grid": [F(circle(7, 7, 1.5)), F(circle(12, 7, 1.5)), F(circle(17, 7, 1.5)),
                  F(circle(7, 12, 1.5)), F(circle(12, 12, 1.5)), F(circle(17, 12, 1.5)),
                  F(circle(7, 17, 1.5)), F(circle(12, 17, 1.5)), F(circle(17, 17, 1.5))],
    "information": [S(circle(12, 12, 8.5)), S("M12 11 V16.2"), F(circle(12, 7.9, 1.05))],
    "warning": [S(TRIANGLE), S("M12 9.6 V13.4"), F(circle(12, 16.3, 1.05))],
    "warning_fill": [FE(TRIANGLE + " M11.1 9.6 A0.9 0.9 0 0 1 12.9 9.6 V13.4 A0.9 0.9 0 0 1 11.1 13.4 Z " + circle(12, 16.3, 1.1))],
    "critical": [S(circle(12, 12, 8.5)), S("M12 7.6 V12.6"), F(circle(12, 15.9, 1.05))],
    "critical_fill": [FE(circle(12, 12, 8.9) + " M11.1 7.6 A0.9 0.9 0 0 1 12.9 7.6 V12.6 A0.9 0.9 0 0 1 11.1 12.6 Z " + circle(12, 15.9, 1.1))],
    "cross_circle": [S(circle(12, 12, 8.5)), S("M9.3 9.3 L14.7 14.7"), S("M14.7 9.3 L9.3 14.7")],
    "cross_circle_fill": [F(circle(12, 12, 8.9)), ('clear', "M9.3 9.3 L14.7 14.7"), ('clear', "M14.7 9.3 L9.3 14.7")],
    "avatar_circle_fill": [FE(circle(12, 12, 8.9) + " " + circle(12, 10.2, 2.9)),
                           ('clear', "M6.9 17.9 C8.1 16 9.9 15.1 12 15.1 C14.1 15.1 15.9 16 17.1 17.9")],
    "bookmark_tray_fill": [FE(rrect(4, 4, 16, 16, 4) + " " + star(12, 12.4, 4.6, 2.2))],
    "extension_fill": [F("M5 9.6 A1.6 1.6 0 0 1 6.6 8 H9.2 A2.4 2.4 0 1 1 13.8 8 H16.4 A1.6 1.6 0 0 1 18 9.6 V12.2 "
                         "A2.4 2.4 0 1 1 18 16.8 V18.4 A1.6 1.6 0 0 1 16.4 20 H6.6 A1.6 1.6 0 0 1 5 18.4 Z")],
    "device_desktop_fill": [F(rrect(3.5, 5, 17, 11.5, 2.2)), S(rrect(3.5, 5, 17, 11.5, 2.2)), S("M9 20 H15"), S("M12 16.5 V20")],
    "device_desktop_send": [S("M20.5 11 V14.3 A2.2 2.2 0 0 1 18.3 16.5 H5.7 A2.2 2.2 0 0 1 3.5 14.3 V7.2 A2.2 2.2 0 0 1 5.7 5 H11"),
                            S("M9 20 H15"), S("M12 16.5 V20"), S("M14.5 8.5 L20 3"), S("M16 3 H20 V7")],
    "add_to_homescreen": [S("M17 12 V18 A2.5 2.5 0 0 1 14.5 20.5 H9.5 A2.5 2.5 0 0 1 7 18 V6 A2.5 2.5 0 0 1 9.5 3.5 H12"),
                          S("M11 17.4 H13"), S("M17.5 3.5 V9.5"), S("M14.5 6.5 H20.5")],
    "append_up_left": [S("M17.5 17.5 L7.5 7.5"), S("M7 13.5 V7 H13.5")],
    "arrow_trending": [S("M4 16.5 L9.5 11 L13 14.5 L20 7.5"), S("M15.5 7.5 H20 V12")],
    "arrow_trending_down": [S("M4 7.5 L9.5 13 L13 9.5 L20 16.5"), S("M15.5 16.5 H20 V12")],
    "audio": [S("M4.5 9.8 V14.2 A1 1 0 0 0 5.5 15.2 H8 L12.2 18.8 V5.2 L8 8.8 H5.5 A1 1 0 0 0 4.5 9.8 Z"),
              S("M15.6 9.2 A4 4 0 0 1 15.6 14.8"), S("M17.8 6.8 A7.2 7.2 0 0 1 17.8 17.2")],
    "audio_wave": [S("M4.5 10.5 V13.5"), S("M8.2 7.5 V16.5"), S("M12 4.5 V19.5"), S("M15.8 8.5 V15.5"), S("M19.5 10.5 V13.5")],
    "autoplay": [S(rrect(3.5, 5.5, 17, 13, 3)), S("M10.2 9.6 V14.4 L14.4 12 Z")],
    "autoplay_slash": [S(rrect(3.5, 5.5, 17, 13, 3)), S("M10.2 9.6 V14.4 L14.4 12 Z"), S("M4 4 L20 20")],
    "camera": [S("M4 9 A2 2 0 0 1 6 7 H8 L9.4 5.2 A1 1 0 0 1 10.2 4.8 H13.8 A1 1 0 0 1 14.6 5.2 L16 7 H18 A2 2 0 0 1 20 9 V17 A2 2 0 0 1 18 19 H6 A2 2 0 0 1 4 17 Z"),
               S(circle(12, 12.8, 3.3))],
    "collection": [S(rrect(4, 8, 16, 12, 3)), S("M6.5 5 H17.5"), S("M9 2.5 H15")],
    "cookies": [S(COOKIE), F(circle(9, 11, 1)), F(circle(13.2, 15.2, 1)), F(circle(8.8, 15.4, 0.9))],
    "cookies_slash": [S(COOKIE), F(circle(9, 11, 1)), F(circle(13.2, 15.2, 1)), S("M4 4 L20 20")],
    "credit_card": [S(rrect(3.5, 5.5, 17, 13, 3)), S("M3.5 10 H20.5"), S("M7 14.8 H10")],
    "cryptominer": [S("M5.5 18.5 L13 11"), S("M9.5 6.5 C12.8 4.3 16.9 4.6 19.5 7 C17.8 7.4 15.9 8.4 14.6 9.9"),
                    S("M14.2 5.1 L18.9 9.8")],
    "debug_drawer": [S(rrect(4, 4.5, 16, 15, 3)), S("M4 9.5 H20"), S("M8 13.5 L10 15 L8 16.5"), S("M11.8 16.5 H14.5")],
    "email_mask": [S(rrect(3.5, 5.5, 17, 13, 3)), S("M4.5 7.5 L12 12.6 L19.5 7.5")],
    "eye": [S(EYE), S(circle(12, 12, 2.8))],
    "eye_slash": [S(EYE), S(circle(12, 12, 2.8)), S("M4.5 4.5 L19.5 19.5")],
    "fingerprinter": [S("M7.2 7.4 A6.6 6.6 0 0 1 18.4 11.6 V13.4"), S("M5.4 11.4 A6.6 6.6 0 0 1 5.9 9.5"),
                      S("M8.8 18.6 C8.3 17 8.4 14.4 8.4 12.6 A3.6 3.6 0 0 1 15.6 12.6 V14.2"),
                      S("M12 12.6 V15.4 C12 17.4 12.7 18.9 13.7 20"), S("M15.6 17.6 V18.2"), S("M5.8 14.8 C5.9 16 6.2 17 6.6 17.8")],
    "image": [S(rrect(3.5, 4.5, 17, 15, 3)), F(circle(9, 9.6, 1.6)), S("M4 17 L9.2 12.4 L12.8 15.4 L15.6 13 L20 16.6")],
    "infinite_tabs": [S("M12 12 C10.6 9.8 9.4 8.5 7.4 8.5 A3.5 3.5 0 0 0 7.4 15.5 C9.4 15.5 10.6 14.2 12 12 C13.4 9.8 14.6 8.5 16.6 8.5 A3.5 3.5 0 0 1 16.6 15.5 C14.6 15.5 13.4 14.2 12 12 Z")],
    "listen_to_page": [S(rrect(4, 4.5, 11, 15, 2.6)), S("M7 9 H12"), S("M7 12.2 H10.5"),
                       S("M18 8.5 A5 5 0 0 1 18 15.5"), S("M16.2 10.4 A2.2 2.2 0 0 1 16.2 13.6")],
    "local_network": [S(rrect(8.5, 3.5, 7, 5.5, 1.8)), S(rrect(3.5, 15, 7, 5.5, 1.8)), S(rrect(13.5, 15, 7, 5.5, 1.8)),
                      S("M12 9 V12 M7 15 V12 H17 V15")],
    "location": [S("M12 20.5 C8.2 16.6 5.8 13.4 5.8 10.1 A6.2 6.2 0 0 1 18.2 10.1 C18.2 13.4 15.8 16.6 12 20.5 Z"),
                 S(circle(12, 10, 2.3))],
    "notification": [S(BELL), S("M10 20 A2 2 0 0 0 14 20")],
    "page_portrait": [S(PAGE), S(PAGE_FOLD)],
    "passkey": [S(circle(9.2, 8, 3.4)), S("M3.8 19 C3.8 15.6 6.2 13.2 9.2 13.2 C10.4 13.2 11.4 13.5 12.2 14"),
                S(circle(17, 12.6, 2.2)), S("M17 14.8 V20 L18.4 18.8")],
    "permissions": [S("M4.5 7.5 H13"), S("M17 7.5 H19.5"), S(circle(15, 7.5, 2)), S("M4.5 16.5 H7"), S("M11 16.5 H19.5"), S(circle(9, 16.5, 2))],
    "qr_code": [S(rrect(4, 4, 6.5, 6.5, 1.6)), S(rrect(13.5, 4, 6.5, 6.5, 1.6)), S(rrect(4, 13.5, 6.5, 6.5, 1.6)),
                F(circle(7.25, 7.25, 1)), F(circle(16.75, 7.25, 1)), F(circle(7.25, 16.75, 1)),
                S("M14 14 H16.5 V16.5"), S("M19.8 14 V14.1"), S("M14 19.8 H14.1"), S("M17 19.8 H19.8 V17.2")],
    "reader_view": READER,
    "reader_view_fill": [FE(rrect(4, 4.5, 16, 15, 3) + " " + rrect(7.2, 8.1, 9.6, 1.8, 0.9) + " " + rrect(7.2, 11.3, 9.6, 1.8, 0.9) + " " + rrect(7.2, 14.5, 6.2, 1.8, 0.9))],
    "reader_view_audio": [S(rrect(4, 4.5, 16, 15, 3)), S("M8 9 H13"), S("M8 12.2 H11"), S("M14.5 13.5 V15.5"), S("M16.8 11.5 V17.5")],
    "reader_view_audio_fill": [FE(rrect(4, 4.5, 16, 15, 3) + " " + rrect(7.2, 8.1, 6.6, 1.8, 0.9) + " " + rrect(7.2, 11.3, 4.6, 1.8, 0.9) + " " + rrect(13.6, 12.6, 1.8, 3.8, 0.9) + " " + rrect(15.9, 10.6, 1.8, 7.8, 0.9))],
    "reader_view_customize": [S("M4 18.5 L8.5 6.5 L13 18.5"), S("M5.6 14.4 H11.4"), S("M15 13 H20"), S("M15 17.5 H20"), S("M17.5 8.5 V10.5")],
    "reading_list": [S("M7.5 4 H16.5 A1.5 1.5 0 0 1 18 5.5 V20 L12 16.4 L6 20 V5.5 A1.5 1.5 0 0 1 7.5 4 Z")],
    "save": [S("M5.5 4.5 H15.4 L19.5 8.6 V17.5 A2 2 0 0 1 17.5 19.5 H6.5 A2 2 0 0 1 4.5 17.5 V5.5 A1 1 0 0 1 5.5 4.5 Z"),
             S("M8 4.5 V8.5 H14.5 V4.5"), S(rrect(7.5, 12.5, 9, 7, 1.2))],
    "shield_exclamation_mark": [S(SHIELD), S("M12 8.6 V12.4"), F(circle(12, 15.3, 1.05))],
    "signature": [S("M4 17.5 C6.4 13.4 7.4 9 6.8 6.6 C6.1 4 3.9 6.4 4.6 9.6 C5.4 13.2 8.6 16.6 11 15 C12.4 14 12 12.3 13 12.1 C14 11.9 14.2 14.4 15.4 14.4 C16.3 14.4 16.8 13.4 17.8 13.4"),
                  S("M4 20.2 H20")],
    "sort": [S("M8 4.5 V19"), S("M4.8 7.6 L8 4.4 L11.2 7.6"), S("M16 5 V19.5"), S("M12.8 16.4 L16 19.6 L19.2 16.4")],
    "storage": STORAGE,
    "storage_slash": STORAGE + [S("M3.5 3.5 L20.5 20.5")],
    "sync": [S("M5.2 10.4 A7 7 0 0 1 17.6 7.4"), S("M18.2 3.9 V7.9 H14.2"), S("M18.8 13.6 A7 7 0 0 1 6.4 16.6"), S("M5.8 20.1 V16.1 H9.8")],
    "sync_tabs": [S(rrect(3.5, 5, 13, 10.5, 2.6)), S("M20.5 9 V16.5 A2.5 2.5 0 0 1 18 19 H8"), S("M3.5 8.6 H16.5")],
    "tab_group": [S(rrect(3.5, 7.5, 13, 12, 2.8)), S("M7.5 4.5 H17.5 A3 3 0 0 1 20.5 7.5 V15.5")],
    "tab_group_close": [S(rrect(3.5, 7.5, 13, 12, 2.8)), S("M7.5 4.5 H17.5 A3 3 0 0 1 20.5 7.5 V15.5"),
                        S("M7.8 11.4 L12.2 15.8"), S("M12.2 11.4 L7.8 15.8")],
    "tab_tray": [S(rrect(4, 4, 16, 16, 4.2)), S(rrect(7.5, 7.5, 9, 9, 2))],
    "text_size": [S("M3.5 18.5 L7.5 8 L11.5 18.5"), S("M5 14.8 H10"), S("M12.5 18.5 L16.5 5.5 L20.5 18.5"), S("M14.3 13.8 H18.7")],
    "themes": [S(circle(12, 12, 8.5)), F("M12 3.5 A8.5 8.5 0 0 1 12 20.5 Z")],
    "thumbs_up": [S(THUMB), S(rrect(4.1, 10.5, 3.9, 8.8, 1.2))],
    "thumbs_up_fill": [F(THUMB), S(THUMB), F(rrect(4.1, 10.5, 3.9, 8.8, 1.2)), S(rrect(4.1, 10.5, 3.9, 8.8, 1.2))],
    "tool": [S("M14.2 4.6 A4.6 4.6 0 0 0 9.8 10.8 L4.8 15.8 A2 2 0 0 0 7.6 18.6 L12.6 13.6 A4.6 4.6 0 0 0 18.8 9.2 L16 12 L13 11 L12 8 Z")],
    "translate_active": [F(rrect(3.5, 3.5, 17, 17, 4.4)),
                         ('clear', "M7 7.8 H13"), ('clear', "M10 6.4 V7.8"), ('clear', "M12 7.8 C11.4 10.5 9.8 12.4 7.4 13.4"),
                         ('clear', "M8.9 9.8 C9.6 11.2 10.6 12.3 12 12.9"), ('clear', "M12.8 17.8 L15.4 11.6 L18 17.8"), ('clear', "M13.7 15.7 H17.1")],
    "whats_new": [S("M5 12.5 V9.8 A1 1 0 0 1 6 8.8 H9.5 L16.5 4.8 V17.2 L9.5 13.2 H6 A1 1 0 0 1 5 12.5 Z"),
                  S("M8 13.2 L9.4 19"), S("M19.2 9.2 V12.8")],
    "play_fill": [F(PLAY), S(PLAY)],
    "pause_outline": [S(rrect(6.5, 5, 3.6, 14, 1.4)), S(rrect(13.9, 5, 3.6, 14, 1.4))],
    "playback_forward": arrow_arc(12, 12.3, 7.2, -30, 300, True),
    "playback_rewind": arrow_arc(12, 12.3, 7.2, 210, 300, False),
    "logo_firefox": [S(circle(12, 12, 8.4)), S("M8 16.4 V11.6"), S("M11.6 16.4 V9.6"), S("M15.2 16.4 V7.6")],
})

# The thumbs-down is the thumbs-up upside down.
ICONS["thumbs_down"] = [('flip', ICONS["thumbs_up"])]
ICONS["thumbs_down_fill"] = [('flip', ICONS["thumbs_up_fill"])]

def x_shape(cx, cy, L, w):
    """An X with round ends as one closed outline, so it can be cut out of a fill."""
    pts = [('L', w, -w), ('L', w, -L), ('A', -w, -L), ('L', -w, -w), ('L', -L, -w), ('A', -L, w),
           ('L', -w, w), ('L', -w, L), ('A', w, L), ('L', w, w), ('L', L, w), ('A', L, -w)]
    c, s = math.cos(math.radians(45)), math.sin(math.radians(45))
    def rot(x, y): return cx + x * c - y * s, cy + x * s + y * c
    x0, y0 = rot(w, -w)
    d = f"M{x0:.3f} {y0:.3f}"
    for kind, x, y in pts[1:]:
        px, py = rot(x, y)
        d += f" L{px:.3f} {py:.3f}" if kind == 'L' else f" A{w} {w} 0 0 0 {px:.3f} {py:.3f}"
    return d + " Z"

BODY = "M7.17 17.2 C8.4 15.4 10 14.5 12 14.5 C14 14.5 15.6 15.4 16.83 17.2 A7.1 7.1 0 0 1 7.17 17.2 Z"
ICONS["cross_circle_fill"] = [FE(circle(12, 12, 8.9) + " " + x_shape(12, 12, 3.82, 0.9))]
ICONS["avatar_circle_fill"] = [FE(circle(12, 12, 8.9) + " " + circle(12, 10.2, 2.9) + " " + BODY)]
ICONS["translate_active"] = [('tonal', rrect(3.5, 3.5, 17, 17, 4.4)),
                             S("M7 7.8 H13"), S("M10 6.4 V7.8"), S("M12 7.8 C11.4 10.5 9.8 12.4 7.4 13.4"),
                             S("M8.9 9.8 C9.6 11.2 10.6 12.3 12 12.9"), S("M12.8 17.8 L15.4 11.6 L18 17.8"), S("M13.7 15.7 H17.1")]
