package br.com.evandro.tripper;

import android.app.Notification;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Lê a notificação de navegação do Google Maps e entrega o passo ao serviço do painel. */
public class NavListener extends NotificationListenerService {
    private static final String MAPS = "com.google.android.apps.maps";
    private IconHash icons;
    private String lastRaw = "";
    private int lastManeuver = 9;

    @Override
    public void onListenerConnected() {
        icons = new IconHash(this);
        Logger.init(this);
        Logger.line("Leitor de notificações ligado");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Notification call = sbn.getNotification();
        if (Notification.CATEGORY_CALL.equals(call.category)) {
            CharSequence who = call.extras.getCharSequence(Notification.EXTRA_TITLE);
            if (who != null) DashService.onCallerName(who.toString());
            return;
        }
        if (!MAPS.equals(sbn.getPackageName()) || !isNavigation(sbn.getNotification())) return;
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        String big = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String sub = str(x.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String info = str(x.getCharSequence(Notification.EXTRA_INFO_TEXT));
        String raw = "título=[" + title + "] texto=[" + text + "] grande=[" + big + "] sub=[" + sub + "] info=[" + info + "]";

        Bitmap arrow = IconHash.toBitmap(this, n.getLargeIcon());
        String arrowHash = arrow != null ? IconHash.hash(arrow) : null;
        if (!raw.equals(lastRaw)) {
            Logger.line("MAPS " + raw + (arrowHash != null ? " seta=" + arrowHash.substring(0, Math.min(8, arrowHash.length())) : " sem seta"));
            lastRaw = raw;
        }
        if (arrow != null) icons.saveSample(arrowHash, arrow);

        String instruction = text.isEmpty() ? big : text;
        int maneuver = NavParser.maneuverFromText(instruction + " " + title);
        if (maneuver != NavParser.UNKNOWN && arrowHash != null && maneuver != 28) icons.learn(arrowHash, maneuver);
        if (maneuver == NavParser.UNKNOWN && arrowHash != null) maneuver = icons.lookup(arrowHash);
        if (maneuver == NavParser.UNKNOWN) {
            Logger.line("   seta desconhecida, mantendo a anterior (" + lastManeuver + ")");
            maneuver = lastManeuver;
        }
        lastManeuver = maneuver;

        int toTurn = NavParser.meters(title);
        if (toTurn < 0) toTurn = NavParser.meters(instruction);
        String trip = sub + " " + info;
        int left = NavParser.meters(trip);
        int seconds = NavParser.remainingSeconds(trip);
        String eta = NavParser.eta(trip);

        NavState state = new NavState(maneuver, maneuver == 10 ? NavParser.roundaboutExit(instruction) : 255,
                Math.max(toTurn, 0), Math.max(left, 0), eta != null ? eta : "0000",
                NavParser.remainingField(Math.max(seconds, 0)), street(instruction));
        DashService.onNav(state, NavParser.remainingUnit(Math.max(seconds, 0)));
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (MAPS.equals(sbn.getPackageName()) && isNavigation(sbn.getNotification())) {
            Logger.line("MAPS navegação encerrada");
            lastRaw = "";
            DashService.onNavEnded();
        }
    }

    private static boolean isNavigation(Notification n) {
        return Notification.CATEGORY_NAVIGATION.equals(n.category) || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    /** Nome da rua: o que vem depois de "na", "em direção a", "para". */
    static String street(String instruction) {
        String[] markers = {" em direção à ", " em direção a ", " em direção ao ", " sentido ", " na ", " no ", " para a ", " para o ", " para "};
        for (String m : markers) {
            int i = instruction.indexOf(m);
            if (i >= 0) return instruction.substring(i + m.length()).trim();
        }
        return instruction.trim();
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }
}
