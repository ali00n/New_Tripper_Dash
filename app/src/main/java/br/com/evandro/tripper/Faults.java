package br.com.evandro.tripper;

import java.util.Random;

/** Falhas de propósito para o teste de estresse. Tudo desligado no uso normal. */
final class Faults {
    private Faults() {
    }

    static final Random random = new Random();
    /** Atraso extra em cada download (ms). */
    static volatile int netDelayMs;
    /** Chance (0 a 100) de um download falhar. */
    static volatile int netFailPct;
    /** GPS ignorado (túnel, garagem). */
    static volatile boolean gpsBlocked;
    /** Ruído no GPS, em metros (sinal ruim entre prédios). */
    static volatile double gpsNoiseM;
    /** Chance (0 a 100) de um pacote de vídeo não sair (Wi-Fi ruim até o painel). */
    static volatile int videoDropPct;

    static void clear() {
        netDelayMs = 0;
        netFailPct = 0;
        gpsBlocked = false;
        gpsNoiseM = 0;
        videoDropPct = 0;
    }

    static boolean active() {
        return netDelayMs > 0 || netFailPct > 0 || gpsBlocked || gpsNoiseM > 0 || videoDropPct > 0;
    }

    /** Chamado antes de cada download: atrasa e/ou falha conforme o teste em curso. */
    static void network() throws java.io.IOException {
        int d = netDelayMs;
        if (d > 0) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException ignored) {
            }
        }
        int f = netFailPct;
        if (f > 0 && random.nextInt(100) < f) throw new java.io.IOException("falha simulada (teste)");
    }

    static boolean dropVideoPacket() {
        int p = videoDropPct;
        return p > 0 && random.nextInt(100) < p;
    }
}
