package com.apkforge.shell;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Blocking "Update Required" screen. Shown when a newer build of this package name exists on the server. */
public class UpdateActivity extends Activity {
    private static final int GREEN = Color.parseColor("#00C566");
    private JSONObject info;
    private File apk;
    private Button dlBtn;
    private LinearLayout progressCard;
    private TextView progText, progPct;
    private ProgressBar progBar;
    private volatile boolean cancelled;
    private boolean pendingInstall;
    private boolean force = true;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try { info = new JSONObject(getIntent().getStringExtra("info")); } catch (Exception e) { info = new JSONObject(); }
        force = info.optBoolean("force", true);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        String appName = info.optString("appName", getString(R.string.app_name));
        String installed = "?";
        try { installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(56), dp(24), dp(32));
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        col.addView(icon, lp(dp(88), dp(88), 0, 0, 0, dp(20)));

        TextView chip = tv("⬇  UPDATE REQUIRED", 12, GREEN, true);
        chip.setLetterSpacing(0.12f);
        chip.setPadding(dp(16), dp(8), dp(16), dp(8));
        chip.setBackground(shape(Color.parseColor("#04180E"), Color.parseColor("#0B6B3B"), 20));
        col.addView(chip, lp(-2, -2, 0, 0, 0, dp(20)));

        TextView title = tv("Update Required", 30, Color.WHITE, true);
        col.addView(title, lp(-2, -2, 0, 0, 0, dp(10)));
        TextView sub = tv("A new version of " + appName + " is available.\nPlease update to continue.", 15, Color.parseColor("#8A8A8A"), false);
        sub.setGravity(Gravity.CENTER);
        col.addView(sub, lp(-2, -2, 0, 0, 0, dp(24)));

        LinearLayout ver = new LinearLayout(this);
        ver.setOrientation(LinearLayout.HORIZONTAL);
        ver.setBackground(shape(Color.parseColor("#0B0B0B"), Color.parseColor("#262626"), 22));
        ver.setPadding(dp(8), dp(20), dp(8), dp(20));
        ver.addView(verCell("Installed", installed, Color.parseColor("#8A8A8A")), new LinearLayout.LayoutParams(0, -2, 1f));
        ver.addView(verCell("Available", info.optString("versionName", "?"), GREEN), new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(ver, lp(-1, -2, 0, 0, 0, dp(16)));

        String notes = info.optString("notes", "");
        if (notes.isEmpty()) notes = "Bug fixes and performance improvements.";
        TextView nt = tv("ⓘ  " + notes, 14, Color.parseColor("#9AA39E"), false);
        nt.setLineSpacing(0, 1.25f);
        nt.setPadding(dp(18), dp(16), dp(18), dp(16));
        nt.setBackground(shape(Color.parseColor("#04120B"), Color.parseColor("#0A3B22"), 18));
        col.addView(nt, lp(-1, -2, 0, 0, 0, dp(16)));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        boolean is64 = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;
        TextView c1 = tv(is64 ? "64-bit" : "32-bit", 12, GREEN, true);
        c1.setPadding(dp(14), dp(6), dp(14), dp(6));
        c1.setBackground(shape(Color.parseColor("#04120B"), Color.parseColor("#0B6B3B"), 16));
        chips.addView(c1);
        long size = info.optLong("size", 0);
        if (size > 0) {
            TextView c2 = tv(String.format("%.1f MB", size / 1048576.0), 12, Color.parseColor("#8A8A8A"), false);
            c2.setPadding(dp(14), dp(6), dp(14), dp(6));
            c2.setBackground(shape(Color.parseColor("#111111"), Color.parseColor("#262626"), 16));
            chips.addView(c2, lp(-2, -2, dp(8), 0, 0, 0));
        }
        col.addView(chips, lp(-2, -2, 0, 0, 0, dp(24)));

        dlBtn = new Button(this);
        dlBtn.setText("⬇  Download Update");
        dlBtn.setAllCaps(false);
        dlBtn.setTextSize(17);
        dlBtn.setTypeface(Typeface.DEFAULT_BOLD);
        dlBtn.setTextColor(Color.WHITE);
        dlBtn.setBackground(shape(GREEN, 0, 18));
        dlBtn.setOnClickListener(v -> startDownload());
        col.addView(dlBtn, lp(-1, dp(58), 0, 0, 0, dp(12)));

        progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        progressCard.setVisibility(View.GONE);
        progressCard.setBackground(shape(Color.parseColor("#0B0B0B"), Color.parseColor("#262626"), 18));
        progressCard.setPadding(dp(18), dp(16), dp(18), dp(12));
        LinearLayout row = new LinearLayout(this);
        progText = tv("Starting…", 14, Color.parseColor("#9A9A9A"), false);
        progPct = tv("0%", 14, GREEN, true);
        row.addView(progText, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(progPct);
        progressCard.addView(row);
        progBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progBar.setMax(100);
        progBar.getProgressDrawable().setColorFilter(GREEN, android.graphics.PorterDuff.Mode.SRC_IN);
        progressCard.addView(progBar, lp(-1, dp(10), 0, dp(10), 0, dp(6)));
        TextView cancel = tv("Cancel", 14, Color.parseColor("#6A6A6A"), false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, dp(8), 0, dp(4));
        cancel.setOnClickListener(v -> { cancelled = true; });
        progressCard.addView(cancel, new LinearLayout.LayoutParams(-1, -2));
        col.addView(progressCard, lp(-1, -2, 0, 0, 0, dp(12)));

        if (force) {
            TextView ft = tv("You must update to continue using " + appName + ".", 13, Color.parseColor("#6A6A6A"), false);
            ft.setGravity(Gravity.CENTER);
            col.addView(ft, lp(-2, -2, 0, dp(8), 0, 0));
        } else {
            TextView later = tv("Maybe later", 15, Color.parseColor("#8A8A8A"), false);
            later.setPadding(dp(16), dp(12), dp(16), dp(12));
            later.setOnClickListener(v -> { startActivity(new Intent(this, MainActivity.class).putExtra("skipUpdate", true)); finish(); });
            col.addView(later, lp(-2, -2, 0, dp(8), 0, 0));
        }
        setContentView(sv);
    }

    private LinearLayout verCell(String label, String value, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.addView(tv(label, 12, Color.parseColor("#7A7A7A"), false));
        TextView v = tv(value, 24, color, true);
        v.setPadding(0, dp(6), 0, 0);
        c.addView(v);
        return c;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private void startDownload() {
        final String url = info.optString("apkUrl");
        if (url.isEmpty()) return;
        cancelled = false;
        dlBtn.setVisibility(View.GONE);
        progressCard.setVisibility(View.VISIBLE);
        apk = new File(getExternalFilesDir(null), "update.apk");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                long total = c.getContentLengthLong();
                long done = 0, lastUi = 0;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled) throw new Exception("cancelled");
                        out.write(buf, 0, n);
                        done += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUi > 150) {
                            lastUi = now;
                            final long d = done, t = total;
                            runOnUiThread(() -> setProgress(d, t));
                        }
                    }
                }
                runOnUiThread(() -> { setProgress(1, 1); install(); });
            } catch (Exception e) {
                final boolean was = cancelled;
                runOnUiThread(() -> {
                    progressCard.setVisibility(View.GONE);
                    dlBtn.setVisibility(View.VISIBLE);
                    if (!was) Toast.makeText(this, "Download failed. Check your connection.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void setProgress(long d, long t) {
        int pct = t > 0 ? (int) (d * 100 / t) : 0;
        progBar.setProgress(pct);
        progPct.setText(pct + "%");
        progText.setText(t > 0 ? String.format("%.1f MB / %.1f MB", d / 1048576.0, t / 1048576.0) : String.format("%.1f MB", d / 1048576.0));
    }

    private void install() {
        if (apk == null || !apk.exists()) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall = true;
            Toast.makeText(this, "Allow installing from this app, then come back", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        pendingInstall = false;
        Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        progressCard.setVisibility(View.GONE);
        dlBtn.setText("⬇  Install Update");
        dlBtn.setVisibility(View.VISIBLE);
        dlBtn.setOnClickListener(v -> install());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstall && Build.VERSION.SDK_INT >= 26 && getPackageManager().canRequestPackageInstalls()) install();
    }

    @Override
    public void onBackPressed() {
        if (force) finishAffinity(); else super.onBackPressed();
    }
}
