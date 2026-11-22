package br.com.evandro.tripper;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rota desenhada no mapa do painel: destino por busca (OSM Nominatim) ou coordenadas,
 * traçado pelo OSRM. Recalcula quando a moto sai da linha.
 */
final class RouteManager {
    private static final String GEOCODE = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=br&q=";
    private static final String ROUTER = "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson&steps=true";
    private static final double OFF_ROUTE_M = 70;
    private static final long REROUTE_MIN_MS = 20_000;
    private static final long REROUTE_MAX_MS = 160_000;
    private int offStreak;
    private long onLineSince;
    private static final double ARRIVED_M = 40;
    private static final Pattern COORDS = Pattern.compile("(-?\\d{1,2}\\.\\d{3,})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})");

    private final MapPainter map;

    /** Navegação própria: recebe o passo atual (para o painel) e o aviso de chegada. */
    interface Guidance {
        void onStep(NavState state, int remainingUnit);

        void onArrived();
    }

    private volatile Guidance guidance;

    void setGuidance(Guidance g) {
        guidance = g;
    }

    /** Manobras da rota: índice do ponto na linha, código do painel, saída de rotatória, rua. */
    private volatile int[] stepIndex = new int[0];
    private volatile int[] stepCode = new int[0];
    private volatile int[] stepExit = new int[0];
    private volatile String[] stepName = new String[0];
    private volatile double[] cum = new double[0];
    private volatile double totalSeconds;
    private volatile int lastIdx;
    /** Fração do segmento lastIdx já percorrida (para pintar o trecho feito de cinza). */
    private volatile double lastT;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Paint casing = line(0xFF0B2A5B, 11);
    private final Paint blue = line(0xFF3D8BFF, 7);
    private final Paint doneCasing = line(0xFF1E2229, 10);
    private final Paint done = line(0xFF6B7280, 6);

    private volatile double[] lats = new double[0];
    private volatile double[] lons = new double[0];
    private volatile double destLat = Double.NaN;
    private volatile double destLon;
    private volatile String destName = "";
    private volatile long lastRouteAt;
    private volatile boolean routing;

    RouteManager(MapPainter map) {
        this.map = map;
    }

    private static Paint line(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    /** Pontos da rota {lats, lons} para o mapa do celular. */
    double[][] points() {
        return new double[][]{lats, lons};
    }

    /** Identidade da rota atual (muda quando recalcula). */
    Object routeId() {
        return lats;
    }

    /** Ponto da linha mais perto: {metros desde o início, distância até a linha}, ou null. Não mexe no progresso. */
    double[] locate(double lat, double lon) {
        double[] la = lats, lo = lons, cu = cum;
        if (la.length < 2 || cu.length != la.length) return null;
        double k = Math.cos(Math.toRadians(lat)) * 111_320;
        int from = Math.max(0, Math.min(lastIdx, la.length - 2) - 30);
        int to = Math.min(la.length - 1, from + 600);
        double bestD = Double.MAX_VALUE, bestS = 0;
        for (int i = from; i < to; i++) {
            double ax = (lo[i] - lon) * k, ay = (la[i] - lat) * 110_540;
            double bx = (lo[i + 1] - lon) * k, by = (la[i + 1] - lat) * 110_540;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (d < bestD) {
                bestD = d;
                bestS = cu[i] + (cu[i + 1] - cu[i]) * t;
            }
        }
        return new double[]{bestS, bestD};
    }

    /** Ponto a s metros do início da linha: {lat, lon, rumo do trecho}, ou null. */
    double[] pointAt(double s) {
        double[] la = lats, lo = lons, cu = cum;
        int n = la.length;
        if (n < 2 || cu.length != n) return null;
        s = Math.max(0, Math.min(cu[n - 1], s));
        int lo0 = 0, hi = n - 1;
        while (hi - lo0 > 1) {
            int mid = (lo0 + hi) >>> 1;
            if (cu[mid] <= s) lo0 = mid; else hi = mid;
        }
        int i = lo0;
        while (i + 2 < n && cu[i + 1] - cu[i] < 0.01) i++; // pula pontos repetidos
        double len = cu[i + 1] - cu[i];
        double t = len <= 0 ? 0 : (s - cu[i]) / len;
        double lat = la[i] + (la[i + 1] - la[i]) * t, lon = lo[i] + (lo[i + 1] - lo[i]) * t;
        double k = Math.cos(Math.toRadians(lat));
        double brg = Math.toDegrees(Math.atan2((lo[i + 1] - lo[i]) * k, la[i + 1] - la[i]));
        return new double[]{lat, lon, MotionSmoother.norm(brg)};
    }

    /** Até onde a moto já andou na linha: {índice do segmento, fração dele}. */
    double[] progress() {
        return new double[]{lastIdx, lastT};
    }

    boolean hasDestination() {
        return !Double.isNaN(destLat);
    }

    String status() {
        return hasDestination()
                ? String.format(Locale.ROOT, "destino=%s (%.5f,%.5f) pontos=%d", destName, destLat, destLon, lats.length)
                : "sem destino";
    }

    /** Destino em texto livre: coordenadas, link do Maps com coordenadas, ou endereço/lugar. */
    void setDestination(String text, Runnable onReady) {
        setDestination(text, onReady, null);
    }

    /** onFail recebe a explicação para mostrar na tela (não achou, sem internet). */
    void setDestination(String text, Runnable onReady, java.util.function.Consumer<String> onFail) {
        worker.execute(() -> {
            try {
                double[] ll = parseCoords(text);
                String name;
                if (ll == null) {
                    String q = cleanQuery(text);
                    List<PlaceSearch.Suggestion> found = PlaceSearch.search(map::fetch, q, map.hasFix() ? map.lat() : Double.NaN, map.lon());
                    if (!found.isEmpty()) {
                        PlaceSearch.Suggestion s = found.get(0);
                        ll = new double[]{s.lat, s.lon};
                        name = s.detail.isEmpty() ? s.title : s.title + ", " + s.detail;
                    } else {
                        JSONArray res = new JSONArray(new String(map.fetch(GEOCODE + URLEncoder.encode(q, "UTF-8")), "UTF-8"));
                        if (res.length() == 0) {
                            Logger.line("Rota: não achei \"" + q + "\"");
                            if (onFail != null) onFail.accept("Não achei \"" + q + "\". Tente com bairro ou cidade.");
                            return;
                        }
                        JSONObject o = res.getJSONObject(0);
                        ll = new double[]{o.getDouble("lat"), o.getDouble("lon")};
                        name = o.optString("display_name", q);
                    }
                } else {
                    name = String.format(Locale.ROOT, "%.5f,%.5f", ll[0], ll[1]);
                }
                destLat = ll[0];
                destLon = ll[1];
                destName = name.length() > 60 ? name.substring(0, 60) : name;
                Logger.line("Rota: destino " + destName);
                computeRoute();
                if (onReady != null) onReady.run();
            } catch (Exception e) {
                Logger.line("Rota: falhou a busca do destino " + e);
                if (onFail != null) onFail.accept("Sem internet para buscar o endereço. Tente de novo.");
            }
        });
    }

    double[][] line() {
        return new double[][]{lats, lons};
    }

    String destName() {
        return destName;
    }

    /** Destino já com coordenadas e nome (ex.: ponto escolhido no painel). */
    void setDestinationPoint(double la, double lo, String name, Runnable onReady) {
        worker.execute(() -> {
            destLat = la;
            destLon = lo;
            destName = name;
            Logger.line("Rota: destino " + name);
            try {
                computeRoute();
            } catch (Exception e) {
                Logger.line("Rota: falhou o cálculo " + e.getMessage());
            }
            if (onReady != null) onReady.run();
        });
    }

    double destLat() {
        return destLat;
    }

    double destLon() {
        return destLon;
    }

    void clear() {
        destLat = Double.NaN;
        lats = new double[0];
        lons = new double[0];
        offStreak = 0;
        onLineSince = 0;
        Logger.line("Rota: limpa");
    }

    static double[] parseCoords(String text) {
        Matcher m = COORDS.matcher(text);
        if (!m.find()) return null;
        double a = Double.parseDouble(m.group(1)), b = Double.parseDouble(m.group(2));
        if (Math.abs(a) > 90 || Math.abs(b) > 180) return null;
        return new double[]{a, b};
    }

    /** Tira links e linhas vazias do texto compartilhado pelo Maps; fica com o nome/endereço. */
    static String cleanQuery(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("http")) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(l);
        }
        return sb.toString();
    }

    private void computeRoute() throws Exception {
        if (!hasDestination() || !map.hasFix()) {
            Logger.line("Rota: esperando GPS para traçar");
            return;
        }
        routing = true;
        try {
            String url = String.format(Locale.ROOT, ROUTER, map.lon(), map.lat(), destLon, destLat);
            JSONObject res = new JSONObject(new String(map.fetch(url), "UTF-8"));
            JSONArray coords = res.getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
            double[] la = new double[coords.length()];
            double[] lo = new double[coords.length()];
            for (int i = 0; i < coords.length(); i++) {
                JSONArray p = coords.getJSONArray(i);
                lo[i] = p.getDouble(0);
                la[i] = p.getDouble(1);
            }
            double[] cu = new double[la.length];
            for (int i = 1; i < la.length; i++) cu[i] = cu[i - 1] + distance(la[i - 1], lo[i - 1], la[i], lo[i]);
            JSONObject r0 = res.getJSONArray("routes").getJSONObject(0);
            JSONArray steps = r0.getJSONArray("legs").getJSONObject(0).getJSONArray("steps");
            int n = steps.length();
            int[] idx = new int[n], code = new int[n], exit = new int[n];
            String[] names = new String[n];
            int from = 0;
            for (int k = 0; k < n; k++) {
                JSONObject st = steps.getJSONObject(k);
                JSONObject man = st.getJSONObject("maneuver");
                JSONArray loc = man.getJSONArray("location");
                double mlo = loc.getDouble(0), mla = loc.getDouble(1);
                int best = from;
                double bd = Double.MAX_VALUE;
                for (int i = from; i < la.length; i++) {
                    double d = distance(mla, mlo, la[i], lo[i]);
                    if (d < bd) { bd = d; best = i; }
                    if (d < 1) break;
                }
                idx[k] = best;
                from = best;
                code[k] = dashManeuver(man.optString("type"), man.optString("modifier"));
                exit[k] = man.has("exit") ? man.optInt("exit", 255) : 255;
                String name = st.optString("name", "");
                if (name.isEmpty()) name = st.optString("ref", "");
                names[k] = name;
            }
            stepIndex = idx;
            stepCode = code;
            stepExit = exit;
            stepName = names;
            cum = cu;
            totalSeconds = r0.optDouble("duration", 0);
            lastIdx = 0;
            lastT = 0;
            lats = la;
            lons = lo;
            lastRouteAt = System.currentTimeMillis();
            Logger.line("Rota: traçada com " + la.length + " pontos e " + n + " manobras ("
                    + Math.round(r0.optDouble("distance", 0) / 100) / 10.0 + " km)");
        } finally {
            routing = false;
        }
    }

    /** Chamado a cada posição: chegou? saiu da rota? */
    void onPosition(double lat, double lon) {
        if (!hasDestination()) return;
        if (distance(lat, lon, destLat, destLon) < ARRIVED_M) {
            Logger.line("Rota: chegou ao destino");
            clear();
            Guidance g = guidance;
            if (g != null) g.onArrived();
            return;
        }
        guide(lat, lon);
        boolean noLine = lats.length == 0;
        boolean off = !noLine && distanceToLine(lat, lon) > OFF_ROUTE_M;
        if (!noLine && !off) {
            // só zera a espera depois de 30 s seguidos na linha (um ponto de GPS na linha não conta)
            long t = System.currentTimeMillis();
            if (onLineSince == 0) onLineSince = t;
            else if (t - onLineSince > 30_000) offStreak = 0;
        } else {
            onLineSince = 0;
        }
        // Fora da linha de novo logo depois de recalcular (estrada que o mapa não conhece, caminho próprio):
        // espera cada vez mais (20, 40, 80, 160 s) em vez de recalcular a cada 20 s, que esquenta o celular.
        long wait = Math.min(REROUTE_MAX_MS, REROUTE_MIN_MS << Math.min(offStreak, 3));
        if ((noLine || off) && !routing && System.currentTimeMillis() - lastRouteAt > wait) {
            lastRouteAt = System.currentTimeMillis();
            if (off) {
                offStreak++;
                Logger.line("Rota: saiu da linha, recalculando" + (offStreak > 1 ? " (" + offStreak + "ª seguida, próxima em até " + Math.min(REROUTE_MAX_MS, REROUTE_MIN_MS << Math.min(offStreak, 3)) / 1000 + " s)" : ""));
            }
            worker.execute(() -> {
                try {
                    computeRoute();
                } catch (Exception e) {
                    Logger.line("Rota: falhou o cálculo " + e.getMessage());
                }
            });
        }
    }

    /** Calcula a próxima manobra e as distâncias, e entrega ao painel. */
    private void guide(double lat, double lon) {
        Guidance g = guidance;
        double[] la = lats, lo = lons, cu = cum;
        int[] idx = stepIndex;
        if (la.length < 2 || cu.length != la.length || idx.length == 0) return;
        // ponto da linha mais próximo (procura a partir do último, com folga para trás)
        double k = Math.cos(Math.toRadians(lat)) * 111_320;
        int start = Math.max(0, lastIdx - 30);
        int bestSeg = start;
        double bestD = Double.MAX_VALUE, bestT = 0;
        for (int i = start; i + 1 < la.length; i++) {
            double ax = (lo[i] - lon) * k, ay = (la[i] - lat) * 110_540;
            double bx = (lo[i + 1] - lon) * k, by = (la[i + 1] - lat) * 110_540;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (d < bestD) { bestD = d; bestSeg = i; bestT = t; }
        }
        lastIdx = bestSeg;
        lastT = bestT;
        if (g == null) return;
        double done = cu[bestSeg] + (cu[bestSeg + 1] - cu[bestSeg]) * bestT;
        double total = cu[cu.length - 1];
        int next = -1;
        for (int s = 1; s < idx.length; s++) {
            if (cu[idx[s]] > done + 3) { next = s; break; }
        }
        if (next < 0) next = idx.length - 1;
        int toTurn = (int) Math.max(0, cu[idx[next]] - done);
        int left = (int) Math.max(0, total - done);
        int secs = total > 0 ? (int) (totalSeconds * left / total) : 0;
        java.util.Calendar eta = java.util.Calendar.getInstance();
        eta.add(java.util.Calendar.SECOND, secs);
        String etaStr = String.format(Locale.ROOT, "%02d%02d", eta.get(java.util.Calendar.HOUR_OF_DAY), eta.get(java.util.Calendar.MINUTE));
        String street = stepName[next];
        g.onStep(new NavState(stepCode[next], stepCode[next] == 49 || stepCode[next] == 10 ? stepExit[next] : 255,
                toTurn, left, etaStr, NavParser.remainingField(secs), street), NavParser.remainingUnit(secs));
    }

    /**
     * Manobra do OSRM → código do painel. Trânsito pela direita (Brasil):
     * rotatória anti-horária = 49.
     */
    static int dashManeuver(String type, String modifier) {
        boolean left = modifier.contains("left");
        boolean right = modifier.contains("right");
        switch (type) {
            case "arrive":
                return left ? 1 : right ? 2 : 0;
            case "depart":
                return 60;
            case "roundabout":
            case "rotary":
            case "roundabout turn":
            case "exit roundabout":
            case "exit rotary":
                return 49;
            case "merge":
                return left ? 3 : right ? 4 : 27;
            case "fork":
                return left ? 5 : right ? 6 : 9;
            case "off ramp":
                return left ? 7 : right ? 8 : 9;
            case "on ramp":
                return left ? 43 : right ? 44 : 9;
        }
        switch (modifier) {
            case "uturn": return 26;
            case "sharp left": return 22;
            case "sharp right": return 23;
            case "slight left": return "turn".equals(type) ? 24 : 43;
            case "slight right": return "turn".equals(type) ? 25 : 44;
            case "left": return 20;
            case "right": return 21;
            default: return 9;
        }
    }

    private double distanceToLine(double lat, double lon) {
        double best = Double.MAX_VALUE;
        double[] la = lats, lo = lons;
        double k = Math.cos(Math.toRadians(lat)) * 111_320;
        for (int i = 0; i + 1 < la.length; i++) {
            double ax = (lo[i] - lon) * k, ay = (la[i] - lat) * 110_540;
            double bx = (lo[i + 1] - lon) * k, by = (la[i + 1] - lat) * 110_540;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double ex = ax + t * dx, ey = ay + t * dy;
            best = Math.min(best, Math.hypot(ex, ey));
        }
        return best;
    }

    static double distance(double lat1, double lon1, double lat2, double lon2) {
        double k = Math.cos(Math.toRadians((lat1 + lat2) / 2)) * 111_320;
        return Math.hypot((lon2 - lon1) * k, (lat2 - lat1) * 110_540);
    }

    /** Desenha a linha no canvas já transladado para a moto e girado pelo rumo. */
    void draw(Canvas c, int zoom, double px, double py, double world) {
        double[] la = lats, lo = lons;
        if (la.length < 2) return;
        int seg = Math.min(lastIdx, la.length - 2);
        double t = lastT;
        double cutLat = la[seg] + (la[seg + 1] - la[seg]) * t, cutLon = lo[seg] + (lo[seg + 1] - lo[seg]) * t;
        Path feito = new Path(), falta = new Path();
        boolean[] started = {false};
        for (int i = 0; i <= seg; i++) addPoint(feito, started, la[i], lo[i], px, py, world);
        addPoint(feito, started, cutLat, cutLon, px, py, world);
        started[0] = false;
        addPoint(falta, started, cutLat, cutLon, px, py, world);
        for (int i = seg + 1; i < la.length; i++) addPoint(falta, started, la[i], lo[i], px, py, world);
        c.drawPath(feito, doneCasing);
        c.drawPath(feito, done);
        c.drawPath(falta, casing);
        c.drawPath(falta, blue);
    }

    private static void addPoint(Path path, boolean[] started, double lat, double lon, double px, double py, double world) {
        double x = (lon + 180.0) / 360.0 * world - px;
        double s = Math.sin(Math.toRadians(lat));
        double y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * world - py;
        if (Math.abs(x) > 4000 || Math.abs(y) > 4000) {
            started[0] = false;
            return;
        }
        if (!started[0]) {
            path.moveTo((float) x, (float) y);
            started[0] = true;
        } else {
            path.lineTo((float) x, (float) y);
        }
    }
}
