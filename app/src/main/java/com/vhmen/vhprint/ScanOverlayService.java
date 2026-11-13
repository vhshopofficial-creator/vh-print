package com.vhmen.vhprint;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Scan Verify floating over PokPok. The SUNMI scanner types each code into the app in front (PokPok) AND broadcasts it
 * (com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED, extra "data"); this service hands the broadcast copy to the Scan Verify
 * page running in a small always-on-top window. The window never takes the keyboard (PokPok keeps it) unless ⌨ is on.
 */
public class ScanOverlayService extends Service {
    static final String SCAN_ACTION = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED";
    static final String STOP = "com.vhmen.vhprint.STOP_OVERLAY";
    static final String PREFS = "vhprint", KEY_URL = "scan_url";
    private static final String CH = "overlay";

    private WindowManager wm;
    private LinearLayout root;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private Button kbdBtn, sizeBtn, hideBtn;
    private boolean keyboard = false, hidden = false;
    private int size = 1;                         // 0 small · 1 large
    private int screenH, headerH;
    private static volatile boolean running = false;

    public static boolean isRunning() { return running; }

    private final BroadcastReceiver scans = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String d = i.getStringExtra("data");
            if (d == null) { byte[] b = i.getByteArrayExtra("source_byte"); if (b != null) d = new String(b, StandardCharsets.UTF_8); }
            if (d != null) inject(d.trim());
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && STOP.equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        foreground();
        if (root == null) show();
        return START_STICKY;
    }

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Scan Verify over PokPok", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ScanOverlayService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Scan Verify is on")
                .setContentText("Scans go to PokPok and Scan Verify")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, n);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void show() {
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenH = dm.heightPixels; headerH = dp(40);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff0f1420);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0xff1e3a8a);
        TextView title = new TextView(this);
        title.setText("  ⠿  Scan Verify");
        title.setTextColor(Color.WHITE); title.setTextSize(15);
        head.addView(title, new LinearLayout.LayoutParams(0, headerH, 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        kbdBtn = small("⌨"); sizeBtn = small("▭"); hideBtn = small("—");
        head.addView(kbdBtn); head.addView(sizeBtn); head.addView(hideBtn);
        root.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, headerH));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("intent:") || u.startsWith("vhprint:")) {           // ⚡ print links etc.
                    try { Intent it = Intent.parseUri(u, Intent.URI_INTENT_SCHEME); it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView v, String url) { v.evaluateJavascript(SELECT_SHIM, null); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                pop(dialog().setMessage(msg).setPositiveButton("OK", (d, w) -> r.confirm()).setOnCancelListener(d -> r.confirm()).create()); return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                pop(dialog().setMessage(msg).setPositiveButton("OK", (d, w) -> r.confirm()).setNegativeButton("Cancel", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).create()); return true;
            }
            @Override public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) {
                EditText in = new EditText(ScanOverlayService.this); in.setText(def == null ? "" : def);
                pop(dialog().setMessage(msg).setView(in).setPositiveButton("OK", (d, w) -> r.confirm(in.getText().toString()))
                        .setNegativeButton("Cancel", (d, w) -> r.cancel()).setOnCancelListener(d -> r.cancel()).create()); return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.y = screenH - height() - dp(60);
        wm.addView(root, lp);

        // drag the window up / down by its title bar
        title.setOnTouchListener(new View.OnTouchListener() {
            float startY; int startTop;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { startY = e.getRawY(); startTop = lp.y; return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { lp.y = Math.max(0, Math.min(screenH - headerH, startTop + Math.round(e.getRawY() - startY))); wm.updateViewLayout(root, lp); return true; }
                return false;
            }
        });
        kbdBtn.setOnClickListener(v -> setKeyboard(!keyboard));
        sizeBtn.setOnClickListener(v -> { size = 1 - size; hidden = false; relayout(); });
        hideBtn.setOnClickListener(v -> { hidden = !hidden; relayout(); });

        IntentFilter f = new IntentFilter(SCAN_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scans, f, Context.RECEIVER_EXPORTED); else registerReceiver(scans, f);

        String url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        if (url.isEmpty()) web.loadData("<h2 style='font-family:sans-serif;color:#fff;background:#0f1420;padding:20px'>Open VH Print and enter the Scan Verify address.</h2>", "text/html", "UTF-8");
        else web.loadUrl(url);
    }

    private Button small(String t) {
        Button b = new Button(this); b.setText(t); b.setTextSize(16); b.setTextColor(Color.WHITE); b.setAllCaps(false);
        b.setBackgroundColor(0x00000000); b.setMinWidth(dp(48)); b.setMinimumWidth(dp(48)); b.setPadding(0, 0, 0, 0);
        return b;
    }

    private int height() { return hidden ? headerH : (size == 1 ? (int) (screenH * 0.55) : (int) (screenH * 0.30)); }

    private void relayout() {
        lp.height = height();
        lp.y = Math.max(0, Math.min(screenH - lp.height, lp.y));
        hideBtn.setText(hidden ? "▢" : "—");
        sizeBtn.setText(size == 1 ? "▭" : "▢");
        wm.updateViewLayout(root, lp);
    }

    /** ⌨ on: the window takes the keyboard (to type a name, a PIN, a note). Scans then type into the window, not PokPok. */
    private void setKeyboard(boolean on) {
        keyboard = on;
        if (on) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        kbdBtn.setTextColor(on ? 0xfffde047 : Color.WHITE);
        wm.updateViewLayout(root, lp);
        if (on) web.requestFocus();
    }

    /** a scan from the SUNMI scanner -> the Scan Verify page (handleScan, or its scan box + Enter) */
    private void inject(String code) {
        if (keyboard || web == null || code.isEmpty()) return;   // with ⌨ on, the scan is already typed into the page
        String q = JSONObject.quote(code);
        web.evaluateJavascript("(function(c){try{if(typeof handleScan==='function'){handleScan(c);return 'h';}"
                + "var b=document.getElementById('scanbox');if(b){b.value=c;b.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));return 'b';}"
                + "}catch(e){return 'e:'+e;}return 'none';})(" + q + ")", null);
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert));
    }
    /** page alerts / confirms / prompts: a dialog shown over other apps (a service has no screen of its own) */
    private void pop(AlertDialog d) {
        if (d.getWindow() != null) d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        d.show();
    }

    @Override public void onDestroy() {
        running = false;
        try { unregisterReceiver(scans); } catch (Exception ignored) { }
        if (root != null) { try { wm.removeView(root); } catch (Exception ignored) { } }
        if (web != null) web.destroy();
        root = null; web = null;
        super.onDestroy();
    }

    // <select> lists cannot open a native popup in a floating window: show them as an in-page list instead
    private static final String SELECT_SHIM = "(function(){if(window.__vhsel)return;window.__vhsel=1;"
            + "function pick(s){var o=document.createElement('div');o.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center';"
            + "var box=document.createElement('div');box.style.cssText='background:#fff;color:#111;border-radius:12px;max-height:85%;overflow:auto;min-width:70%;padding:4px;font-family:sans-serif';"
            + "[].forEach.call(s.options,function(op,i){var b=document.createElement('div');b.textContent=op.text;b.style.cssText='padding:12px 14px;font-size:18px;border-bottom:1px solid #ddd'+(i===s.selectedIndex?';font-weight:700':'');"
            + "b.addEventListener('click',function(ev){ev.stopPropagation();s.selectedIndex=i;s.dispatchEvent(new Event('change',{bubbles:true}));s.dispatchEvent(new Event('input',{bubbles:true}));o.remove();});box.appendChild(b);});"
            + "o.addEventListener('click',function(){o.remove();});o.appendChild(box);document.body.appendChild(o);}"
            + "function h(e){var s=e.target&&e.target.closest&&e.target.closest('select');if(!s||s.disabled)return;e.preventDefault();e.stopPropagation();pick(s);}"
            + "document.addEventListener('touchstart',h,{capture:true,passive:false});document.addEventListener('mousedown',h,true);})();";
}
