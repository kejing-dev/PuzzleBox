"""GLB writer + mesh helpers (pure Python + Pillow; from the factory's klotski-box tools/build_models.py)."""
import io, json, math, os, random, struct
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "assets", "models")

def srgb_lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


class Glb:
    def __init__(self):
        self.bin = bytearray()
        self.views, self.accessors = [], []

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.views.append(v)
        return len(self.views) - 1

    def floats(self, items, n, minmax=False):
        flat = [c for it in items for c in it]
        view = self._view(struct.pack("<%df" % len(flat), *flat), 34962)
        acc = {"bufferView": view, "componentType": 5126, "count": len(items), "type": {2: "VEC2", 3: "VEC3"}[n]}
        if minmax:
            acc["min"] = [min(it[k] for it in items) for k in range(n)]
            acc["max"] = [max(it[k] for it in items) for k in range(n)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    def indices(self, idx):
        view = self._view(struct.pack("<%dI" % len(idx), *idx), 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(idx), "type": "SCALAR"})
        return len(self.accessors) - 1

    def image(self, im):
        b = io.BytesIO()
        im.convert("RGB").save(b, "PNG", optimize=True)
        return self._view(b.getvalue())

    def write(self, path, gltf):
        gltf["asset"] = {"version": "2.0", "generator": "puzzle-box tools/build_models.py"}
        gltf["buffers"] = [{"byteLength": len(self.bin)}]
        gltf["bufferViews"] = self.views
        gltf["accessors"] = self.accessors
        js = json.dumps(gltf, separators=(",", ":")).encode()
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942) + bytes(self.bin))


class Mesh:
    """Positions / normals / uvs / indices, grouped in primitives by material index."""

    def __init__(self):
        self.pos, self.nor, self.uv = [], [], []
        self.prims = {}

    def tri(self, mat, a, b, c, n=None, uvs=((0, 0), (0, 0), (0, 0))):
        if n is None:
            u = [b[k] - a[k] for k in range(3)]; v = [c[k] - a[k] for k in range(3)]
            n = (u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            l = math.sqrt(sum(x * x for x in n)) or 1.0
            n = tuple(x / l for x in n)
        base = len(self.pos)
        for p, t in zip((a, b, c), uvs):
            self.pos.append(tuple(p)); self.nor.append(tuple(n)); self.uv.append(tuple(t))
        self.prims.setdefault(mat, []).extend([base, base + 1, base + 2])

    def quad(self, mat, a, b, c, d, n=None, uvs=((0, 0), (1, 0), (1, 1), (0, 1))):
        """Counter-clockwise a-b-c-d seen from the front (normal side)."""
        self.tri(mat, a, b, c, n, (uvs[0], uvs[1], uvs[2]))
        self.tri(mat, a, c, d, n, (uvs[0], uvs[2], uvs[3]))

    def smooth(self, mat, verts, norms, uvs, idx):
        base = len(self.pos)
        self.pos += [tuple(v) for v in verts]; self.nor += [tuple(n) for n in norms]; self.uv += [tuple(t) for t in uvs]
        self.prims.setdefault(mat, []).extend(base + i for i in idx)

    def write(self, name, materials, images=()):
        g = Glb()
        pa = g.floats(self.pos, 3, minmax=True); na = g.floats(self.nor, 3); ta = g.floats(self.uv, 2)
        prims = [{"attributes": {"POSITION": pa, "NORMAL": na, "TEXCOORD_0": ta}, "indices": g.indices(idx), "material": m} for m, idx in sorted(self.prims.items())]
        gltf = {"scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": name, "mesh": 0}], "meshes": [{"name": name, "primitives": prims}], "materials": materials}
        if images:
            gltf["images"] = [{"bufferView": g.image(im), "mimeType": "image/png"} for im in images]
            gltf["textures"] = [{"source": i, "sampler": 0} for i in range(len(images))]
            gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}]
        path = os.path.join(OUT, name + ".glb")
        g.write(path, gltf)
        tris = sum(len(i) for i in self.prims.values()) // 3
        print("%-16s %6d triangles %8d bytes" % (name + ".glb", tris, os.path.getsize(path)))


def mat(name, color=None, rough=0.6, tex=None, metal=0.0):
    pbr = {"metallicFactor": metal, "roughnessFactor": rough}
    if tex is not None:
        pbr["baseColorTexture"] = {"index": tex}
    if color is not None:
        pbr["baseColorFactor"] = [srgb_lin(c) for c in color] + [1.0]
    return {"name": name, "pbrMetallicRoughness": pbr, "alphaMode": "OPAQUE", "doubleSided": False}


def box(m, mi, x0, x1, y0, y1, z0, z1, uvf=None, sides=True, bottom=True):
    """Axis-aligned box faces; uvf(x, z) gives top uvs; sides take the uv of their top edge (streaks of the edge colour)."""
    uv = uvf or (lambda x, z: (0.5, 0.5))
    m.quad(mi, (x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0), (0, 1, 0), (uv(x0, z1), uv(x1, z1), uv(x1, z0), uv(x0, z0)))
    if sides:
        m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1), (0, 0, 1), (uv(x0, z1), uv(x1, z1), uv(x1, z1), uv(x0, z1)))
        m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0), (0, 0, -1), (uv(x1, z0), uv(x0, z0), uv(x0, z0), uv(x1, z0)))
        m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1), (1, 0, 0), (uv(x1, z1), uv(x1, z0), uv(x1, z0), uv(x1, z1)))
        m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0), (-1, 0, 0), (uv(x0, z0), uv(x0, z1), uv(x0, z1), uv(x0, z0)))
    if bottom:
        m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), (uv(x0, z0), uv(x1, z0), uv(x1, z1), uv(x0, z1)))


def wood(base, w, h, seed, along_x=True, lines=None, contrast=1.0):
    """Wood grain: base colour, long soft streaks along the grain, a few darker rings, fine noise."""
    im = Image.new("RGB", (w, h), base)
    dr = ImageDraw.Draw(im)
    rnd = random.Random(seed)
    n = lines or max(30, (h if along_x else w) // 3)
    for _ in range(n):
        dark = rnd.random() < 0.7
        k = (rnd.uniform(-22, -6) if dark else rnd.uniform(4, 14)) * contrast
        col = tuple(max(0, min(255, int(c + k))) for c in base)
        width = rnd.randint(1, 4)
        if along_x:
            y = rnd.uniform(0, h); wob = rnd.uniform(-6, 6); ph = rnd.uniform(0, 6.28)
            pts = [(x, y + wob * math.sin(x / w * 3.1 + ph)) for x in range(0, w + 8, 8)]
        else:
            x = rnd.uniform(0, w); wob = rnd.uniform(-6, 6); ph = rnd.uniform(0, 6.28)
            pts = [(x + wob * math.sin(y / h * 3.1 + ph), y) for y in range(0, h + 8, 8)]
        dr.line(pts, fill=col, width=width)
    im = im.filter(ImageFilter.GaussianBlur(1.1))
    px = im.load()
    for _ in range(w * h // 6):
        x, y = rnd.randrange(w), rnd.randrange(h)
        d = rnd.randint(-7, 7)
        r, g, b = px[x, y]
        px[x, y] = (max(0, min(255, r + d)), max(0, min(255, g + d)), max(0, min(255, b + d)))
    return im


def chamfer_block(m, mi, w, h, d, b, uv):
    """A w x h x d block centred on the origin with its four top edges chamfered by b; uv(x, z) maps the top view."""
    x0, x1, y0, y1, z0, z1 = -w / 2, w / 2, -h / 2, h / 2, -d / 2, d / 2
    yb = y1 - b
    s2 = 1 / math.sqrt(2)
    cl = lambda x, z: uv(max(x0 + 0.0008, min(x1 - 0.0008, x)), max(z0 + 0.0008, min(z1 - 0.0008, z)))
    # top
    m.quad(mi, (x0 + b, y1, z1 - b), (x1 - b, y1, z1 - b), (x1 - b, y1, z0 + b), (x0 + b, y1, z0 + b), (0, 1, 0),
           (uv(x0 + b, z1 - b), uv(x1 - b, z1 - b), uv(x1 - b, z0 + b), uv(x0 + b, z0 + b)))
    # chamfers
    m.quad(mi, (x0, yb, z1), (x1, yb, z1), (x1 - b, y1, z1 - b), (x0 + b, y1, z1 - b), (0, s2, s2), (cl(x0, z1), cl(x1, z1), cl(x1 - b, z1 - b), cl(x0 + b, z1 - b)))
    m.quad(mi, (x1, yb, z0), (x0, yb, z0), (x0 + b, y1, z0 + b), (x1 - b, y1, z0 + b), (0, s2, -s2), (cl(x1, z0), cl(x0, z0), cl(x0 + b, z0 + b), cl(x1 - b, z0 + b)))
    m.quad(mi, (x1, yb, z1), (x1, yb, z0), (x1 - b, y1, z0 + b), (x1 - b, y1, z1 - b), (s2, s2, 0), (cl(x1, z1), cl(x1, z0), cl(x1 - b, z0 + b), cl(x1 - b, z1 - b)))
    m.quad(mi, (x0, yb, z0), (x0, yb, z1), (x0 + b, y1, z1 - b), (x0 + b, y1, z0 + b), (-s2, s2, 0), (cl(x0, z0), cl(x0, z1), cl(x0 + b, z1 - b), cl(x0 + b, z0 + b)))
    # sides
    m.quad(mi, (x0, y0, z1), (x1, y0, z1), (x1, yb, z1), (x0, yb, z1), (0, 0, 1), (cl(x0, z1), cl(x1, z1), cl(x1, z1), cl(x0, z1)))
    m.quad(mi, (x1, y0, z0), (x0, y0, z0), (x0, yb, z0), (x1, yb, z0), (0, 0, -1), (cl(x1, z0), cl(x0, z0), cl(x0, z0), cl(x1, z0)))
    m.quad(mi, (x1, y0, z1), (x1, y0, z0), (x1, yb, z0), (x1, yb, z1), (1, 0, 0), (cl(x1, z1), cl(x1, z0), cl(x1, z0), cl(x1, z1)))
    m.quad(mi, (x0, y0, z0), (x0, y0, z1), (x0, yb, z1), (x0, yb, z0), (-1, 0, 0), (cl(x0, z0), cl(x0, z1), cl(x0, z1), cl(x0, z0)))
    # bottom
    m.quad(mi, (x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1), (0, -1, 0), (cl(x0, z0), cl(x1, z0), cl(x1, z1), cl(x0, z1)))


