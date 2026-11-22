"""Compila os limites oficiais de favelas (Prefeitura do Rio, Data.Rio, Lim_Favelas_2022) para o app."""
import io, json, math, sys
src, out = sys.argv[1], sys.argv[2]
sys.setrecursionlimit(100000)

def simplify(pts, eps):
    if len(pts) < 3: return pts
    a, b = pts[0], pts[-1]; dmax, idx = 0, 0
    for i in range(1, len(pts) - 1):
        p = pts[i]; dx, dy = b[0] - a[0], b[1] - a[1]
        d = math.hypot(p[0] - a[0], p[1] - a[1]) if dx == dy == 0 else abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / math.hypot(dx, dy)
        if d > dmax: dmax, idx = d, i
    return simplify(pts[:idx + 1], eps)[:-1] + simplify(pts[idx:], eps) if dmax > eps else [a, b]

g = json.load(io.open(src, encoding='utf-8'))
areas = []
for f in g['features']:
    geom = f['geometry']
    if not geom: continue
    polys = geom['coordinates'] if geom['type'] == 'MultiPolygon' else [geom['coordinates']]
    rings = []
    for poly in polys:
        s = simplify([tuple(p[:2]) for p in poly[0]], 0.00008)  # ~8 m
        if len(s) >= 4: rings.append([round(v, 5) for xy in s for v in xy])
    if rings: areas.append({'nome': f['properties'].get('nome') or '', 'p': rings})
io.open(out, 'w', encoding='utf-8').write(json.dumps({'fonte': 'Prefeitura do Rio, Data.Rio: Limite Favelas 2022', 'areas': areas}, separators=(',', ':'), ensure_ascii=False))
print('comunidades', len(areas))
