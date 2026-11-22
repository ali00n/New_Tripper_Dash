package br.com.evandro.tripper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Tela do DOOM no celular: prévia do jogo + controle por toque. O painel recebe o mesmo quadro. */
public class DoomActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImageView screen;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (screen != null) {
                screen.setImageBitmap(DoomEngine.frame());
                screen.invalidate();
            }
            ui.postDelayed(this, 33);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setGravity(Gravity.CENTER_VERTICAL);

        root.addView(dpad(), new LinearLayout.LayoutParams(dp(210), dp(210)));

        screen = new ImageView(this);
        screen.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.setFilterTouchesWhenObscured(true);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        sl.setMargins(dp(8), dp(8), dp(8), dp(8));
        root.addView(screen, sl);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.addView(pad("TIRO", DoomEngine.FIRE, 0xFFB71C1C), new LinearLayout.LayoutParams(dp(150), dp(96)));
        actions.addView(pad("USAR", DoomEngine.USE, 0xFF37474F), new LinearLayout.LayoutParams(dp(150), dp(64)));
        LinearLayout menu = new LinearLayout(this);
        menu.addView(pad("ENTER", DoomEngine.ENTER, 0xFF263238), new LinearLayout.LayoutParams(dp(75), dp(52)));
        menu.addView(pad("ESC", DoomEngine.ESC, 0xFF263238), new LinearLayout.LayoutParams(dp(75), dp(52)));
        actions.addView(menu);
        root.addView(actions);

        setContentView(root);
        if (!DoomEngine.start(this)) finish();
    }

    private View dpad() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        g.setRowCount(3);
        int[][] cells = {{-1, DoomEngine.UP, -1}, {DoomEngine.LEFT, -1, DoomEngine.RIGHT}, {-1, DoomEngine.DOWN, -1}};
        String[] labels = {"▲", "▼", "◀", "▶"};
        for (int[] row : cells)
            for (int which : row) {
                View v = which < 0 ? new View(this) : pad(labels[which], which, 0xFF37474F);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.width = dp(70);
                lp.height = dp(70);
                g.addView(v, lp);
            }
        return g;
    }

    @SuppressLint("ClickableViewAccessibility")
    private Button pad(String label, int which, int color) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(3), Color.BLACK);
        b.setBackground(bg);
        b.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.6f);
                DoomEngine.key(which, true);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                DoomEngine.key(which, false);
            }
            return true;
        });
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
