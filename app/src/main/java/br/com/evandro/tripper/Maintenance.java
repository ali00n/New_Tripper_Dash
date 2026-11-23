package br.com.evandro.tripper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lembretes de manutenção por quilometragem. O hodômetro do app soma só o que o GPS viu rodando
 * (acima de 3 km/h); dá para ajustar o valor para bater com o da moto.
 */
final class Maintenance {
    private static final String PREFS = "manutencao";
    private static final double SOON_KM = 100;

    static final class Item {
        String name;
        int intervalKm;
        double lastKm;

        Item(String name, int intervalKm, double lastKm) {
            this.name = name;
            this.intervalKm = intervalKm;
            this.lastKm = lastKm;
        }

        double remainingKm(double odoKm) {
            return lastKm + intervalKm - odoKm;
        }
    }

    private static double odoM = -1;
    private static double lastLat = Double.NaN, lastLon;
    private static long lastSaveAt;

    private Maintenance() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized void load(Context c) {
        if (odoM < 0) odoM = Double.longBitsToDouble(prefs(c).getLong("odo_m", Double.doubleToLongBits(0)));
    }

    /** Chamado a cada posição do GPS. */
    static synchronized void onLocation(Context c, Location l) {
        load(c);
        boolean moving = l.hasSpeed() && l.getSpeed() * 3.6f > 3 && (!l.hasAccuracy() || l.getAccuracy() <= 30);
        if (moving) {
            if (!Double.isNaN(lastLat)) {
                double d = RouteManager.distance(lastLat, lastLon, l.getLatitude(), l.getLongitude());
                if (d < 300) odoM += d; // salto maior que isso é falha do GPS
            }
            lastLat = l.getLatitude();
            lastLon = l.getLongitude();
        } else {
            lastLat = Double.NaN;
        }
        long now = System.currentTimeMillis();
        if (now - lastSaveAt > 30_000) {
            lastSaveAt = now;
            prefs(c).edit().putLong("odo_m", Double.doubleToLongBits(odoM)).apply();
        }
    }

    static synchronized double odometerKm(Context c) {
        load(c);
        return odoM / 1000.0;
    }

    static synchronized void setOdometerKm(Context c, double km) {
        load(c);
        odoM = Math.max(0, km) * 1000.0;
        prefs(c).edit().putLong("odo_m", Double.doubleToLongBits(odoM)).apply();
    }

    static List<Item> items(Context c) {
        List<Item> out = new ArrayList<>();
        String json = prefs(c).getString("itens", null);
        if (json != null) {
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new Item(o.getString("n"), o.getInt("i"), o.getDouble("u")));
                }
                return out;
            } catch (Exception ignored) {
                out.clear();
            }
        }
        out.add(new Item("Óleo e filtro", 5000, 0));
        out.add(new Item("Lubrificar a corrente", 500, 0));
        out.add(new Item("Pneus (calibragem e desgaste)", 3000, 0));
        out.add(new Item("Revisão geral", 10000, 0));
        return out;
    }

    static void save(Context c, List<Item> items) {
        try {
            JSONArray a = new JSONArray();
            for (Item it : items) a.put(new JSONObject().put("n", it.name).put("i", it.intervalKm).put("u", it.lastKm));
            prefs(c).edit().putString("itens", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** "faltam 1.230 km", "vence agora" ou "atrasado 120 km". */
    static String statusText(Item it, double odoKm) {
        double r = it.remainingKm(odoKm);
        if (r > 0) return String.format(new Locale("pt", "BR"), "faltam %,.0f km", r);
        if (r > -1) return "vence agora";
        return String.format(new Locale("pt", "BR"), "atrasado %,.0f km", -r);
    }

    /** Avisa (uma vez por etapa e por troca) quando falta pouco ou venceu. Chamado de tempos em tempos pelo serviço. */
    static void checkAndNotify(Context c) {
        double odo = odometerKm(c);
        List<Item> items = items(c);
        SharedPreferences p = prefs(c);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            double r = it.remainingKm(odo);
            String stage = r <= 0 ? "venceu" : (r <= SOON_KM ? "perto" : null);
            if (stage == null) continue;
            String key = "aviso:" + it.name + ":" + (long) it.lastKm + ":" + stage;
            if (p.getBoolean(key, false)) continue;
            p.edit().putBoolean(key, true).apply();
            notifyDue(c, i, it, r);
        }
    }

    private static void notifyDue(Context c, int id, Item it, double remaining) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("manutencao", "Manutenção da moto", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent tap = PendingIntent.getActivity(c, 100, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(100 + id, new Notification.Builder(c, "manutencao")
                    .setContentTitle(remaining <= 0 ? "Manutenção vencida: " + it.name : "Manutenção chegando: " + it.name)
                    .setContentText(statusText(it, odometerKm(c)) + ". Toque para abrir o app.")
                    .setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build());
            Logger.line("Manutenção: " + it.name + " " + statusText(it, odometerKm(c)));
        } catch (Exception e) {
            Logger.line("Manutenção: aviso falhou " + e.getMessage());
        }
    }
}
