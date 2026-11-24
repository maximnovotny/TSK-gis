package cz.gsprep.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cz.gsprep.core.Params;

public class MainActivity extends Activity implements Job.Listener {
    private static final int REQ_PHOTOS = 1, REQ_VIDEO = 2, REQ_FOLDER = 3;
    private static final int[] SCALES = {1, 2, 4};
    private static final int[] MAX_SIDES = {2048, 3072, 4096, 5120, 6144, 8192};
    private static final float[] FPS = {0.5f, 1f, 2f, 3f, 4f, 5f, 10f};
    private static final int[] CANDIDATES = {1, 3, 5, 8};
    private static final int ACCENT = 0xFF4DB6AC;

    private final ArrayList<Job.Source> sources = new ArrayList<Job.Source>();
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<String> logLines = new ArrayList<String>();
    private Job current;
    private SharedPreferences prefs;
    private boolean loading;

    private TextView tvSources, tvStatus, tvLog, tvPreviewInfo;
    private LinearLayout videoBox, previewBox;
    private Spinner spFps, spCand, spScale, spMethod, spMax, spFormat;
    private CheckBox cbDrop, cbAuto, cbExif;
    private Slider slDrop, slDenoise, slSharpAmt, slSharpRad, slSharpThr, slExpo, slContrast, slShadows,
            slHighlights, slSat;
    private Button btnPhotos, btnFolder, btnVideo, btnPreview, btnRun, btnCancel;
    private ProgressBar progress;
    private ImageView ivBefore, ivAfter;

    // ================================================================= UI construction

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("gsprep", MODE_PRIVATE);
        loading = true;

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = vbox();
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                return in;
            }
        });

        TextView title = text("GS Foto Prep", 24, true);
        title.setTextColor(ACCENT);
        root.addView(title);
        root.addView(text("Offline zvětšení, doostření a úprava snímků pro Gaussian splatting", 14, false));

        // 1. input -------------------------------------------------------------------------
        section(root, "1. Vstup");
        LinearLayout row = hbox();
        btnPhotos = button("Fotky", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_PHOTOS); }
        });
        btnFolder = button("Složka", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_FOLDER); }
        });
        btnVideo = button("Video", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(REQ_VIDEO); }
        });
        row.addView(btnPhotos, weight());
        row.addView(btnFolder, weight());
        row.addView(btnVideo, weight());
        root.addView(row);
        tvSources = text("Nic není vybráno.", 14, false);
        root.addView(tvSources);

        videoBox = vbox();
        spFps = spinner(videoBox, "Snímků z videa za sekundu",
                new String[] {"0,5", "1", "2", "3", "4", "5", "10"}, "fps", 2);
        spCand = spinner(videoBox, "Kandidátů na snímek (vybere se nejostřejší)",
                new String[] {"1 (bez výběru)", "3", "5", "8"}, "cand", 1);
        root.addView(videoBox);
        videoBox.setVisibility(View.GONE);

        // 2. selection ---------------------------------------------------------------------
        section(root, "2. Výběr ostrých snímků");
        cbDrop = checkbox(root, "Vyřadit nejrozmazanější snímky", "drop", true);
        slDrop = new Slider(root, "Vyřadit", "dropPct", 0, 50, 1, 10, "%.0f %%");

        // 3. upscale -----------------------------------------------------------------------
        section(root, "3. Zvětšení");
        spScale = spinner(root, "Měřítko", new String[] {"1× (bez zvětšení)", "2×", "4×"}, "scale", 1);
        spMethod = spinner(root, "Metoda", new String[] {"AI – Real-ESRGAN (GPU)", "Klasické – bikubické"},
                "method", 0);
        spMax = spinner(root, "Max. delší strana výstupu",
                new String[] {"2048 px", "3072 px", "4096 px", "5120 px", "6144 px", "8192 px"}, "max", 2);
        root.addView(hint("AI zvětšení je náročné: 1080p snímek trvá na telefonu řádově desítky sekund. "
                + "Nejvíc pomůže u malých snímků (video 720p/1080p). Velké fotky stačí doostřit v 1×."));

        // 4. enhancement -------------------------------------------------------------------
        section(root, "4. Vylepšení (stejné pro všechny snímky)");
        LinearLayout presets = hbox();
        presets.addView(button("GS doporučené", new View.OnClickListener() {
            @Override public void onClick(View v) { preset(0); }
        }), weight());
        presets.addView(button("Jen doostřit", new View.OnClickListener() {
            @Override public void onClick(View v) { preset(1); }
        }), weight());
        presets.addView(button("Šum / noc", new View.OnClickListener() {
            @Override public void onClick(View v) { preset(2); }
        }), weight());
        root.addView(presets);
        slDenoise = new Slider(root, "Odšumění", "denoise", 0, 100, 5, 0, "%.0f %%");
        slSharpAmt = new Slider(root, "Doostření – síla", "sharpAmt", 0, 200, 5, 50, "%.0f %%");
        slSharpRad = new Slider(root, "Doostření – poloměr", "sharpRad", 0.5f, 2.5f, 0.1f, 1.0f, "%.1f px");
        slSharpThr = new Slider(root, "Doostření – práh (šum)", "sharpThr", 0, 12, 0.5f, 2.5f, "%.1f / 255");
        slExpo = new Slider(root, "Expozice", "expo", -2, 2, 0.1f, 0, "%+.1f EV");
        slContrast = new Slider(root, "Kontrast", "contrast", -50, 50, 5, 0, "%+.0f %%");
        slShadows = new Slider(root, "Projasnit stíny", "shadows", 0, 100, 5, 0, "%.0f %%");
        slHighlights = new Slider(root, "Ztlumit světla", "highlights", 0, 100, 5, 0, "%.0f %%");
        slSat = new Slider(root, "Sytost", "sat", -50, 50, 5, 0, "%+.0f %%");
        cbAuto = checkbox(root, "Auto úrovně – společné pro celou sadu", "auto", false);

        // 5. output ------------------------------------------------------------------------
        section(root, "5. Výstup");
        spFormat = spinner(root, "Formát", new String[] {"JPEG 95 %", "JPEG 100 %", "PNG (bezeztrátové)"},
                "format", 0);
        cbExif = checkbox(root, "Zachovat EXIF (ohnisko, GPS, čas) – COLMAP z něj čte ohnisko", "exif", true);
        root.addView(hint("Ukládá se do Obrázky/GSPrep/<datum_čas>/. Orientace se zapíše přímo do pixelů."));

        // actions --------------------------------------------------------------------------
        LinearLayout actions = hbox();
        actions.setPadding(0, dp(16), 0, 0);
        btnPreview = button("Náhled", new View.OnClickListener() {
            @Override public void onClick(View v) { start(true); }
        });
        btnRun = button("Spustit", new View.OnClickListener() {
            @Override public void onClick(View v) { start(false); }
        });
        btnRun.setTextColor(ACCENT);
        btnCancel = button("Zrušit", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (current != null) current.cancel();
                tvStatus.setText("Ruším …");
            }
        });
        btnCancel.setEnabled(false);
        actions.addView(btnPreview, weight());
        actions.addView(btnRun, weight());
        actions.addView(btnCancel, weight());
        root.addView(actions);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        tvStatus = text("Připraveno.", 14, false);
        root.addView(tvStatus);

        previewBox = hbox();
        ivBefore = new ImageView(this);
        ivAfter = new ImageView(this);
        for (ImageView iv : new ImageView[] {ivBefore, ivAfter}) {
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { togglePreviewLayout(); }
            });
            LinearLayout.LayoutParams lp = weight();
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            previewBox.addView(iv, lp);
        }
        previewBox.setVisibility(View.GONE);
        root.addView(previewBox);
        tvPreviewInfo = text("", 12, false);
        root.addView(tvPreviewInfo);

        tvLog = text("", 12, false);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextIsSelectable(true);
        root.addView(tvLog);

        section(root, "Tipy pro Gaussian splatting");
        root.addView(hint("• Všechny snímky dostanou stejné úpravy – barvy a jas zůstanou mezi pohledy konzistentní "
                + "(per-snímkové automatiky by model rozbily).\n"
                + "• Vyřazení rozmazaných snímků obvykle pomůže víc než doostření.\n"
                + "• Nepřeostřujte – silné doostření tvoří lemy (halo), které se v modelu projeví jako artefakty.\n"
                + "• Původní 3DGS trénuje na max. ~1600 px; vyšší rozlišení má smysl jen při tréninku "
                + "v plném rozlišení (např. -r 1).\n"
                + "• U videa nastavte 2–3 snímky/s a 3–5 kandidátů; vznikne sekvence vhodná pro COLMAP "
                + "(sequential matcher)."));
        TextView about = text("Model: Real-ESRGAN realesr-general-x4v3 (BSD-3-Clause, Xintao Wang a kol.). "
                + "Vše běží offline na GPU telefonu.", 11, false);
        about.setAlpha(0.6f);
        root.addView(about);

        setContentView(scroll);
        loading = false;
        updateSources();
        handleShare(getIntent());
    }

    // ================================================================= picking

    private void pick(int req) {
        Intent i;
        if (req == REQ_FOLDER) {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(req == REQ_VIDEO ? "video/*" : "image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            tvStatus.setText("Nelze otevřít výběr souborů: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        ContentResolver cr = getContentResolver();
        ArrayList<Job.Source> picked = new ArrayList<Job.Source>();
        if (requestCode == REQ_FOLDER) {
            Uri tree = data.getData();
            if (tree == null) return;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
                Cursor c = cr.query(children, new String[] {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
                if (c != null) {
                    try {
                        while (c.moveToNext()) {
                            String mime = c.getString(2);
                            if (mime == null || !mime.startsWith("image/")) continue;
                            Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                            picked.add(new Job.Source(u, false, c.getString(1)));
                        }
                    } finally {
                        c.close();
                    }
                }
            } catch (Exception e) {
                tvStatus.setText("Složku nelze přečíst: " + e.getMessage());
                return;
            }
        } else {
            boolean video = requestCode == REQ_VIDEO;
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) picked.add(new Job.Source(u, video, MediaIO.displayName(cr, u)));
                }
            } else if (data.getData() != null) {
                Uri u = data.getData();
                picked.add(new Job.Source(u, video, MediaIO.displayName(cr, u)));
            }
        }
        setSources(picked);
    }

    private void setSources(ArrayList<Job.Source> picked) {
        Collections.sort(picked, new Comparator<Job.Source>() {
            @Override
            public int compare(Job.Source a, Job.Source b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        sources.clear();
        sources.addAll(picked);
        previewBox.setVisibility(View.GONE);
        tvPreviewInfo.setText("");
        updateSources();
    }

    /** Photos / videos shared from the gallery ("Sdílet" -> GS Foto Prep). */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent in) {
        if (in == null) return;
        String action = in.getAction();
        ArrayList<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            return;
        }
        ContentResolver cr = getContentResolver();
        ArrayList<Job.Source> picked = new ArrayList<Job.Source>();
        for (Uri u : uris) {
            String type = cr.getType(u);
            boolean video = type != null && type.startsWith("video/");
            picked.add(new Job.Source(u, video, MediaIO.displayName(cr, u)));
        }
        if (!picked.isEmpty()) setSources(picked);
    }

    private void updateSources() {
        int photos = 0, videos = 0;
        for (Job.Source s : sources) {
            if (s.video) videos++; else photos++;
        }
        if (sources.isEmpty()) {
            tvSources.setText("Nic není vybráno. Vyberte fotky, celou složku, nebo video.");
        } else {
            StringBuilder sb = new StringBuilder("Vybráno: ");
            if (photos > 0) sb.append(photos).append(photos == 1 ? " fotka" : (photos < 5 ? " fotky" : " fotek"));
            if (photos > 0 && videos > 0) sb.append(", ");
            if (videos > 0) sb.append(videos).append(videos == 1 ? " video" : " videa");
            sb.append("  (").append(sources.get(0).name);
            if (sources.size() > 1) sb.append(" … ").append(sources.get(sources.size() - 1).name);
            sb.append(")");
            tvSources.setText(sb.toString());
        }
        videoBox.setVisibility(videos > 0 ? View.VISIBLE : View.GONE);
        boolean idle = current == null;
        btnPreview.setEnabled(idle && !sources.isEmpty());
        btnRun.setEnabled(idle && !sources.isEmpty());
    }

    // ================================================================= run

    private Job.Settings collect() {
        Job.Settings s = new Job.Settings();
        Params p = s.params;
        p.scale = SCALES[spScale.getSelectedItemPosition()];
        p.ai = spMethod.getSelectedItemPosition() == 0;
        p.maxSide = MAX_SIDES[spMax.getSelectedItemPosition()];
        p.denoise = slDenoise.get() / 100f;
        p.sharpenAmount = slSharpAmt.get() / 100f;
        p.sharpenRadius = slSharpRad.get();
        p.sharpenThreshold = slSharpThr.get() / 255f;
        p.exposureEv = slExpo.get();
        p.contrast = slContrast.get() / 100f;
        p.shadows = slShadows.get() / 100f;
        p.highlights = slHighlights.get() / 100f;
        p.saturation = slSat.get() / 100f;
        s.dropBlurry = cbDrop.isChecked();
        s.dropPercent = Math.round(slDrop.get());
        s.autoLevels = cbAuto.isChecked();
        s.videoFps = FPS[spFps.getSelectedItemPosition()];
        s.videoCandidates = CANDIDATES[spCand.getSelectedItemPosition()];
        s.format = spFormat.getSelectedItemPosition();
        s.keepExif = cbExif.isChecked();
        return s;
    }

    private void start(boolean preview) {
        if (current != null || sources.isEmpty()) return;
        Job.Settings s = collect();
        if (!preview) {
            logLines.clear();
            tvLog.setText("");
        }
        current = new Job(this, sources, s, preview, this);
        setRunning(true);
        progress.setProgress(0);
        exec.execute(current);
    }

    private void setRunning(boolean running) {
        btnCancel.setEnabled(running);
        btnPhotos.setEnabled(!running);
        btnFolder.setEnabled(!running);
        btnVideo.setEnabled(!running);
        if (running) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            btnPreview.setEnabled(false);
            btnRun.setEnabled(false);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updateSources();
        }
    }

    private void preset(int which) {
        switch (which) {
            case 0: // GS recommended
                spScale.setSelection(1);
                spMethod.setSelection(0);
                spMax.setSelection(2);
                slDenoise.set(0); slSharpAmt.set(50); slSharpRad.set(1.0f); slSharpThr.set(2.5f);
                slExpo.set(0); slContrast.set(0); slShadows.set(0); slHighlights.set(0); slSat.set(0);
                cbDrop.setChecked(true); slDrop.set(10); cbAuto.setChecked(false);
                break;
            case 1: // sharpen only
                spScale.setSelection(0);
                spMethod.setSelection(1);
                slDenoise.set(0); slSharpAmt.set(80); slSharpRad.set(1.0f); slSharpThr.set(2.5f);
                break;
            default: // noisy / night
                spScale.setSelection(1);
                spMethod.setSelection(0);
                slDenoise.set(40); slSharpAmt.set(30); slSharpRad.set(1.2f); slSharpThr.set(4f);
                slShadows.set(20); slHighlights.set(0);
                break;
        }
        tvStatus.setText("Předvolba nastavena.");
    }

    // ================================================================= Job.Listener (worker thread)

    @Override
    public void onStatus(final String text) {
        main.post(new Runnable() {
            @Override public void run() { tvStatus.setText(text); }
        });
    }

    @Override
    public void onProgress(final float fraction) {
        main.post(new Runnable() {
            @Override public void run() { progress.setProgress(Math.round(Math.max(0f, Math.min(1f, fraction)) * 1000)); }
        });
    }

    @Override
    public void onLog(final String line) {
        main.post(new Runnable() {
            @Override
            public void run() {
                logLines.add(line);
                while (logLines.size() > 400) logLines.remove(0);
                tvLog.setText(TextUtils.join("\n", logLines));
            }
        });
    }

    @Override
    public void onPreview(final Bitmap before, final Bitmap after, final String info) {
        main.post(new Runnable() {
            @Override
            public void run() {
                ivBefore.setImageBitmap(before);
                ivAfter.setImageBitmap(after);
                previewBox.setVisibility(View.VISIBLE);
                tvPreviewInfo.setText("Vlevo/nahoře před, vpravo/dole po úpravě (klepnutím změníte rozložení). " + info);
            }
        });
    }

    @Override
    public void onFinished(final String summary) {
        main.post(new Runnable() {
            @Override
            public void run() {
                current = null;
                tvStatus.setText(summary);
                if (!summary.startsWith("Náhled")) progress.setProgress(summary.startsWith("Hotovo") ? 1000 : progress.getProgress());
                setRunning(false);
            }
        });
    }

    private void togglePreviewLayout() {
        boolean horizontal = previewBox.getOrientation() == LinearLayout.HORIZONTAL;
        previewBox.setOrientation(horizontal ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (int i = 0; i < previewBox.getChildCount(); i++) {
            LinearLayout.LayoutParams lp = horizontal
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : weight();
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            previewBox.getChildAt(i).setLayoutParams(lp);
        }
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.cancel();
        exec.shutdown();
        super.onDestroy();
    }

    // ================================================================= widget helpers

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12, false);
        t.setAlpha(0.75f);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private void section(LinearLayout root, String title) {
        TextView t = text(title, 17, true);
        t.setPadding(0, dp(18), 0, dp(4));
        t.setTextColor(ACCENT);
        root.addView(t);
        View line = new View(this);
        line.setBackgroundColor(Color.argb(60, 128, 128, 128));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private CheckBox checkbox(LinearLayout root, String label, final String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean(key, checked).apply();
            }
        });
        root.addView(c);
        return c;
    }

    private Spinner spinner(LinearLayout root, String label, String[] items, final String key, int def) {
        TextView t = text(label, 13, false);
        t.setPadding(0, dp(8), 0, 0);
        root.addView(t);
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        int sel = prefs.getInt(key, def);
        sp.setSelection(sel >= 0 && sel < items.length ? sel : def);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!loading) prefs.edit().putInt(key, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(sp);
        return sp;
    }

    /** Labelled SeekBar with a float value persisted in preferences. */
    private final class Slider {
        final float min, step;
        final String label, fmt, key;
        final SeekBar bar;
        final TextView tv;

        Slider(LinearLayout root, String label, String key, float min, float max, float step, float def,
               String fmt) {
            this.min = min;
            this.step = step;
            this.label = label;
            this.fmt = fmt;
            this.key = key;
            tv = text("", 13, false);
            tv.setPadding(0, dp(8), 0, 0);
            bar = new SeekBar(MainActivity.this);
            bar.setMax(Math.round((max - min) / step));
            root.addView(tv);
            root.addView(bar);
            set(prefs.getFloat(key, def));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    refresh();
                    if (fromUser) prefs.edit().putFloat(Slider.this.key, get()).apply();
                }

                @Override public void onStartTrackingTouch(SeekBar s) {}

                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

        float get() {
            return min + bar.getProgress() * step;
        }

        void set(float v) {
            bar.setProgress(Math.round((v - min) / step));
            refresh();
            prefs.edit().putFloat(key, get()).apply();
        }

        void refresh() {
            tv.setText(label + ": " + String.format(Locale.getDefault(), fmt, get()));
        }
    }
}
