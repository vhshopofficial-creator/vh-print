package com.vhmen.vhprint;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns a PDF into black/white rows for the SUNMI built-in 80 mm printer and sends them over Bluetooth to "InnerPrinter"
 * (the built-in printer shows up as a paired Bluetooth device 00:11:22:33:44:55; ESC/POS "GS v 0" raster works on it).
 * The blank paper at the top and bottom of every page is cut off, so a short chart uses a short piece of paper.
 */
public final class Thermal {
    public static final int WIDTH = 576;                 // 80 mm roll: 72 mm printable x 8 dots/mm
    private static final int MARGIN = 8;                 // dots kept above / below the content of a page
    private static final int BAND = 128;                 // rows per GS v 0 command
    private static final String INNER_ADDR = "00:11:22:33:44:55";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    public interface Progress { void page(int done, int total); }

    /** one page as 1-bit rows: WIDTH/8 bytes per row, MSB = left dot, 1 = black */
    public static final class Raster {
        final byte[] data; final int rows;
        Raster(byte[] data, int rows) { this.data = data; this.rows = rows; }
    }

    public static File copyToCache(Context ctx, InputStream in) throws Exception {
        File f = File.createTempFile("vhprint_", ".pdf", ctx.getCacheDir());
        try (OutputStream out = new FileOutputStream(f); InputStream src = in) {
            byte[] buf = new byte[16384]; int n; long total = 0;
            while ((n = src.read(buf)) != -1) {
                total += n;
                if (total > 50L * 1024 * 1024) throw new Exception("PDF larger than 50 MB");
                out.write(buf, 0, n);
            }
        } catch (Exception e) { f.delete(); throw e; }
        return f;
    }

    /** PDF -> one cropped raster per page (blank pages are skipped) */
    public static List<Raster> render(File pdf, AtomicBoolean cancel) throws Exception {
        List<Raster> out = new ArrayList<>();
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(pfd)) {
            int n = r.getPageCount();
            if (n > 60) throw new Exception("More than 60 pages");
            for (int i = 0; i < n; i++) {
                if (cancel.get()) throw new InterruptedException("Cancelled");
                try (PdfRenderer.Page p = r.openPage(i)) {
                    float scale = (float) WIDTH / p.getWidth();
                    int h = Math.max(1, (int) Math.ceil(p.getHeight() * scale));
                    if (h > 20000) throw new Exception("Page too long");
                    Bitmap bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888);
                    try {
                        bmp.eraseColor(Color.WHITE);
                        Matrix m = new Matrix(); m.postScale(scale, scale);
                        p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                        Raster ras = toRaster(bmp);
                        if (ras != null) out.add(ras);
                    } finally { bmp.recycle(); }
                }
            }
        }
        return out;
    }

    /** bitmap -> 1-bit rows, cropped to the content (+MARGIN); null when the page is blank */
    public static Raster toRaster(Bitmap bmp) {
        int w = Math.min(WIDTH, bmp.getWidth()), h = bmp.getHeight(), bpr = WIDTH / 8;
        int[] px = new int[w];
        byte[] all = new byte[bpr * h];
        int first = -1, last = -1;
        for (int y = 0; y < h; y++) {
            bmp.getPixels(px, 0, w, 0, y, w, 1);
            boolean any = false;
            for (int x = 0; x < w; x++) {
                int c = px[x];
                int a = Color.alpha(c);
                int lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                lum = 255 - ((255 - lum) * a / 255);          // transparent = white
                if (lum < 140) { all[y * bpr + (x >> 3)] |= (byte) (0x80 >> (x & 7)); any = true; }
            }
            if (any) { if (first < 0) first = y; last = y; }
        }
        if (first < 0) return null;
        int top = Math.max(0, first - MARGIN), bottom = Math.min(h - 1, last + MARGIN), rows = bottom - top + 1;
        byte[] data = new byte[bpr * rows];
        System.arraycopy(all, top * bpr, data, 0, data.length);
        return new Raster(data, rows);
    }

    /** ESC/POS bytes for the rasters: init, GS v 0 bands, feed past the tear bar */
    public static byte[] escpos(List<Raster> pages) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int bpr = WIDTH / 8;
        o.write(0x1B); o.write(0x40);                                   // ESC @  init
        for (Raster r : pages) {
            for (int y = 0; y < r.rows; y += BAND) {
                int n = Math.min(BAND, r.rows - y);
                o.write(0x1D); o.write(0x76); o.write(0x30); o.write(0x00); // GS v 0, normal size
                o.write(bpr & 0xFF); o.write((bpr >> 8) & 0xFF);
                o.write(n & 0xFF); o.write((n >> 8) & 0xFF);
                o.write(r.data, y * bpr, n * bpr);
            }
        }
        o.write(0x1B); o.write(0x64); o.write(5);                       // ESC d 5  feed 5 lines
        return o.toByteArray();
    }

    @SuppressLint("MissingPermission")
    public static BluetoothDevice findInner() throws Exception {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) throw new Exception("No Bluetooth on this device");
        if (!ad.isEnabled()) throw new Exception("Turn Bluetooth on (the SUNMI printer is reached through Bluetooth)");
        Set<BluetoothDevice> bonded = ad.getBondedDevices();
        if (bonded != null) for (BluetoothDevice d : bonded) {
            String name = d.getName();
            if (INNER_ADDR.equalsIgnoreCase(d.getAddress()) || (name != null && name.toLowerCase().contains("innerprinter"))) return d;
        }
        return ad.getRemoteDevice(INNER_ADDR);
    }

    /** send the bytes to InnerPrinter in small packets (the printer's buffer is small) */
    @SuppressLint("MissingPermission")
    public static void send(byte[] bytes, AtomicBoolean cancel) throws Exception {
        BluetoothDevice dev = findInner();
        BluetoothSocket s = null;
        try {
            try { s = dev.createRfcommSocketToServiceRecord(SPP); s.connect(); }
            catch (Exception first) {
                try { if (s != null) s.close(); } catch (Exception ignored) { }
                s = dev.createInsecureRfcommSocketToServiceRecord(SPP); s.connect();
            }
            OutputStream out = s.getOutputStream();
            for (int i = 0; i < bytes.length; i += 1024) {
                if (cancel != null && cancel.get()) { out.write(new byte[] { 0x1B, 0x40 }); throw new InterruptedException("Cancelled"); }
                out.write(bytes, i, Math.min(1024, bytes.length - i));
                out.flush();
                Thread.sleep(20);
            }
            out.flush();
            Thread.sleep(1500 + Math.min(6000, bytes.length / 20));     // let the printer finish before the link closes
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }
    }
}
