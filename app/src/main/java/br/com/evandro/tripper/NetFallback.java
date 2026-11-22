package br.com.evandro.tripper;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Internet com DNS de reserva. Em 21/11/2026 o DNS do celular travou por horas (Tailscale) e o mapa e a
 * rota pararam: quando o DNS do sistema falha, este cliente pergunta o endereço por HTTPS direto a
 * 1.1.1.1 ou 8.8.8.8 (por IP, sem precisar de DNS) e segue normalmente.
 */
final class NetFallback {
    private NetFallback() {}

    private static final long CACHE_MS = 5 * 60_000;
    private static final Map<String, Object[]> cache = new ConcurrentHashMap<>();
    private static volatile boolean warned;

    /** Cliente só para as consultas de DNS por HTTPS (fala com IP, não precisa de DNS). */
    private static final OkHttpClient doh = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build();

    /** 22/11: com 2 s o mapa vetorial ficou 4 h sem foto (DNS lento, mas respondendo). */
    private static final int SYSTEM_TIMEOUT_S = 4;
    private static final char NL = (char) 10;
    private static volatile String lastDohError = "";
    private static volatile int dohErrors;
    static volatile android.content.Context appContext;

    private static final java.util.concurrent.ExecutorService lookups = java.util.concurrent.Executors.newCachedThreadPool();

    /** DNS do sistema com prazo: travado, ele demorava dezenas de segundos para desistir. */
    private static List<InetAddress> systemLookup(String host) throws UnknownHostException {
        java.util.concurrent.Future<List<InetAddress>> f = lookups.submit(() -> Dns.SYSTEM.lookup(host));
        try {
            return f.get(SYSTEM_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException) throw (UnknownHostException) e.getCause();
            throw new UnknownHostException(host);
        } catch (Exception e) {
            f.cancel(true);
            throw new UnknownHostException(host + " (DNS do celular sem resposta em " + SYSTEM_TIMEOUT_S + " s)");
        }
    }

    static final Dns DNS = host -> {
        try {
            return systemLookup(host);
        } catch (UnknownHostException e) {
            List<InetAddress> found = resolveByNetworks(host);
            if (found.isEmpty()) found = resolveByHttps(host);
            if (found.isEmpty()) throw new UnknownHostException(e.getMessage() + "; DNS próprio: " + lastDohError);
            if (!warned) {
                warned = true;
                Logger.line("Rede: o DNS do celular falhou, usando DNS próprio (" + host + ")");
            }
            return found;
        }
    };

    /** Cliente para o app inteiro (mapa vetorial, rota, busca). */
    static final OkHttpClient client = new OkHttpClient.Builder()
            .dns(DNS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .addInterceptor(chain -> {
                Request r = chain.request();
                if (r.header("User-Agent") == null) r = r.newBuilder().header("User-Agent", MapPainter.USER_AGENT).build();
                return chain.proceed(r);
            })
            .build();

    static byte[] get(String url) throws Exception {
        Request req = new Request.Builder().url(url).header("User-Agent", MapPainter.USER_AGENT).build();
        try (Response res = client.newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new java.io.IOException("HTTP " + res.code());
            return res.body().bytes();
        }
    }

    private static List<InetAddress> resolveByHttps(String host) {
        Object[] hit = cache.get(host);
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < CACHE_MS) {
            @SuppressWarnings("unchecked") List<InetAddress> l = (List<InetAddress>) hit[1];
            return l;
        }
        List<InetAddress> out = new ArrayList<>();
        for (String server : new String[]{"https://1.1.1.1/dns-query?type=A&name=", "https://8.8.8.8/resolve?type=A&name="}) {
            try {
                Request req = new Request.Builder().url(server + host).header("Accept", "application/dns-json").build();
                try (Response res = doh.newCall(req).execute()) {
                    if (!res.isSuccessful() || res.body() == null) continue;
                    org.json.JSONArray answers = new org.json.JSONObject(res.body().string()).optJSONArray("Answer");
                    if (answers == null) continue;
                    for (int i = 0; i < answers.length(); i++) {
                        org.json.JSONObject a = answers.getJSONObject(i);
                        if (a.optInt("type") == 1) out.add(InetAddress.getByAddress(host, ipv4(a.getString("data"))));
                    }
                }
                if (!out.isEmpty()) break;
            } catch (Exception ex) {
                lastDohError = server.substring(8, server.indexOf('/', 8)) + " " + ex;
                if (++dohErrors <= 5 || dohErrors % 100 == 0) Logger.line("Rede: DNS próprio falhou (" + lastDohError + ")");
            }
        }
        if (!out.isEmpty()) cache.put(host, new Object[]{System.currentTimeMillis(), out});
        return out;
    }

    /** Pergunta o endereço a cada rede com internet (4G e Wi-Fi), com prazo; uma delas costuma responder. */
    private static List<InetAddress> resolveByNetworks(String host) {
        List<InetAddress> out = new ArrayList<>();
        android.content.Context c = appContext;
        if (c == null) return out;
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                java.util.concurrent.Future<InetAddress[]> f = lookups.submit(() -> n.getAllByName(host));
                try {
                    for (InetAddress a : f.get(3, TimeUnit.SECONDS)) out.add(a);
                } catch (Exception e) {
                    f.cancel(true);
                }
                if (!out.isEmpty()) break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Teste remoto: o que cada caminho de DNS responde agora para o host. */
    static String diagnose(String host) {
        StringBuilder sb = new StringBuilder();
        long t = System.currentTimeMillis();
        try {
            sb.append("sistema: ").append(systemLookup(host));
        } catch (Exception e) {
            sb.append("sistema: falhou ").append(e.getMessage());
        }
        sb.append(" (").append(System.currentTimeMillis() - t).append(" ms)").append(NL);
        t = System.currentTimeMillis();
        sb.append("por rede: ").append(resolveByNetworks(host)).append(" (").append(System.currentTimeMillis() - t).append(" ms)").append(NL);
        cache.remove(host);
        t = System.currentTimeMillis();
        sb.append("DNS próprio: ").append(resolveByHttps(host)).append(" (").append(System.currentTimeMillis() - t).append(" ms) último erro: ").append(lastDohError).append(NL);
        try {
            sb.append("baixar: ").append(get("https://" + host + "/").length).append(" bytes").append(NL);
        } catch (Exception e) {
            sb.append("baixar: ").append(e).append(NL);
        }
        return sb.toString();
    }

    private static byte[] ipv4(String s) {
        String[] p = s.split("\\.");
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) b[i] = (byte) Integer.parseInt(p[i]);
        return b;
    }
}
