package br.com.evandro.tripper;

/**
 * Com rota ativa e a moto em cima dela, a vaca anda PELA LINHA DA ROTA (como o Mapbox Navigation e o Kurviger):
 * entre uma leitura do GPS e outra, a previsão segue as curvas da rua em vez de seguir reto.
 * Assim, ao virar uma esquina, o mapa vira junto, em vez de continuar na rua anterior.
 * Longe da rota (saiu dela, sem rota, GPS ruim) devolve null e o MotionSmoother assume.
 */
final class RouteFollower {
    /** A leitura precisa estar a até isso da linha para "grudar". */
    static final double MAX_DIST_M = 25;
    /** Sem leitura nova por mais que isso, para de prever pela rota. */
    private static final long MAX_PREDICT_MS = 4000;
    /** Desvio ao longo da linha (leitura nova discordando da previsão) some nesse tempo. */
    private static final double OFFSET_TAU_MS = 500;
    private static final double BRG_TAU_MS = 250;
    /** Diferença maior que isso ao longo da linha: vai direto. */
    private static final double SNAP_M = 60;

    private Object routeId;
    private boolean onRoute;
    private double sFix, speedMs;
    private long fixAt;
    private double sShown = Double.NaN, offS, shownBearing = Double.NaN;
    private long shownAt;

    /** Leitura nova (posição já medida em measuredAt). */
    synchronized void onFix(RouteManager route, double lat, double lon, double speed, double accuracyM, long measuredAt) {
        double[] loc = route == null || !route.hasDestination() || accuracyM > 40 ? null : route.locate(lat, lon);
        if (loc == null || loc[1] > MAX_DIST_M) {
            onRoute = false;
            return;
        }
        Object id = route.routeId();
        boolean continuing = onRoute && id == routeId && !Double.isNaN(sShown);
        double before = sShown;
        routeId = id;
        onRoute = true;
        sFix = loc[0];
        speedMs = Math.max(0, speed);
        fixAt = measuredAt;
        if (continuing) {
            offS = before - target(shownAt);
            if (Math.abs(offS) > SNAP_M) offS = 0;
        } else {
            offS = 0;
            sShown = Double.NaN;
        }
    }

    private double target(long now) {
        return sFix + speedMs * Math.min(MAX_PREDICT_MS, Math.max(0, now - fixAt)) / 1000.0;
    }

    /** {lat, lon, rumo} na linha da rota, ou null quando não está seguindo a rota. */
    synchronized double[] sample(RouteManager route, long now) {
        if (!onRoute || route == null || route.routeId() != routeId || now - fixAt > MAX_PREDICT_MS) {
            onRoute = false;
            sShown = Double.NaN;
            shownBearing = Double.NaN;
            return null;
        }
        long dt = Double.isNaN(sShown) ? 0 : Math.max(0, now - shownAt);
        shownAt = now;
        offS *= Math.exp(-dt / OFFSET_TAU_MS);
        sShown = target(now) + offS;
        double[] p = route.pointAt(sShown);
        if (p == null) return null;
        if (Double.isNaN(shownBearing)) shownBearing = p[2];
        double kb = 1 - Math.exp(-dt / BRG_TAU_MS);
        shownBearing = MotionSmoother.norm(shownBearing + MotionSmoother.shortest(shownBearing, p[2]) * kb);
        return new double[]{p[0], p[1], shownBearing};
    }
}
