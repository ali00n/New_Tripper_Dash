package br.com.evandro.tripper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;

import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;

import br.com.evandro.tripper.DashProtocol.Tlv;

/** Liga ao Wi-Fi do painel, autentica e troca mensagens. */
final class DashLink {
    interface Log {
        void line(String text);
    }

    private static final String BROADCAST = "192.168.1.255";
    private static final int CONTROL_PORT = 2000;
    private static final int AUX_PORT = 2002;

    private final Context context;
    private final Log log;
    private final ConnectivityManager cm;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();

    private ConnectivityManager.NetworkCallback callback;
    private DatagramSocket controlSocket;
    private DatagramSocket auxSocket;
    private volatile boolean running;
    private int seq;
    private String ssid;

    private BigInteger rsaModulus;
    private BigInteger rsaExponent;
    private byte[] aesKey;
    private volatile boolean authenticated;
    private int rejections;

    interface ButtonListener {
        void onButton(int code);
    }

    private ButtonListener buttonListener;
    private Runnable onAuthenticated;
    private Runnable onKeyFrameWanted;

    /** 09 03 [AA, tipo] = painel pede lista; 09 03 [índice, tipo] = painel escolheu. */
    interface PoiListener {
        void onPoi(int first, int type);
    }

    private PoiListener poiListener;

    void setPoiListener(PoiListener l) {
        poiListener = l;
    }

    interface ScreenModeListener {
        void onScreenMode(boolean digital);
    }

    private ScreenModeListener screenModeListener;

    void setScreenModeListener(ScreenModeListener l) {
        screenModeListener = l;
    }

    void setOnKeyFrameWanted(Runnable r) {
        onKeyFrameWanted = r;
    }

    /** Socket de vídeo preso ao Wi-Fi do painel (senão o Android manda pelo 4G). */
    DatagramSocket createVideoSocket() throws java.io.IOException {
        Network net = openNetwork;
        java.net.InetAddress local = null;
        if (net != null) {
            android.net.LinkProperties lp = cm.getLinkProperties(net);
            if (lp != null) {
                for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof java.net.Inet4Address) local = a.getAddress();
                }
            }
        }
        logRoute();
        DatagramSocket s = local != null ? new DatagramSocket(new InetSocketAddress(local, 0)) : new DatagramSocket();
        log.line("Vídeo: socket saindo pelo IP " + (local != null ? local.getHostAddress() : "(desconhecido)"));
        if (net != null) {
            for (int i = 1; i <= 8; i++) {
                try {
                    net.bindSocket(s);
                    log.line("Vídeo: socket preso à rede do painel (tentativa " + i + ")");
                    break;
                } catch (Exception e) {
                    if (i == 8) log.line("Vídeo: não prendeu à rede (" + e.getMessage() + "), seguindo pelo IP local");
                    sleep(250);
                }
            }
        }
        return s;
    }

    /** Diagnóstico: por qual IP local o Android sairia para o painel, sem amarrar nada. */
    void logRoute() {
        // a sondagem usa rede: fora da linha principal (o Android barra rede nela)
        new Thread(this::logRouteNow, "diag-rota").start();
    }

    private void logRouteNow() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(java.net.InetAddress.getByName("192.168.1.1"), 5000);
            log.line("Rota: para 192.168.1.1 o Android sai pelo IP " + probe.getLocalAddress().getHostAddress());
        } catch (Exception e) {
            log.line("Rota: falhou " + e);
        }
        try {
            Network active = cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = active == null ? null : cm.getNetworkCapabilities(active);
            log.line("Rota: rede padrão = " + active + (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) ? " (VPN)" : "")
                    + ", painel = " + openNetwork + ", VPN bloqueando? " + (cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().isConnected()));
        } catch (Exception ignored) {
        }
    }

    void setButtonListener(ButtonListener l) {
        buttonListener = l;
    }

    void setOnAuthenticated(Runnable r) {
        onAuthenticated = r;
    }

    /** Envia sem escrever o quadro no log (uso repetitivo). */
    void sendQuiet(List<Tlv> tlvs) {
        sender.execute(() -> {
            DatagramSocket s = controlSocket;
            if (s == null) return;
            try {
                seq = (seq + 1) & 0xFF;
                byte[] bytes = DashProtocol.frame(seq, tlvs);
                s.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(BROADCAST), CONTROL_PORT));
            } catch (Exception e) {
                log.line("ERRO envio: " + e);
            }
        });
    }

    /** Último valor de cada campo de telemetria (0C xx); registra só quando muda (para mapear o que é o quê). */
    private final java.util.Map<Integer, String> telemetry = new java.util.concurrent.ConcurrentHashMap<>();

    private void logTelemetryChanges(List<Tlv> tlvs) {
        for (Tlv t : tlvs) {
            if (t.group != 0x0C) continue;
            String v = DashProtocol.hex(t.data);
            String old = telemetry.put(t.id, v);
            if (v.equals(old)) continue;
            if (t.id == 0x26 && t.data.length > 0) logEngine(t.data[0] & 0xFF);
            // 0C 24 muda o tempo todo (~750 linhas por dia): no log, no máximo 1 vez por minuto.
            if (t.id == 0x24) {
                long now = System.currentTimeMillis();
                if (now - lastTele24Log < 60_000) continue;
                lastTele24Log = now;
            }
            log.line(String.format("   tele 0C %02X: %s → %s", t.id, old == null ? "-" : old, v));
        }
    }

    private long lastTele24Log;
    private Boolean engineOn;

    /**
     * 0C 26 (deduzido em 21/11/2026): fica em E8 com o motor rodando e cai 1 a 4 s antes do painel
     * desligar; na partida sobe em rampa até E8. Abaixo de C8 = motor parado.
     */
    private void logEngine(int v) {
        boolean on = v >= 0xC8;
        if (engineOn != null && engineOn == on) return;
        engineOn = on;
        log.line(String.format("Moto: motor %s (0C 26 = %02X)", on ? "LIGADO" : "DESLIGADO", v));
    }

    String telemetryReport() {
        StringBuilder sb = new StringBuilder();
        java.util.List<Integer> ids = new java.util.ArrayList<>(telemetry.keySet());
        java.util.Collections.sort(ids);
        for (int id : ids) sb.append(String.format("0C %02X = %s%n", id, telemetry.get(id)));
        return sb.toString();
    }

    private static boolean isTelemetryOnly(List<Tlv> tlvs) {
        for (Tlv t : tlvs) {
            if (t.group != 0x0C && !(t.group == 1 && t.id == 1)) return false;
        }
        return true;
    }

    DashLink(Context context, Log log) {
        this.context = context.getApplicationContext();
        this.log = log;
        this.cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    boolean isAuthenticated() {
        return authenticated;
    }

    /** Cifra para o painel com a chave da sessão: IV aleatório (16 B) + AES/CBC/PKCS5. Null sem sessão. */
    byte[] encryptForDash(byte[] plain) {
        byte[] key = aesKey;
        if (key == null) return null;
        try {
            byte[] iv = new byte[16];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
            byte[] body = c.doFinal(plain);
            byte[] out = new byte[16 + body.length];
            System.arraycopy(iv, 0, out, 0, 16);
            System.arraycopy(body, 0, out, 16, body.length);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Há um pedido de Wi-Fi em andamento ou uma rede aberta (não pedir de novo). */
    boolean isBusy() {
        return callback != null;
    }

    private void releaseRequest() {
        if (callback == null) return;
        try {
            cm.unregisterNetworkCallback(callback);
        } catch (Exception ignored) {
        }
        callback = null;
    }

    void connectBySpecifier(String ssid, String password) {
        this.ssid = ssid;
        releaseRequest();
        WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid).setWpa2Passphrase(password).build();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec).build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                log.line("Wi-Fi do painel disponível");
                openSockets(network);
            }

            @Override
            public void onUnavailable() {
                log.line("ERRO: Wi-Fi do painel não encontrado ou recusado");
                releaseRequest();
            }

            @Override
            public void onLost(Network network) {
                log.line("Wi-Fi do painel perdido");
                closeSockets();
                openNetwork = null;
                resetAuth();
                releaseRequest();
            }
        };
        log.line("Pedindo o Wi-Fi " + ssid + "… (aceite o aviso do Android)");
        cm.requestNetwork(request, callback);
    }

    /** Usa o Wi-Fi atual (se o celular já estiver ligado ao painel pelas configurações). */
    void connectCurrentWifi(String ssid) {
        this.ssid = ssid;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                log.line("Usando o Wi-Fi atual");
                openSockets(n);
                return;
            }
        }
        log.line("ERRO: nenhum Wi-Fi conectado");
    }

    private Network openNetwork;

    private synchronized void openSockets(Network network) {
        if (network != null && network.equals(openNetwork) && controlSocket != null) {
            log.line("(rede já aberta, ignorando aviso repetido)");
            return;
        }
        for (int attempt = 1; attempt <= 6; attempt++) {
            try {
                closeSockets();
                auxSocket = new DatagramSocket(null);
                auxSocket.setReuseAddress(true);
                auxSocket.bind(new InetSocketAddress(AUX_PORT));
                controlSocket = new DatagramSocket(null);
                controlSocket.setReuseAddress(true);
                controlSocket.setBroadcast(true);
                controlSocket.bind(new InetSocketAddress(CONTROL_PORT));
                openNetwork = network;
                break;
            } catch (Exception e) {
                log.line("ERRO ao abrir portas (tentativa " + attempt + "/6): " + e);
                closeSockets();
                if (attempt == 6) return;
                sleep(500);
            }
        }
        try {
            running = true;
            resetAuth();
            startReceiver(auxSocket, "2002");
            startReceiver(controlSocket, "2000");
            log.line("Portas 2000/2002 abertas. Mandando a abertura…");
            sendOpeningBurst();
        } catch (Exception e) {
            log.line("ERRO ao abrir portas: " + e);
        }
    }

    private void resetAuth() {
        rsaModulus = null;
        rsaExponent = null;
        aesKey = null;
        authenticated = false;
    }

    private void sendOpeningBurst() {
        Calendar c = Calendar.getInstance();
        String name = android.provider.Settings.Global.getString(context.getContentResolver(), "device_name");
        if (name == null || name.trim().isEmpty()) name = BuildConfig.PUBLICO ? "Cow Tripper Dash" : "Tripper do Evandro";
        log.line("Apresentando ao painel como: " + name.trim());
        List<List<Tlv>> burst = DashProtocol.openingBurst(name.trim(),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
        for (List<Tlv> f : burst) {
            send("abertura", f);
            sender.execute(() -> sleep(20));
        }
    }

    void send(String label, List<Tlv> tlvs) {
        sender.execute(() -> {
            DatagramSocket s = controlSocket;
            if (s == null) {
                log.line("(sem conexão) " + label);
                return;
            }
            try {
                seq = (seq + 1) & 0xFF;
                byte[] bytes = DashProtocol.frame(seq, tlvs);
                s.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(BROADCAST), CONTROL_PORT));
                log.line("→ " + label + ": " + DashProtocol.hex(bytes));
            } catch (Exception e) {
                log.line("ERRO envio " + label + ": " + e);
            }
        });
    }

    private void startReceiver(DatagramSocket socket, String port) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            while (running && !socket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, data, 0, data.length);
                    if (isOwnEcho(p)) continue;
                    List<Tlv> tlvs = DashProtocol.parse(data);
                    logTelemetryChanges(tlvs);
                    if (isTelemetryOnly(tlvs)) continue;
                    log.line("← [" + port + " de " + p.getAddress().getHostAddress() + "] " + DashProtocol.hex(data));
                    for (Tlv tlv : tlvs) handle(tlv);
                } catch (Exception e) {
                    if (running) log.line("ERRO recepção " + port + ": " + e);
                }
            }
        }, "rx-" + port);
        t.setDaemon(true);
        t.start();
    }

    private boolean isOwnEcho(DatagramPacket p) {
        String from = p.getAddress().getHostAddress();
        return !from.equals("192.168.1.1") && from.startsWith("192.168.1.") && p.getPort() == CONTROL_PORT
                && p.getLength() > 16 && p.getData()[12] == 'K' && p.getData()[13] == '1';
    }

    private void handle(Tlv tlv) {
        log.line("   " + tlv);
        switch (tlv.group) {
            case 7:
                handleAuth(tlv);
                break;
            case 9:
                handleEvent(tlv);
                break;
            case 3:
                log.line("   ajuste do painel " + String.format("%02X", tlv.id));
                break;
            case 15:
                logDecrypted(tlv);
                break;
            default:
                break;
        }
    }

    private void handleAuth(Tlv tlv) {
        if (tlv.id == 1) {
            if (tlv.firstByte() != 1) {
                rejections++;
                log.line("✖ Painel RECUSOU a autenticação (" + rejections + "/5), código " + tlv.firstByte());
                resetAuth();
                if (rejections < 5) send("pede auth de novo", List.of(Tlv.ofByte(8, 4, 1)));
            } else if (aesKey != null && !authenticated) {
                authenticated = true;
                log.line("✔ AUTENTICADO no painel (" + ssid + ")");
                if (onAuthenticated != null) onAuthenticated.run();
                Calendar c = Calendar.getInstance();
                send("relógio", List.of(DashProtocol.clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))));
            }
            return;
        }
        if (tlv.data.length == 0) return;
        BigInteger value = new BigInteger(1, tlv.data);
        if (tlv.data.length > 8) {
            rsaModulus = value;
            log.line("   chave RSA do painel: módulo " + (tlv.data.length * 8) + " bits (id " + tlv.id + ")");
        } else {
            rsaExponent = value;
            log.line("   chave RSA do painel: expoente " + value + " (id " + tlv.id + ")");
        }
        if (rsaModulus != null && rsaExponent != null && aesKey == null) sendSessionKey();
    }

    private void logDecrypted(Tlv tlv) {
        if (aesKey == null || tlv.data.length < 32 || tlv.data.length % 16 != 0) return;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(aesKey, "AES"),
                    new javax.crypto.spec.IvParameterSpec(tlv.data, 0, 16));
            byte[] plain = c.doFinal(tlv.data, 16, tlv.data.length - 16);
            log.line(String.format("   🔓 0F %02X decifrado: %s", tlv.id, DashProtocol.hex(plain)));
        } catch (Exception e) {
            log.line(String.format("   0F %02X não decifrou com a nossa chave: %s", tlv.id, e.getMessage()));
        }
    }

    private void sendSessionKey() {
        try {
            byte[] key = new byte[32];
            random.nextBytes(key);
            byte[] plain = DashProtocol.concat(ssid.getBytes(StandardCharsets.UTF_8), key);
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsaModulus, rsaExponent));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pub);
            byte[] encrypted = cipher.doFinal(plain);
            aesKey = key;
            send("chave de sessão (SSID + AES)", List.of(new Tlv(8, 0, encrypted)));
        } catch (Exception e) {
            log.line("ERRO ao cifrar a chave: " + e);
        }
    }

    /** Botões que o painel espera ver ecoados (06 80 [código]); 23 atender e 24 música não têm eco. */
    private static final java.util.Set<Integer> ECHO_BUTTONS =
            java.util.Set.of(3, 4, 5, 6, 7, 8, 9, 10, 11, 18, 19, 20, 21, 25, 32, 33, 34);

    private void handleEvent(Tlv tlv) {
        int v = tlv.firstByte();
        switch (tlv.id) {
            case 0:
                log.line(String.format("   ★ BOTÃO 0x%02X (%d) = %s", v, v, DashProtocol.buttonName(v)));
                if (ECHO_BUTTONS.contains(v)) send(String.format("eco botão 0x%02X", v), List.of(Tlv.ofByte(6, 0x80, v)));
                if (buttonListener != null) buttonListener.onButton(v);
                break;
            case 4:
                if (v == DashProtocol.YES) {
                    send("sincronia 06 12", List.of(Tlv.ofByte(6, 18, DashProtocol.YES)));
                    send("sincronia 06 11", List.of(Tlv.ofByte(6, 17, DashProtocol.YES)));
                    if (onKeyFrameWanted != null) onKeyFrameWanted.run();
                }
                break;
            case 3:
                if (tlv.data.length >= 2 && poiListener != null) {
                    int first = tlv.data[0] & 0xFF, type = tlv.data[1] & 0xFF;
                    log.line(String.format("   POI: painel %s tipo %d", first == 0xAA ? "pediu a lista" : "escolheu #" + first, type));
                    poiListener.onPoi(first, type);
                }
                break;
            case 7:
                // Medido em 15/11/2026: 09 07 = AA no modo DIGITAL, 55 no modo ANALÓGICO (na troca e na conexão).
                log.line("   painel em modo " + (v == DashProtocol.NO ? "DIGITAL (AA)" : "ANALÓGICO (55)"));
                if (onKeyFrameWanted != null) onKeyFrameWanted.run();
                if (screenModeListener != null) screenModeListener.onScreenMode(v == DashProtocol.NO);
                break;
            case 5:
                if (v == 1) send("ajustes 06 0E", List.of(Tlv.ofByte(6, 14, DashProtocol.YES)));
                break;
            case 6:
                if (v == DashProtocol.YES) {
                    send("sincronia 06 11", List.of(Tlv.ofByte(6, 17, DashProtocol.YES)));
                    send("sincronia 06 12", List.of(Tlv.ofByte(6, 18, DashProtocol.YES)));
                    if (onKeyFrameWanted != null) onKeyFrameWanted.run();
                }
                break;
            case 10:
                if (v == DashProtocol.YES) send("resposta 06 80", List.of(Tlv.ofByte(6, 128, 10)));
                break;
            default:
                log.line(String.format("   evento 09 %02X = %d", tlv.id, v));
        }
    }

    /** Para testes remotos: "05 56 55, 06 05 55" vira dois TLVs de 1 byte; "0A 02 AA 55 00" vira um TLV de 3 bytes. */
    void sendHex(String spec) {
        java.util.List<Tlv> tlvs = new java.util.ArrayList<>();
        for (String part : spec.split(",")) {
            String[] b = part.trim().split("\\s+");
            if (b.length < 2) continue;
            byte[] data = new byte[b.length - 2];
            for (int i = 2; i < b.length; i++) data[i - 2] = (byte) Integer.parseInt(b[i], 16);
            tlvs.add(new Tlv(Integer.parseInt(b[0], 16), Integer.parseInt(b[1], 16), data));
        }
        if (!tlvs.isEmpty()) send("remoto " + spec, tlvs);
    }

    void sendClock() {
        Calendar c = Calendar.getInstance();
        send("relógio", List.of(DashProtocol.clock(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))));
    }

    void stop() {
        running = false;
        closeSockets();
        openNetwork = null;
        resetAuth();
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
            callback = null;
        }
    }

    private void closeSockets() {
        running = false;
        if (controlSocket != null) controlSocket.close();
        if (auxSocket != null) auxSocket.close();
        controlSocket = null;
        auxSocket = null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
