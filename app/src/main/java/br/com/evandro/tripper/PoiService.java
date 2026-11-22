package br.com.evandro.tripper;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pontos próximos pedidos pelo menu do painel (09 03 [AA, tipo]) e escolha (09 03 [índice, tipo]).
 * Tipos: 10 posto, 11 restaurante, 12 Royal Enfield, 13 estacionamento, 20 favoritos, 30 recentes.
 * Busca no OpenStreetMap (Overpass); recentes ficam salvos no celular.
 */
final class PoiService {
    static final class Place {
        final double lat, lon;
        final String label;

        Place(double lat, double lon, String label) {
            this.lat = lat;
            this.lon = lon;
            this.label = label;
        }
    }

    interface Sink {
        void send(String label, List<DashProtocol.Tlv> tlvs);
    }

    private static final int MAX = 5;
    private final MapPainter map;
    private final SharedPreferences prefs;
    private final Sink sink;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, List<Place>> lists = new HashMap<>();

    PoiService(MapPainter map, SharedPreferences prefs, Sink sink) {
        this.map = map;
        this.prefs = prefs;
        this.sink = sink;
    }

    /** O painel pediu a lista de um tipo. */
    void onRequest(int type) {
        worker.execute(() -> {
            List<Place> found;
            try {
                found = type == 30 || type == 20 ? recents() : search(type);
            } catch (Exception e) {
                Logger.line("POI: falha na busca " + e.getMessage());
                found = Collections.emptyList();
            }
            lists.put(type, found);
            sendList(type, found);
        });
    }

    /** Busca para a tela do app (sem mandar ao painel). */
    void find(int type, java.util.function.Consumer<List<Place>> callback) {
        worker.execute(() -> {
            List<Place> found;
            try {
                found = type == 30 || type == 20 ? recents() : search(type);
            } catch (Exception e) {
                Logger.line("POI: falha na busca " + e.getMessage());
                found = Collections.emptyList();
            }
            callback.accept(found);
        });
    }

    /** O painel escolheu o item [index] da lista [type]. Retorna o lugar ou null. */
    Place onChoice(int type, int index) {
        List<Place> l = lists.get(type);
        if (l == null || index < 0 || index >= l.size()) {
            Logger.line("POI: escolha " + index + " fora da lista do tipo " + type);
            return null;
        }
        Place p = l.get(index);
        Logger.line("POI: painel escolheu " + p.label);
        return p;
    }

    private void sendList(int type, List<Place> places) {
        sink.send("POI: lista tipo " + type + " (05 2E)", List.of(DashProtocol.Tlv.ofByte(5, 0x2E, type)));
        List<DashProtocol.Tlv> items = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < MAX; i++) {
            if (i < places.size()) {
                items.add(DashProtocol.Tlv.ofText(5, 0x2F + i, places.get(i).label, 19));
                names.append(places.get(i).label).append(" | ");
            } else {
                items.add(DashProtocol.Tlv.ofByte(5, 0x2F + i, 0));
            }
        }
        sink.send("POI: " + places.size() + " itens " + names, items);
    }

    private List<Place> search(int type) throws Exception {
        if (!map.hasFix()) throw new IllegalStateException("sem GPS");
        double lat = map.lat(), lon = map.lon();
        String param;
        switch (type) {
            case 10: param = "amenity=fuel"; break;
            case 11: param = "amenity=restaurant"; break;
            case 12: param = "q=" + URLEncoder.encode("Royal Enfield", "UTF-8"); break;
            case 13: param = "amenity=parking"; break;
            default: return Collections.emptyList();
        }
        // Círculos crescentes: para quando acha 5 (os mais próximos de verdade).
        double[] spans = type == 12 ? new double[]{0.6, 2.5} : new double[]{0.015, 0.035, 0.08};
        JSONArray els = new JSONArray();
        for (double span : spans) {
            String url = String.format(Locale.ROOT,
                    "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=40&bounded=1&countrycodes=br&%s&viewbox=%.5f,%.5f,%.5f,%.5f",
                    param, lon - span, lat + span, lon + span, lat - span);
            els = new JSONArray(new String(map.fetch(url), "UTF-8"));
            if (els.length() >= MAX) break;
        }
        List<double[]> pts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            double la = e.optDouble("lat", Double.NaN), lo = e.optDouble("lon", Double.NaN);
            if (Double.isNaN(la)) continue;
            String name = e.optString("name", "");
            if (name.isEmpty()) name = type == 10 ? "Posto" : type == 13 ? "Estacionamento" : "Sem nome";
            pts.add(new double[]{la, lo, RouteManager.distance(lat, lon, la, lo)});
            names.add(name);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(pts.get(a)[2], pts.get(b)[2]));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX, order.size()); i++) {
            double[] p = pts.get(order.get(i));
            out.add(new Place(p[0], p[1], label(names.get(order.get(i)), p[2])));
        }
        Logger.line("POI: tipo " + type + ", " + pts.size() + " achados, mandando " + out.size());
        return out;
    }

    /** "Nome 1.2km" em até 19 caracteres, como o painel mostra. */
    static String label(String name, double meters) {
        String d = meters < 1000 ? ((int) (meters / 10)) * 10 + "m" : String.format(Locale.ROOT, "%.1fkm", meters / 1000);
        String n = name.trim();
        int room = 18 - d.length();
        if (n.length() > room) n = n.substring(0, room).trim();
        return n + " " + d;
    }

    // ---- recentes ----
    void addRecent(String name, double lat, double lon) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("recentes", "[]"));
            JSONArray out = new JSONArray();
            out.put(new JSONArray().put(name).put(lat).put(lon));
            for (int i = 0; i < arr.length() && out.length() < MAX; i++) {
                JSONArray r = arr.getJSONArray(i);
                if (RouteManager.distance(lat, lon, r.getDouble(1), r.getDouble(2)) > 100) out.put(r);
            }
            prefs.edit().putString("recentes", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private List<Place> recents() throws Exception {
        JSONArray arr = new JSONArray(prefs.getString("recentes", "[]"));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray r = arr.getJSONArray(i);
            String n = r.getString(0);
            out.add(new Place(r.getDouble(1), r.getDouble(2), n.length() > 19 ? n.substring(0, 19) : n));
        }
        return out;
    }
}
