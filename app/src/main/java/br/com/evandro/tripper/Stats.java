package br.com.evandro.tripper;

import java.util.Arrays;
import java.util.Locale;

/** Medidas de desempenho para o teste de estresse (quadros, GPS, tiles, vídeo). */
final class Stats {
    private Stats() {
    }

    private static final int N = 2000;
    private static final double[] frameMs = new double[N];
    private static int frames;
    private static final double[] gpsGapMs = new double[N];
    private static int gaps;
    private static long lastFix;
    private static int tileOk, tileFail;
    private static long tileMsSum;
    private static long tileMsMax;
    private static long videoFrames, videoBytes, videoPackets, videoDropped, sendErrors, keyFrames;
    private static double stepMax;
    private static int bigSteps;
    private static long since = System.currentTimeMillis();

    static synchronized void reset() {
        frames = gaps = tileOk = tileFail = 0;
        tileMsSum = tileMsMax = 0;
        videoFrames = videoBytes = videoPackets = videoDropped = sendErrors = keyFrames = 0;
        lastFix = 0;
        stepMax = 0;
        bigSteps = 0;
        since = System.currentTimeMillis();
    }

    static synchronized void frame(double ms) {
        frameMs[frames % N] = ms;
        frames++;
    }

    /** Quanto o mapa andou de um quadro para o outro (metros). Degrau grande = engasgo visível. */
    static synchronized void step(double m) {
        if (m > 200) return; // salto de GPS voltando/simulação nova, não é engasgo
        stepMax = Math.max(stepMax, m);
        if (m > BIG_STEP_M) bigSteps++;
    }

    static final double BIG_STEP_M = 4.0;

    static synchronized void gpsFix(long at) {
        if (lastFix > 0) gpsGapMs[gaps++ % N] = at - lastFix;
        lastFix = at;
    }

    static synchronized void tile(boolean ok, long ms) {
        if (ok) tileOk++;
        else tileFail++;
        tileMsSum += ms;
        tileMsMax = Math.max(tileMsMax, ms);
    }

    static synchronized void video(int bytes, int packets, int dropped, boolean key) {
        videoFrames++;
        videoBytes += bytes;
        videoPackets += packets;
        videoDropped += dropped;
        if (key) keyFrames++;
    }

    static synchronized void sendError() {
        sendErrors++;
    }

    /** Resumo do período desde o último reset. */
    static synchronized Result snapshot() {
        Result r = new Result();
        r.seconds = Math.max(0.001, (System.currentTimeMillis() - since) / 1000.0);
        int n = Math.min(frames, N);
        double[] f = Arrays.copyOf(frameMs, n);
        Arrays.sort(f);
        r.frames = frames;
        r.drawFps = frames / r.seconds;
        r.frameP50 = pct(f, 50);
        r.frameP95 = pct(f, 95);
        r.frameMax = n == 0 ? 0 : f[n - 1];
        int g = Math.min(gaps, N);
        double[] gg = Arrays.copyOf(gpsGapMs, g);
        Arrays.sort(gg);
        r.gpsFixes = gaps;
        r.gpsGapMax = g == 0 ? 0 : gg[g - 1];
        r.tileOk = tileOk;
        r.tileFail = tileFail;
        r.tileAvgMs = tileOk + tileFail == 0 ? 0 : tileMsSum / (double) (tileOk + tileFail);
        r.tileMaxMs = tileMsMax;
        r.videoFps = videoFrames / r.seconds;
        r.videoKbps = videoBytes * 8 / 1000.0 / r.seconds;
        r.videoPackets = videoPackets;
        r.videoDropped = videoDropped;
        r.sendErrors = sendErrors;
        r.keyFrames = keyFrames;
        r.stepMax = stepMax;
        r.bigSteps = bigSteps;
        return r;
    }

    private static double pct(double[] sorted, int p) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)];
    }

    static final class Result {
        double stepMax;
        int bigSteps;
        double seconds, drawFps, frameP50, frameP95, frameMax, gpsGapMax, tileAvgMs, videoFps, videoKbps;
        long frames, gpsFixes, tileOk, tileFail, tileMaxMs, videoPackets, videoDropped, sendErrors, keyFrames;

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "maior degrau do mapa %.1f m (%d acima de %.0f m) | ", stepMax, bigSteps, BIG_STEP_M) + String.format(Locale.ROOT,
                    "%.0fs | desenho %.1f q/s, quadro p50 %.1f ms p95 %.1f ms máx %.1f ms | GPS %d leituras, maior buraco %.1f s | tiles ok %d falha %d média %.0f ms máx %d ms | vídeo %.1f q/s %.0f kbit/s, %d pacotes, %d perdidos, %d erros de envio, %d quadros-chave",
                    seconds, drawFps, frameP50, frameP95, frameMax, gpsFixes, gpsGapMax / 1000, tileOk, tileFail, tileAvgMs, tileMaxMs,
                    videoFps, videoKbps, videoPackets, videoDropped, sendErrors, keyFrames);
        }
    }
}
