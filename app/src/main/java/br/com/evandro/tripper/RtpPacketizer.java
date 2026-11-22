package br.com.evandro.tripper;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** RTP H.264 (RFC 6184, FU-A) do jeito que o painel espera. */
final class RtpPacketizer {
    private static final int MAX_PAYLOAD = 1358;
    private static final byte[] START = {0, 0, 0, 1};
    private final int ssrc;
    private final int fps;
    private int seq;
    private long timestamp;
    private byte[] sps;
    private byte[] pps;

    RtpPacketizer(int fps) {
        SecureRandom r = new SecureRandom();
        this.ssrc = r.nextInt();
        this.seq = r.nextInt(256);
        this.timestamp = (r.nextInt() & 0xFFFFFFFFL) | 1L;
        this.fps = fps;
    }

    /** Guarda SPS/PPS vindos do buffer de configuração do codec. */
    void setConfig(byte[] annexB) {
        for (byte[] nal : splitNals(annexB)) {
            int type = nal[0] & 0x1F;
            if (type == 7) sps = patchSps(nal);
            if (type == 8) pps = nal;
        }
    }

    boolean hasConfig() {
        return sps != null && pps != null;
    }

    /** Converte um quadro (Annex-B) em pacotes RTP. */
    List<byte[]> packetize(byte[] annexB, boolean keyFrame) {
        List<byte[]> nals = new ArrayList<>();
        for (byte[] nal : splitNals(annexB)) {
            int type = nal[0] & 0x1F;
            if (type == 7) { sps = patchSps(nal); continue; }
            if (type == 8) { pps = nal; continue; }
            if (type == 6 || type == 9) continue;
            nals.add(nal);
        }
        if (keyFrame && sps != null && pps != null) {
            for (int i = 0; i < nals.size(); i++) {
                if ((nals.get(i)[0] & 0x1F) == 5) {
                    nals.set(i, join(sps, START, pps, START, nals.get(i)));
                    break;
                }
            }
        }
        List<byte[]> payloads = new ArrayList<>();
        for (byte[] nal : nals) {
            if (nal.length <= MAX_PAYLOAD) {
                payloads.add(nal);
                continue;
            }
            byte indicator = (byte) ((nal[0] & 0xE0) | 28);
            int type = nal[0] & 0x1F;
            int pos = 1;
            boolean first = true;
            while (pos < nal.length) {
                int len = Math.min(MAX_PAYLOAD - 2, nal.length - pos);
                boolean last = pos + len >= nal.length;
                byte[] p = new byte[len + 2];
                p[0] = indicator;
                p[1] = (byte) ((first ? 0x80 : 0) | (last ? 0x40 : 0) | type);
                System.arraycopy(nal, pos, p, 2, len);
                payloads.add(p);
                pos += len;
                first = false;
            }
        }
        List<byte[]> packets = new ArrayList<>(payloads.size());
        for (int i = 0; i < payloads.size(); i++) {
            byte[] pl = payloads.get(i);
            boolean marker = i == payloads.size() - 1;
            byte[] pkt = new byte[pl.length + 12];
            pkt[0] = (byte) 0x80;
            pkt[1] = (byte) ((marker ? 0x80 : 0) | 96);
            pkt[2] = (byte) (seq >>> 8);
            pkt[3] = (byte) seq;
            pkt[4] = (byte) (timestamp >>> 24);
            pkt[5] = (byte) (timestamp >>> 16);
            pkt[6] = (byte) (timestamp >>> 8);
            pkt[7] = (byte) timestamp;
            pkt[8] = (byte) (ssrc >>> 24);
            pkt[9] = (byte) (ssrc >>> 16);
            pkt[10] = (byte) (ssrc >>> 8);
            pkt[11] = (byte) ssrc;
            System.arraycopy(pl, 0, pkt, 12, pl.length);
            packets.add(pkt);
            seq = (seq + 1) % 256;
        }
        if (!packets.isEmpty()) timestamp = ((timestamp + 90000L / fps) & 0xFFFFFFFFL);
        if (timestamp == 0) timestamp = 1;
        return packets;
    }

    /** Perfil baseline: zera o byte de restrições, como o painel espera. */
    private static byte[] patchSps(byte[] nal) {
        byte[] out = nal.clone();
        if (out.length >= 4 && (out[1] & 0xFF) == 66) out[2] = 0;
        return out;
    }

    static List<byte[]> splitNals(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int start = -1;
        int i = 0;
        while (i + 2 < data.length) {
            boolean three = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1;
            boolean four = i + 3 < data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1;
            if (three || four) {
                if (start >= 0) out.add(slice(data, start, i));
                i += three ? 3 : 4;
                start = i;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < data.length) out.add(slice(data, start, data.length));
        if (start < 0 && data.length > 0) out.add(data);
        return out;
    }

    private static byte[] slice(byte[] d, int from, int to) {
        int end = to;
        while (end > from && d[end - 1] == 0) end--;
        byte[] out = new byte[end - from];
        System.arraycopy(d, from, out, 0, out.length);
        return out;
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
