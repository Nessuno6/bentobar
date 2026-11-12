#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""
Turns One UI icons (SVG files, as exported from a One UI design kit) into Android vector drawables that BentoBar draws in
place of its Material Symbols, icon by icon (util/OneUiIcons.kt maps a symbol to `oneui_<name>`).

This fork ships the drawables: these icons are also in the One UI icon pack (MIT, see ONEUI-ICONS-LICENSE), which
tools/oneui_icons_pack.py draws every other symbol from. The app draws Material Symbols for any symbol without one.

    python tools/oneui_icons.py PATH/TO/svg_folder

Only fills are kept (a font-like icon takes the text's colour); a stroke the export added around a fill is dropped.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

# BentoBar's symbol (util/Sym.kt, lower case) -> the One UI icon's file name.
ICONS = {
    "mouse": "list_ic_mouse",
    "keyboard": "list_ic_keyboard",
    "stylus": "list_ic_spen",
    "sports_esports": "list_ic_game_device",
    "headphones": "list_ic_earbuds_buds4",
    "bolt": "ic_icon_charging",
    "bolt_double": "ic_icon_superfast",
    "settings": "tw_ic_ab_setting_mtrl",
    "edit": "tw_ic_ab_edit_mtrl",
    "power_settings_new": "tw_ic_ab_power_mtrl",
    "alarm": "tw_ic_audio_alarm_mtrl",
    "music_note": "ic_audio_media_note",
    "bluetooth": "ic_audio_bluetooth",
    "wifi": "sec_ic_wifi_signal_4",
    "notifications": "ic_notifications_sound",
    "screenshot_monitor": "ic_screen_capture",
    "videocam": "ic_screen_record",
    "desktop_windows": "list_ic_monitor",
    "key": "stat_sys_branded_vpn",
    "watch": "list_ic_wearable",
    "smartphone": "list_ic_mobile",
    "tablet_android": "list_ic_tablet",
    # Glyphs that One UI draws on a coloured circle (a settings category): the circle is dropped.
    "volume_up": "ic_category_sound",
    "handyman": "ic_category_utilities",
    "lock": "tw_ic_do_lockdown",
    "swap_vert": "stat_sys_data_inout",
}

# Material Symbols draw a 24-unit icon in about 20 of its units: every icon is cropped to its outline and given that much room.
COVER = 20 / 24

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
NS = "{http://www.w3.org/2000/svg}"


def colored(fill):
    """A fill that is neither black nor (near) white: the coloured circle behind a settings category's glyph."""
    if not fill or not fill.startswith("#") or len(fill) != 7:
        return False
    r, g, b = (int(fill[i:i + 2], 16) for i in (1, 3, 5))
    return not (max(r, g, b) < 40 or min(r, g, b) > 230)


def bounds(ds):
    from fontTools.pens.boundsPen import BoundsPen
    from fontTools.svgLib.path import parse_path
    pen = BoundsPen(None)
    for d in ds:
        parse_path(d, pen)
    return pen.bounds


# Earbuds as they are connected: one Buds earbud as One UI draws it is the right one, mirrored the left, both side by side.
LAYOUTS = {
    "headphones_right": ("list_ic_earbuds_buds4", "right"),
    "headphones_left": ("list_ic_earbuds_buds4", "left"),
    "headphones_both": ("list_ic_earbuds_buds4", "both"),
    # Any other headphones: One UI's over-ear headset.
    "headphones": ("list_ic_headset", "one"),
    # A linked phone or tablet on its charger: its outline with One UI's charging bolt inside.
    "smartphone_charging": ("list_ic_mobile", "charging"),
    "tablet_android_charging": ("list_ic_tablet", "charging"),
    # The same on super fast charging 2.0: One UI's double bolt inside.
    "smartphone_charging_2": ("list_ic_mobile", "charging_2"),
    "tablet_android_charging_2": ("list_ic_tablet", "charging_2"),
}

# The bolt drawn inside a charging device's outline.
BOLT = "ic_icon_charging"
BOLT_2 = "ic_icon_superfast"


def opacity(p, name):
    try:
        return float(p.get(name, "1"))
    except ValueError:
        return 1.0


def stroked(p):
    """A path drawn as an outline (One UI draws some icons with a stroke and no fill): its stroke width, else 0."""
    if p.get("stroke") in (None, "none") or opacity(p, "stroke-opacity") == 0:
        return 0.0
    if p.get("fill") not in (None, "none") and opacity(p, "fill-opacity") > 0:
        return 0.0
    return float(p.get("stroke-width", "1"))


def visible(p):
    filled = p.get("fill") != "none" and opacity(p, "fill-opacity") > 0
    return bool(p.get("d")) and (filled or stroked(p) > 0)


def element(p, transform=""):
    """One path as a vector drawable's path: filled, or an outline with its width and joins."""
    width = stroked(p)
    if width:
        join = p.get("stroke-linejoin", "miter")
        cap = p.get("stroke-linecap", "butt")
        return (f'<path android:strokeColor="#FF000000" android:strokeWidth="{width:g}" android:strokeLineJoin="{join}" '
                f'android:strokeLineCap="{cap}" android:pathData="{p.get("d")}" />')
    rule = "evenOdd" if (p.get("fill-rule") or p.get("clip-rule")) == "evenodd" else "nonZero"
    return f'<path android:fillColor="#FF000000" android:fillType="{rule}" android:pathData="{p.get("d")}" />'


def vector(svg_path: str, layout: str = "one") -> str:
    root = ET.parse(svg_path).getroot()
    found = [p for p in root.iter(NS + "path") if visible(p)]
    # A glyph on a coloured circle: the circle (the first path, coloured) goes, the glyph stays.
    if len(found) > 1 and colored(found[0].get("fill")):
        found = found[1:]
    if not found:
        raise ValueError("no filled path")
    # An outline reaches half its stroke past its path.
    pad = max(stroked(p) for p in found) / 2
    x0, y0, x1, y1 = bounds([p.get("d") for p in found])
    x0, y0, x1, y1 = x0 - pad, y0 - pad, x1 + pad, y1 + pad
    w, h = x1 - x0, y1 - y0
    bolt = ""
    if layout in ("charging", "charging_2"):
        # The outline only (a phone's camera dot goes), and the bolt centred in it, as tall as fits.
        outline = [p for p in found if stroked(p)]
        found = outline or found[:1]
        bp = [p for p in ET.parse(os.path.join(os.path.dirname(svg_path), (BOLT_2 if layout == "charging_2" else BOLT) + ".svg")).getroot().iter(NS + "path") if visible(p)]
        bx0, by0, bx1, by1 = bounds([p.get("d") for p in bp])
        inner_w, inner_h = w - 4 * pad, h - 4 * pad
        k = min(0.7 * inner_w / (bx1 - bx0), 0.62 * inner_h / (by1 - by0))
        tx = (x0 + x1) / 2 - k * (bx0 + bx1) / 2
        ty = (y0 + y1) / 2 - k * (by0 + by1) / 2
        bolt = (f'            <group android:translateX="{tx:g}" android:translateY="{ty:g}" android:scaleX="{k:g}" android:scaleY="{k:g}">\n' +
                "\n".join("                " + element(p) for p in bp) + "\n            </group>")
    gap = 0.12 * w
    # The copies: (mirrored, how far right of the first), and the whole drawing's width.
    copies = {"one": [(False, 0)], "charging": [(False, 0)], "charging_2": [(False, 0)], "right": [(False, 0)], "left": [(True, 0)], "both": [(True, 0), (False, w + gap)]}[layout]
    width = w + max(off for _, off in copies)
    # A square viewport around the outline, the icon centred in [COVER] of it, as Material Symbols are drawn; a pair is
    # as tall as one and wider than square (its drawable says so, and the icon takes that width).
    side = max(width, h) / COVER if layout != "both" else h / COVER
    wide = max(side, width + (side - h)) if layout == "both" else side
    dx, dy = (wide - width) / 2 - x0, (side - h) / 2 - y0
    paths = []
    for mirrored, off in copies:
        flip = f' android:scaleX="-1" android:pivotX="{(x0 + x1) / 2:g}"' if mirrored else ""
        paths.append(f'        <group android:translateX="{off:g}"{flip}>')
        for p in found:
            paths.append("            " + element(p))
        if bolt:
            paths.append(bolt)
        paths.append('        </group>')
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Generated by tools/oneui_icons.py from {os.path.basename(svg_path)}: not committed (see the script). -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{24 * wide / side:.2f}dp" android:height="24dp" android:viewportWidth="{wide:g}" android:viewportHeight="{side:g}">\n'
        f'    <group android:translateX="{dx:g}" android:translateY="{dy:g}">\n' + "\n".join(paths) + "\n    </group>\n</vector>\n"
    )


def exclude(names):
    path = os.path.join(ROOT, ".git", "info", "exclude")
    if not os.path.isdir(os.path.dirname(path)):
        return
    have = open(path, encoding="utf8").read() if os.path.exists(path) else ""
    line = "app/src/main/res/drawable/oneui_*.xml"
    if line not in have:
        with open(path, "a", encoding="utf8") as f:
            f.write(("\n" if have and not have.endswith("\n") else "") + "# One UI icons: Samsung's designs, for this build only (tools/oneui_icons.py)\n" + line + "\n")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    os.makedirs(OUT, exist_ok=True)
    made, missing = [], []
    jobs = [(sym, name, "one") for sym, name in ICONS.items() if sym != "headphones"] + [(k, n, l) for k, (n, l) in LAYOUTS.items()]
    for sym, name, layout in jobs:
        svg = os.path.join(src, name + ".svg")
        if not os.path.exists(svg):
            missing.append(name)
            continue
        try:
            xml = vector(svg, layout)
        except Exception as e:  # noqa: BLE001
            missing.append(f"{name} ({e})")
            continue
        with open(os.path.join(OUT, f"oneui_{sym}.xml"), "w", encoding="utf8", newline="\n") as f:
            f.write(xml)
        made.append(sym)
    exclude(made)
    print(f"{len(made)} drawables written to {os.path.relpath(OUT, ROOT)}" + (f"; missing: {', '.join(missing)}" if missing else ""))


if __name__ == "__main__":
    main()
