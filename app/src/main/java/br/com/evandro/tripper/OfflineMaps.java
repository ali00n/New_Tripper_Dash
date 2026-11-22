package br.com.evandro.tripper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.maplibre.android.MapLibre;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.offline.OfflineRegion;
import org.maplibre.android.offline.OfflineRegionError;
import org.maplibre.android.offline.OfflineRegionStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Mapa sem internet: baixa uma região inteira (ruas, prédios, nomes, letras) para o banco do MapLibre.
 * O mapa do painel e o do celular usam o mesmo banco: com a região baixada, funcionam no 4G morto.
 * Os dados do OpenFreeMap vão até o zoom 14; acima disso o MapLibre amplia o mesmo dado (fica nítido, é vetor).
 */
final class OfflineMaps {
    /** Um estado: contorno oficial do IBGE (assets/estados.json) e zoom máximo (13 nos estados grandes). */
    static final class Area {
        final String id, name;
        final int maxZoom;
        final double km2;
        final String geometryJson;

        Area(String id, String name, int maxZoom, double km2, String geometryJson) {
            this.id = id;
            this.name = name;
            this.maxZoom = maxZoom;
            this.km2 = km2;
            this.geometryJson = geometryJson;
        }

        org.maplibre.geojson.Geometry geometry() {
            return geometryJson.contains("MultiPolygon") ? org.maplibre.geojson.MultiPolygon.fromJson(geometryJson)
                    : org.maplibre.geojson.Polygon.fromJson(geometryJson);
        }

        /** Ponto dentro do contorno (para sugerir o estado onde a pessoa está). */
        boolean contains(double lat, double lon) {
            try {
                org.json.JSONObject g = new org.json.JSONObject(geometryJson);
                org.json.JSONArray c = g.getJSONArray("coordinates");
                boolean multi = g.getString("type").equals("MultiPolygon");
                for (int p = 0; p < (multi ? c.length() : 1); p++) {
                    org.json.JSONArray ring = (multi ? c.getJSONArray(p) : c).getJSONArray(0);
                    boolean in = false;
                    for (int i = 0, j = ring.length() - 1; i < ring.length(); j = i++) {
                        double xi = ring.getJSONArray(i).getDouble(0), yi = ring.getJSONArray(i).getDouble(1);
                        double xj = ring.getJSONArray(j).getDouble(0), yj = ring.getJSONArray(j).getDouble(1);
                        if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
                    }
                    if (in) return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private static volatile java.util.List<Area> areas;

    /** Os 27 estados, em ordem alfabética. */
    static java.util.List<Area> areas(Context c) {
        if (areas != null) return areas;
        java.util.List<Area> list = new java.util.ArrayList<>();
        try (java.io.InputStream in = c.getAssets().open("estados.json")) {
            org.json.JSONArray arr = new org.json.JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("estados");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                list.add(new Area(o.getString("uf"), o.getString("nome"), o.getInt("zmax"), o.getDouble("km2"), o.getJSONObject("geometria").toString()));
            }
        } catch (Exception e) {
            Logger.line("Mapa sem internet: lista de estados falhou " + e);
        }
        areas = list;
        return list;
    }

    /** Estado onde a moto está agora (ou null). */
    static Area here(Context c, double lat, double lon) {
        if (Double.isNaN(lat)) return null;
        for (Area a : areas(c)) if (a.contains(lat, lon)) return a;
        return null;
    }

    /** Download grande: só no Wi-Fi (rede sem cobrança por uso). */
    static boolean onWifi(Context c) {
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        return cm != null && !cm.isActiveNetworkMetered();
    }

    /** Wi-Fi com internet de verdade (não o Wi-Fi do painel, que não tem internet). */
    static boolean onInternetWifi(Context c) {
        android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return false;
        android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile String progress = "";

    static String progress() {
        return progress;
    }

    /** Estilo gravado em arquivo: o download offline precisa de um endereço de estilo. */
    static String styleUrl(Context c) throws Exception {
        File f = new File(c.getFilesDir(), "estilo-mapa.json");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(MapStyle.json().getBytes(StandardCharsets.UTF_8));
        }
        return "file://" + f.getAbsolutePath();
    }

    /** Baixa (ou continua baixando) a área; o progresso sai no log e em progress(). */
    /**
     * Estilo publicado na internet para o download. 22/11: com o estilo em file:// o download ficava
     * em 0% para sempre (o baixador do MapLibre só busca recursos pela rede). É o mesmo MapStyle.json().
     */
    static final String REMOTE_STYLE = "https://raw.githubusercontent.com/evandroreis01/CowTripperDash/main/mapa/estilo.json";

    static void download(Context context, Area area, java.util.function.Consumer<String> onProgress) {
        download(context, area, REMOTE_STYLE, onProgress);
    }

    static void download(Context context, Area area, String style, java.util.function.Consumer<String> onProgress) {
        Context app = context.getApplicationContext();
        main.post(() -> {
            try {
                MapLibre.getInstance(app);
                OfflineManager om = OfflineManager.getInstance(app);
                om.setOfflineMapboxTileCountLimit(Long.MAX_VALUE);
                String styleUrl = style == null || style.isEmpty() ? styleUrl(app) : style;
                removeUnfinished(om, area);
                org.maplibre.android.offline.OfflineGeometryRegionDefinition def =
                        new org.maplibre.android.offline.OfflineGeometryRegionDefinition(styleUrl, area.geometry(), 0, area.maxZoom, 1.5f, false);
                Logger.line("Mapa sem internet: estilo " + styleUrl);
                om.createOfflineRegion(def, area.id.getBytes(StandardCharsets.UTF_8), new OfflineManager.CreateOfflineRegionCallback() {
                    @Override
                    public void onCreate(OfflineRegion region) {
                        region.setObserver(new OfflineRegion.OfflineRegionObserver() {
                            long lastLog;

                            @Override
                            public void onStatusChanged(OfflineRegionStatus s) {
                                long req = Math.max(1, s.getRequiredResourceCount());
                                int pct = (int) (100 * s.getCompletedResourceCount() / req);
                                String msg = area.name + " (" + area.id + "): " + (s.isComplete() ? "pronto, " : pct + "%, ")
                                        + (s.getCompletedResourceSize() / (1024 * 1024)) + " MB ("
                                        + s.getCompletedResourceCount() + " de " + s.getRequiredResourceCount()
                                        + (s.isRequiredResourceCountPrecise() ? "" : "+") + " pedaços)";
                                progress = msg;
                                if (onProgress != null) onProgress.accept(msg);
                                if (s.isComplete()) {
                                    region.setDownloadState(OfflineRegion.STATE_INACTIVE);
                                    // 22/11: erro 404 no estilo terminava "pronto, 0 MB" e marcava como baixado para sempre.
                                    boolean real = s.getCompletedResourceCount() > 100;
                                    app.getSharedPreferences("painel", Context.MODE_PRIVATE).edit().putBoolean("offline-" + area.id, real).apply();
                                    if (!real) { msg = area.name + ": download falhou (" + s.getCompletedResourceCount() + " pedaços), tenta de novo no Wi-Fi"; progress = msg; }
                                    Logger.line("Mapa sem internet: " + msg);
                                } else if (System.currentTimeMillis() - lastLog > 10_000) {
                                    lastLog = System.currentTimeMillis();
                                    Logger.line("Mapa sem internet: " + msg);
                                }
                            }

                            @Override
                            public void onError(OfflineRegionError e) {
                                Logger.line("Mapa sem internet: erro " + e.getReason() + " " + e.getMessage());
                            }

                            @Override
                            public void mapboxTileCountLimitExceeded(long limit) {
                                Logger.line("Mapa sem internet: passou do limite de " + limit + " pedaços");
                            }
                        });
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE);
                        Logger.line("Mapa sem internet: baixando " + area.name);
                    }

                    @Override
                    public void onError(String error) {
                        Logger.line("Mapa sem internet: não começou " + error);
                        if (onProgress != null) onProgress.accept("Erro: " + error);
                    }
                });
            } catch (Throwable t) {
                Logger.line("Mapa sem internet: falhou " + t);
                if (onProgress != null) onProgress.accept("Erro: " + t.getMessage());
            }
        });
    }

    /** Tentativas antigas que nunca terminaram (as de 0% em file://) saem do banco antes de começar de novo. */
    private static void removeUnfinished(OfflineManager om, Area area) {
        om.listOfflineRegions(new OfflineManager.ListOfflineRegionsCallback() {
            @Override
            public void onList(OfflineRegion[] regions) {
                if (regions == null) return;
                for (OfflineRegion r : regions) {
                    if (!area.id.equals(new String(r.getMetadata(), StandardCharsets.UTF_8))) continue;
                    r.getStatus(new OfflineRegion.OfflineRegionStatusCallback() {
                        @Override
                        public void onStatus(OfflineRegionStatus st) {
                            if (st.isComplete()) return;
                            r.setDownloadState(OfflineRegion.STATE_INACTIVE);
                            r.delete(new OfflineRegion.OfflineRegionDeleteCallback() {
                                @Override public void onDelete() { Logger.line("Mapa sem internet: tentativa antiga de " + area.name + " removida"); }
                                @Override public void onError(String e) { }
                            });
                        }

                        @Override
                        public void onError(String e) { }
                    });
                }
            }

            @Override
            public void onError(String e) { }
        });
    }

    static boolean downloaded(Context c, Area a) {
        return c.getSharedPreferences("painel", Context.MODE_PRIVATE).getBoolean("offline-" + a.id, false);
    }

    /** Teste: finge que não tem internet para o MapLibre (null = volta ao automático). */
    static void forceOffline(Boolean offline) {
        main.post(() -> MapLibre.setConnected(offline == null ? null : !offline));
    }
}
