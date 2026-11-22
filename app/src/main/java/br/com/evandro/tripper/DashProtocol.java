package br.com.evandro.tripper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Formato das mensagens do painel Tripper Dash (quadro + TLVs). Ver protocolo no vault. */
final class DashProtocol {
    static final int YES = 0x55;
    static final int NO = 0xAA;

    /** Marca de projeção (06 05) no cartão de rota: só "ligada" quando há vídeo; senão o painel espera vídeo e dá tempo esgotado. */
    static volatile boolean projectionOn;
    private static final byte[] HEADER_TLV = {0, 0, 0, 0, 2, 1, 0, 5, 'K', '1', 'G', ' '};

    private DashProtocol() {}

    /** Estados de chamada do painel (como o app original): 10 = tocando, 50 = sem chamada. */
    static final int CALL_RINGING = 10;
    static final int CALL_IDLE = 50;

    static List<Tlv> callState(int state) {
        return List.of(Tlv.ofByte(5, 0x21, state), Tlv.ofByte(5, 0x4D, 50));
    }

    /** Nome de quem liga em latin1, até 19 caracteres, sem terminador (vai cifrado). */
    static byte[] callerBytes(String name) {
        String cut = name.length() > 19 ? name.substring(0, 19) : name;
        byte[] out = new byte[cut.length()];
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            out[i] = c <= 255 ? (byte) c : (byte) '?';
        }
        return out;
    }

    static final class Tlv {
        final int group;
        final int id;
        final byte[] data;

        Tlv(int group, int id, byte[] data) {
            this.group = group;
            this.id = id;
            this.data = data;
        }

        static Tlv ofByte(int group, int id, int value) {
            return new Tlv(group, id, new byte[]{(byte) value});
        }

        static Tlv ofShort(int group, int id, int value) {
            return new Tlv(group, id, new byte[]{(byte) (value >> 8), (byte) value});
        }

        static Tlv ofText(int group, int id, String text, int maxChars) {
            String cut = text.length() > maxChars ? text.substring(0, maxChars) : text;
            byte[] out = new byte[cut.length() + 1];
            for (int i = 0; i < cut.length(); i++) {
                char c = cut.charAt(i);
                out[i] = c <= 255 ? (byte) c : (byte) '?';
            }
            return new Tlv(group, id, out);
        }

        int firstByte() {
            return data.length == 0 ? -1 : data[0] & 0xFF;
        }

        @Override
        public String toString() {
            return String.format("%02X %02X [%s]", group, id, hex(data));
        }
    }

    static byte[] frame(int seq, List<Tlv> tlvs) {
        boolean startsWithBrightness = !tlvs.isEmpty() && tlvs.get(0).group == 6 && tlvs.get(0).id == 8;
        int count = tlvs.size() + (startsWithBrightness ? 0 : 1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write((count >> 8) & 0xFF);
        out.write(count & 0xFF);
        out.write(HEADER_TLV, 0, HEADER_TLV.length);
        out.write(seq & 0xFF);
        for (Tlv t : tlvs) {
            out.write(t.group & 0xFF);
            out.write(t.id & 0xFF);
            out.write((t.data.length >> 8) & 0xFF);
            out.write(t.data.length & 0xFF);
            out.write(t.data, 0, t.data.length);
        }
        byte[] bytes = out.toByteArray();
        bytes[0] = (byte) ((bytes.length >> 8) & 0xFF);
        bytes[1] = (byte) (bytes.length & 0xFF);
        return bytes;
    }

    static List<Tlv> parse(byte[] frame) {
        List<Tlv> result = new ArrayList<>();
        if (frame.length < 8) return result;
        int expected = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (expected <= 0) expected = Integer.MAX_VALUE;
        int pos = 8;
        while (result.size() < expected && pos + 4 <= frame.length) {
            int group = frame[pos] & 0xFF;
            int id = frame[pos + 1] & 0xFF;
            int len = ((frame[pos + 2] & 0xFF) << 8) | (frame[pos + 3] & 0xFF);
            int end = Math.min(pos + 4 + len, frame.length);
            byte[] data = new byte[end - pos - 4];
            System.arraycopy(frame, pos + 4, data, 0, data.length);
            result.add(new Tlv(group, id, data));
            pos = end;
        }
        return result;
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) sb.append(String.format("%02X ", b & 0xFF));
        return sb.toString().trim();
    }

    /** Distância no formato do painel: valor + unidade (48 = m, 16 = km x10). */
    static int[] distance(int meters) {
        int m = Math.max(meters, 0);
        int rounded = m <= 100 ? ((m + 9) / 10) * 10 : ((m + 49) / 50) * 50;
        if (rounded <= 999) return new int[]{rounded, 48};
        return new int[]{(int) Math.min(65535, Math.round(rounded / 100.0)), 16};
    }

    // ---------- Mensagens prontas ----------

    static List<List<Tlv>> openingBurst(String deviceName, int h, int m, int s) {
        List<List<Tlv>> frames = new ArrayList<>();
        frames.add(List.of(Tlv.ofByte(8, 4, 1)));
        frames.add(List.of(Tlv.ofText(6, 11, deviceName, 19)));
        frames.add(List.of(clock(h, m, s)));
        frames.add(List.of(Tlv.ofByte(5, 87, YES)));
        frames.add(List.of(Tlv.ofByte(5, 86, NO)));
        frames.add(List.of(Tlv.ofByte(6, 5, NO)));
        frames.add(List.of(Tlv.ofByte(5, 23, NO)));
        frames.add(List.of(new Tlv(10, 2, new byte[]{(byte) 0xAA, 0x55, 0, 0, 0, 0, 0, 0})));
        frames.add(List.of(
                Tlv.ofByte(6, 8, 255), Tlv.ofByte(6, 3, YES), Tlv.ofByte(6, 4, 162),
                Tlv.ofByte(6, 15, NO), Tlv.ofByte(6, 1, 1), Tlv.ofByte(5, 76, 19),
                Tlv.ofShort(5, 45, 0), Tlv.ofByte(5, 27, 25), Tlv.ofByte(5, 33, 50),
                Tlv.ofByte(5, 77, 50)));
        return frames;
    }

    static Tlv clock(int h, int m, int s) {
        return new Tlv(6, 6, new byte[]{(byte) h, (byte) m, (byte) s});
    }

    static List<Tlv> navStart() {
        return List.of(Tlv.ofByte(5, 86, YES));
    }

    static List<Tlv> navActive() {
        return List.of(Tlv.ofByte(6, 5, YES));
    }

    static List<Tlv> navEnd() {
        return List.of(Tlv.ofByte(5, 86, NO));
    }

    static List<Tlv> navInactive() {
        return List.of(Tlv.ofByte(6, 5, NO));
    }

    /** Seta + distâncias. eta = "HHMM", remaining = "HHMMDD". */
    static List<Tlv> navStep(int maneuver, int metersToTurn, int metersLeft, String eta, String remaining, String street) {
        return navStep(maneuver, 255, metersToTurn, metersLeft, eta, remaining, 32, street);
    }

    static List<Tlv> navStep(NavState s, int remainingUnit) {
        return navStep(s.maneuver, s.roundaboutExit, s.metersToTurn, s.metersLeft, s.eta, s.remaining, remainingUnit, s.street);
    }

    static List<Tlv> navStep(int maneuver, int exit, int metersToTurn, int metersLeft, String eta, String remaining,
                             int remainingUnit, String street) {
        int[] turn = distance(metersToTurn);
        int[] left = distance(metersLeft);
        List<Tlv> t = new ArrayList<>();
        t.add(Tlv.ofByte(5, 2, maneuver));
        t.add(Tlv.ofByte(5, 3, exit));
        t.add(Tlv.ofShort(5, 4, turn[0]));
        t.add(Tlv.ofByte(5, 6, turn[1]));
        t.add(new Tlv(5, 8, ascii(eta)));
        t.add(Tlv.ofByte(5, 84, 48));
        t.add(Tlv.ofShort(5, 9, left[0]));
        t.add(Tlv.ofByte(5, 70, left[1]));
        t.add(Tlv.ofByte(5, 10, YES));
        t.add(new Tlv(5, 11, ascii(remaining)));
        t.add(Tlv.ofByte(5, 85, remainingUnit));
        if (street != null) t.add(Tlv.ofText(5, 1, street, 19));
        t.add(Tlv.ofByte(6, 5, projectionOn ? YES : NO));
        t.add(Tlv.ofByte(6, 13, NO));
        return t;
    }

    static String buttonName(int code) {
        switch (code) {
            case 3: return "volume chamada +";
            case 4: return "volume chamada -";
            case 5: return "play/pausa";
            case 6: return "volume +";
            case 7: return "volume -";
            case 8: return "mudo";
            case 9: return "próxima faixa";
            case 10: return "faixa anterior";
            case 11: return "iniciar navegação";
            case 18: return "parar navegação";
            case 19: return "zoom +";
            case 20: return "zoom -";
            case 21: return "recentralizar";
            case 23: return "atender";
            case 24: return "tocar música";
            case 25: return "ir ao POI";
            case 32: return "remover parada";
            case 33: return "discar";
            case 34: return "reiniciar bússola";
            default: return "desconhecido";
        }
    }
}
