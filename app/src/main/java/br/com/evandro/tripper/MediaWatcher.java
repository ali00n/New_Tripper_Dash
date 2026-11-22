package br.com.evandro.tripper;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Lê o tocador ativo (Spotify, YouTube Music...) e manda ao painel:
 * 05 17 = há tocador, 05 19 = tocando/pausado, 05 0D = "título\0álbum\0artista".
 */
final class MediaWatcher {
    interface Sink {
        void send(String label, List<DashProtocol.Tlv> tlvs);
    }

    private final Context context;
    private final Handler handler;
    private final Sink sink;
    private MediaController current;
    private String lastText = "";
    private Boolean lastPlaying;
    private Boolean lastSession;

    MediaWatcher(Context context, Handler handler, Sink sink) {
        this.context = context;
        this.handler = handler;
        this.sink = sink;
    }

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            push(false);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            push(false);
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    void start() {
        if (BuildConfig.PUBLICO) return; // pública sem leitor de notificações: sem música no painel
        try {
            MediaSessionManager msm = context.getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(context, NavListener.class);
            msm.addOnActiveSessionsChangedListener(this::pick, me, handler);
            pick(msm.getActiveSessions(me));
            Logger.line("Música: monitorando o tocador");
        } catch (SecurityException e) {
            Logger.line("Música: falta a leitura de notificações (Liberar Maps)");
        }
    }

    /** Prefere o tocador que está tocando; senão o primeiro da lista. */
    private void pick(List<MediaController> list) {
        MediaController chosen = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null && !list.isEmpty()) chosen = list.get(0);
        }
        if (current != null) current.unregisterCallback(callback);
        current = chosen;
        if (current != null) current.registerCallback(callback, handler);
        push(false);
    }

    /** Reenvia tudo (usado quando o painel conecta). */
    void resend() {
        lastText = "";
        lastPlaying = null;
        lastSession = null;
        push(true);
    }

    private void push(boolean force) {
        MediaController c = current;
        boolean session = c != null;
        if (force || lastSession == null || lastSession != session) {
            lastSession = session;
            sink.send("música: " + (session ? "tocador ativo" : "sem tocador"),
                    List.of(DashProtocol.Tlv.ofByte(5, 0x17, session ? DashProtocol.YES : DashProtocol.NO)));
        }
        if (!session) return;
        PlaybackState st = c.getPlaybackState();
        boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (force || lastPlaying == null || lastPlaying != playing) {
            lastPlaying = playing;
            sink.send("música: " + (playing ? "tocando" : "pausada"),
                    List.of(DashProtocol.Tlv.ofByte(5, 0x19, playing ? DashProtocol.YES : DashProtocol.NO)));
        }
        MediaMetadata m = c.getMetadata();
        String title = text(m, MediaMetadata.METADATA_KEY_TITLE);
        String album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
        String artist = text(m, MediaMetadata.METADATA_KEY_ARTIST);
        coverTitle = title;
        coverArtist = artist.isEmpty() ? album : artist;
        coverPlaying = playing;
        android.graphics.Bitmap art = null;
        if (m != null) {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        if (art != cover) setCover(art);
        if (title.isEmpty()) title = "Tocando agora";
        String key = title + "|" + album + "|" + artist;
        if (!force && key.equals(lastText)) return;
        lastText = key;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        write(b, title);
        b.write(0);
        write(b, album);
        b.write(0);
        write(b, artist);
        sink.send("música: " + title + " / " + artist, List.of(new DashProtocol.Tlv(5, 0x0D, b.toByteArray())));
    }

    // ---- Capa do álbum no painel (modo digital, sem navegação) ----
    private volatile android.graphics.Bitmap cover;
    private volatile android.graphics.Bitmap coverBlur;
    private volatile String coverTitle = "";
    private volatile String coverArtist = "";
    private volatile boolean coverPlaying;
    private final android.graphics.Paint pic = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Paint dim = new android.graphics.Paint();
    private final android.graphics.Paint titlePaint = textPaint(28, true, 0xFFFFFFFF);
    private final android.graphics.Paint artistPaint = textPaint(21, false, 0xFFCFD3DA);

    private static android.graphics.Paint textPaint(float size, boolean bold, int color) {
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        if (bold) p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return p;
    }

    private String lastCoverSize = "";
    private long lastCoverLog;

    private void setCover(android.graphics.Bitmap art) {
        cover = art;
        if (art == null) {
            coverBlur = null;
            return;
        }
        // "desfoque" barato: reduz muito e amplia de volta com filtro
        android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(art, 12, 12, true);
        coverBlur = android.graphics.Bitmap.createScaledBitmap(small, 526, 526, true);
        String size = art.getWidth() + "x" + art.getHeight();
        long now = System.currentTimeMillis();
        if (!size.equals(lastCoverSize) || now - lastCoverLog > 30_000) {
            Logger.line("Música: capa recebida " + size);
            lastCoverSize = size;
            lastCoverLog = now;
        }
    }

    /** Há música tocando com capa ou título para mostrar. */
    boolean hasCover() {
        return coverPlaying && current != null;
    }

    void paintCover(android.graphics.Canvas c, int w, int h) {
        c.drawColor(0xFF121216);
        android.graphics.Bitmap blur = coverBlur, art = cover;
        if (blur != null) {
            c.drawBitmap(blur, null, new android.graphics.Rect(0, (h - w) / 2, w, (h + w) / 2), pic);
            dim.setColor(0x99000000);
            c.drawRect(0, 0, w, h, dim);
        }
        int size = h - 50;
        int left = 25, top = 25;
        if (art != null) {
            c.drawBitmap(art, null, new android.graphics.Rect(left, top, left + size, top + size), pic);
        } else {
            dim.setColor(0xFF2A2D35);
            c.drawRect(left, top, left + size, top + size, dim);
            c.drawText("♪", left + size / 2f - 20, top + size / 2f + 25, textPaint(90, true, 0xFF8890A0));
        }
        float x = left + size + 22;
        float maxW = w - x - 15;
        String[] lines = wrap(coverTitle, maxW, titlePaint);
        float y = h / 2f - (lines.length == 2 ? 26 : 8);
        for (String line : lines) {
            drawFit(c, line, x, y, maxW, titlePaint);
            y += 34;
        }
        drawFit(c, coverArtist, x, y + 2, maxW, artistPaint);
    }

    /** Quebra o título em até 2 linhas pelo espaço entre palavras; a 2ª é cortada com "…" se precisar. */
    private static String[] wrap(String s, float maxW, android.graphics.Paint p) {
        if (s == null || p.measureText(s) <= maxW) return new String[]{s == null ? "" : s};
        String[] words = s.split(" ");
        StringBuilder first = new StringBuilder();
        int i = 0;
        while (i < words.length) {
            String next = first.length() == 0 ? words[i] : first + " " + words[i];
            if (p.measureText(next) > maxW && first.length() > 0) break;
            first.setLength(0);
            first.append(next);
            i++;
        }
        StringBuilder rest = new StringBuilder();
        for (; i < words.length; i++) rest.append(rest.length() == 0 ? "" : " ").append(words[i]);
        return rest.length() == 0 ? new String[]{first.toString()} : new String[]{first.toString(), rest.toString()};
    }

    private static void drawFit(android.graphics.Canvas c, String s, float x, float y, float maxW, android.graphics.Paint p) {
        if (s == null || s.isEmpty()) return;
        String t = s;
        while (t.length() > 1 && p.measureText(t) > maxW) t = t.substring(0, t.length() - 1);
        if (!t.equals(s) && t.length() > 1) t = t.substring(0, t.length() - 1) + "…";
        c.drawText(t, x, y, p);
    }

    private static String text(MediaMetadata m, String key) {
        if (m == null) return "";
        CharSequence s = m.getText(key);
        return s == null ? "" : s.toString().trim();
    }

    private static void write(ByteArrayOutputStream b, String s) {
        String cut = s.length() > 19 ? s.substring(0, 19) : s;
        for (int i = 0; i < cut.length(); i++) {
            char c = cut.charAt(i);
            b.write(c <= 255 ? c : '?');
        }
    }
}
