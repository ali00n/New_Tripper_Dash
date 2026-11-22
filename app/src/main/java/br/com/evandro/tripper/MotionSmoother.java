package br.com.evandro.tripper;

/**
 * Movimento suave entre leituras do GPS (que chegam 1 vez por segundo).
 * 1) Filtra cada leitura pela precisão que o GPS informa: leitura ruim puxa pouco, leitura boa puxa muito.
 * 2) Parado, segura a posição (GPS "passeando" não move a vaca).
 * 3) Entre leituras, projeta pela velocidade e rumo; em túnel continua projetando por alguns segundos.
 * 4) O ponto mostrado = projeção + um "desvio" que some aos poucos. Andando reto não sobra atraso nenhum
 *    (antes era uma mola atrás do alvo: ~10 m de atraso a 60 km/h e pior ainda nas curvas).
 * 5) Em curva (rumo mudou muito) confia na leitura nova inteira, para não continuar na rua anterior.
 */
final class MotionSmoother {
    /** Projeção para a frente: parado/devagar vale pouco; andando, aguenta um túnel curto. */
    static final long MAX_PREDICT_SLOW_MS = 1500;
    static final long MAX_PREDICT_MOVING_MS = 8000;
    static final double MOVING_MS = 5.5; // 20 km/h
    /** Salto maior que isso (GPS voltando de túnel longo) vai direto, sem animação. */
    static final double SNAP_M = 80;
    /** Tempo para o desvio entre o mostrado e o calculado sumir (o "deslizar" quando chega leitura nova). */
    private static final double OFFSET_TAU_MS = 600;
    /** Desvio grande (volta de túnel): some em ~1 s em vez de pular. */
    private static final double CATCHUP_TAU_MS = 400;
    private static final double BRG_TAU_MS = 200;
    /** Rumo mudou mais que isso entre leituras: é curva, vale a leitura nova inteira. */
    static final double TURN_DEG = 20;

    /** Teste A/B: desligado = mapa só anda quando chega leitura nova (comportamento antigo). */
    static volatile boolean enabled = true;

    private double fixLat = Double.NaN, fixLon, fixSpeedMs, fixBearing;
    private long fixAt;
    private double showLat = Double.NaN, showLon, showBearing;
    private long shownAt;
    /** Desvio do ponto mostrado em relação à projeção (graus de lat/lon). */
    private double offLat, offLon;

    void onFix(double lat, double lon, double speedMs, double bearing, long nowMs) {
        onFix(lat, lon, speedMs, bearing, 5, nowMs);
    }

    /** accuracyM = raio de erro informado pelo GPS (Location.getAccuracy). */
    synchronized void onFix(double lat, double lon, double speedMs, double bearing, double accuracyM, long nowMs) {
        speedMs = Math.max(0, speedMs);
        if (!enabled || Double.isNaN(fixLat)) {
            set(lat, lon, speedMs, bearing, nowMs);
            if (Double.isNaN(showLat)) snapShown(nowMs);
            return;
        }
        double[] pred = project(nowMs);
        double gap = distanceM(pred[0], pred[1], lat, lon);
        if (gap > SNAP_M && accuracyM < 30) {
            set(lat, lon, speedMs, bearing, nowMs);
            snapShown(nowMs);
            return;
        }
        double g = gain(accuracyM, speedMs);
        boolean turning = speedMs >= 2 && Math.abs(shortest(fixBearing, bearing)) > TURN_DEG;
        if (turning && accuracyM <= 30) g = 1;
        if (speedMs < 1.0 && gap < Math.max(15, accuracyM)) g = 0.05; // parado: segura
        // o mostrado não pula: o salto vira desvio que some aos poucos
        double[] before = Double.isNaN(showLat) ? null : new double[]{showLat, showLon};
        set(pred[0] + (lat - pred[0]) * g, pred[1] + (lon - pred[1]) * g, speedMs, bearing, nowMs);
        if (before != null) {
            double[] now = project(shownAt);
            offLat = before[0] - now[0];
            offLon = before[1] - now[1];
        }
    }

    /** Andando o GPS do celular é bem melhor do que parado: confia mais para não ficar para trás. */
    static double gain(double accuracyM, double speedMs) {
        if (speedMs >= 3) {
            if (accuracyM <= 20) return 0.5;
            if (accuracyM <= 40) return 0.6;
            return 0.3;
        }
        return gain(accuracyM);
    }

    static double gain(double accuracyM) {
        if (accuracyM <= 8) return 0.8;
        if (accuracyM <= 15) return 0.5;
        if (accuracyM <= 30) return 0.3;
        return 0.15;
    }

    private void set(double lat, double lon, double speedMs, double bearing, long nowMs) {
        fixLat = lat;
        fixLon = lon;
        fixSpeedMs = speedMs;
        fixBearing = bearing;
        fixAt = nowMs;
    }

    private void snapShown(long nowMs) {
        offLat = 0;
        offLon = 0;
        showLat = fixLat;
        showLon = fixLon;
        showBearing = fixBearing;
        shownAt = nowMs;
    }

    synchronized void reset() {
        fixLat = Double.NaN;
        showLat = Double.NaN;
    }

    boolean hasFix() {
        return !Double.isNaN(fixLat);
    }

    /** Onde a moto deve estar agora, pela última leitura filtrada. */
    private double[] project(long nowMs) {
        long cap = fixSpeedMs >= MOVING_MS ? MAX_PREDICT_MOVING_MS : MAX_PREDICT_SLOW_MS;
        long ahead = Math.min(cap, Math.max(0, nowMs - fixAt));
        double meters = fixSpeedMs * ahead / 1000.0;
        double rad = Math.toRadians(fixBearing);
        return new double[]{
                fixLat + meters * Math.cos(rad) / 110_540.0,
                fixLon + meters * Math.sin(rad) / (111_320.0 * Math.cos(Math.toRadians(fixLat)))};
    }

    /** Posição e rumo para desenhar agora: {lat, lon, rumo}. */
    synchronized double[] sample(long nowMs) {
        if (Double.isNaN(fixLat)) return null;
        if (!enabled) {
            snapShown(nowMs);
            return new double[]{fixLat, fixLon, fixBearing};
        }
        double[] target = project(nowMs);
        long dt = Math.max(0, nowMs - shownAt);
        shownAt = nowMs;
        double offM = Math.hypot(offLat * 110_540, offLon * 111_320 * Math.cos(Math.toRadians(fixLat)));
        double keep = Math.exp(-dt / (offM > 12 ? CATCHUP_TAU_MS : OFFSET_TAU_MS));
        offLat *= keep;
        offLon *= keep;
        showLat = target[0] + offLat;
        showLon = target[1] + offLon;
        double kb = 1 - Math.exp(-dt / BRG_TAU_MS);
        showBearing = norm(showBearing + shortest(showBearing, fixBearing) * kb);
        return new double[]{showLat, showLon, showBearing};
    }

    static double shortest(double from, double to) {
        return ((to - from) % 360 + 540) % 360 - 180;
    }

    static double norm(double a) {
        return (a % 360 + 360) % 360;
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double k = Math.cos(Math.toRadians((lat1 + lat2) / 2)) * 111_320;
        return Math.hypot((lon2 - lon1) * k, (lat2 - lat1) * 110_540);
    }
}
