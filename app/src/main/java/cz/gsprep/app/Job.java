package cz.gsprep.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import cz.gsprep.core.GpuEngine;
import cz.gsprep.core.ImageStats;
import cz.gsprep.core.Model;
import cz.gsprep.core.Params;
import cz.gsprep.core.PixelSink;
import cz.gsprep.core.Progress;

/** Background job: analysis (sharpness, set-wide levels, best video frames) + GPU processing. */
final class Job implements Runnable {

    interface Listener {
        void onStatus(String text);
        void onProgress(float fraction);
        void onLog(String line);
        void onPreview(Bitmap before, Bitmap after, String info);
        void onFinished(String summary);
    }

    static final class Source {
        final Uri uri;
        final boolean video;
        final String name;

        Source(Uri uri, boolean video, String name) {
            this.uri = uri;
            this.video = video;
            this.name = name;
        }
    }

    static final class Settings {
        Params params = new Params();
        boolean dropBlurry = true;
        int dropPercent = 10;
        boolean autoLevels = false;
        float videoFps = 2f;
        int videoCandidates = 3;
        int format = MediaIO.FMT_JPEG95;
        boolean keepExif = true;
    }

    private static final class Item {
        Source src;
        long timeUs = -1;
        int index;          // frame number (video) for naming
        double score = Double.NaN;
        boolean keep = true;
    }

    static final String MODEL_ASSET = "realesr_general_x4v3.gsm";
    private static final int ANALYSIS_SIDE = 1024;
    private static final int VIDEO_ANALYSIS_SIDE = 960;
    private static final int MAX_VIDEO_FRAMES = 3000;

    private final Context ctx;
    private final ContentResolver cr;
    private final List<Source> sources;
    private final Settings st;
    private final boolean previewOnly;
    private final Listener ui;
    private volatile boolean cancelled;

    Job(Context ctx, List<Source> sources, Settings settings, boolean previewOnly, Listener ui) {
        this.ctx = ctx.getApplicationContext();
        this.cr = ctx.getContentResolver();
        this.sources = new ArrayList<Source>(sources);
        this.st = settings;
        this.previewOnly = previewOnly;
        this.ui = ui;
    }

    void cancel() {
        cancelled = true;
    }

    private void checkCancel() throws Progress.CancelledException {
        if (cancelled) throw new Progress.CancelledException();
    }

    @Override
    public void run() {
        try {
            if (previewOnly) {
                runPreview();
            } else {
                runBatch();
            }
        } catch (Progress.CancelledException e) {
            ui.onFinished("Zrušeno.");
        } catch (Throwable t) {
            ui.onLog("CHYBA: " + describe(t));
            ui.onFinished("Chyba: " + describe(t));
        }
    }

    static String describe(Throwable t) {
        if (t instanceof OutOfMemoryError) return "nedostatek paměti – zkuste menší max. rozměr";
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    // ================================================================= preview

    private void runPreview() throws Exception {
        Source s = sources.get(0);
        ui.onStatus("Náhled: načítám " + s.name + " …");
        Params p = st.params.copy();
        Bitmap full;
        if (s.video) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, s.uri);
                MediaIO.VideoInfo vi = MediaIO.videoInfo(r);
                full = MediaIO.videoFrame(r, vi, vi.durationUs / 2, 0);
            } finally {
                r.release();
            }
            if (full == null) throw new IllegalStateException("Nelze přečíst snímek z videa");
        } else {
            full = MediaIO.decodePhoto(cr, s.uri, p.maxSide);
        }
        checkCancel();
        full = MediaIO.limit(full, p.maxSide);
        if (st.autoLevels) {
            int[] px = analysisPixels(full);
            long[] hist = new long[768];
            ImageStats.accumulate(px, px.length, hist);
            float[] lv = ImageStats.levels(hist, 0.001, 0.999);
            p.levelsLo = lv[0];
            p.levelsHi = lv[1];
        }
        int w = full.getWidth(), h = full.getHeight();
        int[] t = Params.targetSize(w, h, p.scale, p.maxSide);
        double e = t[0] / (double) w;
        int crop = (int) Math.round(640 / e);
        crop = Math.max(32, Math.min(crop, Math.min(w, h)));
        int cx = (w - crop) / 2, cy = (h - crop) / 2;
        Bitmap in = Bitmap.createBitmap(full, cx, cy, crop, crop);
        if (in != full) full.recycle();
        in = MediaIO.ensureArgb(in);
        int dw = Math.max(1, (int) Math.round(crop * e));

        EglContext egl = new EglContext();
        GpuEngine eng = null;
        try {
            egl.create();
            eng = createEngine(p.ai);
            Bitmap out = runEngine(eng, in, p, dw, dw, 0f, 1f);
            // nearest-neighbour enlargement so that "before" shows the real input pixels
            Bitmap before = (dw == crop) ? in : Bitmap.createScaledBitmap(in, dw, dw, false);
            if (before != in) in.recycle();
            String info = String.format(Locale.US, "Výřez %d×%d px → %d×%d px (%.2f×), %s",
                    crop, crop, dw, dw, e, (p.ai && eng.hasAi()) ? "AI Real-ESRGAN" : "bikubicky");
            ui.onPreview(before, out, info);
            ui.onFinished("Náhled hotov. GPU: " + egl.renderer);
        } finally {
            if (eng != null) eng.release();
            egl.release();
        }
    }

    // ================================================================= batch

    private void runBatch() throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        Params p = st.params.copy();
        List<Item> items = analyse(p);
        checkCancel();
        int keep = 0;
        for (Item it : items) if (it.keep) keep++;
        if (keep == 0) {
            ui.onFinished("Nic ke zpracování.");
            return;
        }
        String session = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ui.onLog("Výstup: Obrázky/" + MediaIO.OUT_DIR + "/" + session + "  (" + keep + " souborů)");

        EglContext egl = new EglContext();
        GpuEngine eng = null;
        int ok = 0, failed = 0, consecutiveFail = 0;
        try {
            egl.create();
            eng = createEngine(p.ai);
            ui.onLog("GPU: " + egl.renderer + " (" + egl.version + ")");
            int cap = Math.min(p.maxSide, eng.maxTextureSize);
            p.maxSide = cap;
            HashSet<String> used = new HashSet<String>();
            int done = 0;
            long procStart = SystemClock.elapsedRealtime();
            for (Item it : items) {
                if (!it.keep) continue;
                checkCancel();
                String outName = outputName(it, used);
                String eta = "";
                if (done > 0) {
                    long per = (SystemClock.elapsedRealtime() - procStart) / done;
                    eta = " · zbývá ~" + formatDuration(per * (keep - done));
                }
                ui.onStatus("Zpracovávám " + (done + 1) + "/" + keep + ": " + outName + eta);
                long ti = SystemClock.elapsedRealtime();
                try {
                    Bitmap in = load(it, cap);
                    int w = in.getWidth(), h = in.getHeight();
                    int[] t = Params.targetSize(w, h, p.scale, cap);
                    final int base = done;
                    final int total = keep;
                    Bitmap out = runEngine(eng, in, p, t[0], t[1], (float) base / total, 1f / total);
                    in.recycle();
                    MediaIO.save(ctx, out, session, outName, st.format,
                            (st.keepExif && !it.src.video) ? it.src.uri : null);
                    out.recycle();
                    ok++;
                    consecutiveFail = 0;
                    ui.onLog(String.format(Locale.US, "✓ %s  %d×%d → %d×%d  (%.1f s)", outName, w, h, t[0], t[1],
                            (SystemClock.elapsedRealtime() - ti) / 1000.0));
                } catch (Progress.CancelledException ce) {
                    throw ce;
                } catch (Throwable ex) {
                    failed++;
                    consecutiveFail++;
                    ui.onLog("✗ " + outName + ": " + describe(ex));
                    if (ex instanceof OutOfMemoryError) System.gc();
                    if (consecutiveFail >= 3) throw new IllegalStateException("3 chyby za sebou – přerušeno");
                }
                done++;
                ui.onProgress((float) done / keep);
            }
        } finally {
            if (eng != null) eng.release();
            egl.release();
        }
        String sum = "Hotovo: " + ok + " uloženo" + (failed > 0 ? ", " + failed + " chyb" : "")
                + " za " + formatDuration(SystemClock.elapsedRealtime() - t0)
                + ".\nSložka: Obrázky/" + MediaIO.OUT_DIR + "/" + session;
        ui.onFinished(sum);
    }

    private GpuEngine createEngine(boolean ai) throws Exception {
        Model model = null;
        if (ai) {
            InputStream is = ctx.getAssets().open(MODEL_ASSET);
            try {
                model = Model.load(is);
            } finally {
                is.close();
            }
        }
        return new GpuEngine(new AndroidGl(), model);
    }

    /** Runs the GPU pipeline on a bitmap. Progress is mapped to [base, base+span] of the bar. */
    private Bitmap runEngine(GpuEngine eng, Bitmap in, Params p, int dw, int dh,
                             final float base, final float span) throws Progress.CancelledException {
        int w = in.getWidth(), h = in.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
        in.copyPixelsToBuffer(buf);
        buf.position(0);
        final Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
        eng.process(buf, w, h, p, dw, dh, new PixelSink() {
            @Override
            public void strip(int y, int rows, int width, int[] argb) {
                out.setPixels(argb, 0, width, 0, y, width, rows);
            }
        }, new Progress() {
            @Override
            public void update(float f) {
                ui.onProgress(base + span * f);
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }
        });
        return out;
    }

    private Bitmap load(Item it, int cap) throws Exception {
        if (!it.src.video) return MediaIO.decodePhoto(cr, it.src.uri, cap);
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, it.src.uri);
            MediaIO.VideoInfo vi = MediaIO.videoInfo(r);
            Bitmap b = MediaIO.videoFrame(r, vi, it.timeUs, 0);
            if (b == null) throw new IllegalStateException("snímek nelze dekódovat");
            return MediaIO.limit(b, cap);
        } finally {
            r.release();
        }
    }

    private String outputName(Item it, HashSet<String> used) {
        String ext = MediaIO.extension(st.format);
        String base = MediaIO.baseName(it.src.name);
        if (it.src.video) base = base + String.format(Locale.US, "_%05d", it.index);
        String name = base + ext;
        int k = 2;
        while (used.contains(name.toLowerCase(Locale.ROOT))) name = base + "_" + (k++) + ext;
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    // ================================================================= analysis

    private List<Item> analyse(Params p) throws Exception {
        boolean hasVideo = false;
        for (Source s : sources) hasVideo |= s.video;
        boolean scorePhotos = st.dropBlurry && st.dropPercent > 0;
        boolean needAnalysis = hasVideo || scorePhotos || st.autoLevels;
        List<Item> items = new ArrayList<Item>();
        long[] hist = new long[768];
        if (needAnalysis) ui.onStatus("Analýza ostrosti a jasu …");
        int si = 0;
        for (Source s : sources) {
            checkCancel();
            if (s.video) {
                analyseVideo(s, items, hist, si, sources.size());
            } else {
                Item it = new Item();
                it.src = s;
                if (scorePhotos || st.autoLevels) {
                    ui.onStatus("Analýza " + (si + 1) + "/" + sources.size() + ": " + s.name);
                    try {
                        Bitmap b = MediaIO.decodePhoto(cr, s.uri, ANALYSIS_SIDE);
                        int[] px = analysisPixels(b);
                        it.score = ImageStats.sharpness(px, b.getWidth(), b.getHeight());
                        if (st.autoLevels) ImageStats.accumulate(px, px.length, hist);
                        b.recycle();
                    } catch (Exception e) {
                        ui.onLog("✗ " + s.name + ": nelze načíst (" + describe(e) + ")");
                        it.keep = false;
                    }
                }
                items.add(it);
                ui.onProgress((si + 1f) / sources.size());
            }
            si++;
        }

        if (st.dropBlurry && st.dropPercent > 0) {
            List<Double> scores = new ArrayList<Double>();
            for (Item it : items) if (it.keep && !Double.isNaN(it.score)) scores.add(it.score);
            int drop = (int) Math.floor(scores.size() * st.dropPercent / 100.0);
            if (drop > 0) {
                double[] arr = new double[scores.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = scores.get(i);
                Arrays.sort(arr);
                double limit = arr[drop - 1];
                int dropped = 0;
                for (Item it : items) {
                    if (dropped < drop && it.keep && !Double.isNaN(it.score) && it.score <= limit) {
                        it.keep = false;
                        dropped++;
                        ui.onLog(String.format(Locale.US, "– vyřazeno (rozmazané, ostrost %.0f): %s",
                                it.score, itemLabel(it)));
                    }
                }
                ui.onLog(String.format(Locale.US, "Vyřazeno %d nejrozmazanějších z %d (hranice ostrosti %.0f)",
                        dropped, arr.length, limit));
            }
        }
        if (st.autoLevels) {
            float[] lv = ImageStats.levels(hist, 0.001, 0.999);
            p.levelsLo = lv[0];
            p.levelsHi = lv[1];
            ui.onLog(String.format(Locale.US, "Auto úrovně pro celou sadu: černá %.0f, bílá %.0f (z 255)",
                    lv[0] * 255, lv[1] * 255));
        }
        ui.onProgress(0f);
        return items;
    }

    private String itemLabel(Item it) {
        if (!it.src.video) return it.src.name;
        return String.format(Locale.US, "%s @ %.2f s", it.src.name, it.timeUs / 1e6);
    }

    private void analyseVideo(Source s, List<Item> items, long[] hist, int si, int sourceCount)
            throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, s.uri);
            MediaIO.VideoInfo vi = MediaIO.videoInfo(r);
            if (vi.durationUs <= 0) throw new IllegalStateException("Neznámá délka videa " + s.name);
            double fps = Math.max(0.05, st.videoFps);
            int slots = (int) Math.max(1, Math.floor(vi.durationUs / 1e6 * fps));
            slots = Math.min(slots, MAX_VIDEO_FRAMES);
            long slotLen = vi.durationUs / slots;
            int k = Math.max(1, st.videoCandidates);
            ui.onLog(String.format(Locale.US, "Video %s: %.1f s, %d×%d, %d snímků (z %d kandidátů)",
                    s.name, vi.durationUs / 1e6, vi.width, vi.height, slots, slots * k));
            for (int slot = 0; slot < slots; slot++) {
                checkCancel();
                ui.onStatus(String.format(Locale.US, "Video %d/%d: výběr nejostřejšího snímku %d/%d",
                        si + 1, sourceCount, slot + 1, slots));
                long slotStart = slot * slotLen;
                double bestScore = -1;
                long bestT = -1;
                int[] bestPx = null;
                for (int c = 0; c < k; c++) {
                    long t = slotStart + (long) (slotLen * (c + 0.5) / k);
                    t = Math.min(t, Math.max(0, vi.durationUs - 1000));
                    Bitmap b = MediaIO.videoFrame(r, vi, t, VIDEO_ANALYSIS_SIDE);
                    if (b == null) continue;
                    int[] px = analysisPixels(b);
                    double sc = ImageStats.sharpness(px, b.getWidth(), b.getHeight());
                    b.recycle();
                    if (sc > bestScore) {
                        bestScore = sc;
                        bestT = t;
                        bestPx = px;
                    }
                }
                if (bestT >= 0) {
                    Item it = new Item();
                    it.src = s;
                    it.timeUs = bestT;
                    it.index = slot + 1;
                    it.score = bestScore;
                    items.add(it);
                    if (st.autoLevels && bestPx != null) ImageStats.accumulate(bestPx, bestPx.length, hist);
                }
                ui.onProgress((si + (slot + 1f) / slots) / sourceCount);
            }
        } finally {
            r.release();
        }
    }

    private static int[] analysisPixels(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    static String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min " + (s % 60) + " s";
        return (m / 60) + " h " + (m % 60) + " min";
    }
}
