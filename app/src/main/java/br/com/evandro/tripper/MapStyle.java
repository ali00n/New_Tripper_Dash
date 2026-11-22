package br.com.evandro.tripper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Estilo "desenhado" aprovado em 19/11 (prints do Méier): fundo escuro, áreas apagadas,
 * prédios com sombra, ruas com contorno, nomes só onde cabem, ícones de posto/hospital/farmácia/polícia.
 * Dados: OpenFreeMap (esquema OpenMapTiles), grátis e sem chave.
 */
final class MapStyle {
    private MapStyle() {
    }

    static final String TILES = "https://tiles.openfreemap.org/planet";
    static final String GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf";

    static String json() {
        StringBuilder s = new StringBuilder();
        s.append("{\"version\":8,\"name\":\"Tripper desenhado\",")
                .append("\"glyphs\":\"").append(GLYPHS).append("\",")
                .append("\"sources\":{\"omt\":{\"type\":\"vector\",\"url\":\"").append(TILES).append("\"}},")
                .append("\"layers\":[");
        layer(s, "{\"id\":\"fundo\",\"type\":\"background\",\"paint\":{\"background-color\":\"#14171c\"}}");
        fill(s, "residencial", "landuse", "[\"in\",\"class\",\"residential\",\"suburb\",\"neighbourhood\"]", "#181c22", 0);
        fill(s, "comercio", "landuse", "[\"in\",\"class\",\"commercial\",\"retail\"]", "#1f1b24", 0);
        fill(s, "industria", "landuse", "[\"==\",\"class\",\"industrial\"]", "#1c1c1e", 0);
        fill(s, "escola", "landuse", "[\"in\",\"class\",\"school\",\"university\",\"college\"]", "#221e18", 0);
        fill(s, "hospital-area", "landuse", "[\"==\",\"class\",\"hospital\"]", "#28181c", 0);
        fill(s, "verde", "landcover", "[\"in\",\"class\",\"grass\",\"wood\",\"farmland\"]", "#15281d", 0);
        fill(s, "parque", "park", null, "#15281d", 0);
        fill(s, "campo", "landuse", "[\"in\",\"class\",\"pitch\",\"stadium\",\"playground\"]", "#1a3424", 0);
        fill(s, "agua", "water", null, "#102638", 0);
        layer(s, "{\"id\":\"predio-sombra\",\"type\":\"fill\",\"source\":\"omt\",\"source-layer\":\"building\",\"minzoom\":15.5,"
                + "\"paint\":{\"fill-color\":\"#0b0d10\",\"fill-translate\":[1.4,1.8]}}");
        layer(s, "{\"id\":\"predio\",\"type\":\"fill\",\"source\":\"omt\",\"source-layer\":\"building\",\"minzoom\":15.5,"
                + "\"paint\":{\"fill-color\":[\"interpolate\",[\"linear\"],[\"zoom\"],15.5,\"#21262e\",17,\"#282e38\"],"
                + "\"fill-outline-color\":\"#363d49\"}}");
        layer(s, "{\"id\":\"trilho\",\"type\":\"line\",\"source\":\"omt\",\"source-layer\":\"transportation\","
                + "\"filter\":[\"==\",\"class\",\"rail\"],\"paint\":{\"line-color\":\"#464c58\",\"line-width\":1.6}}");
        // ruas: contorno escuro por baixo, miolo por cima; largura cresce com o zoom
        // 3 categorias (aprovadas 19/11): local cinza, principal clara, expressa âmbar
        String[][] roads = {
                // classe(s), cor, largura em z17, zoom mínimo
                {"[\"in\",\"class\",\"minor\",\"service\",\"tertiary\"]", "#5c636f", "5", "13.5"},
                {"[\"in\",\"class\",\"primary\",\"secondary\"]", "#f2e3a0", "7.5", "9"},
                {"[\"in\",\"class\",\"motorway\",\"trunk\"]", "#de9c40", "10", "6"},
        };
        for (int pass = 0; pass < 2; pass++) {
            for (String[] r : roads) {
                double w = Double.parseDouble(r[2]);
                String id = (pass == 0 ? "contorno-" : "rua-") + r[0].replaceAll("[^a-z]", "").replace("inclass", "").replace("class", "");
                String width = "[\"interpolate\",[\"exponential\",1.6],[\"zoom\"],12," + fmt(w * 0.18 + (pass == 0 ? 1 : 0))
                        + ",15," + fmt(w * 0.55 + (pass == 0 ? 2 : 0)) + ",17," + fmt(w + (pass == 0 ? 2.6 : 0)) + ",19," + fmt(w * 2.4 + (pass == 0 ? 3 : 0)) + "]";
                layer(s, "{\"id\":\"" + id + "\",\"type\":\"line\",\"source\":\"omt\",\"source-layer\":\"transportation\",\"minzoom\":" + r[3] + ","
                        + "\"filter\":[\"all\",[\"==\",\"$type\",\"LineString\"]," + r[0] + "],"
                        + "\"layout\":{\"line-cap\":\"round\",\"line-join\":\"round\"},"
                        + "\"paint\":{\"line-color\":\"" + (pass == 0 ? "#090a0d" : r[1]) + "\",\"line-width\":" + width + "}}");
            }
        }
        // nomes de rua ao longo da linha (o MapLibre descarta o que colide)
        layer(s, "{\"id\":\"nome-rua\",\"type\":\"symbol\",\"source\":\"omt\",\"source-layer\":\"transportation_name\",\"minzoom\":14,"
                + "\"filter\":[\"in\",\"class\",\"motorway\",\"trunk\",\"primary\",\"secondary\",\"tertiary\",\"minor\"],"
                + "\"layout\":{\"symbol-placement\":\"line\",\"text-field\":\"{name}\",\"text-font\":[\"Noto Sans Bold\"],"
                + "\"text-size\":[\"interpolate\",[\"linear\"],[\"zoom\"],14,10,17,13,19,15],\"symbol-spacing\":350,\"text-padding\":12},"
                + "\"paint\":{\"text-color\":\"#d6dbe4\",\"text-halo-color\":\"#101317\",\"text-halo-width\":2.2}}");
        // ícones
        layer(s, "{\"id\":\"pontos\",\"type\":\"symbol\",\"source\":\"omt\",\"source-layer\":\"poi\",\"minzoom\":15.5,"
                + "\"filter\":[\"in\",\"class\",\"fuel\",\"hospital\",\"police\"],"
                + "\"layout\":{\"icon-image\":\"{class}\",\"icon-size\":1,\"icon-padding\":14,\"icon-allow-overlap\":false}}");
        layer(s, "{\"id\":\"farmacias\",\"type\":\"symbol\",\"source\":\"omt\",\"source-layer\":\"poi\",\"minzoom\":16.5,"
                + "\"filter\":[\"==\",\"class\",\"pharmacy\"],"
                + "\"layout\":{\"icon-image\":\"pharmacy\",\"icon-size\":1,\"icon-padding\":14}}");
        layer(s, "{\"id\":\"bairro\",\"type\":\"symbol\",\"source\":\"omt\",\"source-layer\":\"place\",\"maxzoom\":15.5,"
                + "\"filter\":[\"in\",\"class\",\"suburb\",\"neighbourhood\",\"town\",\"city\"],"
                + "\"layout\":{\"text-field\":\"{name}\",\"text-font\":[\"Noto Sans Bold\"],\"text-size\":13,\"text-transform\":\"uppercase\",\"text-letter-spacing\":0.08},"
                + "\"paint\":{\"text-color\":\"#8d96a5\",\"text-halo-color\":\"#101317\",\"text-halo-width\":2}}");
        s.setLength(s.length() - 1);
        s.append("]}");
        return s.toString();
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static void fill(StringBuilder s, String id, String layer, String filter, String color, double minzoom) {
        layer(s, "{\"id\":\"" + id + "\",\"type\":\"fill\",\"source\":\"omt\",\"source-layer\":\"" + layer + "\","
                + (filter != null ? "\"filter\":" + filter + "," : "") + "\"paint\":{\"fill-color\":\"" + color + "\"}}");
    }

    private static void layer(StringBuilder s, String json) {
        s.append(json).append(',');
    }

    // ---- ícones desenhados por nós (mesmos dos prints) ----

    static Bitmap icon(String kind, float density) {
        int size = Math.round(22 * density);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(size / 22f, size / 22f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(12, 14, 18));
        c.drawCircle(11, 11, 11, p);
        Paint w = new Paint(Paint.ANTI_ALIAS_FLAG);
        w.setColor(Color.WHITE);
        switch (kind) {
            case "fuel": {
                p.setColor(Color.rgb(240, 140, 40));
                c.drawCircle(11, 11, 9, p);
                Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
                d.setColor(Color.rgb(25, 20, 15));
                c.drawRoundRect(new RectF(7, 6, 13, 16), 1, 1, d);
                c.drawRect(8, 7, 12, 10, p);
                d.setStyle(Paint.Style.STROKE);
                d.setStrokeWidth(1.5f);
                Path hose = new Path();
                hose.moveTo(13, 9);
                hose.lineTo(16, 12);
                hose.lineTo(16, 15);
                c.drawPath(hose, d);
                break;
            }
            case "hospital":
            case "pharmacy":
                p.setColor(kind.equals("hospital") ? Color.rgb(225, 60, 70) : Color.rgb(60, 185, 110));
                c.drawRoundRect(new RectF(2, 2, 20, 20), 4, 4, p);
                c.drawRect(9.4f, 5.5f, 12.6f, 16.5f, w);
                c.drawRect(5.5f, 9.4f, 16.5f, 12.6f, w);
                break;
            default: { // polícia
                p.setColor(Color.rgb(60, 110, 220));
                c.drawCircle(11, 11, 9, p);
                Path sh = new Path();
                sh.moveTo(11, 5);
                sh.lineTo(16, 8);
                sh.lineTo(15, 14);
                sh.lineTo(11, 17);
                sh.lineTo(7, 14);
                sh.lineTo(6, 8);
                sh.close();
                c.drawPath(sh, w);
            }
        }
        return b;
    }

    static final String[] ICONS = {"fuel", "hospital", "pharmacy", "police"};
}
