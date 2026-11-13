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
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * VHM Scan Helper (test). PokPok stays in front, untouched. The SUNMI scanner types each code into PokPok and also
 * broadcasts it; this service gives the broadcast copy to Scan Verify, which runs invisibly, and shows its result as a
 * short popup: 🔵 order loaded (3 s) · 🟢 match (2 s) · 🔴 wrong item / ⚠ check (stays until OK).
 * The small "VHM Helper running" pill opens the full Scan Verify page (staff name, issues, ship short…).
 */
public class ScanOverlayService extends Service {
    static final String SCAN_ACTION = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED";
    static final String STOP = "com.vhmen.vhprint.STOP_OVERLAY";
    static final String PREFS = "vhprint", KEY_URL = "scan_url";
    private static final String CH = "overlay";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private int screenW, screenH;
    private static volatile boolean running = false;
    public static boolean isRunning() { return running; }

    // the invisible Scan Verify page (shown full-screen when the pill is tapped)
    private LinearLayout pageBox;
    private TextView pageBar;
    private WebView web;
    private WindowManager.LayoutParams pageLp;
    private boolean full = false;

    // popup + pill
    private LinearLayout card;
    private WindowManager.LayoutParams cardLp;
    private TextView pill;
    private Runnable hideCard = () -> fadeOut(450);
    private ToneGenerator tones;
    private String lastOrder = "";

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
        if (web == null) start();
        return START_STICKY;
    }

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "VHM Scan Helper", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ScanOverlayService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("VHM Scan Helper is on")
                .setContentText("Scans go to PokPok and Scan Verify")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, n);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private static GradientDrawable round(int color, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(r); return g; }
    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); if (bold) t.setTypeface(null, Typeface.BOLD); return t;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface"})
    private void start() {
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels; screenH = dm.heightPixels;
        try { tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 100); } catch (Exception ignored) { }

        // ---- Scan Verify page: full size but invisible and untouchable until the pill is tapped ----
        pageBox = new LinearLayout(this); pageBox.setOrientation(LinearLayout.VERTICAL); pageBox.setBackgroundColor(Color.WHITE);
        pageBar = text("  ✕  Close Scan Verify · back to PokPok", 16, Color.WHITE, true);
        pageBar.setBackgroundColor(0xff1e3a8a); pageBar.setGravity(Gravity.CENTER_VERTICAL); pageBar.setVisibility(View.GONE);
        pageBar.setOnClickListener(v -> setFull(false));
        pageBox.addView(pageBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void state(String json) { main.post(() -> onState(json)); }
        }, "VHHelper");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith("intent:") || u.startsWith("vhprint:")) {
                    try { Intent it = Intent.parseUri(u, Intent.URI_INTENT_SCHEME); it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it); } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
            @Override public void onPageFinished(WebView v, String url) { v.evaluateJavascript(SELECT_SHIM, null); v.evaluateJavascript(WATCH, null); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                pop(dialog().setMessage(msg).setPositiveButton("OK", (d, w) -> r.confirm()).setOnCancelListener(d -> r.confirm()).create()); return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                pop(dialog().setMessage(msg).setPositiveButton("OK", (d, w) -> r.confirm()).setNegativeButton("Cancel", (d, w) -> r.cancel()).setOnCancelListener(d -> r.cancel()).create()); return true;
            }
            @Override public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) {
                EditText in = new EditText(ScanOverlayService.this); in.setText(def == null ? "" : def);
                pop(dialog().setMessage(msg).setView(in).setPositiveButton("OK", (d, w) -> r.confirm(in.getText().toString()))
                        .setNegativeButton("Cancel", (d, w) -> r.cancel()).setOnCancelListener(d -> r.cancel()).create()); return true;
            }
        });
        pageBox.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        pageLp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        setPageFlags();
        wm.addView(pageBox, pageLp);

        // ---- "VHM Helper running" pill ----
        pill = text("VHM Helper running", 13, Color.WHITE, false);
        pill.setBackground(round(0xee111827, dp(16))); pill.setPadding(dp(14), dp(6), dp(14), dp(6));
        pill.setOnClickListener(v -> setFull(true));
        WindowManager.LayoutParams plp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        plp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; plp.y = dp(84);
        wm.addView(pill, plp);

        IntentFilter f = new IntentFilter(SCAN_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scans, f, Context.RECEIVER_EXPORTED); else registerReceiver(scans, f);

        String url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        if (url.isEmpty()) { pill.setText("Set the Scan Verify address in VH Print"); }
        else web.loadUrl(url);
    }

    private void setPageFlags() {
        if (full) { pageLp.flags = 0; pageLp.alpha = 1f; }
        else { pageLp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; pageLp.alpha = 0f; }
    }

    /** pill tapped: the real Scan Verify page full screen (to choose staff, see issues, ship short…); ✕ hides it again */
    private void setFull(boolean on) {
        full = on;
        pageBar.setVisibility(on ? View.VISIBLE : View.GONE);
        setPageFlags();
        wm.updateViewLayout(pageBox, pageLp);
        pill.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) { closeCard(); web.requestFocus(); }
    }

    /** scanner broadcast -> Scan Verify (in full mode the scan is typed into the page directly, so it is skipped here) */
    private void inject(String code) {
        if (full || web == null || code.isEmpty()) return;
        web.evaluateJavascript("(function(c){try{if(typeof handleScan==='function'){handleScan(c);return 'h';}"
                + "var b=document.getElementById('scanbox');if(b){b.value=c;b.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));return 'b';}"
                + "}catch(e){return 'e:'+e;}return 'none';})(" + JSONObject.quote(code) + ")", null);
    }

    // ---------------- popups ----------------
    private void onState(String json) {
        if (full) return;
        try {
            JSONObject j = new JSONObject(json);
            if (j.optBoolean("needName")) { pill.setText("Tap here · choose your name in Scan Verify"); pill.setBackground(round(0xeeb45309, dp(16))); return; }
            pill.setText("VHM Helper running"); pill.setBackground(round(0xee111827, dp(16)));
            String cls = j.optString("cls"), big = j.optString("big"), order = j.optString("order");
            int got = j.optInt("got"), total = j.optInt("total");
            if (cls.contains("done")) { showMatch(j, true); beep(ToneGenerator.TONE_PROP_ACK, 300); vibrate(new long[] { 0, 40, 60, 40 }); }
            else if (cls.contains("ok")) { showMatch(j, false); beep(ToneGenerator.TONE_PROP_BEEP, 150); vibrate(new long[] { 0, 30 }); }
            else if (cls.contains("bad") || cls.contains("dup") || cls.contains("stop")) { showWrong(j); beep(ToneGenerator.TONE_SUP_ERROR, 700); vibrate(new long[] { 0, 200, 80, 200 }); }
            else if (cls.contains("warn")) { showWarn(j); beep(ToneGenerator.TONE_PROP_BEEP2, 300); vibrate(new long[] { 0, 60, 40, 60 }); }
            else if (!order.isEmpty() && (!order.equals(lastOrder) || big.startsWith("Order"))) { showOrder(j); beep(ToneGenerator.TONE_PROP_BEEP, 120); }
            if (!order.isEmpty()) lastOrder = order;
        } catch (Exception ignored) { }
    }

    private LinearLayout newCard(int color) {
        closeCard();
        card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(color, dp(18))); card.setPadding(dp(16), dp(14), dp(16), dp(14)); card.setElevation(dp(8));
        return card;
    }
    private void head(LinearLayout c, String icon, String title, String sub) {
        LinearLayout h = new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL);
        TextView i = text(icon, 30, Color.WHITE, true); i.setPadding(0, 0, dp(12), 0); h.addView(i);
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL);
        tt.addView(text(title, 21, Color.WHITE, true)); if (!sub.isEmpty()) tt.addView(text(sub, 13, Color.WHITE, true));
        h.addView(tt); c.addView(h);
    }
    private void showCard(LinearLayout c, long hideMs, boolean touchable) {
        cardLp = new WindowManager.LayoutParams(screenW - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | (touchable ? WindowManager.LayoutParams.FLAG_DIM_BEHIND : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT);
        if (touchable) cardLp.dimAmount = 0.35f;
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; cardLp.y = dp(56);
        wm.addView(c, cardLp);
        // slide down + fade in, like a phone notification
        c.setAlpha(0f); c.setTranslationY(-dp(24));
        c.animate().alpha(1f).translationY(0).setDuration(220).start();
        main.removeCallbacks(hideCard);
        if (hideMs > 0) main.postDelayed(hideCard, hideMs);
    }
    /** slide up + fade out, then remove (a newer popup replaces it at once instead) */
    private void fadeOut(long ms) {
        main.removeCallbacks(hideCard);
        final LinearLayout c = card; if (c == null) return;
        c.animate().alpha(0f).translationY(-dp(16)).setDuration(ms).withEndAction(() -> {
            if (card == c) { try { wm.removeView(c); } catch (Exception ignored) { } card = null; }
        }).start();
    }
    private void closeCard() {
        main.removeCallbacks(hideCard);
        if (card != null) { try { wm.removeView(card); } catch (Exception ignored) { } card = null; }
    }

    /** 🔵 order scanned: customer + the items to scan */
    private void showOrder(JSONObject j) {
        LinearLayout c = newCard(0xff1d4ed8);
        head(c, "▤", "បានស្កេនកម្ម៉ង់", "Order loaded · " + j.optString("order"));
        JSONArray items = j.optJSONArray("items");
        int n = items == null ? 0 : items.length();
        c.addView(pad(text(j.optString("fb") + " · " + j.optInt("total") + " item(s) to scan", 14, Color.WHITE, false), 8));
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(0x33ffffff, dp(10))); box.setPadding(dp(12), dp(8), dp(12), dp(8));
        for (int k = 0; k < Math.min(n, 6); k++) {
            JSONObject it = items.optJSONObject(k);
            LinearLayout row = new LinearLayout(this);
            row.addView(text(it.optString("code") + " · " + it.optString("label"), 15, Color.WHITE, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(text(it.optString("r"), 15, Color.WHITE, true));
            box.addView(row);
        }
        if (n > 6) box.addView(text("+ " + (n - 6) + " more", 13, Color.WHITE, false));
        c.addView(pad(box, 8));
        String warn = j.optString("note");
        c.addView(pad(text(warn.isEmpty() ? "Now scan each item." : warn, 13, Color.WHITE, false), 8));
        showCard(c, 2600, false);
    }

    /** 🟢 match (or order complete): the item + progress */
    private void showMatch(JSONObject j, boolean done) {
        LinearLayout c = newCard(0xff15803d);
        head(c, "✓", done ? "ORDER COMPLETE" : "ត្រូវ · MATCH", "Order " + j.optString("order"));
        String item = j.optString("small");
        if (!item.isEmpty()) c.addView(pad(text(item, 18, Color.WHITE, true), 8));
        int got = j.optInt("got"), total = j.optInt("total");
        if (total > 0) {
            LinearLayout row = new LinearLayout(this);
            row.addView(text(got + " of " + total + " items", 14, Color.WHITE, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(text(done ? "ready for packer" : (total - got) + " left", 14, Color.WHITE, false));
            c.addView(pad(row, 8));
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(total); pb.setProgress(got);
            c.addView(pad(pb, 4));
        }
        showCard(c, done ? 2600 : 1600, false);
    }

    /** 🔴 wrong item / duplicate: what was scanned + what to take instead; stays until OK */
    private void showWrong(JSONObject j) {
        String cls = j.optString("cls");
        LinearLayout c = newCard(cls.contains("dup") ? 0xff6d28d9 : 0xffb91c1c);
        head(c, "✗", j.optString("big"), "Order " + j.optString("order"));
        String scanned = j.optString("scanned");
        if (!scanned.isEmpty()) {
            LinearLayout b1 = new LinearLayout(this); b1.setOrientation(LinearLayout.VERTICAL);
            b1.setBackground(round(0x33000000, dp(10))); b1.setPadding(dp(12), dp(8), dp(12), dp(8));
            b1.addView(text("You scanned", 12, Color.WHITE, false));
            TextView sc = text(scanned, 16, Color.WHITE, true);
            sc.setPaintFlags(sc.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            b1.addView(sc); c.addView(pad(b1, 10));
        }
        String exp = j.optString("expected");
        if (!exp.isEmpty()) {
            LinearLayout b2 = new LinearLayout(this); b2.setOrientation(LinearLayout.VERTICAL);
            b2.setBackground(round(Color.WHITE, dp(10))); b2.setPadding(dp(12), dp(8), dp(12), dp(8));
            b2.addView(text("យកមួយនេះជំនួស · Grab this instead", 12, 0xff5d6b80, false));
            b2.addView(text(exp, 20, 0xff111827, true));
            String loc = j.optString("loc"); if (!loc.isEmpty()) b2.addView(text("Shelf " + loc, 13, 0xff5d6b80, false));
            c.addView(pad(b2, 10));
        } else if (!j.optString("small").isEmpty()) c.addView(pad(text(j.optString("small"), 14, Color.WHITE, false), 8));
        c.addView(okButton("OK, I'll swap it", cls.contains("dup") ? 0xff6d28d9 : 0xffb91c1c));
        showCard(c, 0, true);
    }

    /** ⚠ anything else that needs a look (order changed, already scanned, check by eye, confirm a new label…) */
    private void showWarn(JSONObject j) {
        LinearLayout c = newCard(0xffb45309);
        head(c, "⚠", j.optString("big"), "Order " + j.optString("order"));
        String sm = j.optString("small"); if (!sm.isEmpty()) c.addView(pad(text(sm, 14, Color.WHITE, false), 8));
        LinearLayout row = new LinearLayout(this);
        TextView ok = okButton("OK", 0xffb45309); row.addView(ok, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (j.optBoolean("action")) {
            TextView open = okButton("Open Scan Verify", 0xffb45309); open.setOnClickListener(v -> setFull(true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); lp.leftMargin = dp(8);
            row.addView(open, lp);
        }
        c.addView(row);
        showCard(c, 0, true);
    }

    private TextView okButton(String label, int color) {
        TextView b = text(label, 17, color, true); b.setGravity(Gravity.CENTER);
        b.setBackground(round(Color.WHITE, dp(12))); b.setPadding(0, dp(12), 0, dp(12));
        b.setOnClickListener(v -> fadeOut(180));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12); b.setLayoutParams(lp);
        return b;
    }
    private View pad(View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp); v.setLayoutParams(lp); return v;
    }
    private void beep(int tone, int ms) { try { if (tones != null) tones.startTone(tone, ms); } catch (Exception ignored) { } }
    private void vibrate(long[] p) {
        try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.vibrate(VibrationEffect.createWaveform(p, -1)); } catch (Exception ignored) { }
    }

    private AlertDialog.Builder dialog() { return new AlertDialog.Builder(new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)); }
    private void pop(AlertDialog d) { if (d.getWindow() != null) d.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); d.show(); }

    @Override public void onDestroy() {
        running = false;
        try { unregisterReceiver(scans); } catch (Exception ignored) { }
        closeCard();
        if (pill != null) try { wm.removeView(pill); } catch (Exception ignored) { }
        if (pageBox != null) try { wm.removeView(pageBox); } catch (Exception ignored) { }
        if (web != null) web.destroy();
        if (tones != null) tones.release();
        web = null; pageBox = null; pill = null;
        super.onDestroy();
    }

    /**
     * Reads Scan Verify's own screen after every scan (status box, order card, item lines) and sends a summary to the
     * popups. The page's own sounds are muted (the app beeps); a saved staff name starts the page by itself.
     */
    private static final String WATCH = "(function(){if(window.__vhw)return;window.__vhw=1;"
            + "var $=function(i){return document.getElementById(i)};"
            + "try{muted=true;}catch(e){}"
            + "function txt(n){return n?(n.textContent||'').replace(/\\s+/g,' ').trim():''}"
            + "function autostart(){var ov=$('overlay');if(!ov||!document.body.contains(ov))return false;var st=$('ovstaff'),sel=$('ovpick');"
            + "var has=(st&&st.value.trim())||(sel&&sel.style.display!=='none'&&sel.value&&sel.value!=='__other');if(has&&$('start')){$('start').click();try{muted=true;}catch(e){}return false;}return true;}"
            + "function send(){try{var need=autostart();var s=$('status');if(!s)return;var sm=s.querySelector('small');"
            + "var big=s.childNodes[0]?txt(s.childNodes[0]):'';var oc=$('ordercard'),open=oc&&oc.style.display!=='none';"
            + "var items=[],exp='',loc='';document.querySelectorAll('#items .it').forEach(function(e){var b=e.querySelector('.l b'),m=e.querySelector('.m'),r=e.querySelector('.r');"
            + "var lab=m&&m.childNodes[0]?txt(m.childNodes[0]):'';var lc='';if(m)m.querySelectorAll('.loc').forEach(function(x){var t=txt(x);if(t.indexOf('📍')===0)lc=t.slice(2).trim();});"
            + "items.push({code:txt(b),label:lab,r:txt(r).replace(' ✓',''),done:e.classList.contains('done')});"
            + "if(!exp&&!e.classList.contains('done')){exp=txt(b)+' · '+lab;loc=lc;}});"
            + "var sub=txt($('ordersub')),pm=sub.match(/(\\d+)\\/(\\d+) items/);"
            + "var scanned='';if(sm){var bb=sm.querySelector('b'),bg=sm.querySelector('.big');scanned=bb?txt(bb):(bg?txt(bg):'');}"
            + "var small=sm?(sm.innerText||'').split('EXPECTED')[0].split('\\n').filter(Boolean).slice(0,2).join(' · '):'';"
            + "var act=$('act'),acting=!!(act&&act.classList.contains('on'));"
            + "VHHelper.state(JSON.stringify({needName:need,cls:s.className||'',big:big,small:small.slice(0,140),scanned:scanned,"
            + "order:open?txt($('ordertitle')):'',fb:sub.split(' — ')[0].split(' · ')[0],got:pm?+pm[1]:0,total:pm?+pm[2]:0,items:items,expected:exp,loc:loc,"
            + "note:(s.className==='warn'?big:''),action:acting||/CHECK|CONFIRM|ORDER CHANGED|SCANNED BEFORE|SCANNED TWICE/i.test(big)}));}catch(e){}}"
            + "var t=null;var st=$('status');if(st)new MutationObserver(function(){clearTimeout(t);t=setTimeout(send,60);}).observe(st,{childList:true,subtree:true,characterData:true,attributes:true});"
            + "setTimeout(send,1500);})();";

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
