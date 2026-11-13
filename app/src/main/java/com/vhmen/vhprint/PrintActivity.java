package com.vhmen.vhprint;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Inflater;

/**
 * ⚡ One-tap print from a web page: vhprint://raster?w=576&h=<rows>&d=<base64url(zlib(1-bit rows))>
 * (the Stock Count page builds this link). Shows a small "Printing…" box, prints, closes by itself.
 */
public class PrintActivity extends Activity {
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor();   // one print at a time
    private TextView msg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(60, 50, 60, 50); box.setGravity(Gravity.CENTER);
        msg = new TextView(this); msg.setTextSize(20); msg.setTextColor(0xff15171c); msg.setText("🖨 Printing…");
        box.addView(msg); setContentView(box);
        setFinishOnTouchOutside(false);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    private void handle(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u == null || !"vhprint".equals(u.getScheme()) || !"raster".equals(u.getHost())) { done("✗ Nothing to print", false); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this, MainActivity.class));
            done("Open VH Print and tap ‘Allow Bluetooth’ first", false);
            return;
        }
        final Thermal.Raster r;
        try { r = decode(u); } catch (Exception e) { done("✗ " + e.getMessage(), false); return; }
        msg.setText("🖨 Printing…");
        QUEUE.execute(() -> {
            try {
                Thermal.send(Thermal.escpos(Collections.singletonList(r)), null);
                runOnUiThread(() -> done("✓ Printed", true));
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> done("✗ " + m, false));
            }
        });
    }

    static Thermal.Raster decode(Uri u) throws Exception {
        int w = Integer.parseInt(u.getQueryParameter("w")), h = Integer.parseInt(u.getQueryParameter("h"));
        if (w != Thermal.WIDTH) throw new Exception("Width must be " + Thermal.WIDTH + " dots");
        if (h < 1 || h > 40000) throw new Exception("Bad height");
        String d = u.getQueryParameter("d");
        if (d == null || d.isEmpty()) throw new Exception("No picture data");
        byte[] comp = Base64.decode(d, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        Inflater inf = new Inflater();
        inf.setInput(comp);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int need = (w / 8) * h;
        while (!inf.finished() && out.size() <= need) {
            int n = inf.inflate(buf);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            out.write(buf, 0, n);
        }
        inf.end();
        byte[] rows = out.toByteArray();
        if (rows.length != need) throw new Exception("Picture data is damaged (" + rows.length + " of " + need + " bytes)");
        return new Thermal.Raster(rows, h);
    }

    private void done(String text, boolean ok) {
        msg.setText(text);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, ok ? 900 : 3500);
    }
}
