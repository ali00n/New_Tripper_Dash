"""Compila os radares do RJ (OpenStreetMap via Overpass: highway=speed_camera) para o app.

Saída: {"fonte":..., "r":[[lat, lon, limite_kmh(0=?), direcao_graus(-1=?)], ...]}
"""
import io, json, sys
src, out = sys.argv[1], sys.argv[2]
CARD = {'N': 0, 'NNE': 22, 'NE': 45, 'ENE': 67, 'E': 90, 'ESE': 112, 'SE': 135, 'SSE': 157,
        'S': 180, 'SSW': 202, 'SW': 225, 'WSW': 247, 'W': 270, 'WNW': 292, 'NW': 315, 'NNW': 337}
els = json.load(io.open(src, encoding='utf-8'))['elements']
r = []
for e in els:
    t = e.get('tags', {})
    if e['type'] != 'node' or t.get('highway') != 'speed_camera': continue
    try: lim = int(''.join(ch for ch in t.get('maxspeed', '') if ch.isdigit()) or 0)
    except ValueError: lim = 0
    d = t.get('direction', t.get('camera:direction', '')).strip().upper()
    if d in CARD: deg = CARD[d]
    else:
        try: deg = int(float(d)) % 360
        except ValueError: deg = -1
    r.append([round(e['lat'], 6), round(e['lon'], 6), lim, deg])
io.open(out, 'w', encoding='utf-8').write(json.dumps({'fonte': 'OpenStreetMap (ODbL), highway=speed_camera, RJ', 'r': r}, separators=(',', ':')))
print('radares', len(r), 'com limite', sum(1 for x in r if x[2]), 'com direcao', sum(1 for x in r if x[3] >= 0))
