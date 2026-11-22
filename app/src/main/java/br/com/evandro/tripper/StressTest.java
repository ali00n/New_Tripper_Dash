package br.com.evandro.tripper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Teste de estresse com a moto parada: simula um rolê a 40 km/h e passa por fases de
 * sobrecarga (CPU), internet lenta, sem internet, GPS ruim, GPS perdido e Wi-Fi do painel ruim.
 * Mede quadros, degraus do mapa, tiles e vídeo; grava o relatório em files/estresse-*.txt.
 */
final class StressTest {
    interface Env {
        MapPainter map();

        boolean videoRunning();

        Context context();

        /** Pontos da rota ativa {lats, lons} ou null. */
        default double[][] route() {
            return null;
        }

        /** Posição simulada passada à navegação (setas, manobras, chegada). */
        default void onSimPosition(double lat, double lon) {
        }
    }

    private static final int FPS = 12;
    private static final double SPEED_KMH = 40;
    private static volatile boolean running;
    private static volatile String lastReport = "nenhum teste rodado ainda";

    static boolean isRunning() {
        return running;
    }

    static String lastReport() {
        return lastReport;
    }

    /** Rolê simulado com velocidade oscilando (15 a 110 km/h) para ver o zoom mudar no painel. */
    static String zoomRide(Env env, int seconds) {
        if (running) return "teste já em andamento\n";
        if (env.map().speedKmh() > 5 && !env.map().isSimulated()) return "recusado: moto andando\n";
        running = true;
        new Thread(() -> {
            MapPainter map = env.map();
            double lat = map.hasFix() ? map.lat() : -22.9020, lon = map.hasFix() ? map.lon() : -43.2810, heading = 20;
            double[][] r = env.route();
            boolean onRoute = r != null && r[0].length >= 2;
            int seg = 0;
            double along = 0;
            if (onRoute) {
                lat = r[0][0];
                lon = r[1][0];
            }
            try {
                Logger.line("Simulação de zoom: " + seconds + " s, velocidade de 15 a 110 km/h" + (onRoute ? ", seguindo a rota" : ", sem rota (reta)"));
                for (int t = 0; t < seconds; t++) {
                    double kmh = 62.5 - 47.5 * Math.cos(2 * Math.PI * t / 40.0);
                    double m = kmh / 3.6;
                    if (onRoute) {
                        // anda m metros pela linha da rota
                        double left = m;
                        while (left > 0 && seg < r[0].length - 1) {
                            double segLen = RouteManager.distance(r[0][seg], r[1][seg], r[0][seg + 1], r[1][seg + 1]);
                            if (along + left < segLen) {
                                along += left;
                                left = 0;
                            } else {
                                left -= segLen - along;
                                along = 0;
                                seg++;
                            }
                        }
                        if (seg >= r[0].length - 1) {
                            Logger.line("Simulação de zoom: chegou ao fim da rota");
                            break;
                        }
                        double segLen = Math.max(0.01, RouteManager.distance(r[0][seg], r[1][seg], r[0][seg + 1], r[1][seg + 1]));
                        double f = along / segLen;
                        lat = r[0][seg] + (r[0][seg + 1] - r[0][seg]) * f;
                        lon = r[1][seg] + (r[1][seg + 1] - r[1][seg]) * f;
                        double dy = (r[0][seg + 1] - r[0][seg]) * 110_540, dx = (r[1][seg + 1] - r[1][seg]) * 111_320 * Math.cos(Math.toRadians(lat));
                        heading = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360;
                    } else {
                        heading = (heading + 4 * Math.sin(t / 12.0)) % 360;
                        lat += m * Math.cos(Math.toRadians(heading)) / 110_540.0;
                        lon += m * Math.sin(Math.toRadians(heading)) / (111_320.0 * Math.cos(Math.toRadians(lat)));
                    }
                    map.simulate(lat, lon, (float) heading, (float) kmh);
                    env.onSimPosition(lat, lon);
                    if (t % 5 == 0) Logger.line("Simulação de zoom: " + Math.round(kmh) + " km/h");
                    sleep(1000);
                }
            } finally {
                map.stopSimulation();
                running = false;
                Logger.line("Simulação de zoom: fim");
            }
        }, "simula-zoom").start();
        return "simulação de zoom começou (" + seconds + " s)\n";
    }

    /** Começa o teste; recusa se a moto estiver andando. Fase 0 = todas. */
    static String start(Env env, int seconds) {
        if (running) return "teste já em andamento\n";
        if (env.map().speedKmh() > 5 && !env.map().isSimulated()) return "recusado: moto andando (" + Math.round(env.map().speedKmh()) + " km/h)\n";
        running = true;
        new Thread(() -> run(env, Math.max(8, seconds)), "estresse").start();
        return "teste de estresse começou, uns " + (8 * Math.max(8, seconds) / 60 + 1) + " min; resultado em /estresse-resultado\n";
    }

    private static void run(Env env, int secs) {
        StringBuilder rep = new StringBuilder();
        MapPainter map = env.map();
        double lat = map.hasFix() ? map.lat() : -22.9020, lon = map.hasFix() ? map.lon() : -43.2810;
        Ride ride = new Ride(map, lat, lon);
        Thread rider = new Thread(ride, "estresse-gps");
        Thread drawer = null;
        Stopper stop = new Stopper();
        try {
            line(rep, "Teste de estresse " + new java.util.Date() + " | versão " + versionName(env.context())
                    + " | vídeo pro painel " + (env.videoRunning() ? "LIGADO (mede o envio real)" : "desligado (desenho medido aqui mesmo)"));
            rider.start();
            if (!env.videoRunning()) {
                drawer = new Thread(() -> drawLoop(map, stop), "estresse-desenho");
                drawer.start();
            }
            sleep(3000); // aquecer: GPS simulado e tiles do lugar
            phase(rep, env, "1 normal", secs, () -> { }, () -> { });
            phase(rep, env, "2 sem suavização (como era antes)", secs, () -> MotionSmoother.enabled = false, () -> MotionSmoother.enabled = true);
            Burner burn = new Burner();
            phase(rep, env, "3 sobrecarga de CPU (" + Burner.THREADS + " núcleos a 100%)", secs, burn::start, burn::stop);
            phase(rep, env, "4 internet lenta (+4 s e 30% de falha, cache de memória limpo)", secs,
                    () -> { Faults.netDelayMs = 4000; Faults.netFailPct = 30; map.dropMemoryTiles(); }, Faults::clear);
            phase(rep, env, "5 sem internet (cache de memória limpo, só disco)", secs,
                    () -> { Faults.netFailPct = 100; map.dropMemoryTiles(); OfflineMaps.forceOffline(true); },
                    () -> { Faults.clear(); OfflineMaps.forceOffline(null); });
            phase(rep, env, "6 GPS ruim (ruído de 25 m)", secs, () -> ride.noiseM = 25, () -> ride.noiseM = 0);
            phase(rep, env, "7 GPS perdido (túnel)", secs, () -> ride.blocked = true, () -> ride.blocked = false);
            phase(rep, env, "8 volta do túnel", Math.max(6, secs / 2), () -> { }, () -> { });
            phase(rep, env, "9 Wi-Fi do painel ruim (10% dos pacotes perdidos)", secs, () -> Faults.videoDropPct = 10, Faults::clear);
            line(rep, "Fim.");
        } catch (Throwable t) {
            line(rep, "ERRO no teste: " + t);
        } finally {
            Faults.clear();
            MotionSmoother.enabled = true;
            ride.stop = true;
            stop.stop = true;
            map.stopSimulation();
            running = false;
            lastReport = rep.toString();
            save(env.context(), lastReport);
        }
    }

    private interface Step {
        void run();
    }

    private static void phase(StringBuilder rep, Env env, String name, int secs, Step setup, Step cleanup) {
        Logger.line("Estresse: fase " + name);
        setup.run();
        Stats.reset();
        long mem0 = usedMb();
        sleep(secs * 1000L);
        Stats.Result r = Stats.snapshot();
        cleanup.run();
        String verdict = verdict(name, r, env.videoRunning());
        line(rep, "[" + verdict + "] " + name + ": " + r + String.format(Locale.ROOT, " | memória %d→%d MB | temperatura %s",
                mem0, usedMb(), thermal(env.context())));
    }

    /** Régua: fluido = quadro p95 abaixo do tempo de um quadro, mapa sem degrau visível, desenho no ritmo. */
    static String verdict(String name, Stats.Result r, boolean video) {
        double budget = 1000.0 / FPS;
        boolean smoothExpected = !name.startsWith("2") && !name.startsWith("7");
        StringBuilder bad = new StringBuilder();
        if (r.frameP95 > budget) bad.append("quadro lento; ");
        if (r.drawFps < FPS * 0.8) bad.append("abaixo do ritmo; ");
        if (smoothExpected && r.bigSteps > 2) bad.append("degraus; ");
        if (video && r.sendErrors > 0) bad.append("erro de envio; ");
        if (name.startsWith("2")) return r.bigSteps > 2 ? "REFERÊNCIA: engasgava" : "REFERÊNCIA";
        return bad.length() == 0 ? "OK" : "FALHOU: " + bad.toString().trim();
    }

    /** Sem vídeo ligado, desenha aqui no mesmo ritmo do painel para medir o custo real. */
    private static void drawLoop(MapPainter map, Stopper stop) {
        Bitmap b = Bitmap.createBitmap(VideoProjector.WIDTH, VideoProjector.HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        long interval = 1000 / FPS;
        while (!stop.stop) {
            long t0 = System.currentTimeMillis();
            map.paint(c, b.getWidth(), b.getHeight());
            long wait = interval - (System.currentTimeMillis() - t0);
            if (wait > 0) sleep(wait);
        }
    }

    /** Rolê simulado: reta a 40 km/h com curvas suaves, 1 leitura por segundo como o GPS real. */
    private static final class Ride implements Runnable {
        final MapPainter map;
        double lat, lon, heading = 20;
        volatile double noiseM;
        volatile boolean blocked;
        volatile boolean stop;

        Ride(MapPainter map, double lat, double lon) {
            this.map = map;
            this.lat = lat;
            this.lon = lon;
        }

        @Override
        public void run() {
            long t = 0;
            while (!stop) {
                double m = SPEED_KMH / 3.6;
                heading = (heading + 6 * Math.sin(t / 15.0)) % 360;
                lat += m * Math.cos(Math.toRadians(heading)) / 110_540.0;
                lon += m * Math.sin(Math.toRadians(heading)) / (111_320.0 * Math.cos(Math.toRadians(lat)));
                if (!blocked) {
                    double nl = lat, no = lon;
                    if (noiseM > 0) {
                        nl += Faults.random.nextGaussian() * noiseM / 110_540.0;
                        no += Faults.random.nextGaussian() * noiseM / 102_000.0;
                    }
                    map.simulate(nl, no, (float) heading, (float) SPEED_KMH, Math.max(5, noiseM));
                }
                t++;
                sleep(1000);
            }
        }
    }

    private static final class Stopper {
        volatile boolean stop;
    }

    private static final class Burner {
        static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());
        private volatile boolean on;

        void start() {
            on = true;
            for (int i = 0; i < THREADS; i++) {
                new Thread(() -> {
                    double x = 0;
                    while (on) x += Math.sqrt(x + 1.0);
                    if (x == -1) Logger.line("");
                }, "estresse-cpu").start();
            }
        }

        void stop() {
            on = false;
        }
    }

    private static long usedMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    private static String thermal(Context c) {
        try {
            android.content.Intent bat = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int t = bat == null ? -1 : bat.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1);
            int st = c.getSystemService(android.os.PowerManager.class).getCurrentThermalStatus();
            return (t > 0 ? (t / 10.0) + " °C bateria, " : "") + "nível térmico " + st + " (0 = normal)";
        } catch (Exception e) {
            return "?";
        }
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static void line(StringBuilder rep, String s) {
        rep.append(s).append('\n');
        Logger.line("Estresse: " + s);
    }

    private static void save(Context c, String text) {
        try {
            String name = new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new java.util.Date());
            try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), "estresse-" + name + ".txt"))) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Logger.line("Estresse: não salvei o relatório " + e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
