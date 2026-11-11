"""Builds every model of the PuzzleBox volume as GLB (pure Python + Pillow, no Blender on this machine).

  python3 tools/build_models.py [--test-f]     # --test-f: "F↑" on the piece faces and the wedge (profile D10)

Writes app/src/main/assets/models/ (numbers = domain/Layout.kt, design spatialLayout):
  piece_<T>.glb     the eleven blocks: 38 mm cubes with a 1.5 mm bevel on every edge, one per cell (2 mm seams), lacquered
                    wood (grain tinted with the design colour); origin = centre of canonical cell (0,0,0)
  box_<X>_<Y>_<Z>.glb   walnut floor plate (1 cm) + four 8 mm corner posts; box frame origin = near-bottom edge, x centred
  lid_<X>_<Z>.glb   walnut lid (8 mm), origin = bottom centre
  wedge.glb         walnut wedge under the box (top = the 30° box underside), volume coordinates
  tray.glb          maple tray 0.62 x 0.24 x 0.015 with a 6 mm rim, volume coordinates
  stand.glb         walnut turn stand (Ø 16 cm, top y -0.12, 32 segments) + the tab bracket, volume coordinates
  tab.glb           a maple tab 6 x 4 x 1 cm (the icon + word face is a texture in the app), origin = centre
All materials OPAQUE (D24). Texture convention: image up = +Y / far, image right = +X.
"""
import math, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from glb import Mesh, mat, box, wood, srgb_lin, chamfer_block, OUT
from PIL import Image, ImageDraw, ImageFont

TEST_F = "--test-f" in sys.argv
U, CUBE, BEV = 0.04, 0.038, 0.0015
FLOOR_T, MARGIN, POST = 0.01, 0.01, 0.008
TILT = math.radians(30)
PIVOT_Y, PIVOT_Z = -0.15, -0.07
WALNUT = (0x5A, 0x3A, 0x26)
WALNUT_DARK = (0x3E, 0x28, 0x1A)
MAPLE = (0xE3, 0xC6, 0x98)
MAPLE_LIGHT = (0xEE, 0xD9, 0xB4)
TRAY = (0x3E, 0x28, 0x1A)

PIECES = {
    "I2": ([(0, 0, 0), (1, 0, 0)], 0xE9C46A), "I3": ([(0, 0, 0), (1, 0, 0), (2, 0, 0)], 0xF4A261),
    "I4": ([(0, 0, 0), (1, 0, 0), (2, 0, 0), (3, 0, 0)], 0xE76F51), "O": ([(0, 0, 0), (1, 0, 0), (0, 0, 1), (1, 0, 1)], 0x2A9D8F),
    "V": ([(0, 0, 0), (1, 0, 0), (0, 0, 1)], 0x8AB17D), "L": ([(0, 0, 0), (1, 0, 0), (2, 0, 0), (0, 0, 1)], 0x4D7CC7),
    "T": ([(0, 0, 0), (1, 0, 0), (2, 0, 0), (1, 0, 1)], 0xD1495B), "Z": ([(0, 0, 0), (1, 0, 0), (1, 0, 1), (2, 0, 1)], 0x7B5EA7),
    "A": ([(0, 0, 0), (1, 0, 0), (0, 0, 1), (0, 1, 0)], 0x5C946E), "B": ([(0, 0, 0), (1, 0, 0), (1, 0, 1), (1, 1, 1)], 0xF7B32B),
    "P": ([(0, 0, 0), (1, 0, 0), (0, 0, 1), (0, 1, 1)], 0x3FA7D6),
}
DIMS = [(2, 2, 2), (3, 2, 2), (2, 3, 2), (3, 2, 3), (4, 2, 2), (3, 2, 4), (3, 3, 2), (4, 3, 2), (2, 3, 3), (3, 3, 3)]


def rgb(h):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255)


def bevel_cube(m, mi, cx, cy, cz, s, b):
    """A cube of side s centred on (cx, cy, cz), every edge bevelled by b: 6 faces, 12 edge strips, 8 corner triangles."""
    h, k = s / 2, s / 2 - b
    P = lambda x, y, z: (cx + x, cy + y, cz + z)
    uvq = ((0, 1), (1, 1), (1, 0), (0, 0))
    # faces: for axis a and sign sg, the face square at a = sg*h, other coords +-k
    for a in range(3):
        for sg in (-1, 1):
            o = [i for i in range(3) if i != a]
            def v(u, w):
                c = [0, 0, 0]; c[a] = sg * h; c[o[0]] = u; c[o[1]] = w; return P(*c)
            n = [0, 0, 0]; n[a] = sg
            quad = [v(-k, -k), v(k, -k), v(k, k), v(-k, k)]
            # orient counter-clockwise seen from outside
            e1 = [quad[1][i] - quad[0][i] for i in range(3)]; e2 = [quad[2][i] - quad[0][i] for i in range(3)]
            cr = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
            if sum(cr[i] * n[i] for i in range(3)) < 0:
                quad = quad[::-1]
            m.quad(mi, *quad, tuple(n), uvq)
    # edges: between axis a (sign sa) and axis c (sign sc), running along the third axis
    s2 = 1 / math.sqrt(2)
    for a in range(3):
        for c in range(a + 1, 3):
            r = 3 - a - c
            for sa in (-1, 1):
                for sc in (-1, 1):
                    def v(ta, tc, tr):
                        p = [0, 0, 0]; p[a] = ta; p[c] = tc; p[r] = tr; return P(*p)
                    q = [v(sa * h, sc * k, -k), v(sa * h, sc * k, k), v(sa * k, sc * h, k), v(sa * k, sc * h, -k)]
                    n = [0, 0, 0]; n[a] = sa * s2; n[c] = sc * s2
                    e1 = [q[1][i] - q[0][i] for i in range(3)]; e2 = [q[2][i] - q[0][i] for i in range(3)]
                    cr = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                    if sum(cr[i] * n[i] for i in range(3)) < 0:
                        q = q[::-1]
                    m.quad(mi, *q, tuple(n), ((0, 0), (1, 0), (1, 0.05), (0, 0.05)))
    # corners
    s3 = 1 / math.sqrt(3)
    for sx in (-1, 1):
        for sy in (-1, 1):
            for sz in (-1, 1):
                t = [P(sx * h, sy * k, sz * k), P(sx * k, sy * h, sz * k), P(sx * k, sy * k, sz * h)]
                n = (sx * s3, sy * s3, sz * s3)
                e1 = [t[1][i] - t[0][i] for i in range(3)]; e2 = [t[2][i] - t[0][i] for i in range(3)]
                cr = (e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                if sum(cr[i] * n[i] for i in range(3)) < 0:
                    t = t[::-1]
                m.tri(mi, t[0], t[1], t[2], n, ((0, 0), (0, 0.05), (0.05, 0)))


def lacquer(color, seed):
    """Lacquered wood face: the colour with soft grain streaks (contrast kept low so the colour reads)."""
    im = wood(color, 256, 256, seed=seed, along_x=True, contrast=0.45)
    if TEST_F:
        dr = ImageDraw.Draw(im)
        f = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 150)
        dr.text((128, 128), "F↑" if False else "F", font=f, fill=(20, 20, 20), anchor="mm")
        dr.polygon([(200, 30), (230, 90), (170, 90)], fill=(20, 20, 20))
    return im


def build_piece(name):
    cells, col = PIECES[name]
    m = Mesh()
    for (x, y, z) in cells:
        bevel_cube(m, 0, x * U, y * U, z * U, CUBE, BEV)
    m.write("piece_" + name, [mat("Lacquer_" + name, tex=0, rough=0.38)], [lacquer(rgb(col), seed=sum(map(ord, name)))])


def build_box(X, Y, Z):
    W, D = X * U, Z * U
    mouth = FLOOR_T + Y * U
    x0, x1, z0, z1 = -W / 2 - MARGIN, W / 2 + MARGIN, -D - 2 * MARGIN, 0.0
    m = Mesh()
    uv = lambda x, z: ((x - x0) / (x1 - x0), (z - z0) / (z1 - z0))
    box(m, 0, x0, x1, 0.0, FLOOR_T, z0, z1, uvf=uv)
    p = POST
    for (px, pz) in ((x0, z1 - p), (x1 - p, z1 - p), (x0, z0), (x1 - p, z0)):
        box(m, 1, px, px + p, FLOOR_T, mouth + 0.002, pz, pz + p, uvf=lambda x, z: (0.5, 0.5), bottom=False)
    floor = wood(WALNUT_DARK, 384, 384, seed=X * 100 + Y * 10 + Z, along_x=False, contrast=0.8)
    m.write("box_%d_%d_%d" % (X, Y, Z), [mat("BoxFloor", tex=0, rough=0.62), mat("BoxPost", color=WALNUT_DARK, rough=0.55)], [floor])


def build_lid(X, Z):
    W, D = X * U + 2 * MARGIN, Z * U + 2 * MARGIN
    m = Mesh()
    chamfer_block(m, 0, W, 0.008, D, 0.002, lambda x, z: (x / W + 0.5, z / D + 0.5))
    # a small knob in the middle
    box(m, 0, -0.012, 0.012, 0.004, 0.009, -0.006, 0.006, uvf=lambda x, z: (0.5, 0.5), bottom=False)
    m.write("lid_%d_%d" % (X, Z), [mat("Lid", tex=0, rough=0.5)], [wood(WALNUT, 256, 256, seed=X * 7 + Z, along_x=True, contrast=0.9)])


def build_wedge():
    D = 0.18
    hw = 0.10
    fz, fy = PIVOT_Z, PIVOT_Y
    bz, by = fz - D * math.cos(TILT), fy + D * math.sin(TILT)
    F = -0.20
    m = Mesh()
    uv = lambda x, z: ((x + hw) / (2 * hw), (fz - z) / (fz - bz))
    n = (0.0, math.cos(TILT), math.sin(TILT))
    m.quad(0, (-hw, fy, fz), (hw, fy, fz), (hw, by, bz), (-hw, by, bz), n, (uv(-hw, fz), uv(hw, fz), uv(hw, bz), uv(-hw, bz)))
    m.quad(0, (-hw, F, fz), (hw, F, fz), (hw, fy, fz), (-hw, fy, fz), (0, 0, 1), ((0, 1), (1, 1), (1, 0.75), (0, 0.75)))
    m.quad(0, (hw, F, bz), (-hw, F, bz), (-hw, by, bz), (hw, by, bz), (0, 0, -1), ((1, 1), (0, 1), (0, 0), (1, 0)))
    m.quad(0, (hw, F, fz), (hw, F, bz), (hw, by, bz), (hw, fy, fz), (1, 0, 0), ((0, 1), (1, 1), (1, 0), (0, 0.6)))
    m.quad(0, (-hw, F, bz), (-hw, F, fz), (-hw, fy, fz), (-hw, by, bz), (-1, 0, 0), ((1, 1), (0, 1), (0, 0.6), (1, 0)))
    m.write("wedge", [mat("Wedge", tex=0, rough=0.66)], [wood(WALNUT, 512, 512, seed=21, along_x=True, contrast=0.8)])


def build_tray():
    x0, x1, z0, z1 = -0.31, 0.31, 0.03, 0.27
    F, T, R, rim = -0.20, -0.185, -0.175, 0.006
    m = Mesh()
    uv = lambda x, z: ((x - x0) / (x1 - x0), (z - z0) / (z1 - z0))
    box(m, 0, x0, x1, F, T, z0, z1, uvf=uv)
    for (a0, a1, b0, b1) in ((x0, x1, z0, z0 + rim), (x0, x1, z1 - rim, z1), (x0, x0 + rim, z0 + rim, z1 - rim), (x1 - rim, x1, z0 + rim, z1 - rim)):
        box(m, 1, a0, a1, T, R, b0, b1, uvf=uv, bottom=False)
    # dark walnut (not maple): every block colour stays >= 25 L* away from it (the yellow domino was 0.6 on maple)
    m.write("tray", [mat("Tray", tex=0, rough=0.68), mat("TrayRim", tex=1, rough=0.6)],
            [wood(TRAY, 1024, 400, seed=31, along_x=True, contrast=0.7), wood(WALNUT, 512, 64, seed=32, along_x=True)])


def build_stand():
    cx, cz, r = 0.21, -0.08, 0.08
    F, T, N = -0.20, -0.12, 32
    m = Mesh()
    ring = [(cx + r * math.cos(2 * math.pi * i / N), cz + r * math.sin(2 * math.pi * i / N)) for i in range(N)]
    # side (smooth normals)
    verts, norms, uvs, idx = [], [], [], []
    for i in range(N + 1):
        a = 2 * math.pi * (i % N) / N
        x, z = cx + r * math.cos(a), cz + r * math.sin(a)
        verts += [(x, F, z), (x, T, z)]; norms += [(math.cos(a), 0, math.sin(a))] * 2; uvs += [(i / N, 1), (i / N, 0)]
    for i in range(N):
        b = 2 * i
        idx += [b, b + 3, b + 1, b, b + 2, b + 3]
    m.smooth(1, verts, norms, uvs, idx)
    # top cap (maple disc with rings)
    for i in range(N):
        (ax, az), (bx, bz) = ring[i], ring[(i + 1) % N]
        tuv = lambda x, z: ((x - cx) / (2 * r) + 0.5, (z - cz) / (2 * r) + 0.5)
        m.tri(0, (cx, T, cz), (bx, T, bz), (ax, T, az), (0, 1, 0), (tuv(cx, cz), tuv(bx, bz), tuv(ax, az)))
    # tab bracket: a walnut bar along the stand's near edge, under the three tabs
    box(m, 1, 0.105, 0.315, -0.170, -0.154, 0.004, 0.026, uvf=lambda x, z: (0.5, 0.5))
    top = Image.new("RGB", (512, 512), TRAY)
    dr = ImageDraw.Draw(top)
    for k in range(6, 256, 22):
        dr.ellipse((256 - k, 256 - k, 256 + k, 256 + k), outline=(0x4E, 0x34, 0x22), width=3)
    m.write("stand", [mat("StandTop", tex=0, rough=0.6), mat("StandSide", tex=1, rough=0.6)], [top, wood(WALNUT, 512, 128, seed=41, along_x=True)])


def build_tab():
    m = Mesh()
    w, h, t = 0.06, 0.04, 0.01
    # the slab stands upright facing +Z: build a block w x t x h on its back and rotate (y <-> z)
    sub = Mesh()
    chamfer_block(sub, 0, w, t, h, 0.0015, lambda x, z: (x / w + 0.5, z / h + 0.5))
    for p, n in zip(sub.pos, sub.nor):
        pass
    m.pos = [(x, -z, y) for (x, y, z) in sub.pos]
    m.nor = [(x, -z, y) for (x, y, z) in sub.nor]
    m.uv = sub.uv
    m.prims = sub.prims
    m.write("tab", [mat("Tab", tex=0, rough=0.6)], [wood(MAPLE_LIGHT, 256, 192, seed=51, along_x=True, contrast=0.6)])


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for n in PIECES:
        build_piece(n)
    for d in DIMS:
        build_box(*d)
    for (x, z) in sorted({(d[0], d[2]) for d in DIMS}):
        build_lid(x, z)
    build_wedge(); build_tray(); build_stand(); build_tab()
