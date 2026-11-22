package br.com.evandro.tripper;

import android.content.Context;
import android.os.PowerManager;

/**
 * Celular no guidão, no sol do Rio: quando o Android avisa que está quente, o app alivia sozinho
 * (menos quadros no vídeo e menos fotos do mapa) e volta ao normal quando esfria.
 */
final class ThermalGuard {
    private ThermalGuard() {
    }

    private static int lastLevel = -1;

    static void start(Context context) {
        PowerManager pm = context.getSystemService(PowerManager.class);
        if (pm == null) return;
        apply(pm.getCurrentThermalStatus());
        pm.addThermalStatusListener(context.getMainExecutor(), ThermalGuard::apply);
    }

    static void apply(int status) {
        // 21/11: RJ é quente e o celular vai no bolso; "SEVERE" (3) acontecia o tempo todo e derrubava o vídeo.
        // Agora só alivia no CRITICAL (4) e só vai ao mínimo no EMERGENCY (5), perto de o Android desligar sozinho.
        int level = status >= PowerManager.THERMAL_STATUS_EMERGENCY ? 2 : status >= PowerManager.THERMAL_STATUS_CRITICAL ? 1 : 0;
        if (level == lastLevel) return;
        lastLevel = level;
        switch (level) {
            case 2:
                VideoProjector.fpsCap = 6;
                VectorMap.extraGapMs = 700;
                break;
            case 1:
                VideoProjector.fpsCap = Math.min(10, VideoProjector.fps);
                VectorMap.extraGapMs = 200;
                break;
            default:
                VideoProjector.fpsCap = VideoProjector.fps;
                VectorMap.extraGapMs = 0;
        }
        Logger.line("Temperatura: nível " + status + (level == 0 ? " → normal (12 q/s)" : level == 1 ? " → quente, aliviando (10 q/s)" : " → muito quente, mínimo (6 q/s)"));
    }
}
