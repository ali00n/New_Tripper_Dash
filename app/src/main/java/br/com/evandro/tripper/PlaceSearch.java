package br.com.evandro.tripper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Busca de endereços com sugestões enquanto digita (Photon, dados do OpenStreetMap, grátis, sem chave).
 * Prioriza o que está perto da moto; só Brasil.
 */
final class PlaceSearch {
    static final String PHOTON = "https://photon.komoot.io/api/?limit=8&q=%s&lat=%.5f&lon=%.5f&location_bias_scale=0.2&zoom=12";

    static final class Suggestion {
        final String title, detail;
        final double lat, lon, meters;
        final boolean isStreet;
        final String number;

        Suggestion(String title, String detail, double lat, double lon, double meters, boolean isStreet, String number) {
            this.title = title;
            this.detail = detail;
            this.lat = lat;
            this.lon = lon;
            this.meters = meters;
            this.isStreet = isStreet;
            this.number = number;
        }

        String distance() {
            return meters < 1000 ? ((int) (meters / 10)) * 10 + " m" : String.format(Locale.ROOT, "%.1f km", meters / 1000).replace('.', ',');
        }
    }

    interface Fetcher {
        byte[] fetch(String url) throws Exception;
    }

    private static final java.util.regex.Pattern NUMBER_AT_END = java.util.regex.Pattern.compile("^(.*?)[,\\s]+(?:n[º°o.]?\\s*)?(\\d{1,5})\\s*$");

    /** "av" vira "avenida", "r." vira "rua", "estr" vira "estrada" (o mapa guarda o nome por extenso). */
    static String expand(String q) {
        String s = " " + q.replaceAll("[^\\p{L}\\p{N}\\s,.ºª°'-]", " ").trim().replaceAll("\\s+", " ") + " ";
        s = s.replaceAll("(?i) av\\.? ", " avenida ").replaceAll("(?i) r\\.? ", " rua ").replaceAll("(?i) estr\\.? ", " estrada ")
                .replaceAll("(?i) pça\\.? | pc\\.? ", " praça ").replaceAll("(?i) tv\\.? ", " travessa ");
        return s.trim();
    }

    static List<Suggestion> search(Fetcher net, String query, double nearLat, double nearLon) throws Exception {
        String q = expand(query);
        if (q.length() < 3) return new ArrayList<>();
        double lat = Double.isNaN(nearLat) ? -22.9068 : nearLat, lon = Double.isNaN(nearLat) ? -43.1729 : nearLon;
        java.util.regex.Matcher m = NUMBER_AT_END.matcher(q);
        if (m.matches() && m.group(1).trim().length() >= 3) {
            // O OpenStreetMap quase não tem número de casa no Brasil: busca a rua e marca o número como aproximado.
            String street = m.group(1).trim(), number = m.group(2);
            List<Suggestion> exact = new ArrayList<>(), approx = new ArrayList<>();
            for (Suggestion sg : fetch(net, street, lat, lon)) {
                if (number.equals(sg.number)) exact.add(sg);
                else if (sg.isStreet) approx.add(new Suggestion(sg.title + ", " + number, "número aproximado · " + sg.detail, sg.lat, sg.lon, sg.meters, false, number));
            }
            exact.addAll(dedupe(approx));
            if (!exact.isEmpty()) return exact;
        }
        return dedupe(fetch(net, q, lat, lon));
    }

    private static List<Suggestion> fetch(Fetcher net, String q, double lat, double lon) throws Exception {
        String url = String.format(Locale.ROOT, PHOTON, URLEncoder.encode(q, "UTF-8"), lat, lon);
        return dropFar(parse(new String(net.fetch(url), "UTF-8"), lat, lon));
    }

    /** Com algum resultado a menos de 100 km, some o que está a mais de 300 km (outro estado). */
    static List<Suggestion> dropFar(List<Suggestion> in) {
        boolean anyNear = false;
        for (Suggestion s : in) anyNear |= s.meters < 100_000;
        if (!anyNear) return in;
        List<Suggestion> out = new ArrayList<>();
        for (Suggestion s : in) if (s.meters <= 300_000) out.add(s);
        return out;
    }

    /** A mesma rua vem em vários pedaços: fica só o mais perto de cada nome+bairro. */
    static List<Suggestion> dedupe(List<Suggestion> in) {
        List<Suggestion> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Suggestion s : in) if (seen.add(s.title.toLowerCase(Locale.ROOT) + "|" + s.detail.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }

    static List<Suggestion> parse(String json, double lat, double lon) throws Exception {
        JSONArray features = new JSONObject(json).optJSONArray("features");
        List<Suggestion> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i);
            JSONObject p = f.optJSONObject("properties");
            JSONArray c = f.optJSONObject("geometry") == null ? null : f.getJSONObject("geometry").optJSONArray("coordinates");
            if (p == null || c == null || c.length() < 2) continue;
            String cc = p.optString("countrycode", "");
            if (!cc.isEmpty() && !"BR".equalsIgnoreCase(cc)) continue;
            double la = c.getDouble(1), lo = c.getDouble(0);
            String street = p.optString("street", ""), number = p.optString("housenumber", "");
            String name = p.optString("name", "");
            String title = !name.isEmpty() ? name : (street + (number.isEmpty() ? "" : ", " + number));
            if (title.trim().isEmpty()) continue;
            List<String> parts = new ArrayList<>();
            if (!name.isEmpty() && !street.isEmpty()) parts.add(street + (number.isEmpty() ? "" : ", " + number));
            for (String k : new String[]{"district", "locality", "city", "state"}) {
                String v = p.optString(k, "");
                if (!v.isEmpty() && !parts.contains(v) && !v.equals(title)) parts.add(v);
                if (parts.size() >= 3) break;
            }
            boolean isStreet = "highway".equals(p.optString("osm_key", "")) && name.length() > 0;
            out.add(new Suggestion(title, String.join(", ", parts), la, lo, RouteManager.distance(lat, lon, la, lo), isStreet, number));
        }
        return out;
    }
}
