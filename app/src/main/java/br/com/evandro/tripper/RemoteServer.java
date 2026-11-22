package br.com.evandro.tripper;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Acesso remoto de manutenção (TEMPORÁRIO, sai nas próximas versões).
 * Só escuta no IP do Tailscale (100.64.0.0/10) e exige o token no cabeçalho X-Token.
 */
final class RemoteServer {
    interface Commands {
        String run(String command, Map<String, String> args);


        /** Quadro atual do painel em PNG (amostra). */
        byte[] snapshotPng(Map<String, String> args);
    }

    static final int PORT = 8787;
    private static final int MAX_BODY = 64 * 1024;
    private final Context context;
    private final Commands commands;
    private final String token;
    private ServerSocket server;
    private volatile boolean running;

    RemoteServer(Context context, Commands commands) {
        this.context = context.getApplicationContext();
        this.commands = commands;
        this.token = readToken(context);
    }

    void start() {
        if (running || token.isEmpty()) return;
        running = true;
        new Thread(this::loop, "remote-server").start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
    }

    private void loop() {
        while (running) {
            InetAddress ts = tailscaleAddress();
            if (ts == null) {
                sleep(5000);
                continue;
            }
            try (ServerSocket s = new ServerSocket()) {
                server = s;
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(ts, PORT));
                Logger.line("Acesso remoto aberto em " + ts.getHostAddress() + ":" + PORT);
                while (running) {
                    Socket c = s.accept();
                    new Thread(() -> handle(c), "remote-client").start();
                }
            } catch (Exception e) {
                if (running) Logger.line("Acesso remoto caiu (" + e.getMessage() + "), reabrindo");
                sleep(3000);
            }
        }
    }

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(30000);
            InputStream in = sock.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            if (!constantTimeEquals(token, headers.getOrDefault("x-token", ""))) {
                respond(sock.getOutputStream(), 403, "proibido\n");
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            Map<String, String> args = query(target);
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (length > MAX_BODY) {
                respond(sock.getOutputStream(), 413, "grande demais\n");
                return;
            }
            byte[] body = readBody(in, length);

            if (path.equals("/foto")) {
                byte[] png = commands.snapshotPng(query(target));
                OutputStream o = sock.getOutputStream();
                o.write(("HTTP/1.0 200 X\r\nContent-Type: image/png\r\nContent-Length: " + png.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                o.write(png);
                o.flush();
                return;
            }
            String command = path.startsWith("/") ? path.substring(1) : path;
            String result = commands.run(command, args);
            respond(sock.getOutputStream(), result == null ? 404 : 200, result == null ? "comando desconhecido\n" : result);
        } catch (Exception e) {
            Logger.line("Remoto: erro " + e.getMessage());
        }
    }

    static InetAddress tailscaleAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] b = a.getAddress();
                    if ((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) return a;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Map<String, String> query(String target) {
        Map<String, String> m = new HashMap<>();
        int q = target.indexOf('?');
        if (q < 0) return m;
        for (String kv : target.substring(q + 1).split("&")) {
            int e = kv.indexOf('=');
            try {
                if (e > 0) m.put(kv.substring(0, e), java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) break;
        }
        if (ch == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, int length) throws java.io.IOException {
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }
        return body;
    }

    private static void respond(OutputStream out, int code, String text) throws java.io.IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.0 " + code + " X\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + b.length + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String readToken(Context context) {
        int id = context.getResources().getIdentifier("remote_token", "raw", context.getPackageName());
        if (id == 0) return "";
        try (InputStream in = context.getResources().openRawResource(id)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
