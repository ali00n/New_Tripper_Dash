package br.com.evandro.tripper;

import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.List;

/** Desenha quadros, codifica em H.264 e manda por RTP para o painel (192.168.1.1:5000). */
final class VideoProjector {
    interface Painter {
        void paint(Canvas canvas, int width, int height, long frame);
    }

    static final int WIDTH = 526;
    static final int HEIGHT = 300;
    /** Quadros por segundo (ajustável pelo remoto, vale ao religar o vídeo). */
    static volatile int fps = 12;
    /** Teto de quadros por segundo baixado pelo controle de temperatura (ThermalGuard). */
    static volatile int fpsCap = 12;
    /** Quadro completo a cada 3 s (antes 1 s): menos rajada no Wi-Fi do painel. */
    /** Ajustáveis ao vivo pelo acesso remoto (videocfg) para achar o que o painel aguenta em movimento. */
    /** Quadro completo a cada (ms). 22/11: 3 s fazia o painel "segurar e soltar" a cada perda; 1 s ficou melhor. */
    static volatile int keyEveryMs = 1000;
    static volatile int bitrate = 1_000_000;
    /** Espaço entre pacotes do mesmo quadro (µs); 0 = rajada (como era). */
    static volatile int paceUs = 1500;
    /** Destino do vídeo; trocável pelo acesso remoto (unicast 192.168.1.1 ou broadcast 192.168.1.255). */
    static volatile String target = "192.168.1.255";
    private static final int VIDEO_PORT = 5000;

    interface SocketFactory {
        DatagramSocket create() throws java.io.IOException;
    }

    private final Painter painter;
    private final SocketFactory socketFactory;
    private MediaCodec codec;
    private Surface surface;
    private DatagramSocket socket;
    private RtpPacketizer rtp;
    private volatile boolean running;
    private long frames;
    private long packets;

    VideoProjector(Painter painter, SocketFactory socketFactory) {
        this.painter = painter;
        this.socketFactory = socketFactory;
    }

    boolean isRunning() {
        return running;
    }

    synchronized boolean start() {
        if (running) return true;
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, Math.max(1, keyEveryMs / 1000));
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            f.setInteger("prepend-sps-pps-to-idr-frames", 1);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            socket = socketFactory.create();
            socket.setBroadcast(true);
            Logger.line("Vídeo: destino " + target + ":" + VIDEO_PORT);
            socket.setTrafficClass(136);
            rtp = new RtpPacketizer(fps);
            running = true;
            frames = 0;
            packets = 0;
            new Thread(this::drawLoop, "video-draw").start();
            new Thread(this::drainLoop, "video-send").start();
            Logger.line("Vídeo ligado: " + WIDTH + "x" + HEIGHT + " @" + fps + " fps, codec " + codec.getName());
            return true;
        } catch (Exception e) {
            Logger.line("ERRO ao ligar o vídeo: " + e);
            stop();
            return false;
        }
    }

    void requestKeyFrame() {
        MediaCodec c = codec;
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(b);
        } catch (Exception ignored) {
        }
    }

    private void drawLoop() {
        while (running) {
            long interval = 1000 / Math.max(4, Math.min(fps, fpsCap));
            long t0 = System.currentTimeMillis();
            try {
                Canvas c = surface.lockHardwareCanvas();
                try {
                    painter.paint(c, WIDTH, HEIGHT, frames);
                } finally {
                    surface.unlockCanvasAndPost(c);
                }
                frames++;
                if (frames % Math.max(1, Math.round(fps * keyEveryMs / 1000.0)) == 0) requestKeyFrame();
            } catch (Exception e) {
                if (running) Logger.line("ERRO ao desenhar: " + e);
            }
            long wait = interval - (System.currentTimeMillis() - t0);
            if (wait > 0) sleep(wait);
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastReport = System.currentTimeMillis();
        while (running) {
            try {
                int idx = codec.dequeueOutputBuffer(info, 100_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    takeCsd(codec.getOutputFormat());
                    continue;
                }
                if (idx < 0) continue;
                ByteBuffer buf = codec.getOutputBuffer(idx);
                byte[] data = new byte[info.size];
                if (buf != null) {
                    buf.position(info.offset);
                    buf.get(data, 0, info.size);
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    rtp.setConfig(data);
                    Logger.line("Vídeo: SPS/PPS recebidos (" + data.length + " B): "
                            + DashProtocol.hex(java.util.Arrays.copyOf(data, Math.min(data.length, 16))));
                    continue;
                }
                boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                if (key) keyFrames++;
                if (key && keyFrames == 1) logSps(data);
                if (sentFrames < 6) logNals(data, key);
                sentFrames++;
                List<byte[]> pkts = rtp.packetize(data, key, info.presentationTimeUs);
                InetAddress dash = InetAddress.getByName(target);
                int dropped = 0;
                for (byte[] p : pkts) {
                    if (Faults.dropVideoPacket()) {
                        dropped++;
                        continue;
                    }
                    int pace = paceUs;
                    if (pace > 0 && sentInFrame++ > 0) java.util.concurrent.locks.LockSupport.parkNanos(pace * 1000L);
                    try {
                        socket.send(new DatagramPacket(p, p.length, dash, VIDEO_PORT));
                    } catch (java.io.IOException e) {
                        Stats.sendError();
                        throw e;
                    }
                }
                sentInFrame = 0;
                packets += pkts.size();
                Stats.video(data.length, pkts.size(), dropped, key);
                if (System.currentTimeMillis() - lastReport > 10_000) {
                    Logger.line("Vídeo: " + frames + " quadros desenhados, " + keyFrames + " quadros-chave, " + packets + " pacotes enviados");
                    lastReport = System.currentTimeMillis();
                }
            } catch (Exception e) {
                if (running) Logger.line("ERRO no envio do vídeo: " + e);
                sleep(200);
            }
        }
    }

    private int sentInFrame;
    private int sentFrames;

    /** Troca a qualidade com o vídeo rodando (o encoder aceita sem reiniciar). */
    void applyBitrate() {
        MediaCodec c = codec;
        if (c == null) return;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
            c.setParameters(b);
        } catch (Exception e) {
            Logger.line("Vídeo: não aceitou o bitrate novo: " + e);
        }
    }
    private int keyFrames;

    private void logSps(byte[] data) {
        for (byte[] n : RtpPacketizer.splitNals(data)) {
            int t = n.length > 0 ? n[0] & 0x1F : -1;
            if (t == 7 || t == 8) Logger.line("Vídeo: NAL " + t + " = " + DashProtocol.hex(n));
        }
    }

    /** Encoders c2 da MTK entregam SPS/PPS no formato de saída (csd-0/csd-1), não como buffer. */
    private void takeCsd(MediaFormat format) {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (String k : new String[]{"csd-0", "csd-1"}) {
            ByteBuffer b = format.getByteBuffer(k);
            if (b == null) continue;
            byte[] d = new byte[b.remaining()];
            b.duplicate().get(d);
            all.write(d, 0, d.length);
        }
        byte[] csd = all.toByteArray();
        if (csd.length == 0) {
            Logger.line("Vídeo: formato mudou, mas sem csd");
            return;
        }
        rtp.setConfig(csd);
        Logger.line("Vídeo: SPS/PPS do formato (" + csd.length + " B): " + DashProtocol.hex(csd));
    }

    private void logNals(byte[] data, boolean key) {
        StringBuilder s = new StringBuilder("Vídeo: quadro " + sentFrames + (key ? " CHAVE" : "") + " " + data.length + " B, NALs:");
        for (byte[] n : RtpPacketizer.splitNals(data)) s.append(' ').append(n.length > 0 ? (n[0] & 0x1F) : -1).append('(').append(n.length).append(')');
        s.append(rtp.hasConfig() ? " [com SPS]" : " [SEM SPS]");
        Logger.line(s.toString());
    }

    synchronized void stop() {
        running = false;
        sleep(150);
        try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
        try { if (surface != null) surface.release(); } catch (Exception ignored) { }
        if (socket != null) socket.close();
        codec = null;
        surface = null;
        socket = null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
