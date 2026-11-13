package com.vhmen.vhprint;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.net.Uri;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Setup screen: allow Bluetooth, test print, open Android's print settings to turn the service on. */
public class MainActivity extends Activity {
    private static final int REQ_BT = 7;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 60, 40, 40);
        ScrollView sv = new ScrollView(this); sv.addView(box); setContentView(sv);

        TextView t = new TextView(this); t.setText("VH Print"); t.setTextSize(26); t.setTypeface(null, Typeface.BOLD); t.setTextColor(0xff15171c); box.addView(t);
        TextView d = new TextView(this); d.setTextSize(15); d.setPadding(0, 12, 0, 24);
        d.setText("Prints from Chrome on the SUNMI built-in 80 mm printer. No footer, no licence.\n\n"
                + "1 · Turn Bluetooth on.\n2 · Tap ‘Allow Bluetooth’.\n3 · Tap ‘Test print’.\n"
                + "4 · Tap ‘Print settings’ → VH Print (SUNMI 80mm) → On.\n"
                + "5 · In Chrome: Print → choose ‘SUNMI 80mm (VH Print)’.");
        box.addView(d);
        status = new TextView(this); status.setTextSize(16); status.setPadding(0, 0, 0, 24); status.setTextColor(0xff1e4fc2); box.addView(status);

        button(box, "Allow Bluetooth", v -> askBt());
        button(box, "Test print", v -> testPrint());
        button(box, "Print settings (turn VH Print on)", v -> startActivity(new Intent(Settings.ACTION_PRINT_SETTINGS)));

        // ---- Scan Verify floating over PokPok ----
        TextView h2 = new TextView(this); h2.setText("\nVHM Scan Helper (with PokPok)"); h2.setTextSize(20); h2.setTypeface(null, Typeface.BOLD); h2.setTextColor(0xff15171c); box.addView(h2);
        TextView d2 = new TextView(this); d2.setTextSize(14); d2.setPadding(0, 8, 0, 8);
        d2.setText("Each scan goes to PokPok (as now) and to Scan Verify, which runs hidden and shows a popup: "
                + "blue = order loaded, green = match, red = wrong item (stays until OK).\n"
                + "Tap ‘VHM Helper running’ at the bottom to open the full Scan Verify (staff name, issues, ship short).\n"
                + "Needs: Barcode Scanner app → Output method → Broadcast ON (already on).");
        box.addView(d2);
        final SharedPreferences prefs = getSharedPreferences(ScanOverlayService.PREFS, MODE_PRIVATE);
        final EditText url = new EditText(this); url.setSingleLine(true); url.setTextSize(14);
        url.setHint("Scan Verify address (https://…)");
        url.setText(prefs.getString(ScanOverlayService.KEY_URL, "https://m3ngy35-glitch.github.io/vhm-scan/"));
        box.addView(url);
        button(box, "1 · Allow ‘Display over other apps’", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        button(box, "2 · Start VHM Scan Helper", v -> {
            String u = url.getText().toString().trim();
            if (!u.startsWith("https://")) { status.setText("✗ The address must start with https://"); return; }
            prefs.edit().putString(ScanOverlayService.KEY_URL, u).apply();
            if (!Settings.canDrawOverlays(this)) { status.setText("✗ Tap 1 first and allow VH Print"); return; }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 8);
            startForegroundService(new Intent(this, ScanOverlayService.class));
            status.setText("✓ Helper is on — open PokPok and scan");
        });
        button(box, "Stop VHM Scan Helper", v -> { startService(new Intent(this, ScanOverlayService.class).setAction(ScanOverlayService.STOP)); status.setText("Scan Verify window stopped"); });
        showState();
    }

    private void button(LinearLayout box, String label, View.OnClickListener l) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(17); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12; box.addView(b, lp);
    }

    private boolean btAllowed() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }
    private void askBt() {
        if (btAllowed()) { status.setText("✓ Bluetooth allowed"); return; }
        requestPermissions(new String[] { "android.permission.BLUETOOTH_CONNECT" }, REQ_BT);
    }
    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) { super.onRequestPermissionsResult(req, p, r); showState(); }
    private void showState() { status.setText(btAllowed() ? "✓ Bluetooth allowed — try a test print" : "Bluetooth not allowed yet — tap ‘Allow Bluetooth’"); }

    private void testPrint() {
        if (!btAllowed()) { askBt(); return; }
        status.setText("Printing…");
        worker.execute(() -> {
            try {
                Bitmap bmp = Bitmap.createBitmap(Thermal.WIDTH, 260, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp); c.drawColor(Color.WHITE);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK);
                p.setTextSize(44); p.setTypeface(Typeface.DEFAULT_BOLD); c.drawText("VH Print ✓", 20, 60, p);
                p.setTypeface(Typeface.DEFAULT); p.setTextSize(28); c.drawText("SUNMI 80 mm · 576 dots", 20, 110, p);
                p.setStrokeWidth(3); c.drawRect(4, 140, Thermal.WIDTH - 4, 240, stroke(p));
                p.setStyle(Paint.Style.FILL); c.drawText("← full width →", Thermal.WIDTH / 2f - 90, 200, p);
                Thermal.Raster r = Thermal.toRaster(bmp); bmp.recycle();
                Thermal.send(Thermal.escpos(Collections.singletonList(r)), null);
                runOnUiThread(() -> status.setText("✓ Sent. The box should touch both edges of the paper."));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("✗ " + (e.getMessage() == null ? e.toString() : e.getMessage())));
            }
        });
    }
    private static Paint stroke(Paint p) { p.setStyle(Paint.Style.STROKE); return p; }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
