package br.com.evandro.tripper;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.Toast;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

/**
 * Mapa do celular: pinça = zoom, arrastar = mover, girar com dois dedos.
 * Segue a moto até a pessoa mexer; o botão Centralizar volta a seguir.
 * Segurar o dedo num ponto = rota até ali. O painel continua sempre seguindo a moto.
 */
final class PhoneMap {
    interface Listener {
        void onFollowChanged(boolean following);
    }

    final MapView view;
    private final Activity activity;
    private MapLibreMap map;
    private Style style;
    private boolean following = true;
    private Listener listener;
    private int lastRouteSize = -1;
    private long lastRouteKey = Long.MIN_VALUE;

    PhoneMap(Activity activity) {
        this.activity = activity;
        MapLibre.getInstance(activity);
        view = new MapView(activity);
        view.setContentDescription("Mapa, pinça para zoom, arraste para mover");
        view.onCreate(null);
        view.getMapAsync(m -> {
            map = m;
            m.getUiSettings().setAttributionEnabled(false);
            m.getUiSettings().setLogoEnabled(false);
            m.getUiSettings().setCompassMargins(0, dpx(90), dpx(12), 0);
            Style.Builder b = new Style.Builder().fromJson(MapStyle.json());
            float d = activity.getResources().getDisplayMetrics().density;
            for (String k : MapStyle.ICONS) b.withImage(k, MapStyle.icon(k, d));
            b.withImage("moto-img", bike(d));
            m.setStyle(b, s -> {
                style = s;
                s.addSource(new GeoJsonSource("rota-feita"));
                s.addSource(new GeoJsonSource("rota"));
                s.addLayer(new LineLayer("rota-feita-contorno", "rota-feita").withProperties(
                        PropertyFactory.lineColor("#1e2229"), PropertyFactory.lineWidth(10f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));
                s.addLayer(new LineLayer("rota-feita-linha", "rota-feita").withProperties(
                        PropertyFactory.lineColor("#6b7280"), PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));
                s.addSource(new GeoJsonSource("moto"));
                s.addLayer(new LineLayer("rota-contorno", "rota").withProperties(
                        PropertyFactory.lineColor("#08224f"), PropertyFactory.lineWidth(11f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));
                s.addLayer(new LineLayer("rota-linha", "rota").withProperties(
                        PropertyFactory.lineColor("#4292ff"), PropertyFactory.lineWidth(7f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));
                s.addLayer(new SymbolLayer("moto-icone", "moto").withProperties(
                        PropertyFactory.iconImage("moto-img"), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                        PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("rumo"))));
                update();
            });
            m.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) setFollowing(false);
            });
            m.addOnMapLongClickListener(p -> {
                DashService.startIdle(activity);
                DashService.goToPlace(p.getLatitude(), p.getLongitude(), "Ponto no mapa");
                Toast.makeText(activity, "Rota até o ponto marcado", Toast.LENGTH_SHORT).show();
                return true;
            });
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isFollowing() {
        return following;
    }

    void setFollowing(boolean f) {
        if (following == f) return;
        following = f;
        if (listener != null) listener.onFollowChanged(f);
        if (f) update();
    }

    /** Botão de localização: volta para a moto agora e segue de novo. */
    void recenter() {
        boolean was = following;
        following = true;
        if (!was && listener != null) listener.onFollowChanged(true);
        if (map == null) return;
        double[] bike = DashService.bikePosition();
        if (bike == null) {
            Toast.makeText(activity, "Procurando o GPS…", Toast.LENGTH_SHORT).show();
            return;
        }
        double z = map.getCameraPosition().zoom < 13 ? 15.5 : map.getCameraPosition().zoom;
        map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(bike[0], bike[1])).bearing(bike[2]).zoom(z).tilt(0).build()), 400);
    }

    void zoomBy(double delta) {
        if (map != null) map.animateCamera(CameraUpdateFactory.zoomBy(delta), 250);
    }

    /** Chamado a cada ~0,7 s pela tela: posição da moto, rota e câmera (se seguindo). */
    void update() {
        if (map == null || style == null) return;
        double[] bike = DashService.bikePosition();
        if (bike != null) {
            Feature f = Feature.fromGeometry(Point.fromLngLat(bike[1], bike[0]));
            f.addNumberProperty("rumo", bike[2]);
            GeoJsonSource src = style.getSourceAs("moto");
            if (src != null) src.setGeoJson(f);
            if (following) {
                CameraPosition cp = new CameraPosition.Builder().target(new LatLng(bike[0], bike[1]))
                        .bearing(bike[2]).zoom(map.getCameraPosition().zoom < 3 ? 15.5 : map.getCameraPosition().zoom).tilt(0).build();
                map.easeCamera(CameraUpdateFactory.newCameraPosition(cp), 650);
            }
        }
        double[][] r = DashService.routePoints();
        double[] prog = DashService.routeProgress();
        int size = r == null ? 0 : r[0].length;
        int seg = prog == null || size < 2 ? 0 : Math.min((int) prog[0], size - 2);
        double t = prog == null ? 0 : prog[1];
        long key = size * 1_000_000L + seg * 100L + Math.round(t * 20);
        if (size != lastRouteSize || key != lastRouteKey) {
            lastRouteSize = size;
            lastRouteKey = key;
            List<Point> done = new ArrayList<>(), left = new ArrayList<>();
            if (size >= 2) {
                Point cut = Point.fromLngLat(r[1][seg] + (r[1][seg + 1] - r[1][seg]) * t, r[0][seg] + (r[0][seg + 1] - r[0][seg]) * t);
                for (int i = 0; i <= seg; i++) done.add(Point.fromLngLat(r[1][i], r[0][i]));
                done.add(cut);
                left.add(cut);
                for (int i = seg + 1; i < size; i++) left.add(Point.fromLngLat(r[1][i], r[0][i]));
            }
            GeoJsonSource src = style.getSourceAs("rota");
            if (src != null) src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(left.size() >= 2 ? left : new ArrayList<>())));
            GeoJsonSource feita = style.getSourceAs("rota-feita");
            if (feita != null) feita.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(done.size() >= 2 ? done : new ArrayList<>())));
        }
    }

    private int dpx(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    /** Moto vista de cima (mesma do painel), em imagem para o mapa do celular. */
    private static Bitmap bike(float density) {
        int w = Math.round(34 * density), h = Math.round(46 * density);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(density, density);
        MapPainter.drawBikeAt(c, 17, 22, 0.75f);
        return b;
    }

    // ciclo de vida do MapView
    void onStart() { view.onStart(); }
    void onResume() { view.onResume(); }
    void onPause() { view.onPause(); }
    void onStop() { view.onStop(); }
    void onDestroy() { view.onDestroy(); }
    void onLowMemory() { view.onLowMemory(); }
}
