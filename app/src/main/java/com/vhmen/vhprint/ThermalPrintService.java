package com.vhmen.vhprint;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PrintAttributes;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintDocument;
import android.printservice.PrintJob;
import android.printservice.PrintService;
import android.printservice.PrinterDiscoverySession;

import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android print service: shows "SUNMI 80mm (VH Print)" in Chrome's printer list and prints on the built-in printer. */
public class ThermalPrintService extends PrintService {
    // 80 mm wide; the page is long and the blank part is cut off before printing
    static final PrintAttributes.MediaSize ROLL = new PrintAttributes.MediaSize("VH_ROLL_80", "80 mm roll", 3150, 11811);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected PrinterDiscoverySession onCreatePrinterDiscoverySession() {
        return new PrinterDiscoverySession() {
            @Override public void onStartPrinterDiscovery(List<PrinterId> priorityList) { publish(); }
            @Override public void onStopPrinterDiscovery() { }
            @Override public void onValidatePrinters(List<PrinterId> ids) { publish(); }
            @Override public void onStartPrinterStateTracking(PrinterId id) { publish(); }
            @Override public void onStopPrinterStateTracking(PrinterId id) { }
            @Override public void onDestroy() { }
            private void publish() {
                PrinterId id = generatePrinterId("sunmi-inner");
                PrinterCapabilitiesInfo caps = new PrinterCapabilitiesInfo.Builder(id)
                        .addMediaSize(ROLL, true)
                        .addResolution(new PrintAttributes.Resolution("r203", "203 dpi", 203, 203), true)
                        .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build();
                PrinterInfo p = new PrinterInfo.Builder(id, "SUNMI 80mm (VH Print)", PrinterInfo.STATUS_IDLE)
                        .setDescription("Built-in printer · 80 mm roll").setCapabilities(caps).build();
                addPrinters(Collections.singletonList(p));
            }
        };
    }

    @Override protected void onPrintJobQueued(PrintJob job) {
        if (!job.start()) return;
        final String key = job.getId().toString();
        final AtomicBoolean cancel = new AtomicBoolean(false);
        cancels.put(key, cancel);
        PrintDocument doc = job.getDocument();
        final ParcelFileDescriptor fd = doc == null ? null : doc.getData();
        if (fd == null) { job.fail("No document"); cancels.remove(key); return; }
        worker.execute(() -> {
            File pdf = null;
            try {
                pdf = Thermal.copyToCache(this, new FileInputStream(fd.getFileDescriptor()));
                List<Thermal.Raster> pages = Thermal.render(pdf, cancel);
                if (pages.isEmpty()) throw new Exception("Nothing to print (blank page)");
                Thermal.send(Thermal.escpos(pages), cancel);
                main.post(() -> { if (cancel.get()) job.cancel(); else job.complete(); });
            } catch (SecurityException se) {
                main.post(() -> job.fail("Open the VH Print app once and allow Bluetooth"));
            } catch (Exception e) {
                final String m = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> { if (cancel.get()) job.cancel(); else job.fail(m); });
            } finally {
                try { fd.close(); } catch (Exception ignored) { }
                if (pdf != null) pdf.delete();
                cancels.remove(key);
            }
        });
    }

    @Override protected void onRequestCancelPrintJob(PrintJob job) {
        AtomicBoolean c = cancels.get(job.getId().toString());
        if (c != null) c.set(true); else job.cancel();
    }

    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
