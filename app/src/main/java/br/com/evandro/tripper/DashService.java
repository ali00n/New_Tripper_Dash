package br.com.evandro.tripper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.KeyEvent;

import java.util.Calendar;

/** Serviço em primeiro plano: mantém o painel ligado e repassa a navegação. */
public class DashService extends Service {
    static final String EXTRA_SSID = "ssid";
    static final String EXTRA_PASS = "pass";
    static final String ACTION_STOP = "br.com.evandro.tripper.STOP";
    private static final long RESEND_MS = 1500;
    private static final long CLOCK_MS = 5000;
    private static final long NAV_STALE_MS = 15000;

    private static DashService current;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private DashLink link;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NavState navState;
    private int navRemainingUnit = 32;
    private boolean navActive;
    private long lastNavAt;
    private NavState lastSent;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (link != null && link.isAuthenticated()) {
                if (navActive && navState != null) {
                    sendNav(false);
                }
            }
            handler.postDelayed(this, RESEND_MS);
        }
    };

    /**
     * Conexão automática: a cada 20 s, se o painel não está conectado e o Wi-Fi dele
     * aparece na busca do celular, pede a conexão (moto ligou = conecta sozinho).
     */
    /** Painel achado pelo prefixo RE_ na edição pública; só é salvo depois de autenticar. */
    private volatile String candidateSsid;
    /** Rede vista há mais que isto é lista velha do Android (moto já desligada): não tenta. */
    private static final long SCAN_FRESH_MS = 35_000;
    /** Tentativas seguidas sem conectar; a partir de 3, tenta só a cada 3 rodadas (1 min). */
    private int failedTries;
    private int skipTicks;

    private final Runnable autoConnect = new Runnable() {
        @Override
        public void run() {
            handler.removeCallbacks(this);
            handler.postDelayed(this, 20_000);
            if (link == null) return;
            android.content.SharedPreferences prefs = getSharedPreferences("painel", MODE_PRIVATE);
            if (link.isAuthenticated()) {
                failedTries = 0;
                skipTicks = 0;
                // Só depois de autenticar o painel vira "o painel desta moto"; antes disso nada é gravado.
                if (candidateSsid != null && prefs.getString("ssid", DEFAULT_SSID).isEmpty()) {
                    prefs.edit().putString("ssid", candidateSsid).apply();
                    Logger.line("Auto: painel " + candidateSsid + " salvo como o desta moto");
                }
                return;
            }
            if (!prefs.getBoolean("auto", true) && !forceSearch) return; // conexão automática desligada nos Ajustes
            if (link.isBusy()) return;
            if (skipTicks > 0) {
                skipTicks--;
                return;
            }
            String ssid = prefs.getString("ssid", DEFAULT_SSID);
            try {
                android.net.wifi.WifiManager wm = getSystemService(android.net.wifi.WifiManager.class);
                if (!wm.isWifiEnabled()) return;
                wm.startScan();
                long nowUs = android.os.SystemClock.elapsedRealtime() * 1000L;
                for (android.net.wifi.ScanResult r : wm.getScanResults()) {
                    if (nowUs - r.timestamp > SCAN_FRESH_MS * 1000L) continue;
                    String found = r.getWifiSsid() != null ? r.getWifiSsid().toString().replace("\"", "") : r.SSID;
                    // 22/11 (Realme 12+): rede OCULTA vem com nome vazio; vazio == vazio fazia o app tentar
                    // conectar numa rede sem nome e o Android derrubava o app.
                    if (found == null || found.trim().isEmpty()) continue;
                    if (ssid.isEmpty() && found.startsWith("RE_")) {
                        ssid = found;
                        candidateSsid = found;
                    }
                    if (ssid.equals(found)) {
                        Logger.line("Auto: painel " + ssid + " à vista há " + (nowUs - r.timestamp) / 1_000_000 + " s, conectando");
                        link.connectBySpecifier(ssid, prefs.getString("pass", "12345678"));
                        failedTries++;
                        if (failedTries >= 3) skipTicks = 2;
                        return;
                    }
                }
            } catch (SecurityException e) {
                Logger.line("Auto: sem permissão para ver redes Wi-Fi");
            }
        }
    };

    private long perfSince = System.currentTimeMillis();

    /** A cada minuto com o mapa no painel: uma linha com fluidez, GPS, vídeo e temperatura (medir antes de mexer). */
    private void logPerformance() {
        long now = System.currentTimeMillis();
        if (now - perfSince < 60_000) return;
        perfSince = now;
        if (projector == null || !projector.isRunning()) {
            Stats.reset();
            return;
        }
        Stats.Result r = Stats.snapshot();
        Stats.reset();
        Intent bat = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        float temp = bat == null ? Float.NaN : bat.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        boolean charging = bat != null && bat.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        Logger.line(String.format(java.util.Locale.ROOT,
                "Desempenho 1 min: vídeo %.1f q/s (teto %d), quadro p95 %.0f ms máx %.0f ms, GPS maior buraco %.1f s, envio %d erros/%d perdidos, bateria %.1f °C%s, %s",
                r.videoFps, VideoProjector.fpsCap, r.frameP95, r.frameMax, r.gpsGapMax / 1000, r.sendErrors, r.videoDropped,
                temp, charging ? " carregando" : "", dashSignal()));
    }

    /** Força do sinal do Wi-Fi do painel: dBm (perto de -40 = ótimo, abaixo de -75 = fraco) e velocidade do link. */
    @SuppressWarnings("deprecation")
    private String dashSignal() {
        try {
            android.net.wifi.WifiInfo wi = null;
            android.net.Network net = link == null ? null : link.dashNetwork();
            if (net != null) {
                android.net.NetworkCapabilities nc = getSystemService(android.net.ConnectivityManager.class).getNetworkCapabilities(net);
                if (nc != null && nc.getTransportInfo() instanceof android.net.wifi.WifiInfo) wi = (android.net.wifi.WifiInfo) nc.getTransportInfo();
            }
            if (wi == null || wi.getRssi() <= -127) wi = getSystemService(android.net.wifi.WifiManager.class).getConnectionInfo();
            if (wi == null || wi.getRssi() <= -127) return "sinal do painel ?";
            return "sinal do painel " + wi.getRssi() + " dBm, link " + wi.getTxLinkSpeedMbps() + " Mbit/s";
        } catch (Exception e) {
            return "sinal do painel ?";
        }
    }

    private boolean autoOfflineDone;
    private long autoOfflineCheckedAt;

    /** Uma vez por sessão: no Wi-Fi de casa, baixa sozinho o mapa do estado onde a moto está (mapa sem internet). */
    private void maybeAutoOffline() {
        if (autoOfflineDone || link == null || link.isAuthenticated() || link.isBusy()) return;
        long now = System.currentTimeMillis();
        if (now - autoOfflineCheckedAt < 60_000) return;
        autoOfflineCheckedAt = now;
        double[] pos = bikePosition();
        if (pos == null) return;
        OfflineMaps.Area a = OfflineMaps.here(this, pos[0], pos[1]);
        if (a == null) return;
        autoOfflineDone = true;
        if (OfflineMaps.downloaded(this, a)) return;
        if (!OfflineMaps.onInternetWifi(this)) {
            autoOfflineDone = false;
            return;
        }
        Logger.line("Mapa sem internet: baixando " + a.name + " sozinho (Wi-Fi de casa)");
        OfflineMaps.download(this, a, null);
    }

    private final android.content.BroadcastReceiver powerIn = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Logger.line("Auto: celular começou a carregar, procurando o painel");
            failedTries = 0;
            skipTicks = 0;
            handler.post(autoConnect);
        }
    };

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (link != null && link.isAuthenticated()) {
                Calendar c = Calendar.getInstance();
                link.sendQuiet(java.util.List.of(DashProtocol.clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))));
            }
            maybeAutoOffline();
            logPerformance();
            if (trips != null) {
                trips.tick();
                long nowMs = System.currentTimeMillis();
                if (autoTrip && trips.isRecording()) {
                    if (link.isAuthenticated()) lostSince = 0;
                    else if (lostSince == 0) lostSince = nowMs;
                    else if (nowMs - lostSince > 60_000) {
                        trips.finish();
                        autoTrip = false;
                        lostSince = 0;
                        Logger.line("Rolê automático: painel desligou, rolê encerrado");
                    }
                }
                if (!trips.isRecording()) autoTrip = false;
                if (nowMs - lastMaintCheck > 600_000) {
                    lastMaintCheck = nowMs;
                    Maintenance.checkAndNotify(DashService.this);
                }
                if (gps != null && !link.isAuthenticated() && !trips.isRecording() && !projecting && !route.hasDestination() && !MainActivity.onScreen()) stopGps();
            }
            handler.postDelayed(this, CLOCK_MS);
        }
    };

    /** Busca manual (botão "Ligar com a moto"): vale mesmo com a conexão automática desligada. */
    static volatile boolean forceSearch;

    static void searchNow(Context context) {
        forceSearch = true;
        startIdle(context);
        DashService s = current;
        if (s != null) s.handler.post(() -> {
            s.failedTries = 0;
            s.skipTicks = 0;
            s.handler.post(s.autoConnect);
        });
    }

    /** Rolê gravado sozinho ao conectar: termina quando o painel some por mais de 1 min. */
    private boolean autoTrip;
    private long lostSince, lastMaintCheck;

    static void start(Context context, String ssid, String pass) {
        Intent i = new Intent(context, DashService.class).putExtra(EXTRA_SSID, ssid).putExtra(EXTRA_PASS, pass);
        context.startForegroundService(i);
    }

    static final String ACTION_MIRROR = "br.com.evandro.tripper.ESPELHAR";
    private final ScreenMirror mirror = new ScreenMirror();
    private MapPainter map;
    private MediaWatcher media;
    private VideoProjector.Painter frame;
    private RouteManager route;
    private PoiService poi;
    private TripRecorder trips;
    private volatile android.graphics.Bitmap tripPreview;

    interface VideoDone {
        void done(android.net.Uri png, android.net.Uri mp4, String error);
    }

    static void startTrip() {
        DashService s = current;
        if (s == null) return;
        s.trips.start();
        s.handler.post(s::startGps);
    }

    static void stopTrip() {
        DashService s = current;
        if (s != null) s.trips.finish();
    }

    static String tripStatus() {
        DashService s = current;
        if (s == null || !s.trips.isRecording()) return null;
        return String.format(java.util.Locale.ROOT, "● Registrando: %.1f km", s.trips.metersSoFar() / 1000);
    }

    /** Resumo para a tela inicial do app. */
    static final class HomeInfo {
        boolean connected, digital, recording, serviceOn, searching;
        NavState nav;
        String dest;
        double tripKm;
            }

    static HomeInfo homeInfo() {
        HomeInfo h = new HomeInfo();
        DashService s = current;
        if (s == null) return h;
        h.serviceOn = true;
        h.searching = forceSearch || s.getSharedPreferences("painel", MODE_PRIVATE).getBoolean("auto", true);
        h.connected = s.link.isAuthenticated();
        h.digital = s.dashDigital;
        h.nav = s.navActive ? s.navState : null;
        h.dest = s.route.hasDestination() ? s.route.destName() : null;
        h.recording = s.trips.isRecording();
        h.tripKm = s.trips.metersSoFar() / 1000;
        return h;
    }

    /** Mapa desenhado em pé, para o fundo da tela do app. */
    static android.graphics.Bitmap mapPreview(int w, int h) {
        DashService s = current;
        if (s == null || s.map == null) return null;
        if (!s.map.hasFix()) s.handler.post(s::startGps);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        s.map.paint(new android.graphics.Canvas(b), w, h);
        return b;
    }

    static java.util.List<TripRecorder.Trip> listTrips() {
        DashService s = current;
        return s == null ? java.util.Collections.emptyList() : s.trips.list();
    }

    /** Gera imagem + animação da viagem (em segundo plano) e salva na galeria. */
    static void makeVideo(java.io.File trip, VideoDone cb) {
        DashService s = current;
        if (s == null) {
            cb.done(null, null, "serviço desligado");
            return;
        }
        new Thread(() -> {
            try {
                TripVideo v = new TripVideo(s, s.map);
                v.prepare(trip);
                android.graphics.Bitmap prev = android.graphics.Bitmap.createBitmap(TripVideo.W, TripVideo.H, android.graphics.Bitmap.Config.ARGB_8888);
                v.drawFrame(new android.graphics.Canvas(prev), 1f);
                s.tripPreview = prev;
                android.net.Uri png = v.savePng();
                Logger.line("Diário: imagem salva na galeria");
                android.net.Uri mp4 = v.saveMp4();
                Logger.line("Diário: vídeo salvo na galeria (Movies/Tripper)");
                cb.done(png, mp4, null);
            } catch (Exception e) {
                Logger.line("Diário: falha ao gerar " + e);
                cb.done(null, null, e.getMessage());
            }
        }, "diario-video").start();
    }

    /** Viagem de teste: a rota atual "percorrida" a ~45 km/h. */
    private String makeTestTrip() {
        double[][] l = route.line();
        if (l[0].length < 2) return "sem rota para simular";
        try {
            org.json.JSONArray pts = new org.json.JSONArray();
            long t0 = System.currentTimeMillis() - 3_600_000L;
            double dist = 0;
            for (int i = 0; i < l[0].length; i++) {
                if (i > 0) dist += RouteManager.distance(l[0][i - 1], l[1][i - 1], l[0][i], l[1][i]);
                pts.put(new org.json.JSONArray().put(l[0][i]).put(l[1][i]).put(org.json.JSONObject.NULL)
                        .put(35 + 25 * Math.abs(Math.sin(i / 15.0))).put(t0 + (long) (dist / 12.5 * 1000)));
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("inicio", t0).put("metros", Math.round(dist)).put("movimento_s", Math.round(dist / 12.5))
                    .put("terminada", true).put("pontos", pts);
            java.io.File f = new java.io.File(new java.io.File(getFilesDir(), "viagens"), "teste.json");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "viagem de teste criada: " + Math.round(dist / 100) / 10.0 + " km";
        } catch (Exception e) {
            return "falhou " + e;
        }
    }
    /** A navegação está sendo guiada pelo próprio app (as notificações do Maps são ignoradas). */
    private volatile boolean ownNav;

    /** Define o destino (texto, endereço, coordenadas ou texto compartilhado do Maps). */
    static void setDestination(String text, Runnable onReady) {
        setDestination(text, onReady, null);
    }

    static void setDestination(String text, Runnable onReady, java.util.function.Consumer<String> onFail) {
        DashService s = current;
        if (s == null) {
            Logger.line("Rota: serviço desligado");
            if (onFail != null) onFail.accept("O serviço do painel está desligado.");
            return;
        }
        s.handler.post(s::startGps);
        s.getSharedPreferences("painel", MODE_PRIVATE).edit().putString("destino", text).apply();
        s.route.setDestination(text, () -> {
            if (s.route.hasDestination()) s.poi.addRecent(s.route.destName(), s.route.destLat(), s.route.destLon());
            if (onReady != null) onReady.run();
        }, onFail);
    }

    /** Sugestões de endereço enquanto digita (roda fora da tela; resposta no callback). */
    static java.util.List<PlaceSearch.Suggestion> suggest(String query) throws Exception {
        DashService s = current;
        if (s == null) return new java.util.ArrayList<>();
        return PlaceSearch.search(s.map::fetch, query, s.map.hasFix() ? s.map.lat() : Double.NaN, s.map.lon());
    }

    /** Posição da moto para o mapa do celular; com o app aberto, liga o GPS se ainda estiver desligado. */

    static double[] bikePosition() {
        DashService s = current;
        if (s == null || s.map == null) return null;
        if (s.gps == null && MainActivity.onScreen()) s.handler.post(s::startGps);
        return s.map.shownPosition();
    }

    static double[][] routePoints() {
        DashService s = current;
        return s == null || s.route == null || !s.route.hasDestination() ? null : s.route.points();
    }

    /** Quanto da rota já foi percorrido: {segmento, fração} ou null. */
    static double[] routeProgress() {
        DashService s = current;
        return s == null || s.route == null || !s.route.hasDestination() ? null : s.route.progress();
    }

    /** Destino escolhido numa sugestão. */
    static void goToPlace(double lat, double lon, String name) {
        DashService s = current;
        if (s == null) return;
        s.handler.post(() -> s.navigateTo(new PoiService.Place(lat, lon, name)));
    }

    static double[] destination() {
        DashService s = current;
        return s == null || !s.route.hasDestination() ? null : new double[]{s.route.destLat(), s.route.destLon()};
    }

    static void clearRoute() {
        DashService s = current;
        if (s != null) {
            s.getSharedPreferences("painel", MODE_PRIVATE).edit().remove("destino").apply();
            s.route.clear();
            s.handler.post(() -> {
                if (s.ownNav) {
                    s.ownNav = false;
                    s.endNav();
                }
            });
        }
    }
    private android.location.LocationListener gps;

    /** GPS ligado enquanto o mapa está no painel. */
    private void startGps() {
        if (gps != null) return;
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Logger.line("Mapa: sem permissão de localização");
            return;
        }
        try {
            startForeground(1, buildNotification("Mapa no painel"), foregroundTypes() | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } catch (Exception e) {
            Logger.line("Mapa: não deu para marcar o serviço como localização (" + e.getMessage() + ")");
        }
        android.location.LocationManager lm = getSystemService(android.location.LocationManager.class);
        gps = l -> {
            map.onLocation(l);
            if (!map.isSimulated()) {
                route.onPosition(l.getLatitude(), l.getLongitude());
                trips.onLocation(l);
                Maintenance.onLocation(DashService.this, l);
            }
        };
        try {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, gps, android.os.Looper.getMainLooper());
            android.location.Location last = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            if (last == null) last = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            if (last != null) map.onLocation(last);
            Logger.line("Mapa: GPS ligado");
        } catch (SecurityException e) {
            Logger.line("Mapa: GPS negado " + e.getMessage());
            gps = null;
        }
    }

    private void stopGps() {
        if (gps == null) return;
        getSystemService(android.location.LocationManager.class).removeUpdates(gps);
        gps = null;
        Logger.line("Mapa: GPS desligado");
    }

    /** Chamado pela tela depois que o Evandro autoriza a gravação de tela. */
    static void startMirror(Context context, int resultCode, Intent data) {
        context.startForegroundService(new Intent(context, DashService.class).setAction(ACTION_MIRROR)
                .putExtra("code", resultCode).putExtra("data", data));
    }

    /** Notificação que abre o pedido de espelhamento (o Android exige um toque por sessão). */
    private void askMirror() {
        if (mirror.isRunning()) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("espelho", "Espelhar o Waze", NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_MIRROR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, 9, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(9, new Notification.Builder(this, "espelho")
                .setContentTitle("Painel no modo mapa")
                .setContentText("Toque para espelhar o Waze")
                .setSmallIcon(android.R.drawable.ic_menu_mapmode)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build());
        Logger.line("Espelho: pedi o toque para espelhar (notificação)");
    }

    private int foregroundTypes() {
        int t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (mirror.isRunning()) t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (gps != null) t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        return t;
    }

    /** Desenha o mesmo quadro que vai ao painel (526x300) numa imagem. */
    android.graphics.Bitmap snapshot() {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(526, 300, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        frame.paint(c, 526, 300, 0);
        return b;
    }

    /** Amostra para a tela do app (liga o GPS se precisar). */
    static android.graphics.Bitmap preview() {
        DashService s = current;
        if (s == null || s.map == null) return null;
        if (!s.map.hasFix()) s.handler.post(s::startGps);
        return s.snapshot();
    }

    // ---- Ligações no painel ----
    private volatile String callerName;
    private int callState = DashProtocol.CALL_IDLE;
    private Object callListener;

    /** Nome vindo da notificação de chamada (discador). */
    static void onCallerName(String name) {
        DashService s = current;
        if (s == null || name == null || name.trim().isEmpty()) return;
        s.callerName = name.trim();
        if (s.callState == DashProtocol.CALL_RINGING) s.handler.post(s::sendCaller);
    }

    private void watchCalls() {
        if (callListener != null || android.os.Build.VERSION.SDK_INT < 31) return;
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Logger.line("Ligações: falta a permissão de telefone (botão Liberar ligações)");
            return;
        }
        android.telephony.TelephonyManager tm = getSystemService(android.telephony.TelephonyManager.class);
        class L extends android.telephony.TelephonyCallback implements android.telephony.TelephonyCallback.CallStateListener {
            @Override
            public void onCallStateChanged(int state) {
                handler.post(() -> onCallState(state));
            }
        }
        L l = new L();
        tm.registerTelephonyCallback(getMainExecutor(), l);
        callListener = l;
        Logger.line("Ligações: monitorando");
    }

    /** Estado bruto do telefone (TelephonyManager.CALL_STATE_*). */
    private int phoneState = android.telephony.TelephonyManager.CALL_STATE_IDLE;

    /** Gambiarra: o painel não tem "desligar"; durante a ligação, play/pause (ou atender de novo) encerra. */
    private boolean handleCallButton(int code) {
        if (phoneState == android.telephony.TelephonyManager.CALL_STATE_IDLE) return false;
        Logger.line("Ligações: botão " + code + " durante a chamada");
        boolean ringing = phoneState == android.telephony.TelephonyManager.CALL_STATE_RINGING;
        if (ringing && (code == 1 || code == 23)) {
            answerCall();
            return true;
        }
        if (code == 5 || code == 1 || code == 23) {
            endCall(ringing ? "recusada" : "encerrada");
            return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private void endCall(String what) {
        try {
            boolean ok = getSystemService(android.telecom.TelecomManager.class).endCall();
            Logger.line("Ligações: " + what + " pelo joystick" + (ok ? "" : " (o Android recusou)"));
        } catch (SecurityException e) {
            Logger.line("Ligações: falta permissão para encerrar (botão Liberar ligações)");
        }
    }

    private void onCallState(int state) {
        phoneState = state;
        int dash = state == android.telephony.TelephonyManager.CALL_STATE_RINGING ? DashProtocol.CALL_RINGING : DashProtocol.CALL_IDLE;
        if (dash == callState) return;
        callState = dash;
        Logger.line("Ligações: " + (dash == DashProtocol.CALL_RINGING ? "tocando" : "sem chamada"));
        if (!link.isAuthenticated()) return;
        link.send("chamada: estado " + dash, DashProtocol.callState(dash));
        if (dash == DashProtocol.CALL_RINGING) {
            sendCaller();
        } else {
            link.send("chamada encerrada", java.util.List.of(DashProtocol.Tlv.ofByte(5, 0x22, 0)));
            callerName = null;
        }
    }

    private void sendCaller() {
        if (!link.isAuthenticated() || callState != DashProtocol.CALL_RINGING) return;
        String name = callerName != null ? callerName : "Chamada";
        byte[] enc = link.encryptForDash(DashProtocol.callerBytes(name));
        if (enc != null) link.send("chamada (cifrada): " + name, java.util.List.of(new DashProtocol.Tlv(5, 0x22, enc)));
        else link.send("chamada: " + name, java.util.List.of(DashProtocol.Tlv.ofText(5, 0x22, name, 19)));
    }

    private void answerCall() {
        if (callState != DashProtocol.CALL_RINGING) return;
        try {
            android.telecom.TelecomManager tm = getSystemService(android.telecom.TelecomManager.class);
            tm.acceptRingingCall();
            Logger.line("Ligações: atendida pelo joystick");
        } catch (SecurityException e) {
            Logger.line("Ligações: falta permissão para atender (botão Liberar ligações)");
        }
    }

    static void watchCallsNow() {
        DashService s = current;
        if (s != null) s.handler.post(s::watchCalls);
    }

    /** Navega até um ponto (escolhido no painel ou no app). */
    private void navigateTo(PoiService.Place p) {
        String name = p.label;
        getSharedPreferences("painel", MODE_PRIVATE).edit().putString("destino", p.lat + "," + p.lon).apply();
        handler.post(this::startGps);
        route.setDestinationPoint(p.lat, p.lon, name, () -> poi.addRecent(name, p.lat, p.lon));
    }

    static void findPlaces(int type, java.util.function.Consumer<java.util.List<PoiService.Place>> cb) {
        DashService s = current;
        if (s == null) return;
        if (!s.map.hasFix()) s.handler.post(s::startGps);
        s.poi.find(type, cb);
    }

    static void goToPlace(PoiService.Place p) {
        DashService s = current;
        if (s != null) s.navigateTo(p);
    }

    static void reloadMarker() {
        DashService s = current;
        if (s != null && s.map != null) s.map.reloadMarker();
    }

    static void startIdle(Context context) {
        context.startForegroundService(new Intent(context, DashService.class));
    }

    static void stop(Context context) {
        context.startService(new Intent(context, DashService.class).setAction(ACTION_STOP));
    }

    static boolean isAuthenticated() {
        return current != null && current.link != null && current.link.isAuthenticated();
    }

    static void sendTestArrow() {
        DashService s = current;
        if (s == null) {
            Logger.line("(serviço desligado) toque em Conectar antes");
            return;
        }
        onNav(new NavState(21, 255, 150, 2500, "1830", "001500", "Rua de Teste"), 32);
    }

    private VideoProjector projector;
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    static void toggleVideo() {
        DashService s = current;
        if (s == null || !s.link.isAuthenticated()) {
            Logger.line("Conecte ao painel antes de ligar o vídeo");
            return;
        }
        s.handler.post(() -> {
            if (s.projecting || s.projector.isRunning()) {
                s.projecting = true;
                s.stopProjection();
                return;
            }
            s.startProjection();
        });
    }

    /**
     * Painel entrou no modo digital: (re)liga a projeção como o original faz.
     * Desliga o aviso de mapa, espera 40 ms, liga de novo e manda um passo "início" (manobra 9).
     */
    /** Último modo informado pelo painel (09 07): digital = mapa em tela cheia possível. */
    private volatile boolean dashDigital;

    private void onScreenMode(boolean digital) {
        boolean changed = dashDigital != digital;
        dashDigital = digital;
        if (changed) Logger.line("Painel no modo " + (digital ? "DIGITAL: mapa em tela cheia" : "ANALÓGICO: só setas"));
        handler.post(digital ? this::startProjection : this::stopProjection);
    }

    private boolean projecting;
    private int projTicks;

    /** Cartão de rota: o passo atual, ou um destino "parado" quando não há rota no Maps. */
    private java.util.List<DashProtocol.Tlv> routeCard() {
        NavState s = navActive && navState != null ? forDash(navState)
                : new NavState(BLANK, 255, 0, 0, "0000", "000000", "");
        return DashProtocol.navStep(s, navActive ? navRemainingUnit : 32);
    }

    private static final int BLANK = 255;

    /** No mapa, "siga em frente" sem curva à vista vira seta em branco (a caixinha some). */
    private NavState forDash(NavState s) {
        if (!projecting || s.maneuver != 9 || s.metersToTurn > 0) return s;
        return new NavState(BLANK, s.roundaboutExit, s.metersToTurn, s.metersLeft, s.eta, s.remaining, s.street);
    }

    /**
     * Sequência de projeção (como o app oficial, segundo o better-dash):
     * contexto + listas vazias, 4 cartões de rota, 05 56 55, 06 80 0B (iniciar navegação),
     * cartão de novo, 0,45 s e só então o vídeo. Depois: 05 56 55 a 4 Hz, cartão a 1 Hz.
     */
    private void startProjection() {
        if (!link.isAuthenticated()) {
            Logger.line("Conecte ao painel antes de ligar o mapa");
            return;
        }
        if (projecting) {
            projector.requestKeyFrame();
            return;
        }
        projecting = true;
        DashProtocol.projectionOn = true;
        projTicks = 0;
        startGps();
        poi.onRequest(30); // destinos recentes no menu do painel
        for (int i = 0; i < 4; i++) {
            handler.postDelayed(() -> link.send("mapa: cartão de rota", routeCard()), i * 350L);
        }
        handler.postDelayed(() -> {
            link.send("mapa: quadro de projeção (05 56 55)", DashProtocol.navStart());
            link.send("mapa: projeção ligada (06 05 55)", DashProtocol.navActive());
            link.send("mapa: INICIAR NAVEGAÇÃO (06 80 0B)", java.util.List.of(DashProtocol.Tlv.ofByte(6, 0x80, 0x0B)));
            link.send("mapa: cartão de rota", routeCard());
            handler.removeCallbacks(projectionBeat);
            handler.post(projectionBeat);
        }, 1600);
        handler.postDelayed(() -> {
            if (!projector.isRunning() && !projector.start()) return;
            projector.requestKeyFrame();
            Logger.line("mapa: vídeo no ar");
            // Abertura obrigatória a cada conexão :
            // começa quando o vídeo entra no ar, senão acaba antes de o painel mostrar.
            intro.start();
            Logger.line("Abertura: moto no painel");
        }, 2050);
    }

    private void stopProjection() {
        if (!projecting) return;
        projecting = false;
        DashProtocol.projectionOn = navActive;
        handler.removeCallbacks(projectionBeat);
        if (projector.isRunning()) projector.stop();
        if (!route.hasDestination()) stopGps();
        if (navActive) {
            // Continua navegando (só setas): reafirma a navegação em vez de encerrar.
            link.send("navegação continua (05 56 55)", DashProtocol.navStart());
            link.send("navegação ativa (06 05 55)", DashProtocol.navActive());
            lastSent = null;
            sendNav(false);
            Logger.line("mapa: vídeo parado, navegação segue só com setas");
        } else {
            link.send("mapa: quadro parado (05 56 AA)", DashProtocol.navEnd());
            link.send("mapa: projeção desligada (06 05 AA)", DashProtocol.navInactive());
            Logger.line("mapa: projeção desligada");
        }
    }

    private final Runnable projectionBeat = new Runnable() {
        @Override
        public void run() {
            if (!projecting) return;
            if (!link.isAuthenticated()) {
                projecting = false;
                DashProtocol.projectionOn = false;
                if (projector.isRunning()) projector.stop();
                Logger.line("mapa: painel caiu, projeção parada");
                return;
            }
            link.sendQuiet(DashProtocol.navStart());
            if (projTicks++ % 4 == 0) link.sendQuiet(routeCard());
            handler.postDelayed(this, 250);
        }
    };

    /** Tela de teste: prova que o vídeo chega ao painel. */
    private void paintTestScreen(android.graphics.Canvas c, int w, int h, long frame) {
        c.drawColor(android.graphics.Color.rgb(10, 20, 40));
        paint.setColor(android.graphics.Color.rgb(255, 140, 0));
        float x = (frame * 8) % w;
        c.drawCircle(x, h - 30, 14, paint);
        paint.setColor(android.graphics.Color.WHITE);
        paint.setTextSize(34);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        c.drawText("TRIPPER DO EVANDRO", w / 2f, 70, paint);
        paint.setTextSize(64);
        c.drawText(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()), w / 2f, 160, paint);
        NavState n = navState;
        paint.setTextSize(28);
        c.drawText(n != null ? "manobra " + n.maneuver + " · " + n.metersToTurn + " m" : "sem rota", w / 2f, 220, paint);
    }

    static void onNav(NavState state, int remainingUnit) {
        DashService s = current;
        if (s == null || s.ownNav) return;
        s.handler.post(() -> s.updateNav(state, remainingUnit));
    }

    static void onNavEnded() {
        DashService s = current;
        if (s == null || s.ownNav) return;
        s.handler.post(s::endNav);
    }

    @Override
    public void onCreate() {
        NetFallback.appContext = getApplicationContext();
        super.onCreate();
        current = this;
        Logger.init(this);
        link = new DashLink(this, Logger::line);
        link.setButtonListener(this::onButton);
        map = new MapPainter(this);
        ThermalGuard.start(this);
        route = new RouteManager(map);
        map.setRoute(route);
        route.setGuidance(new RouteManager.Guidance() {
            @Override
            public void onStep(NavState state, int remainingUnit) {
                handler.post(() -> {
                    if (!ownNav) Logger.line("Navegação própria: guiando pelo app ");
                    ownNav = true;
                    updateNav(state, remainingUnit);
                });
            }

            @Override
            public void onArrived() {
                getSharedPreferences("painel", MODE_PRIVATE).edit().remove("destino").apply();
                handler.post(() -> {
                    ownNav = false;
                    endNav();
                });
            }
        });
        projector = new VideoProjector(frame = (c, w, h, f) -> {
            if (intro.active()) {
                intro.paint(c, w, h, true);
                return;
            }
            if (BuildConfig.DOOM && DoomEngine.running()) {
                DoomEngine.paint(c, w, h);
                return;
            }
            if (mirror.paint(c, w, h)) return;
            if (media != null && media.hasCover() && !navActive && (route == null || !route.hasDestination())) {
                media.paintCover(c, w, h);
                return;
            }
            map.paint(c, w, h);
        }, link::createVideoSocket);
        link.setScreenModeListener(this::onScreenMode);
        link.setOnKeyFrameWanted(() -> {
            if (projector.isRunning()) projector.requestKeyFrame();
        });
        link.setOnAuthenticated(() -> handler.post(() -> {
            getSystemService(NotificationManager.class).notify(1, buildNotification("Painel conectado"));
            if (media != null) media.resend();
            startGps(); // diário de viagem grava enquanto a moto estiver conectada
            if (getSharedPreferences("painel", MODE_PRIVATE).getBoolean("auto_role", true) && !trips.isRecording()) {
                trips.start();
                autoTrip = true;
                lostSince = 0;
                Logger.line("Rolê automático: gravando enquanto o painel estiver conectado");
            }
            if (dashDigital) {
                Logger.line("Autenticado com o painel no digital: ligando o mapa");
                startProjection();
            }
            if (navActive) {
                DashProtocol.projectionOn = true;
                link.send("início da navegação", DashProtocol.navStart());
                link.send("navegação ativa", DashProtocol.navActive());
                lastSent = null;
                sendNav(false);
            }
        }));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tripper:painel");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tripper:painel");
        wifiLock.acquire();
        handler.postDelayed(tick, RESEND_MS);
        handler.postDelayed(clock, CLOCK_MS);
        handler.postDelayed(autoConnect, 3000);
        // Celular começou a carregar (USB da moto) = provável moto ligada: procura o painel já.
        registerReceiver(powerIn, new android.content.IntentFilter(Intent.ACTION_POWER_CONNECTED));
        watchCalls();
        media = new MediaWatcher(this, handler, (label, tlvs) -> {
            if (link.isAuthenticated()) link.send(label, tlvs);
        });
        media.start();
        trips = new TripRecorder(this);
        poi = new PoiService(map, getSharedPreferences("painel", MODE_PRIVATE), (label, tlvs) -> {
            if (link.isAuthenticated()) link.send(label, tlvs);
        });
        link.setPoiListener((first, type) -> {
            if (first == 0xAA) {
                if (!map.hasFix()) handler.post(this::startGps);
                poi.onRequest(type);
                return;
            }
            PoiService.Place p = poi.onChoice(type, first);
            if (p != null) navigateTo(p);
        });
        String saved = getSharedPreferences("painel", MODE_PRIVATE).getString("destino", null);
        if (saved != null && !saved.isEmpty()) {
            Logger.line("Rota: retomando o destino salvo (" + saved + ")");
            handler.postDelayed(() -> setDestination(saved, null), 2000);
        }
        if (!BuildConfig.PUBLICO) remote = new RemoteServer(this, new RemoteServer.Commands() {
            @Override
            public String run(String command, java.util.Map<String, String> args) {
                return remoteCommand(command, args);
            }

            @Override
            public byte[] snapshotPng(java.util.Map<String, String> args) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                if (args.containsKey("tela")) {
                    android.graphics.Bitmap t = MainActivity.capture();
                    if (t != null) {
                        t.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                        return out.toByteArray();
                    }
                }
                if (args.containsKey("mp4") && TripVideo.lastMp4 != null) {
                    try {
                        return java.nio.file.Files.readAllBytes(TripVideo.lastMp4.toPath());
                    } catch (Exception e) {
                        return new byte[0];
                    }
                }
                android.graphics.Bitmap b = args.containsKey("viagem") && tripPreview != null ? tripPreview : snapshot();
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                return out.toByteArray();
            }

        });
        if (remote != null) remote.start();
    }

    private RemoteServer remote;
    private final Intro intro = new Intro();
    /** Wi-Fi do painel do Evandro; na edição pública vazio = conecta no primeiro painel "RE_" à vista. */
    static final String DEFAULT_SSID = "";


    /** Teste de estresse (moto parada): fases de sobrecarga, rede e GPS ruins; relatório no log. */
    String startStressTest(int secondsPerPhase) {
        return StressTest.start(new StressTest.Env() {
            @Override
            public MapPainter map() {
                return map;
            }

            @Override
            public boolean videoRunning() {
                return projector != null && projector.isRunning();
            }

            @Override
            public android.content.Context context() {
                return DashService.this;
            }
        }, secondsPerPhase);
    }

    static String stressTest() {
        DashService s = current;
        return s == null ? "serviço desligado" : s.startStressTest(20);
    }

    private String remoteCommand(String command, java.util.Map<String, String> args) {
        android.content.SharedPreferences prefs = getSharedPreferences("painel", MODE_PRIVATE);
        switch (command) {
            case "vetorial":
                map.setVector(!"0".equals(args.getOrDefault("on", "1")));
                return "mapa vetorial " + ("0".equals(args.getOrDefault("on", "1")) ? "desligado" : "ligado") + "\n";
            case "mapa-status":
                return map.status() + "\n";
            case "abertura":
                intro.start();
                return "abertura tocando (aparece no painel se o vídeo estiver ligado)\n";
            case "simular-zoom":
                return StressTest.zoomRide(new StressTest.Env() {
                    @Override
                    public MapPainter map() {
                        return map;
                    }

                    @Override
                    public boolean videoRunning() {
                        return projector != null && projector.isRunning();
                    }

                    @Override
                    public android.content.Context context() {
                        return DashService.this;
                    }

                    @Override
                    public double[][] route() {
                        return route.hasDestination() ? route.points() : null;
                    }

                    @Override
                    public void onSimPosition(double lat, double lon) {
                        handler.post(() -> route.onPosition(lat, lon));
                    }
                }, Integer.parseInt(args.getOrDefault("s", "90")));
            case "offline-baixar": {
                String id = args.getOrDefault("uf", "RJ").toUpperCase(java.util.Locale.ROOT);
                for (OfflineMaps.Area a : OfflineMaps.areas(this))
                    if (a.id.equals(id)) {
                        String estilo = args.get("estilo");
                        OfflineMaps.download(this, a, estilo == null ? OfflineMaps.REMOTE_STYLE : ("local".equals(estilo) ? "" : estilo), null);
                        return "baixando " + a.name + " (progresso no log)\n";
                    }
                return "área desconhecida\n";
            }
            case "dnsteste":
                return NetFallback.diagnose(args.getOrDefault("host", "tiles.openfreemap.org"));
            case "estilo":
                return MapStyle.json();
            case "offline-status":
                return OfflineMaps.progress() + "\n";
            case "sem-internet":
                OfflineMaps.forceOffline("1".equals(args.getOrDefault("on", "1")) ? Boolean.TRUE : null);
                return "MapLibre " + ("1".equals(args.getOrDefault("on", "1")) ? "fingindo sem internet" : "normal") + "\n";
            case "estresse":
                return startStressTest(Integer.parseInt(args.getOrDefault("s", "20")));
            case "estresse-resultado":
                return (StressTest.isRunning() ? "(teste ainda rodando; parcial no log)\n" : "") + StressTest.lastReport();
            case "status":
                return "autenticado=" + link.isAuthenticated() + " video=" + projector.isRunning() + " modo=" + (dashDigital ? "digital" : "analogico") + " projecao=" + projecting + " espelho=" + mirror.isRunning()
                        + " nav=" + navActive + " " + (navState != null ? navState : "") + " versao=" + versionName() + "\n";
            case "logs": {
                java.io.File[] fs = getExternalFilesDir(null).listFiles((d, n) -> n.startsWith("tripper-log-"));
                if (fs == null) return "nenhum\n";
                java.util.Arrays.sort(fs);
                StringBuilder sb = new StringBuilder();
                for (java.io.File f : fs) sb.append(f.getName()).append(' ').append(f.length() / 1024).append(" KB\n");
                return sb.toString();
            }
            case "logarq": {
                String nome = args.getOrDefault("nome", "");
                if (!nome.matches("tripper-log-[0-9-]+\\.txt")) return "nome inválido\n";
                java.io.File f = new java.io.File(getExternalFilesDir(null), nome);
                int n = Integer.parseInt(args.getOrDefault("n", "400"));
                String q = args.getOrDefault("q", "");
                try {
                    java.util.List<String> all = java.nio.file.Files.readAllLines(f.toPath());
                    java.util.List<String> sel = new java.util.ArrayList<>();
                    for (String l : all) if (q.isEmpty() || l.contains(q)) sel.add(l);
                    int de = Integer.parseInt(args.getOrDefault("de", String.valueOf(Math.max(0, sel.size() - n))));
                    StringBuilder sb = new StringBuilder("linhas=" + sel.size() + "\n");
                    for (int i = Math.max(0, de); i < Math.min(sel.size(), de + n); i++) sb.append(sel.get(i)).append('\n');
                    return sb.toString();
                } catch (Exception e) {
                    return "erro " + e + "\n";
                }
            }
            case "log": {
                String h = Logger.history();
                int n = Integer.parseInt(args.getOrDefault("n", "150"));
                String[] lines = h.split("\n");
                StringBuilder sb = new StringBuilder();
                for (int i = Math.max(0, lines.length - n); i < lines.length; i++) sb.append(lines[i]).append('\n');
                return sb.toString();
            }
            case "conectar":
                handler.post(() -> link.connectBySpecifier(prefs.getString("ssid", DEFAULT_SSID), prefs.getString("pass", "12345678")));
                return "pedindo o Wi-Fi do painel\n";
            case "presumida": {
                // Teste A/B: liga/desliga suavização + andar pela linha da rota + foto adiantada (o que entrou depois de 19/11).
                if (args.containsKey("on")) MotionSmoother.enabled = !"0".equals(args.get("on"));
                String st = "rota presumida " + (MotionSmoother.enabled ? "LIGADA (suaviza, segue a linha, foto adiantada)" : "DESLIGADA (posição crua do GPS, como antes de 19/11)");
                Logger.line("Teste: " + st);
                return st + (char) 10;
            }
            case "videocfg": {
                if (args.containsKey("kbps")) VideoProjector.bitrate = Integer.parseInt(args.get("kbps")) * 1000;
                if (args.containsKey("chave")) VideoProjector.keyEveryMs = (int) Math.round(Double.parseDouble(args.get("chave")) * 1000);
                if (args.containsKey("fps")) {
                    VideoProjector.fps = Integer.parseInt(args.get("fps"));
                    VideoProjector.fpsCap = VideoProjector.fps;
                    handler.post(() -> { toggleVideo(); handler.postDelayed(DashService::toggleVideo, 1500); });
                }
                if (args.containsKey("pace")) VideoProjector.paceUs = Integer.parseInt(args.get("pace"));
                if (projector != null) {
                    projector.applyBitrate();
                    projector.requestKeyFrame();
                }
                String cfg = "vídeo: " + VideoProjector.fps + " q/s, " + VideoProjector.bitrate / 1000 + " kbit/s, quadro completo a cada " + VideoProjector.keyEveryMs
                        + " ms, espaço entre pacotes " + VideoProjector.paceUs + " µs";
                Logger.line("Ajuste remoto: " + cfg);
                return cfg + (char) 10;
            }
            case "video":
                toggleVideo();
                return "alternando o vídeo\n";
            case "destino":
                VideoProjector.target = args.getOrDefault("ip", "192.168.1.255");
                return "destino do vídeo = " + VideoProjector.target + " (vale ao religar o vídeo)\n";
            case "rota":
                link.logRoute();
                return "ok, veja o log\n";
            case "espelhar":
                handler.post(this::askMirror);
                return "notificação de espelhamento enviada\n";
            case "redes":
                return map.networksReport();
            case "ir":
                setDestination(args.getOrDefault("q", ""), null);
                return "buscando destino\n";
            case "semrota":
                route.clear();
                return "ok\n";
            case "chamadateste": {
                String nome = args.getOrDefault("nome", "Teste");
                handler.post(() -> {
                    callerName = nome;
                    callState = DashProtocol.CALL_IDLE;
                    onCallState(android.telephony.TelephonyManager.CALL_STATE_RINGING);
                    handler.postDelayed(() -> onCallState(android.telephony.TelephonyManager.CALL_STATE_IDLE), 12_000);
                });
                return "simulando chamada de " + nome + " por 12 s" + (char) 10;
            }
            case "simular":
                if (args.containsKey("off")) map.stopSimulation();
                else {
                    double sla = Double.parseDouble(args.get("lat")), slo = Double.parseDouble(args.get("lon"));
                    map.simulate(sla, slo, Float.parseFloat(args.getOrDefault("rumo", "0")),
                            Float.parseFloat(args.getOrDefault("vel", "30")));
                    route.onPosition(sla, slo);
                }
                return "ok" + (char) 10;
            case "viagemteste":
                return makeTestTrip() + (char) 10;
            case "rolevideo": {
                java.io.File f = new java.io.File(new java.io.File(getFilesDir(), "viagens"), args.getOrDefault("arquivo", "teste.json"));
                makeVideo(f, (png, mp4, err) -> Logger.line("Diário: " + (err == null ? "pronto " + mp4 : "erro " + err)));
                return "gerando" + (char) 10;
            }
            case "poi":
                poi.onRequest(Integer.parseInt(args.getOrDefault("tipo", "10")));
                return "buscando" + (char) 10;
            case "tele":
                return link.telemetryReport();
            case "mapa":
                return map.status() + " " + route.status() + "\n";
            case "zoommapa":
                MapPainter.zoomOverride = Integer.parseInt(args.getOrDefault("z", "0"));
                return "zoom do mapa = " + (MapPainter.zoomOverride == 0 ? "automático" : MapPainter.zoomOverride) + "\n";
            case "zoom":
                ScreenMirror.zoomOut = Float.parseFloat(args.getOrDefault("z", "1.4"));
                return "zoom = " + ScreenMirror.zoomOut + "\n";
            case "centro":
                ScreenMirror.centerY = Float.parseFloat(args.getOrDefault("y", "0.55"));
                return "centro do recorte = " + ScreenMirror.centerY + "\n";
            case "keyframe":
                projector.requestKeyFrame();
                return "ok\n";
            case "seta":
                sendTestArrow();
                return "ok\n";
            case "fim":
                onNavEnded();
                return "ok\n";
            case "tlv":
                link.sendHex(args.getOrDefault("hex", ""));
                return "enviado\n";
            case "desconectar":
                handler.post(() -> {
                    stopProjection();
                    link.stop();
                });
                return "ok\n";
            default:
                return null;
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_MIRROR.equals(intent.getAction())) {
            startForeground(1, buildNotification("Espelhando o Waze no painel"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent data = intent.getParcelableExtra("data", Intent.class);
            boolean ok = data != null && mirror.start(this, intent.getIntExtra("code", 0), data, () -> handler.post(() ->
                    startForeground(1, buildNotification("Painel"), foregroundTypes())));
            if (!ok) startForeground(1, buildNotification("Painel"), foregroundTypes());
            else if (link.isAuthenticated()) startProjection();
            return START_STICKY;
        }
        startForeground(1, buildNotification("Conectando ao painel…"), foregroundTypes());
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Logger.line("Serviço desligado pelo usuário");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_SSID)) {
            link.connectBySpecifier(intent.getStringExtra(EXTRA_SSID), intent.getStringExtra(EXTRA_PASS));
        }
        return START_STICKY;
    }

    private void updateNav(NavState state, int remainingUnit) {
        lastNavAt = System.currentTimeMillis();
        navState = state;
        navRemainingUnit = remainingUnit;
        if (!navActive) {
            navActive = true;
            DashProtocol.projectionOn = true;
            if (link.isAuthenticated()) {
                link.send("início da navegação", DashProtocol.navStart());
                link.send("navegação ativa", DashProtocol.navActive());
            }
        }
        sendNav(true);
    }

    private void sendNav(boolean changedOnly) {
        if (!link.isAuthenticated() || navState == null) return;
        boolean changed = !navState.equals(lastSent);
        if (changedOnly && !changed) return;
        if (changed) Logger.line("PAINEL ← " + navState);
        link.sendQuiet(DashProtocol.navStep(forDash(navState), navRemainingUnit));
        lastSent = navState;
    }

    private void endNav() {
        if (!navActive) return;
        navActive = false;
        navState = null;
        lastSent = null;
        DashProtocol.projectionOn = projecting;
        // No digital o mapa continua (sem rota); só no analógico avisa o fim da navegação.
        if (link.isAuthenticated() && !projecting) {
            link.send("fim da navegação", DashProtocol.navEnd());
            link.send("navegação inativa", DashProtocol.navInactive());
        }
    }

    private void onButton(int code) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (handleCallButton(code)) return;
        if (BuildConfig.DOOM && DoomEngine.dashButton(code)) return;
        if (intro.active()) return; // abertura não pula (estilo WinRAR): botões esperam ela acabar
        switch (code) {
            case 11:
                // "Navegação" escolhida no menu do painel: no digital, liga o mapa.
                if (!projecting && dashDigital) {
                    Logger.line("Painel pediu navegação no digital: ligando o mapa");
                    handler.post(this::startProjection);
                }
                break;
            case 18:
                handler.post(this::stopProjection);
                break;
            case 5: media(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            case 9: media(am, KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case 10: media(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case 6: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); break;
            case 7: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); break;
            case 8: am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0); break;
            case 23: answerCall(); break;
            case 3: am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_RAISE, 0); break;
            case 4: am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_LOWER, 0); break;
            default: break;
        }
    }

    private static void media(AudioManager am, int key) {
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("painel", "Painel da moto", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DashService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "painel")
                .setContentTitle(BuildConfig.PUBLICO ? "New Tripper Dash" : "Tripper do Evandro")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Desligar", stop).build())
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(powerIn); } catch (Exception ignored) { }
        if (projector != null) projector.stop();
        if (remote != null) remote.stop();
        link.stop();
        if (wakeLock.isHeld()) wakeLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
        current = null;
        forceSearch = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
