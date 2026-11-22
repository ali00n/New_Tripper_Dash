package br.com.evandro.tripper;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.nio.charset.StandardCharsets;

/**
 * Abertura ao conectar no painel: vaca mugindo + barra de carregando rápida (1,5 s),
 * depois 3 s de "considere fazer um PIX" com a chave e o QR Code. Depois disso, o mapa.
 */
final class Intro {
    /** O painel leva ~1 a 2 s para começar a mostrar o vídeo: a vaca fica parada esse tempo antes de mugir. */
    static final long HOLD_MS = 1200;
    static final long COW_MS = 2000;
    static final long PIX_MS = 3000;
    static final String APP_NAME = "COW TRIPPER DASH";
    static final long TOTAL_MS = HOLD_MS + COW_MS + PIX_MS;

    private volatile long startedAt;
    private Bitmap qr;
    private String qrFor = "";
    private final Paint bg = new Paint(), bar = new Paint(Paint.ANTI_ALIAS_FLAG), barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG), bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);

    Intro() {
        bg.setColor(Color.rgb(16, 19, 24));
        bar.setColor(Color.rgb(66, 146, 255));
        barBg.setColor(Color.rgb(40, 46, 56));
        title.setColor(Color.WHITE);
        title.setTextSize(21);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        small.setColor(Color.rgb(170, 178, 190));
        small.setTextSize(15);
        bubble.setColor(Color.WHITE);
        bubbleText.setColor(Color.rgb(20, 20, 20));
        bubbleText.setTextSize(22);
        bubbleText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bubbleText.setTextAlign(Paint.Align.CENTER);
    }

    void start() {
        startedAt = System.currentTimeMillis();
    }

    boolean active() {
        long t = System.currentTimeMillis() - startedAt;
        return startedAt > 0 && t >= 0 && t < TOTAL_MS;
    }

    /** Desenha o quadro da abertura no celular (tela retangular). */
    void paint(Canvas c, int w, int h, String key, String name, String city) {
        paint(c, w, h, key, name, city, false);
    }

    /**
     * panel=true: painel da moto é redondo, o vídeo aparece dentro de um círculo e perde laterais e o arco de cima.
     * Tudo fica concentrado no miolo (x ~95 a 430, y ~70 a 280).
     */
    void paint(Canvas c, int w, int h, String key, String name, String city, boolean panel) {
        long t = System.currentTimeMillis() - startedAt;
        c.drawRect(0, 0, w, h, bg);
        if (panel) {
            if (t < HOLD_MS) paintPanelCow(c, w, h, 0);
            else if (t < HOLD_MS + COW_MS) paintPanelCow(c, w, h, t - HOLD_MS);
            else paintPanelPix(c, w, h, key, name, city);
            return;
        }
        if (t < HOLD_MS) paintCow(c, w, h, 0);
        else if (t < HOLD_MS + COW_MS) paintCow(c, w, h, t - HOLD_MS);
        else paintPix(c, w, h, key, name, city);
    }

    private void paintCow(Canvas c, int w, int h, long t) {
        float k = t / (float) COW_MS;
        // vaca grande, cabeça balançando no mugido
        float bob = (float) Math.sin(t / 70.0) * 3;
        c.save();
        c.translate(w / 2f - 40, h / 2f - 18 + bob);
        c.rotate(-90);
        MapPainter.drawCowAt(c, 0, 0, 2.6f);
        c.restore();
        // balão "MUUU!" crescendo
        float s = Math.min(1f, k * 3f);
        String muu = "MUUU" + "U".repeat((int) (k * 4)) + "!";
        float bx = w / 2f + 95, by = h / 2f - 55;
        float bw = (bubbleText.measureText(muu) + 30) * s, bh = 44 * s;
        if (s > 0.05f) {
            c.drawRoundRect(new RectF(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2), 22 * s, 22 * s, bubble);
            android.graphics.Path tail = new android.graphics.Path();
            tail.moveTo(bx - bw / 2 + 18, by + bh / 2 - 4);
            tail.lineTo(bx - bw / 2 - 6, by + bh / 2 + 14 * s);
            tail.lineTo(bx - bw / 2 + 34, by + bh / 2 - 4);
            tail.close();
            c.drawPath(tail, bubble);
            bubbleText.setTextSize(22 * s);
            c.drawText(muu, bx, by + 8 * s, bubbleText);
        }
        // nome do app
        Paint name = new Paint(title);
        name.setTextSize(24);
        name.setColor(Color.rgb(242, 227, 160));
        name.setTextAlign(Paint.Align.CENTER);
        name.setLetterSpacing(0.12f);
        c.drawText(APP_NAME, w / 2f, 40, name);
        // barra de carregando, rápida
        float p = Math.min(1f, k / 0.9f);
        float x0 = 113, x1 = w - 113, y = h - 52;
        c.drawRoundRect(x0, y, x1, y + 10, 5, 5, barBg);
        c.drawRoundRect(x0, y, x0 + (x1 - x0) * p, y + 10, 5, 5, bar);
        small.setTextAlign(Paint.Align.CENTER);
        c.drawText("carregando " + Math.round(p * 100) + "%", w / 2f, y + 32, small);
    }

    private void paintPix(Canvas c, int w, int h, String key, String name, String city) {
        Bitmap q = qr(key, name, city);
        int qs = h - 40;
        float qx = w - qs - 22, qy = 20;
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        c.drawRoundRect(qx - 8, qy - 8, qx + qs + 8, qy + qs + 8, 10, 10, white);
        if (q != null) c.drawBitmap(q, null, new RectF(qx, qy, qx + qs, qy + qs), null);
        // a vaca também na tela do PIX, no canto de baixo
        c.save();
        c.translate(54, h - 64);
        c.rotate(-90);
        MapPainter.drawCowAt(c, 0, 0, 1.25f);
        c.restore();
        float tx = 24, avail = qx - 8 - tx - 14;
        title.setTextAlign(Paint.Align.LEFT);
        small.setTextAlign(Paint.Align.LEFT);
        float y = 72;
        for (String line : wrap("Considere fazer um PIX para ajudar o nosso app", title, avail)) {
            c.drawText(line, tx, y, title);
            y += 28;
        }
        y += 18;
        c.drawText("Chave PIX", tx, y, small);
        y += 26;
        Paint keyPaint = new Paint(title);
        keyPaint.setTextSize(19);
        keyPaint.setColor(Color.rgb(242, 227, 160));
        for (String line : wrap(key, keyPaint, avail)) {
            c.drawText(line, tx, y, keyPaint);
            y += 24;
        }
        c.drawText("Aponte a câmera para o QR Code", tx + 70, h - 26, small);
    }

    private void paintPanelCow(Canvas c, int w, int h, long t) {
        float k = t / (float) COW_MS;
        Paint nm = new Paint(title);
        nm.setTextSize(19);
        nm.setColor(Color.rgb(242, 227, 160));
        nm.setTextAlign(Paint.Align.CENTER);
        nm.setLetterSpacing(0.1f);
        c.drawText(APP_NAME, w / 2f, 92, nm);
        float bob = (float) Math.sin(t / 70.0) * 2;
        c.save();
        c.translate(w / 2f - 30, 165 + bob);
        c.rotate(-90);
        MapPainter.drawCowAt(c, 0, 0, 1.9f);
        c.restore();
        float s = Math.min(1f, k * 3f);
        String muu = "MUUU" + "U".repeat((int) (k * 3)) + "!";
        bubbleText.setTextSize(17 * s);
        float bx = w / 2f + 75, by = 135;
        float bw = (bubbleText.measureText(muu) + 22), bh = 34 * s;
        if (s > 0.05f) {
            c.drawRoundRect(new RectF(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2), 17 * s, 17 * s, bubble);
            c.drawText(muu, bx, by + 6 * s, bubbleText);
        }
        float p = Math.min(1f, k / 0.9f);
        float x0 = 165, x1 = w - 165, y = 228;
        c.drawRoundRect(x0, y, x1, y + 8, 4, 4, barBg);
        c.drawRoundRect(x0, y, x0 + (x1 - x0) * p, y + 8, 4, 4, bar);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(13);
        c.drawText("carregando " + Math.round(p * 100) + "%", w / 2f, y + 26, small);
        small.setTextSize(15);
    }

    private void paintPanelPix(Canvas c, int w, int h, String key, String name, String city) {
        Paint t1 = new Paint(title);
        t1.setTextSize(17);
        t1.setTextAlign(Paint.Align.CENTER);
        c.drawText("Ajude o app com um PIX", w / 2f, 80, t1);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(13);
        c.drawText("Cow Tripper Dash é grátis", w / 2f, 99, small);
        // QR à direita do centro, dentro do círculo
        Bitmap q = qr(key, name, city);
        float qs = 122, qx = w / 2f + 12, qy = 114;
        Paint white = new Paint();
        white.setColor(Color.WHITE);
        c.drawRoundRect(qx - 6, qy - 6, qx + qs + 6, qy + qs + 6, 8, 8, white);
        if (q != null) c.drawBitmap(q, null, new RectF(qx, qy, qx + qs, qy + qs), null);
        // chave à esquerda
        small.setTextAlign(Paint.Align.RIGHT);
        float rx = w / 2f - 10;
        c.drawText("Chave PIX", rx, 140, small);
        Paint kp = new Paint(title);
        kp.setTextSize(15);
        kp.setColor(Color.rgb(242, 227, 160));
        kp.setTextAlign(Paint.Align.RIGHT);
        float y = 162;
        for (String line : wrap(key, kp, 150)) {
            c.drawText(line, rx, y, kp);
            y += 19;
        }
        c.save();
        c.translate(rx - 40, 222);
        c.rotate(-90);
        MapPainter.drawCowAt(c, 0, 0, 0.95f);
        c.restore();
        small.setTextAlign(Paint.Align.CENTER);
        c.drawText("aponte a câmera do celular", w / 2f, 270, small);
        small.setTextSize(15);
    }

    private static java.util.List<String> wrap(String text, Paint p, float max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(test) > max && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(test);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private synchronized Bitmap qr(String key, String name, String city) {
        String payload = brCode(key, name, city);
        if (qr != null && qrFor.equals(payload)) return qr;
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, java.util.Map.of(com.google.zxing.EncodeHintType.MARGIN, 1));
            Bitmap b = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.ARGB_8888);
            for (int y = 0; y < m.getHeight(); y++)
                for (int x = 0; x < m.getWidth(); x++) b.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            qr = b;
            qrFor = payload;
        } catch (Exception e) {
            Logger.line("Abertura: QR Code falhou " + e);
        }
        return qr;
    }

    // ---- PIX "copia e cola" estático (BR Code do Banco Central) ----

    static String brCode(String key, String name, String city) {
        String acct = field("00", "br.gov.bcb.pix") + field("01", key.trim());
        String p = field("00", "01") + field("26", acct) + field("52", "0000") + field("53", "986") + field("58", "BR")
                + field("59", ascii(name, 25)) + field("60", ascii(city, 15)) + field("62", field("05", "***")) + "6304";
        return p + String.format("%04X", crc16(p.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String field(String id, String v) {
        return id + String.format("%02d", v.length()) + v;
    }

    private static String ascii(String s, int max) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "").toUpperCase(java.util.Locale.ROOT);
        return n.length() > max ? n.substring(0, max) : n;
    }

    static int crc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
            crc &= 0xFFFF;
        }
        return crc;
    }

    // ---- mugido sintético (sai no fone/capacete se estiver conectado) ----

    static void moo() {
        new Thread(() -> {
            try {
                int rate = 22050;
                int n = (int) (rate * 1.3);
                short[] pcm = new short[n];
                double phase = 0;
                for (int i = 0; i < n; i++) {
                    double t = i / (double) rate;
                    double f = 165 - 45 * t + 4 * Math.sin(2 * Math.PI * 5.5 * t); // desce e treme
                    phase += 2 * Math.PI * f / rate;
                    double saw = 0;
                    for (int hmc = 1; hmc <= 9; hmc++) saw += Math.sin(phase * hmc) / hmc * (hmc == 3 || hmc == 4 ? 1.8 : 1.0);
                    double env = Math.min(1, t / 0.12) * Math.min(1, (1.3 - t) / 0.35);
                    pcm[i] = (short) (saw * env * 6500);
                }
                android.media.AudioTrack track = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(rate)
                                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(n * 2).build();
                track.write(pcm, 0, n);
                track.play();
                Thread.sleep(1500);
                track.release();
            } catch (Exception e) {
                Logger.line("Abertura: mugido falhou " + e);
            }
        }, "mugido").start();
    }
}
