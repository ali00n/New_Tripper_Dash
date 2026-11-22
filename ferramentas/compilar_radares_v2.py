"""Radares (OSM highway=speed_camera) encaixados na pista, para qualquer estado.

Uso: python compilar_radares_v2.py saida.json RJ SP MG ...   (ou BR para todos)
Para cada radar: acha o trecho de rua mais próximo (até 45 m), encaixa o ponto nele e grava
  [lat, lon, limite_kmh(0=?), sentido_graus(-1=?), eixo_graus(-1=?)]
sentido: tag direction do radar, ou sentido da rua se for mão única (oneway=yes/-1).
eixo: direção da rua (0-180): o app só avisa quem anda AO LONGO da rua, nunca quem cruza.
"""
import json, math, sys, time, urllib.request, urllib.parse
UF = ['AC','AL','AP','AM','BA','CE','DF','ES','GO','MA','MT','MS','MG','PA','PB','PR','PE','PI','RJ','RN','RS','RO','RR','SC','SP','SE','TO']
CARD = {'N':0,'NNE':22,'NE':45,'ENE':67,'E':90,'ESE':112,'SE':135,'SSE':157,'S':180,'SSW':202,'SW':225,'WSW':247,'W':270,'WNW':292,'NW':315,'NNW':337}
HW = 'motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|living_street'
SERVERS = ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter']

def fetch(uf):
    q = f'[out:json][timeout:240];area["ISO3166-2"="BR-{uf}"]->.a;node["highway"="speed_camera"](area.a)->.c;.c out body;way(around.c:45)["highway"~"^({HW})$"];out geom;'
    for tent in range(4):
        url = SERVERS[tent % len(SERVERS)]
        try:
            r = urllib.request.Request(url, data=urllib.parse.urlencode({'data': q}).encode(), headers={'User-Agent': 'TripperDashPessoal/1.0 (uso pessoal)'})
            return json.loads(urllib.request.urlopen(r, timeout=300).read())['elements']
        except Exception as e:
            print(uf, 'tentativa', tent + 1, 'falhou:', e, flush=True); time.sleep(10)
    return None

def bearing(a, b):
    dy = (b[0] - a[0]) * 110540; dx = (b[1] - a[1]) * 111320 * math.cos(math.radians(a[0]))
    return (math.degrees(math.atan2(dx, dy)) + 360) % 360

def project(p, a, b):
    k = math.cos(math.radians(p[0])) * 111320
    ax, ay = (a[1] - p[1]) * k, (a[0] - p[0]) * 110540
    bx, by = (b[1] - p[1]) * k, (b[0] - p[0]) * 110540
    dx, dy = bx - ax, by - ay
    L = dx * dx + dy * dy
    t = 0 if L == 0 else max(0, min(1, -(ax * dx + ay * dy) / L))
    ex, ey = ax + t * dx, ay + t * dy
    return math.hypot(ex, ey), (p[0] + ey / 110540, p[1] + ex / k)

def process(els):
    cams = [e for e in els if e['type'] == 'node' and e.get('tags', {}).get('highway') == 'speed_camera']
    ways = [e for e in els if e['type'] == 'way' and 'geometry' in e]
    out = []; snapped = 0; withdir = 0
    for c in cams:
        t = c.get('tags', {}); p = (c['lat'], c['lon'])
        try: lim = int(''.join(ch for ch in t.get('maxspeed', '') if ch.isdigit()) or 0)
        except ValueError: lim = 0
        best = None
        for w in ways:
            g = [(n['lat'], n['lon']) for n in w['geometry']]
            for i in range(len(g) - 1):
                d, q = project(p, g[i], g[i + 1])
                if best is None or d < best[0]: best = (d, q, g[i], g[i + 1], w.get('tags', {}))
        if lim == 0 and best and best[0] <= 45:
            try: lim = int(''.join(ch for ch in best[4].get('maxspeed', '') if ch.isdigit()) or 0)
            except ValueError: pass
        dtag = t.get('direction', t.get('camera:direction', '')).strip().upper()
        if dtag in CARD: sentido = CARD[dtag]
        else:
            try: sentido = int(float(dtag)) % 360
            except ValueError: sentido = -1
        eixo = -1; lat, lon = p
        if best and best[0] <= 45:
            snapped += 1; lat, lon = best[1]
            seg = bearing(best[2], best[3]); eixo = round(seg % 180)
            ow = best[4].get('oneway', '')
            if sentido < 0 and dtag in ('FORWARD', 'BACKWARD'): sentido = round(seg if dtag == 'FORWARD' else (seg + 180) % 360)
            if sentido < 0 and (ow in ('yes', '1', 'true') or best[4].get('highway') in ('motorway', 'motorway_link')): sentido = round(seg)
            if sentido < 0 and ow == '-1': sentido = round((seg + 180) % 360)
        if sentido >= 0: withdir += 1
        out.append([round(lat, 6), round(lon, 6), lim, sentido, eixo])
    return out, len(cams), snapped, withdir

if __name__ == '__main__':
    saida = sys.argv[1]; ufs = UF if sys.argv[2:] == ['BR'] else sys.argv[2:]
    todos = []; resumo = {}
    for uf in ufs:
        els = fetch(uf)
        if els is None: resumo[uf] = 'FALHOU'; continue
        r, n, s, d = process(els)
        todos += r; resumo[uf] = f'{n} radares, {s} na pista, {d} com sentido'
        print(uf, resumo[uf], flush=True); time.sleep(2)
    json.dump({'fonte': 'OpenStreetMap (ODbL), highway=speed_camera, encaixado na pista', 'gerado': time.strftime('%Y-%m-%d'),
               'formato': '[lat, lon, limite, sentido, eixo]', 'r': todos}, open(saida, 'w', encoding='utf-8'), separators=(',', ':'))
    print('TOTAL', len(todos), json.dumps(resumo, ensure_ascii=False))
