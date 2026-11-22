package br.com.evandro.tripper;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Traduz o texto da notificação do Google Maps para um passo do painel. */
final class NavParser {
    static final int UNKNOWN = -1;

    private static final Pattern DISTANCE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern HOURS = Pattern.compile("(\\d+)\\s*h");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*min");
    private static final Pattern EXIT = Pattern.compile("(\\d)\\s*[aªo°º]?\\s*saida|saida\\s*(\\d)");

    private NavParser() {}

    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace(' ', ' ').replace(' ', ' ');
    }

    /** Código de manobra pelo texto, ou UNKNOWN. */
    static int maneuverFromText(String raw) {
        String t = normalize(raw);
        boolean right = t.contains("direita") || t.contains(" right");
        boolean left = t.contains("esquerda") || t.contains(" left");
        if (t.contains("recalcul")) return 28;
        if (t.contains("retorno") || t.contains("u-turn") || t.contains("meia-volta")) return 26;
        if (t.contains("rotatoria") || t.contains("rotunda") || t.contains("roundabout") || t.contains("balao")) return 10;
        if (t.contains("balsa") || t.contains("ferry")) return 62;
        if (t.contains("chegou") || t.contains("chegada ao destino") || t.contains("destino")) {
            return right ? 2 : left ? 1 : 0;
        }
        if (t.contains("acentuad") || t.contains("sharp")) return right ? 23 : left ? 22 : UNKNOWN;
        if (t.contains("suave") || t.contains("ligeir") || t.contains("levemente") || t.contains("slight")) {
            return right ? 25 : left ? 24 : UNKNOWN;
        }
        if (t.contains("mantenha") || t.contains("keep")) return right ? 44 : left ? 43 : 9;
        if (t.contains("pegue a saida") || t.contains("saida") || t.contains("exit")) return right ? 7 : left ? 8 : UNKNOWN;
        if (t.contains("acesso") || t.contains("ramp")) return right ? 29 : left ? 30 : UNKNOWN;
        if (t.contains("bifurca") || t.contains("fork")) return right ? 5 : left ? 6 : UNKNOWN;
        if (t.contains("entre") || t.contains("converg") || t.contains("merge")) return right ? 3 : left ? 4 : 27;
        if (t.contains("vire") || t.contains("dobre") || t.contains("curva") || t.contains("turn")) {
            return right ? 21 : left ? 20 : UNKNOWN;
        }
        if (t.contains("siga") || t.contains("continue") || t.contains("em frente") || t.contains("straight")) return 9;
        return UNKNOWN;
    }

    static int roundaboutExit(String raw) {
        Matcher m = EXIT.matcher(normalize(raw));
        if (!m.find()) return 255;
        String g = m.group(1) != null ? m.group(1) : m.group(2);
        return Integer.parseInt(g);
    }

    /** Primeira distância encontrada, em metros, ou -1. */
    static int meters(String raw) {
        Matcher m = DISTANCE.matcher(normalize(raw));
        if (!m.find()) return -1;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        return (int) Math.round(m.group(2).equals("km") ? v * 1000 : v);
    }

    /** Hora de chegada "HHMM" ou null. */
    static String eta(String raw) {
        Matcher m = CLOCK.matcher(normalize(raw));
        if (!m.find()) return null;
        return String.format(Locale.US, "%02d%02d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
    }

    /** Tempo restante em segundos, ou -1. */
    static int remainingSeconds(String raw) {
        String t = normalize(raw);
        String noClock = CLOCK.matcher(t).replaceAll(" ");
        int total = 0;
        boolean found = false;
        Matcher h = HOURS.matcher(noClock);
        if (h.find()) {
            total += Integer.parseInt(h.group(1)) * 3600;
            found = true;
        }
        Matcher m = MINUTES.matcher(noClock);
        if (m.find()) {
            total += Integer.parseInt(m.group(1)) * 60;
            found = true;
        }
        return found ? total : -1;
    }

    /** Tempo restante no formato do painel "HHMMDD". */
    static String remainingField(int seconds) {
        int s = Math.max(seconds, 0);
        return String.format(Locale.US, "%02d%02d%02d", (s % 86400) / 3600, (s % 3600) / 60, Math.min(s / 86400, 99));
    }

    /** Unidade do tempo restante: 48 dias, 16 horas, 32 minutos. */
    static int remainingUnit(int seconds) {
        if (seconds >= 86400) return 48;
        return seconds >= 3600 ? 16 : 32;
    }
}
