package br.com.evandro.tripper;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Log único do app: tela, arquivo e logcat. */
final class Logger {
    interface Listener {
        void onLine(String line);
    }

    private static final String TAG = "TripperF1";
    private static final int MEMORY_LINES = 400;
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final ArrayDeque<String> recent = new ArrayDeque<>();
    private static FileWriter file;
    private static Listener listener;

    private Logger() {}

    static synchronized void init(Context context) {
        if (file != null) return;
        try {
            File dir = context.getExternalFilesDir(null);
            String name = "tripper-log-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date()) + ".txt";
            file = new FileWriter(new File(dir, name), true);
        } catch (Exception ignored) {
            file = null;
        }
    }

    static synchronized void setListener(Listener l) {
        listener = l;
    }

    static synchronized String history() {
        StringBuilder sb = new StringBuilder();
        for (String s : recent) sb.append(s).append('\n');
        return sb.toString();
    }

    static synchronized void line(String text) {
        String line = CLOCK.format(new Date()) + " " + text;
        android.util.Log.i(TAG, line);
        recent.addLast(line);
        while (recent.size() > MEMORY_LINES) recent.removeFirst();
        if (file != null) {
            try {
                file.write(line + "\n");
                file.flush();
            } catch (Exception ignored) {
            }
        }
        if (listener != null) listener.onLine(line);
    }
}
