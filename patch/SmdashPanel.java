package app.smdash.inject;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;

/**
 * Injected into the STOCK Screenmate settings "Display" section (SectionDisplay.onViewModelReady).
 * Adds a self-contained block at the TOP of the scroll content: a master "Show Dashboard" toggle
 * above a row of dashboard-style thumbnails (Stock / Arc / Stack / Strip / Mini), and hides the
 * stock's now-redundant native "Dashboard Visibility" switch (our master replaces it).
 *
 * It NEVER writes Settings.Global (the stock app may lack WRITE_SECURE_SETTINGS); it only
 * broadcasts to our app (app.smdash), which owns all state + flag writes:
 *   - Stock                  -> app.smdash.SHOWSTOCK
 *   - Arc/Stack/Strip/Mini   -> app.smdash.SHOWOURS  (extra "style"=key)
 *   - master OFF             -> app.smdash.HIDEALL
 * Initial UI state is READ (no permission needed) from Settings.Global smdash_style / smdash_master,
 * which our app writes whenever the selection changes.
 *
 * Thumbnails for our four styles are real tile renders bundled at assets/smdash/thumb_<key>.png;
 * "stock" (and any missing asset) falls back to a schematic drawn here.
 */
public final class SmdashPanel implements View.OnClickListener, CompoundButton.OnCheckedChangeListener,
        SeekBar.OnSeekBarChangeListener, View.OnAttachStateChangeListener {

    /** Watches smdash_style / smdash_master so the panel's highlight + master toggle track the live
     *  dashboard even while the panel stays open (e.g. after a 5-tap switch on the stock dashboard). */
    static final class GlobalObserver extends ContentObserver {
        final SmdashPanel p;
        GlobalObserver(SmdashPanel p) {
            super(new Handler(Looper.getMainLooper()));
            this.p = p;
        }
        @Override
        public void onChange(boolean self) {
            p.syncFromGlobal();
        }
    }

    static final String PKG = "app.smdash";
    static final String A_STOCK = "app.smdash.SHOWSTOCK";
    static final String A_OURS = "app.smdash.SHOWOURS";
    static final String A_HIDE = "app.smdash.HIDEALL";
    static final String G_STYLE = "smdash_style";
    static final String G_MASTER = "smdash_master";

    static final String[] KEYS = {"stock", "arc", "stack", "strip", "mini"};
    static final String[] LABELS = {"Stock", "Arc", "Stack", "Strip", "Mini"};

    // palette (dark panel friendly)
    static final int ACCENT = 0xFF29E0A6;   // our dashboard green
    static final int CARD_BG = 0xFF15171B;
    static final int CARD_EDGE = 0x22FFFFFF;
    static final int INK = 0xFFF2F4F6;
    static final int SUB = 0xFF9AA0A6;
    static final int STOCK_BLUE = 0xFF1461FF; // the stock app's own accent (color/accent) — match its switches
    static final int VER_RED = 0xFFF2564B;    // app-version text
    static final String RELEASES_URL = "https://github.com/PavelDemyanov/screenmate-dash/releases";
    static final String TAG_GITHUB = "__smdash_github__"; // link tag, routed through onClick(this)

    final Context ctx;
    final float dens;
    String selected = "stock";
    boolean master = true;

    Switch masterSw;
    LinearLayout thumbRow;
    TextView transpPctLabel;
    GlobalObserver observer;
    boolean observerReg;
    boolean syncing; // guards masterSw.setChecked() during a live sync so it doesn't re-broadcast
    final ArrayList<FrameLayout> wraps = new ArrayList<>();

    static int clampPct(int p) {
        return p < 0 ? 0 : (p > 80 ? 80 : p);
    }

    SmdashPanel(Context c) {
        ctx = c;
        dens = c.getResources().getDisplayMetrics().density;
    }

    int dp(float x) {
        return Math.round(x * dens);
    }

    /** Entry point (called from SectionDisplay smali with p0 = the section FrameLayout). */
    public static void inject(View sectionRoot) {
        try {
            if (!(sectionRoot instanceof ViewGroup)) return;
            ViewGroup scroll = findScroll((ViewGroup) sectionRoot);
            if (scroll == null || scroll.getChildCount() == 0) return;
            if (scroll.findViewWithTag("smdash_block") != null) return; // already injected

            SmdashPanel p = new SmdashPanel(sectionRoot.getContext());
            p.readState();

            View content = scroll.getChildAt(0);
            scroll.removeView(content);

            LinearLayout col = new LinearLayout(p.ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            col.addView(p.buildBlock());

            content.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(content);

            scroll.addView(col);

            // our master toggle replaces the stock's native "Dashboard Visibility" — hide it to
            // avoid a duplicate control that would fight our state
            hideStockRow(sectionRoot, "swDashboardVisibility");
            hideStockRow(sectionRoot, "tvDashboardVisibility");
            // our own transparency slider (in the block above) replaces the stock's native one
            hideStockRow(sectionRoot, "sliDashboardTransparency");
            hideStockRow(sectionRoot, "tvDashboardTransparency");

            // keep the highlight/toggle live if the dashboard changes while the panel is open
            p.observer = new GlobalObserver(p);
            col.addOnAttachStateChangeListener(p);
            if (col.isAttachedToWindow()) p.registerObserver();

            Log.i("smdash", "settings panel injected");
        } catch (Throwable t) {
            Log.e("smdash", "panel inject failed", t);
        }
    }

    /** Depth-first find of the NestedScrollView (matched by class name, no androidx compile dep). */
    static ViewGroup findScroll(ViewGroup root) {
        int n = root.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = root.getChildAt(i);
            if (c.getClass().getName().endsWith("NestedScrollView")) return (ViewGroup) c;
            if (c instanceof ViewGroup) {
                ViewGroup r = findScroll((ViewGroup) c);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** Hide a stock view found by resource name (GONE collapses in the stock's ConstraintLayout). */
    static void hideStockRow(View root, String name) {
        try {
            Resources res = root.getResources();
            int id = res.getIdentifier(name, "id", root.getContext().getPackageName());
            if (id != 0) {
                View v = root.findViewById(id);
                if (v != null) v.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {
        }
    }

    void readState() {
        try {
            String s = Settings.Global.getString(ctx.getContentResolver(), G_STYLE);
            if (s != null) {
                for (String k : KEYS) {
                    if (k.equals(s)) { selected = s; break; }
                }
            }
            master = Settings.Global.getInt(ctx.getContentResolver(), G_MASTER, 1) != 0;
        } catch (Throwable ignored) {
        }
    }

    LinearLayout buildBlock() {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setTag("smdash_block");
        block.setPadding(dp(20), dp(14), dp(20), dp(6));

        block.addView(buildHeader());

        // master row: "Show Dashboard"  [switch]
        LinearLayout mrow = new LinearLayout(ctx);
        mrow.setOrientation(LinearLayout.HORIZONTAL);
        mrow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mrp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mrp.topMargin = dp(6);
        mrow.setLayoutParams(mrp);

        TextView mlabel = label("Show Dashboard", 16, INK, false, 0f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlabel.setLayoutParams(llp);
        mrow.addView(mlabel);

        masterSw = new Switch(ctx);
        masterSw.setChecked(master);
        masterSw.setScaleX(1.15f);
        masterSw.setScaleY(1.15f);
        // blue like the stock's own switches (default Material tint is purple)
        int[][] sw = {{android.R.attr.state_checked}, {}};
        masterSw.setThumbTintList(new ColorStateList(sw, new int[]{STOCK_BLUE, 0xFFECEFF1}));
        masterSw.setTrackTintList(new ColorStateList(sw, new int[]{STOCK_BLUE, 0xFF565A60}));
        masterSw.setOnCheckedChangeListener(this);
        mrow.addView(masterSw);
        block.addView(mrow);

        TextView slabel = label("Dashboard Style", 13, SUB, false, 0f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        slp.bottomMargin = dp(8);
        slabel.setLayoutParams(slp);
        block.addView(slabel);

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        thumbRow = new LinearLayout(ctx);
        thumbRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < KEYS.length; i++) thumbRow.addView(buildThumb(i));
        hs.addView(thumbRow);
        block.addView(hs);

        // Dashboard transparency — OUR own slider grouped with our block. The stock's native one is
        // hidden below (its AppSlider lives in a barrier-constrained ConstraintLayout that can't be
        // safely reparented). This drives our overlay via app.smdash.SETTRANSP (our app owns the value).
        LinearLayout trow = new LinearLayout(ctx);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trp.topMargin = dp(14);
        trow.setLayoutParams(trp);
        trow.addView(label("Dashboard Transparency", 13, SUB, false, 0f));
        View tsp = new View(ctx);
        trow.addView(tsp, new LinearLayout.LayoutParams(0, dp(1), 1f));
        int initPct = clampPct(Settings.Global.getInt(ctx.getContentResolver(), "smdash_transp", 40));
        transpPctLabel = label(initPct + "%", 13, INK, false, 0f);
        trow.addView(transpPctLabel);
        block.addView(trow);

        SeekBar sb = new SeekBar(ctx);
        sb.setMax(80);
        sb.setProgress(initPct);
        sb.setThumbTintList(ColorStateList.valueOf(STOCK_BLUE));
        sb.setProgressTintList(ColorStateList.valueOf(STOCK_BLUE));
        sb.setOnSeekBarChangeListener(this);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = dp(2);
        sb.setLayoutParams(sblp);
        block.addView(sb);

        // divider under our block
        View div = new View(ctx);
        LinearLayout.LayoutParams dvp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dvp.topMargin = dp(14);
        div.setLayoutParams(dvp);
        div.setBackgroundColor(0x14FFFFFF);
        block.addView(div);

        applyMasterDim();
        return block;
    }

    View buildThumb(int i) {
        String key = KEYS[i];

        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.rightMargin = dp(10);
        item.setLayoutParams(ip);
        item.setTag(key);
        item.setOnClickListener(this);

        FrameLayout wrap = new FrameLayout(ctx);
        int cw = dp(94), ch = dp(58);
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(cw, ch);
        wrap.setLayoutParams(wp);
        wrap.setBackground(border(key.equals(selected)));
        int pad = dp(3);
        wrap.setPadding(pad, pad, pad, pad);

        ImageView card = new ImageView(ctx);
        card.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap bmp = loadAsset("smdash/thumb_" + key + ".png");           // real tile render …
        if (bmp == null) bmp = drawThumb(key, cw - 2 * pad, ch - 2 * pad); // … or schematic fallback
        card.setImageBitmap(bmp);
        wrap.addView(card);
        item.addView(wrap);
        wraps.add(wrap);

        TextView lab = label(LABELS[i], 11, key.equals(selected) ? INK : SUB, false, 0f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        lab.setLayoutParams(lp);
        lab.setGravity(Gravity.CENTER);
        item.addView(lab);

        return item;
    }

    Bitmap loadAsset(String path) {
        try (InputStream is = ctx.getAssets().open(path)) {
            return BitmapFactory.decodeStream(is);
        } catch (Throwable t) {
            return null;
        }
    }

    GradientDrawable border(boolean sel) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD_BG);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(sel ? 2 : 1), sel ? ACCENT : CARD_EDGE);
        return g;
    }

    /** Schematic fallback thumbnail. "stock" is a muted grey STRIP (echoes the real Tesla dashboard,
     *  visually distinct from our green Arc); the rest only render if their asset is missing. */
    Bitmap drawThumb(String key, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = dp(8);
        p.setColor(0xFF0E1013);
        cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);

        Paint num = new Paint(Paint.ANTI_ALIAS_FLAG);
        num.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        num.setColor(INK);
        float cx = w / 2f, cy = h / 2f;

        if (key.equals("stock")) {
            // muted grey strip: big number left, faint KM/H + P R N D, thin baseline
            int grey = 0xFFAEB4BA;
            num.setColor(grey);
            num.setTextAlign(Paint.Align.LEFT);
            num.setTextSize(h * 0.42f);
            cv.drawText("88", dp(10), cy + h * 0.02f, num);
            Paint sm = new Paint(Paint.ANTI_ALIAS_FLAG);
            sm.setColor(0xFF6B7075);
            sm.setTextSize(h * 0.13f);
            sm.setTextAlign(Paint.Align.LEFT);
            cv.drawText("KM/H", dp(11), cy + h * 0.24f, sm);
            sm.setTextSize(h * 0.20f);
            sm.setTextAlign(Paint.Align.CENTER);
            cv.drawText("P R N D", w * 0.66f, cy + h * 0.06f, sm);
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(0xFF303439);
            line.setStrokeWidth(dp(1.5f));
            cv.drawLine(dp(10), h * 0.72f, w - dp(10), h * 0.72f, line);
            return bmp;
        }

        Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(dp(3));
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(ACCENT);
        num.setTextAlign(Paint.Align.CENTER);
        if (key.equals("arc")) {
            RectF rr = new RectF(dp(10), h * 0.30f, w - dp(10), h * 1.35f);
            cv.drawArc(rr, 180, 180, false, arc);
            num.setTextSize(h * 0.42f);
            cv.drawText("88", cx, cy + h * 0.14f, num);
        } else if (key.equals("stack")) {
            cv.drawLine(w * 0.22f, h * 0.30f, w * 0.78f, h * 0.30f, arc);
            num.setTextSize(h * 0.40f);
            cv.drawText("88", cx, cy + h * 0.10f, num);
        } else if (key.equals("strip")) {
            num.setTextSize(h * 0.34f);
            num.setTextAlign(Paint.Align.LEFT);
            cv.drawText("88", dp(12), cy + h * 0.11f, num);
            cv.drawLine(w * 0.55f, cy, w * 0.86f, cy, arc);
        } else { // mini
            arc.setStrokeWidth(dp(2.5f));
            cv.drawCircle(cx, cy - h * 0.04f, h * 0.30f, arc);
            num.setTextSize(h * 0.30f);
            cv.drawText("88", cx, cy + h * 0.06f, num);
        }
        return bmp;
    }

    TextView label(String s, float sp, int color, boolean caps, float spacing) {
        TextView t = new TextView(ctx);
        t.setText(caps ? s.toUpperCase() : s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (spacing > 0f) t.setLetterSpacing(spacing / 10f);
        if (caps) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Header row: "SM DASH  vX.Y" (version in red) on the left, a GitHub update link on the right. */
    LinearLayout buildHeader() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(label("SM DASH", 11, SUB, true, 1.5f));

        String ver = appVersion();
        if (!ver.isEmpty()) {
            TextView v = label(ver, 11, VER_RED, false, 0f);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.leftMargin = dp(8);
            v.setLayoutParams(vp);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(v);
        }

        View spacer = new View(ctx);
        row.addView(spacer, new LinearLayout.LayoutParams(0, dp(1), 1f));

        TextView link = label("Update on GitHub", 12, STOCK_BLUE, false, 0f);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setPadding(dp(6), dp(4), 0, dp(4));
        link.setTag(TAG_GITHUB);       // no anonymous class (d8 chokes on it) — route via onClick(this)
        link.setOnClickListener(this);
        row.addView(link);

        return row;
    }

    /** OUR app's version. This panel runs in the stock/settings process, where Android's package-
     *  visibility filter blocks getPackageInfo("app.smdash") — so app.smdash publishes it into
     *  Settings.Global (readable without a query) on every service start; we just read it. */
    String appVersion() {
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), "smdash_ver");
            return (v == null || v.isEmpty()) ? "" : "v" + v;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Open a URL in the browser (from the stock settings process → needs NEW_TASK). */
    void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.e("smdash", "openUrl failed", t);
        }
    }

    void applyMasterDim() {
        if (thumbRow == null) return;
        thumbRow.setAlpha(master ? 1f : 0.35f);
    }

    void refreshSelection() {
        for (int i = 0; i < wraps.size(); i++) {
            wraps.get(i).setBackground(border(KEYS[i].equals(selected)));
        }
    }

    @Override
    public void onClick(View v) {
        Object tag = v.getTag();
        if (TAG_GITHUB.equals(tag)) { openUrl(RELEASES_URL); return; } // link works regardless of master
        if (!master) return; // thumbnails locked while the master toggle is off
        if (!(tag instanceof String)) return;
        selected = (String) tag;
        refreshSelection();
        broadcastSelection();
    }

    @Override
    public void onCheckedChanged(CompoundButton b, boolean checked) {
        if (syncing) return; // a live sync flipped the switch, not the user — don't re-broadcast
        master = checked;
        applyMasterDim();
        if (checked) broadcastSelection();
        else send(A_HIDE, null);
    }

    // --- live sync: reflect the current dashboard selection/master into the panel UI ---

    @Override
    public void onViewAttachedToWindow(View v) {
        registerObserver();
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        unregisterObserver();
    }

    void registerObserver() {
        if (observerReg || observer == null) return;
        try {
            ContentResolver cr = ctx.getContentResolver();
            cr.registerContentObserver(Settings.Global.getUriFor(G_STYLE), false, observer);
            cr.registerContentObserver(Settings.Global.getUriFor(G_MASTER), false, observer);
            observerReg = true;
            syncFromGlobal(); // pick up anything that changed while we were detached
        } catch (Throwable t) {
            Log.e("smdash", "registerObserver failed", t);
        }
    }

    void unregisterObserver() {
        if (!observerReg || observer == null) return;
        try {
            ctx.getContentResolver().unregisterContentObserver(observer);
        } catch (Throwable ignored) {
        }
        observerReg = false;
    }

    /** Re-read the live selection/master from Settings.Global and refresh the highlight + toggle. */
    void syncFromGlobal() {
        readState();
        refreshSelection();
        if (masterSw != null && masterSw.isChecked() != master) {
            syncing = true;
            masterSw.setChecked(master);
            syncing = false;
        }
        applyMasterDim();
    }

    @Override
    public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
        if (transpPctLabel != null) transpPctLabel.setText(progress + "%");
        if (fromUser) sendTransp(progress / 100f); // 0..80 -> 0..0.8
    }

    @Override
    public void onStartTrackingTouch(SeekBar sb) { }

    @Override
    public void onStopTrackingTouch(SeekBar sb) { }

    /** Push a transparency value (0..0.8) to our overlay app (it owns transpOverride). */
    void sendTransp(float v) {
        try {
            Intent i = new Intent("app.smdash.SETTRANSP").setPackage(PKG);
            i.putExtra("v", v);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Log.e("smdash", "sendTransp failed", t);
        }
    }

    void broadcastSelection() {
        if ("stock".equals(selected)) send(A_STOCK, null);
        else send(A_OURS, selected);
    }

    void send(String action, String style) {
        try {
            Intent i = new Intent(action).setPackage(PKG);
            if (style != null) i.putExtra("style", style);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            Log.e("smdash", "broadcast failed", t);
        }
    }
}
