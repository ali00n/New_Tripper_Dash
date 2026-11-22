package br.com.evandro.tripper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Depois de uma atualização, religa o serviço (e o acesso remoto) sem abrir o app. */
public class UpdatedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.init(context);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        Logger.line(boot ? "Celular ligou, iniciando o serviço" : "App atualizado, religando o serviço");
        DashService.startIdle(context);
    }
}
