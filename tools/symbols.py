"""Material Symbols (Apache-2.0, third_party/material-symbols/*.svg, viewBox 0 -960 960 960) -> Android vector drawables
res/drawable/ic_<name>.xml (white fill, tinted by the Icon composable). Also prints the path data for BoardTextures.kt.

  python3 tools/symbols.py
"""
import os, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "third_party", "material-symbols")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
NAMES = {"extension": "ic_tab_play", "inventory_2": "ic_tab_boxes", "help": "ic_tab_rules", "settings": "ic_tab_settings",
         "undo": "ic_undo", "lightbulb": "ic_hint", "auto_fix_high": "ic_help", "volume_off": "ic_mute", "volume_up": "ic_sound",
         "restart_alt": "ic_restart", "logout": "ic_exit", "check_circle": "ic_done"}
for src, name in NAMES.items():
    d = re.search(r'\bd="([^"]+)"', open(os.path.join(SRC, src + ".svg")).read()).group(1)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Material Symbols Rounded "%s" (Apache-2.0), tools/symbols.py -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" '
           'android:viewportWidth="960" android:viewportHeight="960">\n    <group android:translateY="960">\n'
           '        <path android:fillColor="#FFFFFFFF" android:pathData="%s" />\n    </group>\n</vector>\n') % (src, d)
    open(os.path.join(OUT, name + ".xml"), "w").write(xml)
    print("wrote", name)
for src in ("360", "move_down", "u_turn_right"):
    d = re.search(r'\bd="([^"]+)"', open(os.path.join(SRC, src + ".svg")).read()).group(1)
    print(src, d)
