"""PuzzleBox icon (design-package appIcon): a coral #E5533D disc; an isometric, slightly tilted clear box drawn in white
lines with two coloured blocks inside (blue #4D7CC7, green #8AB17D); a red T #D1495B dropping into the mouth. No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
CORAL = (0xE5, 0x53, 0x3D)
WHITE = (0xFF, 0xFF, 0xFF)
BLUE = (0x4D, 0x7C, 0xC7)
GREEN = (0x8A, 0xB1, 0x7D)
RED = (0xD1, 0x49, 0x5B)
SHADE = lambda c, k: tuple(int(v * k) for v in c)

# isometric projection of box cells (x right-down, z left-down, y up), centred in the 108 grid
def P(x, y, z):
    return (54 + (x - z) * 14.0, 77 - y * 17.0 + (x + z) * 8.5 - 8.5 * 2)

def face(pts):
    return [P(*p) for p in pts]

def cube(x, y, z):
    top = face([(x, y + 1, z), (x + 1, y + 1, z), (x + 1, y + 1, z + 1), (x, y + 1, z + 1)])
    right = face([(x + 1, y, z), (x + 1, y + 1, z), (x + 1, y + 1, z + 1), (x + 1, y, z + 1)])
    left = face([(x, y, z + 1), (x + 1, y, z + 1), (x + 1, y + 1, z + 1), (x, y + 1, z + 1)])
    return top, right, left

BOX_W, BOX_H, BOX_D = 2, 2, 2
INSIDE = [((0, 0, 0), BLUE), ((1, 0, 0), BLUE), ((0, 0, 1), GREEN), ((1, 0, 1), GREEN), ((0, 1, 0), BLUE)]
FALLING = [((0, 2.35, 1), RED), ((1, 2.35, 1), RED), ((1, 2.35, 0), RED)]
EDGES = [((2, 0, 0), (2, 0, 2)), ((2, 0, 2), (0, 0, 2)),
         ((0, 2, 0), (2, 2, 0)), ((2, 2, 0), (2, 2, 2)), ((2, 2, 2), (0, 2, 2)), ((0, 2, 2), (0, 2, 0)),
         ((2, 0, 0), (2, 2, 0)), ((2, 0, 2), (2, 2, 2)), ((0, 0, 2), (0, 2, 2))]


def line_poly(a, b, w=1.5):
    (x0, y0), (x1, y1) = P(*a), P(*b)
    dx, dy = x1 - x0, y1 - y0; l = math.hypot(dx, dy) or 1
    nx, ny = -dy / l * w, dx / l * w
    return [(x0 + nx, y0 + ny), (x1 + nx, y1 + ny), (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)]


def cubes_polys(items):
    out = []
    for (c, col) in sorted(items, key=lambda it: (it[0][1], it[0][0] + it[0][2])):
        t, r, l = cube(*c)
        out += [(t, col), (r, SHADE(col, 0.78)), (l, SHADE(col, 0.62))]
    return out


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=CORAL + (255,)))


def layer_box():
    def d(dr, k):
        for poly, col in cubes_polys(INSIDE):
            dr.polygon(sc(poly, k), fill=col + (255,))
        for a, b in EDGES:
            dr.polygon(sc(line_poly(a, b), k), fill=WHITE + (255,))
    return big(d)


def layer_tile():
    def d(dr, k):
        for poly, col in cubes_polys(FALLING):
            dr.polygon(sc(poly, k), fill=col + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- PuzzleBox launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
    ]
    for poly, col in cubes_polys(INSIDE):
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(col), path(poly)))
    lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(WHITE), "".join(path(line_poly(a, b)) for a, b in EDGES)))
    for poly, col in cubes_polys(FALLING):
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(col), path(poly)))
    lines.append('</vector>')
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_box(), layer_tile()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
