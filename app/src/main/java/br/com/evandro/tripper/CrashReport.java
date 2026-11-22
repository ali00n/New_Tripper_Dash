package br.com.evandro.tripper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Se o app fechar sozinho, guarda o erro e mostra na próxima abertura, com botão para enviar.
 * 22/11: usuário com Realme 12+ relatou "abre e fecha" e não tínhamos como ver o motivo.
 */
final class CrashReport {
    private CrashReport() {}

    private static volatile boolean installed;

    static void install(Context context) {
        if (installed) return;
        installed = true;
        File out = new File(context.getApplicationContext().getFilesDir(), "ultimo-erro.txt");
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (FileOutputStream f = new FileOutputStream(out)) {
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                String text = "Cow Tripper Dash " + BuildConfig.VERSION_NAME + (BuildConfig.PUBLICO ? "" : " (dev)")
                        + "\nCelular: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ")\nThread: " + thread.getName() + "\n\n" + sw;
                f.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void showIfAny(Activity activity) {
        File f = new File(activity.getFilesDir(), "ultimo-erro.txt");
        if (!f.exists()) return;
        String text;
        try {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return;
        }
        f.delete();
        Logger.line("App tinha fechado sozinho: " + text.split("\n", 6)[Math.min(4, text.split("\n").length - 1)]);
        new AlertDialog.Builder(activity)
                .setTitle("O app fechou sozinho da última vez")
                .setMessage("Ajude a corrigir: envie este erro pelo Instagram @mimosa450cc.\n\n" + text.substring(0, Math.min(700, text.length())))
                .setPositiveButton("Enviar", (d, w) -> activity.startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Enviar erro")))
                .setNeutralButton("Copiar", (d, w) -> {
                    ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("erro", text));
                })
                .setNegativeButton("Fechar", null)
                .show();
    }
}
