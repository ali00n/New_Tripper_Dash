"""Amostra (mockup) do quadro do painel com a camada de risco, antes de implementar no app."""
import io, json, math, sys, urllib.request, urllib.parse
from PIL import Image, ImageDraw, ImageFilter

out_dir, risco_path = sys.argv[1], sys.argv[2]
UA = {'User-Agent': 'TripperDashPessoal/1.0 (amostra)'}
W, H, BX, BY = 526, 300, 0.5, 0.70

def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read()

def world(lat, lon, z):
    n = 256 * 2 ** z
    s = math.sin(math.radians(lat))
    return (lon + 180) / 360 * n, (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n

def night(img):
    img = img.convert('RGB'); px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b = px[x, y]; mx, mn = max(r, g, b), min(r, g, b)
            if mn >= 248: px[x, y] = (0xB9, 0xBC, 0xC2)
            elif mx < 110: px[x, y] = (14, 14, 16)
            elif r >= 225 and r >= g and g >= b - 10 and mx - mn >= 30: px[x, y] = (0xE3, 0xA2, 0x3C)
            else:
                ir, ig, ib = 255 - r, 255 - g, 255 - b
                c = lambda v: max(0, min(255, int(v)))
                px[x, y] = (c(-0.574*ir + 1.430*ig + 0.144*ib), c(0.426*ir + 0.430*ig + 0.144*ib), c(0.426*ir + 1.430*ig - 0.856*ib))
    return img

tile_cache = {}
def tile(z, x, y):
    k = (z, x, y)
    if k not in tile_cache:
        tile_cache[k] = night(Image.open(io.BytesIO(get('https://tile.openstreetmap.org/%d/%d/%d.png' % k))))
    return tile_cache[k]

def cow(d, cx, cy, k=1.035):
    def ov(x, y, rx, ry, fill=None, outline=None, w=1):
        d.ellipse([cx + (x - rx) * k, cy + (y - ry) * k, cx + (x + rx) * k, cy + (y + ry) * k], fill=fill, outline=outline, width=w)
    blk, wht = (25, 25, 25), (250, 250, 245)
    d.line([cx, cy + 22 * k, cx + 2 * k, cy + 33 * k], fill=blk, width=2); ov(2.5, 35, 2.5, 3.5, blk)
    for x, y in [(-12, -9), (12, -9), (-11, 17), (11, 17)]: ov(x, y, 3, 3.5, blk)
    d.rounded_rectangle([cx - 13 * k, cy - 14 * k, cx + 13 * k, cy + 24 * k], radius=12 * k, fill=wht, outline=blk, width=2)
    for x, y, rx, ry in [(-7, -6, 6, 5), (7, 4, 5, 7), (-5, 15, 5, 4)]: ov(x, y, rx, ry, blk)
    ov(-9, -25, 5, 2, (225, 205, 160)); ov(9, -25, 5, 2, (225, 205, 160)); ov(-10, -20, 4, 2.5, blk); ov(10, -20, 4, 2.5, blk)
    ov(0, -21, 7, 8, wht, blk, 2); ov(2, -22, 3.5, 4, blk); ov(0, -28, 5.5, 3.5, (240, 160, 170))
    ov(-2, -28.5, 1, 1, blk); ov(2, -28.5, 1, 1, blk)

def inside(lat, lon, ring):
    n = len(ring) // 2; c = False; j = n - 1
    for i in range(n):
        xi, yi, xj, yj = ring[2*i], ring[2*i+1], ring[2*j], ring[2*j+1]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi: c = not c
        j = i
    return c

def geocode(q):
    r = json.loads(get('https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=br&q=' + urllib.parse.quote(q)))
    return float(r[0]['lat']), float(r[0]['lon'])


out_dir, com_path = sys.argv[1], sys.argv[2]
com = json.load(io.open(com_path, encoding='utf-8'))['areas']
from PIL import ImageFont
def font(sz, bold=True):
    try: return ImageFont.truetype('arialbd.ttf' if bold else 'arial.ttf', sz)
    except Exception: return None

def near(lat, lon, km=3):
    k = km / 111
    return [a for a in com if any(abs(r[1] - lat) < k and abs(r[0] - lon) < k for r in [a['p'][0]])]

def which(lat, lon, cands):
    for a in cands:
        if any(inside(lat, lon, r) for r in a['p']): return a
    return None

def dist_m(la1, lo1, la2, lo2):
    return math.hypot((lo2 - lo1) * math.cos(math.radians(la1)) * 111320, (la2 - la1) * 110540)

def frame(lat, lon, bearing, z, route, name):
    R = int(math.hypot(W, H)) + 40; S = 2 * R
    px, py = world(lat, lon, z)
    base = Image.new('RGB', (S, S), (18, 18, 22))
    for tx in range(int((px - R) // 256), int((px + R) // 256) + 1):
        for ty in range(int((py - R) // 256), int((py + R) // 256) + 1):
            base.paste(tile(z, tx, ty), (int(tx * 256 - px + R), int(ty * 256 - py + R)))
    over = Image.new('RGBA', (S, S), (0, 0, 0, 0)); od = ImageDraw.Draw(over)
    def P(la, lo):
        x, y = world(la, lo, z); return (x - px + R, y - py + R)
    cands = near(lat, lon)
    labels = []
    for a in cands:
        for ring in a['p']:
            pts = [P(ring[i + 1], ring[i]) for i in range(0, len(ring), 2)]
            od.polygon(pts, fill=(230, 40, 40, 70))
            od.line(pts + [pts[0]], fill=(255, 70, 70, 220), width=3)
            cx = sum(p[0] for p in pts) / len(pts); cy = sum(p[1] for p in pts) / len(pts)
            labels.append((cx, cy, a['nome']))
    img = Image.alpha_composite(base.convert('RGBA'), over); d = ImageDraw.Draw(img)
    if route:
        pts = [P(la, lo) for la, lo in route]
        d.line(pts, fill=(11, 42, 91), width=11, joint='curve'); d.line(pts, fill=(61, 139, 255), width=7, joint='curve')
    rot = img.rotate(bearing, center=(R, R), resample=Image.BICUBIC)
    ox, oy = int(R - W * BX), int(R - H * BY)
    crop = rot.crop((ox, oy, ox + W, oy + H)); d = ImageDraw.Draw(crop)
    a = math.radians(-bearing); f = font(14)
    for x, y, nome in labels:  # nomes ficam na horizontal
        x, y = x - R, y - R
        sx = W * BX + x * math.cos(a) - y * math.sin(a); sy = H * BY + x * math.sin(a) + y * math.cos(a)
        if 0 < sx < W and 0 < sy < H and nome:
            tw = d.textlength(nome, font=f)
            d.text((sx - tw / 2, sy - 8), nome, fill=(255, 210, 210), font=f, stroke_width=3, stroke_fill=(40, 0, 0))
    cow(d, W * BX, H * BY)
    here = which(lat, lon, cands)
    if here:
        col = (230, 30, 30)
        for i in range(6): d.rectangle([i, i, W - 1 - i, H - 1 - i], outline=col)
        label = 'COMUNIDADE: ' + here['nome'].upper()
        fb = font(16); tw = d.textlength(label, font=fb)
        d.rounded_rectangle([W - tw - 30, 12, W - 12, 40], radius=8, fill=col)
        d.text((W - tw - 21, 16), label, fill=(255, 255, 255), font=fb)
    crop.convert('RGB').save(out_dir + '/' + name + '.png')
    return here

start = geocode('Estrada do Portela, Madureira, Rio de Janeiro')
dest = geocode('Méier, Rio de Janeiro')
r = json.loads(get('https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson' % (start[1], start[0], dest[1], dest[0])))
route = [(c[1], c[0]) for c in r['routes'][0]['geometry']['coordinates']]
def bearing_at(i):
    la, lo = route[i]; nla, nlo = route[min(len(route) - 1, i + 4)]
    return math.degrees(math.atan2((nlo - lo) * math.cos(math.radians(la)), nla - la)) % 360
inside_i = approach_i = None
for i, (la, lo) in enumerate(route):
    cands = near(la, lo, 1)
    if which(la, lo, cands):
        if inside_i is None: inside_i = i
    elif approach_i is None:
        for a in cands:
            ring = a['p'][0]
            dmin = min(dist_m(la, lo, ring[k + 1], ring[k]) for k in range(0, len(ring), 2))
            if 60 < dmin < 160: approach_i = i; break
print('perto', approach_i, 'dentro', inside_i, 'pontos', len(route))
if approach_i is not None:
    la, lo = route[approach_i]; frame(la, lo, bearing_at(approach_i), 16, route, 'comunidade-perto')
if inside_i is not None:
    la, lo = route[inside_i]; print('dentro de', frame(la, lo, bearing_at(inside_i), 16, route, 'comunidade-dentro')['nome'])
else:
    a = [x for x in com if 'Serrinha' in x['nome']][0]; ring = a['p'][0]
    la, lo = sum(ring[1::2]) / (len(ring) // 2), sum(ring[0::2]) / (len(ring) // 2)
    print('dentro de', (frame(la, lo, 0, 16, None, 'comunidade-dentro') or {}).get('nome'))
