package br.com.evandro.tripper;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tela de controle: conectar, liberar permissões e ver o log. */
public class MainActivity extends Activity {
    static final String EXTRA_MIRROR = "espelhar";
    private static final int REQ_MIRROR = 42;
    private static final int REQ_ICON = 43;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView logView;
    private TextView status;
    private TextView tripRec;
    private LinearLayout tripListBox;

    private void showTripsLater() {
        if (tripListBox != null) showTrips(tripListBox);
    }

    private final Runnable previewLoop = new Runnable() {
        @Override
        public void run() {
            if (phoneMap != null) {
                try {
                    phoneMap.update();
                } catch (Throwable t) {
                    Logger.line("Mapa do celular: " + t);
                }
            } else if (mapView != null && mapView.getWidth() > 0) {
                int w = 540, h = Math.round(540f * mapView.getHeight() / mapView.getWidth());
                android.graphics.Bitmap b = DashService.mapPreview(w, h);
                if (b != null) mapView.setImageBitmap(b);
            }
            if (statusPill != null) refreshHome();
            ui.postDelayed(this, 700);
        }
    };
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashReport.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS}, 1);
        Logger.init(this);
        DashService.startIdle(this);
        setContentView(buildUi());
        if (state == null) showIntro(true);
        if (getIntent().getBooleanExtra(EXTRA_MIRROR, false)) askMirror();
        if (BuildConfig.PUBLICO) checkUpdate();
        CrashReport.showIfAny(this);
        handleShare(getIntent());
        logView.setText(Logger.history());
        Logger.setListener(line -> ui.post(() -> {
            logView.append(line + "\n");
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        }));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_MIRROR, false)) askMirror();
        handleShare(intent);
    }

    /** Local compartilhado por outro app (ou qualquer texto): vira destino da rota. */
    private void handleShare(Intent intent) {
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) return;
        Logger.line("Rota: recebido do compartilhar: " + text.replace('\n', ' '));
        goTo(text);
    }

    /** Traça a rota no painel. */
    private void goTo(String text) {
        DashService.startIdle(this);
        DashService.setDestination(text, () -> {}, msg -> ui.post(() -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()));
    }

    // ---- sugestões enquanto digita ----
    private final java.util.concurrent.ExecutorService searcher = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Runnable pendingSearch;
    private volatile String lastQuery = "";

    private void watchSuggestions(EditText field, LinearLayout box) {
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String q = e.toString().trim();
                if (pendingSearch != null) ui.removeCallbacks(pendingSearch);
                lastQuery = q;
                if (q.length() < 3) {
                    box.removeAllViews();
                    box.setVisibility(android.view.View.GONE);
                    return;
                }
                pendingSearch = () -> searcher.execute(() -> {
                    try {
                        java.util.List<PlaceSearch.Suggestion> list = DashService.suggest(q);
                        ui.post(() -> { if (q.equals(lastQuery)) showSuggestions(field, box, list); });
                    } catch (Exception ex) {
                        ui.post(() -> {
                            if (!q.equals(lastQuery)) return;
                            box.removeAllViews();
                            box.addView(hint("Sem internet para sugerir agora. O botão Ir tenta de novo."));
                            box.setVisibility(android.view.View.VISIBLE);
                        });
                    }
                });
                ui.postDelayed(pendingSearch, 350);
            }
        });
    }

    private void showSuggestions(EditText field, LinearLayout box, java.util.List<PlaceSearch.Suggestion> list) {
        box.removeAllViews();
        if (list.isEmpty()) {
            box.addView(hint("Nada encontrado. Tente com o bairro ou a cidade."));
        }
        for (PlaceSearch.Suggestion sg : list) {
            LinearLayout item = column();
            item.setPadding(dp(4), dp(10), dp(4), dp(10));
            LinearLayout top = new LinearLayout(this);
            top.addView(text(sg.title, 16, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(text(sg.distance(), 13, GRAY, false));
            item.addView(top);
            if (!sg.detail.isEmpty()) item.addView(text(sg.detail, 13, GRAY, false));
            item.setOnClickListener(v -> {
                lastQuery = "";
                field.setText("");
                box.removeAllViews();
                box.setVisibility(android.view.View.GONE);
                hideKeyboard(field);
                DashService.startIdle(this);
                DashService.goToPlace(sg.lat, sg.lon, sg.title);
            });
            box.addView(item);
        }
        box.setVisibility(android.view.View.VISIBLE);
    }

    private void hideKeyboard(android.view.View v) {
        android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    /** Pede ao Android a gravação de tela (o toque é obrigatório a cada sessão). */
    private void askMirror() {
        android.media.projection.MediaProjectionManager mpm = getSystemService(android.media.projection.MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MIRROR);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ICON) {
            saveIcon(resultCode == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
        if (requestCode != REQ_MIRROR) return;
        if (resultCode != RESULT_OK || data == null) {
            Logger.line("Espelho: gravação de tela recusada");
            return;
        }
        DashService.startMirror(this, resultCode, data);
        openWaze();
    }

    /** Abre o Waze (o espelho já está gravando a tela); sem Waze instalado, avisa. */
    private void openWaze() {
        Intent waze = getPackageManager().getLaunchIntentForPackage("com.waze");
        if (waze == null) {
            android.widget.Toast.makeText(this, "Waze não está instalado neste celular.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(waze);
    }

    /** Ícone desenhado pelo app (apaga a imagem própria, se houver). */
    private void setIcon(String style) {
        getSharedPreferences("painel", MODE_PRIVATE).edit().putString("icone", style).apply();
        new java.io.File(getFilesDir(), "icone.png").delete();
        DashService.reloadMarker();
    }

    /** Copia a imagem escolhida para files/icone.png (vira o ícone da moto no mapa). */
    private void saveIcon(Uri uri) {
        if (uri == null) return;
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in);
            if (b == null) throw new java.io.IOException("imagem inválida");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "icone.png"))) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            DashService.reloadMarker();
        } catch (Exception e) {
            Logger.line("Ícone: falhou " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 7) DashService.watchCallsNow();
    }

    /** Botões flutuantes do mapa: +, − e Centralizar (aparece quando a pessoa arrasta o mapa). */
    private void addMapButtons(android.widget.FrameLayout root) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        Button plus = mapButton("+"), minus = mapButton("−");
        android.widget.ImageButton center = new android.widget.ImageButton(this);
        center.setImageBitmap(locateIcon());
        center.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        center.setBackground(round(Color.argb(230, 20, 24, 30), 28));
        center.setContentDescription("Voltar para a minha posição");
        plus.setOnClickListener(v -> phoneMap.zoomBy(1));
        minus.setOnClickListener(v -> phoneMap.zoomBy(-1));
        center.setOnClickListener(v -> phoneMap.recenter());
        phoneMap.setListener(f -> center.setBackground(round(f ? Color.argb(230, 20, 24, 30) : Color.rgb(66, 146, 255), 28)));
        for (Button b : new Button[]{plus, minus}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
            lp.topMargin = dp(8);
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            col.addView(b, lp);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(56), dp(56));
        cl.topMargin = dp(14);
        col.addView(center, cl);
        android.widget.FrameLayout.LayoutParams fl = new android.widget.FrameLayout.LayoutParams(-2, -2);
        fl.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
        fl.topMargin = mapH - dp(MAP_BUTTONS_DP);
        fl.rightMargin = dp(12);
        root.addView(col, fl);
        mapButtonsView = col;
    }

    /** Altura da coluna de botões + folga acima da aba/barra do puxador. */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private Button offlineButton(OfflineMaps.Area a, String label, TextView status) {
        return button((OfflineMaps.downloaded(this, a) ? "Atualizar mapa de " + a.name : label), v -> startOffline(a, status));
    }

    private void startOffline(OfflineMaps.Area a, TextView status) {
        if (!OfflineMaps.onWifi(this)) {
            android.widget.Toast.makeText(this, "Conecte no Wi-Fi para baixar o mapa (é grande).", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        status.setText(a.name + ": começando…");
        OfflineMaps.download(this, a, msg -> ui.post(() -> status.setText(msg)));
    }

    private static final int MAP_BUTTONS_DP = 48 * 2 + 8 * 2 + 56 + 14 + 70;

    /** Ícone de localização: mira (círculo, ponto no meio e quatro traços). */
    private android.graphics.Bitmap locateIcon() {
        int s = dp(30);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.4f));
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        float m = s / 2f, r = s * 0.30f;
        c.drawCircle(m, m, r, p);
        c.drawLine(m, 0 + dp(1.5f), m, m - r, p);
        c.drawLine(m, m + r, m, s - dp(1.5f), p);
        c.drawLine(dp(1.5f), m, m - r, m, p);
        c.drawLine(m + r, m, s - dp(1.5f), m, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        c.drawCircle(m, m, s * 0.12f, p);
        return b;
    }

    private Button mapButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(round(Color.argb(230, 20, 24, 30), 24));
        return b;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (phoneMap != null) phoneMap.onStart();
    }

    @Override
    protected void onStop() {
        if (phoneMap != null) phoneMap.onStop();
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (phoneMap != null) phoneMap.onLowMemory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (phoneMap != null) phoneMap.onResume();
        visible = this;
        refreshStatus();
        ui.removeCallbacks(previewLoop);
        ui.post(previewLoop);
    }

    private android.view.View introView;

    /** Abertura no celular (mesma do painel): ao abrir o app e ao conectar no painel. Não pula (estilo WinRAR). */
    void showIntro(boolean moo) {
        ui.post(() -> {
            SharedPreferences pp = getSharedPreferences("painel", MODE_PRIVATE);
            if (introView != null || rootView == null) return;
            Intro in = new Intro();
            in.start();
            android.view.View v = new android.view.View(this) {
                @Override
                protected void onDraw(android.graphics.Canvas c) {
                    c.drawColor(Color.rgb(16, 19, 24));
                    float s = Math.min(getWidth() / 526f, getHeight() / 300f);
                    c.save();
                    c.translate((getWidth() - 526 * s) / 2f, (getHeight() - 300 * s) / 2f);
                    c.scale(s, s);
                    in.paint(c, 526, 300);
                    c.restore();
                    if (in.active()) postInvalidateOnAnimation();
                    else post(MainActivity.this::hideIntro);
                }
            };
            v.setContentDescription("Abertura do Tripper");
            v.setClickable(true); // segura os toques: a abertura não pula
            introView = v;
            rootView.addView(v, new android.widget.FrameLayout.LayoutParams(-1, -1));
        });
    }

    private void hideIntro() {
        if (introView != null && rootView != null) rootView.removeView(introView);
        introView = null;
    }

    /** O serviço chama ao conectar no painel: se o app estiver aberto, mostra junto (sem mugir de novo). */
    static void introOnConnect() {
        MainActivity a = visible;
        if (a != null) a.showIntro(false);
    }

    /** App aberto na tela (o mapa do celular precisa do GPS ligado). */
    static boolean onScreen() {
        return visible != null;
    }

    private void refreshStatus() {
        String n = BuildConfig.PUBLICO ? "" : notificationAccess() ? "✔ leitura do Maps liberada" : "✖ falta liberar a leitura do Maps";
        String b = batteryFree() ? "✔ bateria liberada" : "✖ falta liberar a bateria";
        boolean always = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String g = always ? "✔ localização o tempo todo" : "✖ falta localização \"o tempo todo\" (mapa com tela apagada)";
        String p = DashService.isAuthenticated() ? "✔ painel conectado" : "… painel desconectado";
        if (status != null) status.setText((n.isEmpty() ? "" : n + "\n") + b + "\n" + g + "\n" + p);
        if (tripRec != null) {
            String t = DashService.tripStatus();
            tripRec.setText(t == null ? "" : t);
        }
        ui.postDelayed(this::refreshStatus, 2000);
    }

    private boolean notificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(new ComponentName(this, NavListener.class).flattenToString());
    }

    private boolean batteryFree() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // ---------------- Estilo B: mapa primeiro ----------------
    private static final int ORANGE = Color.rgb(252, 82, 0);
    private static final int INK = Color.rgb(26, 26, 26);
    private static final int GRAY = Color.rgb(107, 107, 107);
    private static final int TILE_BG = Color.rgb(244, 244, 244);

    private android.widget.ImageView mapView;
    private PhoneMap phoneMap;
    private TextView turnDist, turnStreet, statusPill;
    private TextView destLabel, destName, destMeta, tileDone, tileLeft, tripKm, tripState;
    private Button recButton;
    private int mapH;

    static volatile MainActivity visible;

    /** Captura a tela do app (para conferência pelo acesso remoto). */
    static android.graphics.Bitmap capture() {
        MainActivity a = visible;
        if (a == null) return null;
        final android.graphics.Bitmap[] out = new android.graphics.Bitmap[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        a.ui.post(() -> {
            try {
                android.view.View v = a.getWindow().getDecorView();
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(v.getWidth(), v.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                v.draw(new android.graphics.Canvas(b));
                out[0] = android.graphics.Bitmap.createScaledBitmap(b, v.getWidth() / 2, v.getHeight() / 2, true);
            } catch (Exception ignored) {
            }
            done.countDown();
        });
        try {
            done.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(previewLoop); // app em segundo plano não pede GPS (evita o liga/desliga)
        if (phoneMap != null) phoneMap.onPause();
        visible = null;
        super.onPause();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.GradientDrawable round(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private android.view.View buildUi() {
        SharedPreferences prefs = getSharedPreferences("painel", MODE_PRIVATE);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        // Mapa ao vivo ao fundo (desenhado em pé para o celular).
        int screenH = getResources().getDisplayMetrics().heightPixels;
        mapH = (int) (screenH * 0.58f);
        try {
            phoneMap = new PhoneMap(this);
            root.addView(phoneMap.view, new android.widget.FrameLayout.LayoutParams(-1, mapH));
            addMapButtons(root);
        } catch (Throwable t) {
            Logger.line("Mapa do celular: não abriu, fica a imagem do painel: " + t);
            phoneMap = null;
            mapView = new android.widget.ImageView(this);
            mapView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            mapView.setContentDescription("Mapa ao vivo");
            root.addView(mapView, new android.widget.FrameLayout.LayoutParams(-1, mapH));
        }

        // Topo: próxima curva + estado do painel.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(36), dp(14), 0);
        LinearLayout turn = new LinearLayout(this);
        turn.setOrientation(LinearLayout.VERTICAL);
        turn.setBackground(round(Color.argb(220, 20, 20, 22), 14));
        turn.setPadding(dp(14), dp(10), dp(14), dp(10));
        turnDist = text("—", 20, Color.WHITE, true);
        turnStreet = text("Sem rota", 12, Color.rgb(214, 214, 214), false);
        turn.addView(turnDist);
        turn.addView(turnStreet);
        top.addView(turn, new LinearLayout.LayoutParams(0, -2, 1));
        statusPill = text("Painel", 12, Color.WHITE, true);
        statusPill.setBackground(round(Color.argb(220, 20, 20, 22), 999));
        statusPill.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.leftMargin = dp(10);
        statusPill.setOnClickListener(v -> ligarComAMoto());
        statusPill.setOnLongClickListener(v -> {
            if (!DashService.homeInfo().serviceOn) return false;
            DashService.stop(this);
            android.widget.Toast.makeText(this, "Painel desligado", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        top.addView(statusPill, pl);
        root.addView(top, new android.widget.FrameLayout.LayoutParams(-1, -2));

        // Folha branca de baixo.
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
        sbg.setColor(Color.WHITE);
        float r = dp(24);
        sbg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sbg);
        sheet.setPadding(dp(16), dp(10), dp(16), dp(10));
        // Puxador: toque ou arraste para baixar a aba (fica só o mapa) e para subir de novo.
        android.widget.FrameLayout grip = new android.widget.FrameLayout(this);
        grip.setContentDescription("Puxador da aba: toque ou arraste para baixar ou subir");
        android.view.View handle = new android.view.View(this);
        handle.setBackground(round(Color.rgb(200, 200, 200), 3));
        grip.addView(handle, new android.widget.FrameLayout.LayoutParams(dp(44), dp(5), android.view.Gravity.CENTER));
        sheet.addView(grip, new LinearLayout.LayoutParams(-1, dp(GRIP_DP)));
        grip.setOnTouchListener(new android.view.View.OnTouchListener() {
            float downY;

            @Override
            public boolean onTouch(android.view.View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    downY = e.getRawY();
                    return true;
                }
                if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    float dy = e.getRawY() - downY;
                    if (dy > dp(24)) setSheetCollapsed(true);
                    else if (dy < -dp(24)) setSheetCollapsed(false);
                    else setSheetCollapsed(!sheetCollapsed);
                    v.performClick();
                    return true;
                }
                return true;
            }
        });

        android.widget.FrameLayout content = new android.widget.FrameLayout(this);
        android.view.View[] pages = {homePage(), pertoPage(), diarioPage(), ajustesPage(prefs)};
        for (android.view.View p : pages) content.addView(p, new android.widget.FrameLayout.LayoutParams(-1, -1));
        sheet.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(0, dp(6), 0, 0);
        String[] names = {"Mapa", "Perto", "Diário", "Ajustes"};
        TextView[] tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            // Edição pública básica: só Mapa e Ajustes (Perto e Diário ficam na edição do Evandro).
            if (BuildConfig.PUBLICO && (i == 1 || i == 2)) continue;
            final int k = i;
            TextView t = text(names[i], 13, GRAY, true);
            t.setGravity(android.view.Gravity.CENTER);
            t.setPadding(0, dp(10), 0, dp(10));
            t.setOnClickListener(v -> showTab(k, pages, tabs, prefs));
            tabs[i] = t;
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        sheet.addView(nav);
        sheetView = sheet;
        sheetContent = content;
        sheetNav = nav;
        rootView = root;
        android.widget.FrameLayout.LayoutParams sl = new android.widget.FrameLayout.LayoutParams(-1, screenH - mapH + dp(40));
        sl.gravity = android.view.Gravity.BOTTOM;
        root.addView(sheet, sl);
        int startTab = Math.min(prefs.getInt("aba2", 0), names.length - 1);
        if (tabs[startTab] == null) startTab = 0;
        showTab(startTab, pages, tabs, prefs);
        // Android 15 desenha por baixo das barras do sistema: afasta topo e navegação delas.
        sheetFullH = sl.height;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            top.setPadding(dp(14), bars.top + dp(8), dp(14), 0);
            sheet.setPadding(dp(16), dp(4), dp(16), dp(10) + bars.bottom);
            bottomInset = bars.bottom;
            // 22/11: no Android 15 o teclado cobre a tela; sem isto as sugestões da busca ficavam embaixo dele.
            int ime = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            imeLift = Math.max(0, ime - bars.bottom);
            imeTop = bars.top;
            applySheetState(false);
            return insets;
        });
        sheetCollapsed = prefs.getBoolean("aba_baixada", false);
        applySheetState(false);
        return root;
    }

    private static final int GRIP_DP = 30;
    private boolean sheetCollapsed;
    private int sheetFullH, bottomInset, imeLift, imeTop;
    private android.view.View sheetView, sheetContent, sheetNav, mapButtonsView;
    private android.widget.FrameLayout rootView;

    /** Baixa (só o mapa + barra com o puxador) ou sobe a aba de baixo; lembra a escolha. */
    private void setSheetCollapsed(boolean collapsed) {
        if (sheetCollapsed == collapsed) return;
        sheetCollapsed = collapsed;
        getSharedPreferences("painel", MODE_PRIVATE).edit().putBoolean("aba_baixada", collapsed).apply();
        if (collapsed && sheetView != null) hideKeyboard(sheetView);
        applySheetState(true);
    }

    private void applySheetState(boolean animate) {
        if (sheetView == null) return;
        if (animate) android.transition.TransitionManager.beginDelayedTransition(rootView);
        sheetContent.setVisibility(sheetCollapsed ? android.view.View.GONE : android.view.View.VISIBLE);
        sheetNav.setVisibility(sheetCollapsed ? android.view.View.GONE : android.view.View.VISIBLE);
        android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) sheetView.getLayoutParams();
        int barH = dp(GRIP_DP) + dp(4) + dp(10) + bottomInset;
        lp.height = sheetCollapsed ? barH : sheetFullH + bottomInset;
        // Teclado aberto: a aba sobe e encolhe para caber entre o topo e o teclado (busca + sugestões à vista).
        lp.bottomMargin = imeLift;
        if (imeLift > 0) {
            int room = getResources().getDisplayMetrics().heightPixels + bottomInset - imeLift - imeTop - dp(8);
            lp.height = Math.min(lp.height, Math.max(dp(160), room));
        }
        sheetView.setLayoutParams(lp);
        android.view.View map = phoneMap != null ? phoneMap.view : mapView;
        if (map != null) {
            android.widget.FrameLayout.LayoutParams ml = (android.widget.FrameLayout.LayoutParams) map.getLayoutParams();
            ml.height = sheetCollapsed ? getResources().getDisplayMetrics().heightPixels + bottomInset - barH + dp(24) : mapH;
            map.setLayoutParams(ml);
        }
        if (mapButtonsView != null) {
            android.widget.FrameLayout.LayoutParams bl = (android.widget.FrameLayout.LayoutParams) mapButtonsView.getLayoutParams();
            int mapBottom = sheetCollapsed ? getResources().getDisplayMetrics().heightPixels + bottomInset - barH : mapH;
            bl.topMargin = mapBottom - dp(MAP_BUTTONS_DP);
            mapButtonsView.setLayoutParams(bl);
        }
    }

    private android.view.View homePage() {
        LinearLayout p = column();
        if (BuildConfig.DOOM) p.addView(row(button("JOGAR DOOM", v -> startActivity(new Intent(this, DoomActivity.class)))));
        EditText dest = field("", false);
        dest.setHint("Para onde?");
        LinearLayout go = new LinearLayout(this);
        go.addView(dest, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button ir = pill("Ir", ORANGE, Color.WHITE);
        ir.setOnClickListener(v -> goTo(dest.getText().toString()));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(72), dp(52));
        il.leftMargin = dp(8);
        go.addView(ir, il);
        p.addView(go);
        LinearLayout sugg = column();
        sugg.setVisibility(android.view.View.GONE);
        p.addView(sugg);
        watchSuggestions(dest, sugg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(android.view.Gravity.BOTTOM);
        head.setPadding(0, dp(14), 0, 0);
        LinearLayout names = column();
        destLabel = text("SEM ROTA", 12, GRAY, true);
        destName = text("Sem destino", 22, INK, true);
        names.addView(destLabel);
        names.addView(destName);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        destMeta = text("", 13, GRAY, false);
        head.addView(destMeta);
        p.addView(head);

        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, dp(12), 0, dp(12));
        tileDone = tile(tiles, "faltam");
        tileLeft = tile(tiles, "restante");
        p.addView(tiles);

        LinearLayout actions = new LinearLayout(this);
        Button end = pill("Encerrar rota", Color.WHITE, INK);
        android.graphics.drawable.GradientDrawable eb = round(Color.WHITE, 26);
        eb.setStroke(dp(1), Color.rgb(226, 226, 226));
        end.setBackground(eb);
        end.setOnClickListener(v -> DashService.clearRoute());
        actions.addView(end, new LinearLayout.LayoutParams(0, dp(52), 1));
        p.addView(actions);
        p.addView(hint("O app navega sozinho: setas, distância e chegada vão direto ao painel."));
        return scroll(p);
    }

    private android.view.View pertoPage() {
        LinearLayout p = column();
        p.addView(section("Perto de mim"));
        LinearLayout results = column();
        LinearLayout chips = new LinearLayout(this);
        chips.addView(chip("Posto", v -> showPlaces(10, results)), new LinearLayout.LayoutParams(0, dp(46), 1));
        chips.addView(chip("Comer", v -> showPlaces(11, results)), chipLp());
        chips.addView(chip("Estacionar", v -> showPlaces(13, results)), chipLp());
        p.addView(chips);
        LinearLayout chips2 = new LinearLayout(this);
        chips2.setPadding(0, dp(8), 0, 0);
        chips2.addView(chip("Royal Enfield", v -> showPlaces(12, results)), new LinearLayout.LayoutParams(0, dp(46), 1));
        chips2.addView(chip("Recentes", v -> showPlaces(30, results)), chipLp());
        p.addView(chips2);
        p.addView(results);
        p.addView(hint("Toque num lugar para navegar até ele. Também dá para escolher pelo menu do painel."));
        return scroll(p);
    }

    private android.view.View diarioPage() {
        LinearLayout p = column();
        LinearLayout tiles = new LinearLayout(this);
        tiles.setPadding(0, dp(4), 0, dp(12));
        tripKm = tile(tiles, "rodados");
        tripState = tile(tiles, "gravação");
        p.addView(tiles);
        recButton = pill("Registrar rolê", ORANGE, Color.WHITE);
        recButton.setOnClickListener(v -> {
            if (DashService.tripStatus() == null) {
                DashService.startIdle(this);
                DashService.startTrip();
            } else {
                DashService.stopTrip();
                ui.postDelayed(this::showTripsLater, 500);
            }
        });
        p.addView(recButton, new LinearLayout.LayoutParams(-1, dp(52)));
        p.addView(section("Meus rolês"));
        p.addView(hint("Toque num rolê para gerar o vídeo (Reels/Stories) e a imagem."));
        LinearLayout tripList = column();
        tripListBox = tripList;
        p.addView(tripList);
        TextView refresh = text("Atualizar lista", 13, ORANGE, true);
        refresh.setPadding(0, dp(12), 0, 0);
        refresh.setOnClickListener(v -> showTrips(tripList));
        p.addView(refresh);
        showTrips(tripList);
        return scroll(p);
    }

    private android.view.View ajustesPage(SharedPreferences prefs) {
        LinearLayout p = column();
        status = text("", 13, INK, false);
        p.addView(section("Estado"));
        p.addView(status);
        p.addView(section("Wi-Fi do painel"));
        EditText ssid = field(prefs.getString("ssid", DashService.DEFAULT_SSID), false);
        EditText pass = field(prefs.getString("pass", "12345678"), true);
        p.addView(ssid);
        p.addView(pass);
        p.addView(row(
                button("Conectar", v -> {
                    String s = ssid.getText().toString().trim();
                    String pw = pass.getText().toString();
                    prefs.edit().putString("ssid", s).putString("pass", pw).apply();
                    DashService.start(this, s, pw);
                }),
                button("Desligar", v -> DashService.stop(this))));
        p.addView(section("Mapa sem internet"));
        p.addView(hint("Baixe o mapa do seu estado pelo Wi-Fi: depois o mapa funciona mesmo sem sinal. Dá para baixar mais de um."));
        TextView offStatus = text(OfflineMaps.progress(), 13, INK, false);
        p.addView(offStatus);
        double[] pos = DashService.bikePosition();
        OfflineMaps.Area mine = pos == null ? null : OfflineMaps.here(this, pos[0], pos[1]);
        if (mine != null) p.addView(row(offlineButton(mine, "Baixar mapa de " + mine.name + " (onde você está)", offStatus)));
        Button pick = button("Escolher outro estado", v -> {
            java.util.List<OfflineMaps.Area> all = OfflineMaps.areas(this);
            String[] names = new String[all.size()];
            for (int i = 0; i < names.length; i++)
                names[i] = all.get(i).name + (OfflineMaps.downloaded(this, all.get(i)) ? "  ✓ baixado" : "");
            new android.app.AlertDialog.Builder(this).setTitle("Baixar mapa de qual estado?")
                    .setItems(names, (d, which) -> startOffline(all.get(which), offStatus)).show();
        });
        p.addView(row(pick));
        p.addView(section("Sobre"));
        p.addView(hint("New Tripper Dash " + versionName() + ". App gratuito e independente, sem vínculo com a Royal Enfield."));
        if (!APOIA_URL.isEmpty())
            p.addView(row(button("Apoie o projeto (Apoia.se)", v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APOIA_URL))))));
        if (!BuildConfig.PUBLICO) {
            p.addView(section("Teste de estresse"));
            p.addView(hint("Com a moto parada: simula um rolê e força CPU, internet, GPS e Wi-Fi ruins (uns 3 min). O resultado sai no Log."));
            p.addView(row(button("Rodar teste", v -> Logger.line("Estresse: " + DashService.stressTest().trim()))));
        }
        p.addView(section("Permissões"));
        Button battery = button("Bateria", v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));
        // Pública: sem leitor de notificações (ver src/publico/AndroidManifest.xml).
        p.addView(BuildConfig.PUBLICO ? row(battery)
                : row(button("Leitura do Maps", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))), battery));
        p.addView(row(
                button("Localização sempre", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))),
                button("Ligações", v -> requestPermissions(new String[]{
                        Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS}, 7))));
        p.addView(section("Ícone da moto no mapa"));
        p.addView(row(
                button("Moto", v -> setIcon("moto")),
                button("Seta", v -> setIcon("seta")),
                button("Imagem…", v -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_ICON))));
        p.addView(section("Painel (avançado)"));
        p.addView(row(
                button("Mapa no painel", v -> DashService.toggleVideo()),
                button("Espelhar Waze", v -> askMirror())));
        if (!BuildConfig.PUBLICO) p.addView(row(
                button("Seta teste", v -> DashService.sendTestArrow()),
                button("Marcar no log", v -> Logger.line("===== MARCA DO EVANDRO ====="))));
        logView = text("", 10, Color.rgb(40, 120, 60), false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setVisibility(android.view.View.GONE);
        TextView showLog = text("Ver log", 13, ORANGE, true);
        showLog.setPadding(0, dp(14), 0, dp(6));
        showLog.setOnClickListener(v -> logView.setVisibility(logView.getVisibility() == android.view.View.VISIBLE
                ? android.view.View.GONE : android.view.View.VISIBLE));
        p.addView(showLog);
        p.addView(logView);
        scroll = (ScrollView) scroll(p);
        return scroll;
    }

    /** Botão "Ligar com a moto": liga o serviço e conecta no Wi-Fi do painel (RE_...) sozinho. */
    private void ligarComAMoto() {
        DashService.HomeInfo h = DashService.homeInfo();
        if (h.connected) {
            android.widget.Toast.makeText(this, "Painel conectado. Segure o botão para desligar.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (h.serviceOn) {
            android.widget.Toast.makeText(this, "Já estou procurando o painel. Ligue a moto.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("painel", MODE_PRIVATE);
        String ssid = prefs.getString("ssid", DashService.DEFAULT_SSID);
        if (ssid.isEmpty()) DashService.startIdle(this); // acha o RE_... sozinho
        else DashService.start(this, ssid, prefs.getString("pass", "12345678"));
        android.widget.Toast.makeText(this, "Procurando o painel… ligue a moto.", android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Atualiza mapa, curva, estado e cartões da tela inicial. */
    private void refreshHome() {
        DashService.HomeInfo h = DashService.homeInfo();
        if (h.connected) {
            statusPill.setText(h.digital ? "● Painel · digital" : "● Painel · analógico");
            statusPill.setTextColor(Color.rgb(61, 220, 132));
        } else if (h.serviceOn) {
            statusPill.setText("◌ Procurando painel…");
            statusPill.setTextColor(Color.rgb(255, 193, 7));
        } else {
            statusPill.setText("⏻ Ligar com a moto");
            statusPill.setTextColor(Color.WHITE);
        }
        if (h.nav != null) {
            NavState n = h.nav;
            turnDist.setText(n.metersToTurn >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f km", n.metersToTurn / 1000.0) : n.metersToTurn + " m");
            turnStreet.setText(n.street == null || n.street.isEmpty() ? "Siga a rota" : n.street);
            tileLeft.setText(formatRemaining(n.remaining));
            destMeta.setText(String.format(java.util.Locale.ROOT, "%.0f km · %s:%s", n.metersLeft / 1000.0,
                    n.eta.length() >= 4 ? n.eta.substring(0, 2) : "--", n.eta.length() >= 4 ? n.eta.substring(2, 4) : "--"));
        } else {
            turnDist.setText(h.dest != null ? "Calculando…" : "Sem rota");
            turnStreet.setText(h.dest != null ? "" : "Toque em Perto para escolher");
            tileLeft.setText("—");
            destMeta.setText("");
        }
        destName.setText(h.dest != null ? shortName(h.dest) : "Sem destino");
        destLabel.setText(h.dest != null ? "INDO PARA" : "SEM ROTA");
        tileDone.setText(h.nav != null ? String.format(java.util.Locale.ROOT, "%.1f km", h.nav.metersLeft / 1000.0) : "—");
        tripKm.setText(String.format(java.util.Locale.ROOT, "%.1f km", h.recording ? h.tripKm : 0));
        tripState.setText(h.recording ? "● gravando" : "parado");
        recButton.setText(h.recording ? "■ Encerrar rolê" : "● Registrar rolê");
        recButton.setBackground(round(h.recording ? INK : ORANGE, 26));
    }

    private static String shortName(String s) {
        int c = s.indexOf(',');
        return c > 0 ? s.substring(0, c) : s;
    }

    private static String formatRemaining(String hhmmdd) {
        if (hhmmdd == null || hhmmdd.length() < 4) return "—";
        int h = Integer.parseInt(hhmmdd.substring(0, 2)), m = Integer.parseInt(hhmmdd.substring(2, 4));
        return h > 0 ? h + "h" + String.format(java.util.Locale.ROOT, "%02d", m) : m + " min";
    }

    /** Lista as viagens; tocar = gerar animação + imagem e oferecer compartilhar. */
    private void showTrips(LinearLayout box) {
        box.removeAllViews();
        java.util.List<TripRecorder.Trip> trips = DashService.listTrips();
        if (trips.isEmpty()) {
            box.addView(hint("Nenhuma viagem ainda. Dê um rolê com a moto conectada."));
            return;
        }
        for (TripRecorder.Trip t : trips) {
            String label = String.format(java.util.Locale.ROOT, "%s · %.1f km · %d min", t.title, t.km, t.movingSec / 60);
            Button b = button(label, v -> {
                TextView status = hint("Gerando animação de " + t.title + "… (uns 20 s)");
                box.addView(status, 0);
                DashService.makeVideo(t.file, (png, mp4, err) -> ui.post(() -> {
                    if (err != null) {
                        status.setText("Falhou: " + err);
                        return;
                    }
                    status.setText("Pronto! Salvo na galeria (Tripper).");
                    box.addView(row(
                            button("Compartilhar vídeo", x -> share(mp4, "video/mp4")),
                            button("Compartilhar imagem", x -> share(png, "image/png"))), 1);
                }));
            });
            b.setAllCaps(false);
            box.addView(b);
        }
    }

    private void share(Uri uri, String type) {
        Intent send = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Postar o rolê"));
    }

    /** Busca pontos próximos e mostra como botões; tocar = navegar até lá. */
    private void showPlaces(int type, LinearLayout box) {
        box.removeAllViews();
        box.addView(hint("Buscando…"));
        DashService.startIdle(this);
        DashService.findPlaces(type, list -> ui.post(() -> {
            box.removeAllViews();
            if (list.isEmpty()) {
                box.addView(hint("Nada encontrado por perto (ou sem GPS/internet)."));
                return;
            }
            for (PoiService.Place p : list) {
                Button b = button(p.label, v -> {
                    DashService.goToPlace(p);
                    box.removeAllViews();
                    box.addView(hint("Navegando até " + p.label));
                });
                b.setAllCaps(false);
                box.addView(b);
            }
        }));
    }

    /** Arquivo com a última versão pública (no GitHub aberto do projeto). */
    private static final String UPDATE_URL = "https://raw.githubusercontent.com/ali00n/New_Tripper_Dash/main/versao.json";

    /** No máximo a cada 12 h: se saiu versão nova, avisa e leva para a página de download. */
    private void checkUpdate() {
        SharedPreferences prefs = getSharedPreferences("painel", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("update-check", 0) < 12 * 3600_000L) return;
        prefs.edit().putLong("update-check", now).apply();
        new Thread(() -> {
            try {
                org.json.JSONObject v = new org.json.JSONObject(new String(NetFallback.get(UPDATE_URL), java.nio.charset.StandardCharsets.UTF_8));
                if (v.getInt("versionCode") <= BuildConfig.VERSION_CODE) return;
                String name = v.optString("versionName"), notes = v.optString("notas"), url = v.optString("url");
                Logger.line("Atualização: versão " + name + " disponível");
                ui.post(() -> {
                    if (isFinishing()) return;
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Nova versão " + name)
                            .setMessage(notes.isEmpty() ? "Tem versão nova do New Tripper Dash." : notes)
                            .setPositiveButton("Baixar", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                            .setNegativeButton("Depois", null)
                            .show();
                });
            } catch (Exception e) {
                Logger.line("Atualização: não deu para conferir (" + e.getMessage() + ")");
            }
        }, "atualizacao").start();
    }

    /** Página do Apoia.se do projeto; vazio = botão escondido. */
    static final String APOIA_URL = "";

    private void showTab(int index, android.view.View[] pages, TextView[] tabs, SharedPreferences prefs) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? android.view.View.VISIBLE : android.view.View.GONE);
            if (tabs[i] != null) tabs[i].setTextColor(i == index ? ORANGE : GRAY);
        }
        prefs.edit().putInt("aba2", index).apply();
    }

    private LinearLayout column() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        return p;
    }

    private android.view.View scroll(LinearLayout content) {
        ScrollView s = new ScrollView(this);
        s.addView(content);
        return s;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private TextView tile(LinearLayout parent, String label) {
        LinearLayout box = column();
        box.setGravity(android.view.Gravity.CENTER);
        box.setBackground(round(TILE_BG, 12));
        box.setPadding(0, dp(10), 0, dp(10));
        TextView value = text("—", 18, INK, true);
        value.setGravity(android.view.Gravity.CENTER);
        TextView l = text(label, 11, GRAY, false);
        l.setGravity(android.view.Gravity.CENTER);
        box.addView(value);
        box.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(8);
        parent.addView(box, lp);
        return value;
    }

    private Button pill(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setBackground(round(bg, 26));
        b.setStateListAnimator(null);
        return b;
    }

    private Button chip(String label, android.view.View.OnClickListener l) {
        Button b = pill(label, TILE_BG, INK);
        b.setTextSize(13);
        b.setBackground(round(TILE_BG, 23));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.leftMargin = dp(8);
        return lp;
    }

    private TextView section(String value) {
        TextView t = text(value, 15, INK, true);
        t.setPadding(0, dp(14), 0, dp(6));
        return t;
    }

    private TextView hint(String value) {
        TextView t = text(value, 12, GRAY, false);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private EditText field(String value, boolean password) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextColor(INK);
        e.setHintTextColor(GRAY);
        e.setSingleLine(true);
        e.setBackground(round(TILE_BG, 14));
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setMinHeight(dp(48));
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        return e;
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = pill(label, TILE_BG, INK);
        b.setTextSize(13);
        b.setBackground(round(TILE_BG, 14));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(0, dp(4), 0, dp(4));
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(buttons[i], lp);
        }
        return r;
    }

    @Override
    protected void onDestroy() {
        Logger.setListener(null);
        ui.removeCallbacksAndMessages(null);
        if (phoneMap != null) phoneMap.onDestroy();
        super.onDestroy();
    }
}
