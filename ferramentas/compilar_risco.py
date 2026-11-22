"""Compila a camada de risco do RJ para o app (dados oficiais ISP-RJ por CISP).

Entrada: BaseDPEvolucaoMensalCisp.csv e cisp.kml (ispdados.rj.gov.br/Arquivos).
Saída: risco.json compacto com polígonos simplificados e nível 0..3 por área.
"""
import csv, io, json, math, re, sys

csv_path, kml_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

rows = list(csv.DictReader(io.open(csv_path, encoding='latin1'), delimiter=';'))
def ym(r): return int(r['ano']) * 12 + int(r['mes'])
last = max(ym(r) for r in rows)
first = last - 11
def num(v):
    try: return float(v)
    except: return 0.0
agg = {}
for r in rows:
    if not (first <= ym(r) <= last): continue
    c = int(float(r['cisp']))
    a = agg.setdefault(c, {'rv': 0, 'let': 0, 'rua': 0, 'munic': r['munic']})
    a['rv'] += num(r['roubo_veiculo'])
    a['let'] += num(r['letalidade_violenta'])
    a['rua'] += num(r['roubo_rua'])

kml = io.open(kml_path, encoding='utf-8').read()
def simplify(pts, eps):
    if len(pts) < 3: return pts
    a, b = pts[0], pts[-1]
    dmax, idx = 0, 0
    for i in range(1, len(pts) - 1):
        p = pts[i]
        dx, dy = b[0] - a[0], b[1] - a[1]
        if dx == 0 and dy == 0: d = math.hypot(p[0] - a[0], p[1] - a[1])
        else: d = abs(dy * p[0] - dx * p[1] + b[0] * a[1] - b[1] * a[0]) / math.hypot(dx, dy)
        if d > dmax: dmax, idx = d, i
    if dmax > eps:
        return simplify(pts[:idx + 1], eps)[:-1] + simplify(pts[idx:], eps)
    return [a, b]

sys.setrecursionlimit(100000)
areas = []
for pm in re.findall(r'<Placemark.*?</Placemark>', kml, re.S):
    cisp = int(float(re.search(r'name="CISP">([^<]+)<', pm).group(1)))
    km2 = float(re.search(r'name="areakm2">([^<]+)<', pm).group(1) or 0)
    rings = []
    for coords in re.findall(r'<outerBoundaryIs><LinearRing><coordinates>(.*?)</coordinates>', pm, re.S):
        pts = [tuple(map(float, t.split(',')[:2])) for t in coords.split()]
        s = simplify(pts, 0.0003)  # ~30 m
        if len(s) >= 4: rings.append(s)
    st = agg.get(cisp)
    if not st or not rings or km2 <= 0: continue
    score = (st['rv'] * 3 + st['let'] * 2 + st['rua']) / km2
    areas.append({'cisp': cisp, 'km2': km2, 'score': score, **st, 'rings': rings})

scores = sorted(a['score'] for a in areas)
def q(p): return scores[min(len(scores) - 1, int(p * len(scores)))]
cuts = [q(0.40), q(0.70), q(0.90)]
for a in areas:
    a['nivel'] = sum(a['score'] >= c for c in cuts)

out = {
    'fonte': 'ISP-RJ (ispdados.rj.gov.br), BaseDPEvolucaoMensalCisp + cisp.kml',
    'periodo': '%d-%02d a %d-%02d' % ((first - 1) // 12, (first - 1) % 12 + 1, (last - 1) // 12, (last - 1) % 12 + 1),
    'formula': '(3*roubo_veiculo + 2*letalidade_violenta + roubo_rua) por km2, 12 meses; nivel por quantis 40/70/90%',
    'areas': [{'c': a['cisp'], 'n': a['nivel'], 'rv': int(a['rv']), 'l': int(a['let']), 'r': int(a['rua']),
               'p': [[round(v, 5) for xy in ring for v in xy] for ring in a['rings']]} for a in areas],
}
io.open(out_path, 'w', encoding='utf-8').write(json.dumps(out, separators=(',', ':'), ensure_ascii=False))
print('periodo', out['periodo'], 'areas', len(areas), 'cortes', [round(c, 1) for c in cuts])
top = sorted(areas, key=lambda a: -a['score'])[:12]
for a in top: print('CISP %3d %-22s nivel %d  roubo_veic %4d  letal %3d  rua %5d  km2 %.1f' % (a['cisp'], a['munic'][:22], a['nivel'], a['rv'], a['let'], a['rua'], a['km2']))
