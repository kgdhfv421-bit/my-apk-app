package com.apkforge.shell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.pm.PackageInfoCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewAssetLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String HOST = "https://appassets.androidplatform.net";
    private static final int REQ_FILE = 71, REQ_PERM = 72;

    private JSONObject cfg, wv;
    private WebView web;
    private SwipeRefreshLayout swipe;
    private ProgressBar bar;
    private FrameLayout root;
    private View splash, customView;
    private WebChromeClient.CustomViewCallback customCb;
    private WebChromeClient chrome;
    private ValueCallback<Uri[]> fileCb;
    private WebViewAssetLoader loader;
    private long lastBack;
    private boolean contentLoaded;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = Cfg.load(this);
        wv = cfg.optJSONObject("webview");
        if (wv == null) wv = new JSONObject();
        if (!cfg.optBoolean("showActionBar")) requestWindowFeature(Window.FEATURE_NO_TITLE);
        applyWindow();
        buildUi();
        requestRuntimePermissions();
        PushService.registerToken(this);
        if (getIntent().getBooleanExtra("skipUpdate", false)) startContent();
        else checkUpdate();
    }

    /* ---------------- window ---------------- */
    private void applyWindow() {
        Window w = getWindow();
        if (!cfg.optBoolean("showStatusBar", true)) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (cfg.optBoolean("keepScreenOn")) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (cfg.optBoolean("cutout") && Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        try {
            int c = Color.parseColor(cfg.optString("barColor", "#2196F3"));
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(c);
            w.setNavigationBarColor(c);
            boolean light = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) > 170;
            if (light && Build.VERSION.SDK_INT >= 26)
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            else if (light && Build.VERSION.SDK_INT >= 23)
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Exception ignored) { }
    }

    /* ---------------- UI ---------------- */
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        col.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        swipe = new SwipeRefreshLayout(this);
        swipe.addView(web, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        swipe.setEnabled(wv.optBoolean("pullToRefresh"));
        swipe.setOnRefreshListener(() -> web.reload());
        swipe.setOnChildScrollUpCallback((p, c) -> web.getScrollY() > 0);
        col.addView(swipe, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        JSONObject ads = cfg.optJSONObject("ads");
        if (ads != null && ads.optBoolean("enabled")) setupAds(ads, col);

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // splash overlay while we check for updates
        FrameLayout sp = new FrameLayout(this);
        int bg = Color.WHITE;
        try { bg = Color.parseColor(cfg.optString("splashColor", "#FFFFFF")); } catch (Exception ignored) { }
        sp.setBackgroundColor(bg);
        ImageView iv = new ImageView(this);
        int res = getResources().getIdentifier("splash", "drawable", getPackageName());
        if (res == 0) res = getResources().getIdentifier("ic_launcher", "drawable", getPackageName());
        iv.setImageResource(res);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sp.addView(iv, new FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER));
        sp.setClickable(true);
        splash = sp;
        root.addView(sp, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        setupWebView();
    }

    private void setupAds(JSONObject ads, LinearLayout col) {
        try {
            MobileAds.initialize(this, s -> { });
            String banner = ads.optString("banner", ""), inter = ads.optString("interstitial", "");
            if (banner.isEmpty()) banner = "ca-app-pub-3940256099942544/6300978111";
            if (inter.isEmpty()) inter = "ca-app-pub-3940256099942544/1033173712";
            AdView av = new AdView(this);
            av.setAdSize(AdSize.BANNER);
            av.setAdUnitId(banner);
            col.addView(av, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            av.loadAd(new AdRequest.Builder().build());
            InterstitialAd.load(this, inter, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
                @Override public void onAdLoaded(@NonNull InterstitialAd ad) { if (!isFinishing()) ad.show(MainActivity.this); }
                @Override public void onAdFailedToLoad(@NonNull LoadAdError e) { }
            });
        } catch (Throwable ignored) { }
    }

    @SuppressWarnings({"deprecation", "SetJavaScriptEnabled"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(wv.optBoolean("javascript", true));
        s.setDomStorageEnabled(wv.optBoolean("domStorage", true));
        s.setAllowFileAccess(wv.optBoolean("fileAccess"));
        s.setAllowContentAccess(wv.optBoolean("contentAccess"));
        boolean zoom = wv.optBoolean("zoom");
        s.setSupportZoom(zoom);
        s.setBuiltInZoomControls(zoom);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(wv.optBoolean("wideViewport", true));
        s.setLoadWithOverviewMode(wv.optBoolean("overview"));
        s.setGeolocationEnabled(wv.optBoolean("geolocation"));
        s.setMediaPlaybackRequiresUserGesture(!wv.optBoolean("mediaAutoplay", true));
        String cache = wv.optString("cacheMode", "default");
        s.setCacheMode("none".equals(cache) ? WebSettings.LOAD_NO_CACHE : "cache".equals(cache) ? WebSettings.LOAD_CACHE_ELSE_NETWORK : WebSettings.LOAD_DEFAULT);
        String mixed = wv.optString("mixedContent", "compat");
        s.setMixedContentMode("always".equals(mixed) ? WebSettings.MIXED_CONTENT_ALWAYS_ALLOW : "never".equals(mixed) ? WebSettings.MIXED_CONTENT_NEVER_ALLOW : WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (cfg.optBoolean("desktopMode"))
            s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        String theme = cfg.optString("theme", "auto");
        boolean sysNight = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean dark = "night".equals(theme) || ("auto".equals(theme) && sysNight);
        if (dark) {
            if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(true);
            else if (Build.VERSION.SDK_INT >= 29) s.setForceDark(WebSettings.FORCE_DARK_ON);
        }
        if (!wv.optBoolean("longPressMenu", true)) { web.setLongClickable(false); web.setOnLongClickListener(v -> true); }
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "AndroidDownloader");

        loader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return loader.shouldInterceptRequest(r.getUrl()); }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme();
                if (sc.equals("http") || sc.equals("https") || sc.equals("file") || sc.equals("about")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { Toast.makeText(MainActivity.this, "No app found to open this link", Toast.LENGTH_SHORT).show(); }
                return true;
            }

            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                if (wv.optBoolean("loadingIndicator", true)) { bar.setProgress(5); bar.setVisibility(View.VISIBLE); }
            }

            @Override public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                swipe.setRefreshing(false);
                String css = cfg.optString("customCss", ""), js = cfg.optString("customJs", "");
                if (!css.isEmpty()) v.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=" + JSONObject.quote(css) + ";document.head.appendChild(s);})()", null);
                if (!js.isEmpty()) v.evaluateJavascript(js, null);
            }

            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && wv.optBoolean("noInternetDialog", true) && !isFinishing()) {
                    new AlertDialog.Builder(MainActivity.this).setTitle("No internet connection")
                            .setMessage("Please check your connection and try again.").setCancelable(false)
                            .setPositiveButton("Retry", (d, i) -> v.reload()).setNegativeButton("Close", (d, i) -> finish()).show();
                }
            }
        });

        chrome = new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) { bar.setProgress(p); }

            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }

            @Override public void onPermissionRequest(final PermissionRequest r) { runOnUiThread(() -> r.grant(r.getResources())); }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) { cb.invoke(origin, wv.optBoolean("geolocation"), false); }

            @Override public void onShowCustomView(View v, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = v; customCb = cb;
                root.addView(v, new FrameLayout.LayoutParams(-1, -1));
                v.setBackgroundColor(Color.BLACK);
            }

            @Override public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCb != null) customCb.onCustomViewHidden();
            }
        };
        web.setWebChromeClient(chrome);

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mime);
                r.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url));
                r.addRequestHeader("User-Agent", ua);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show(); }
        });
    }

    /* ---------------- update check ---------------- */
    private void checkUpdate() {
        final String api = cfg.optString("api");
        final String pkg = getPackageName();
        new Thread(() -> {
            JSONObject r = null;
            boolean need = false;
            try {
                r = new JSONObject(Cfg.http(api + "/api/app/" + pkg + "/latest", "GET", null, 4000));
                long installed = PackageInfoCompat.getLongVersionCode(getPackageManager().getPackageInfo(pkg, 0));
                need = r.optBoolean("found") && r.optLong("versionCode") > installed;
            } catch (Exception ignored) { }
            final boolean fneed = need;
            final JSONObject fr = r;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (fneed) {
                    startActivity(new Intent(this, UpdateActivity.class).putExtra("info", fr.toString()));
                    finish();
                } else startContent();
            });
        }).start();
    }

    private void startContent() {
        if (contentLoaded) return;
        contentLoaded = true;
        splash.animate().alpha(0f).setDuration(250).withEndAction(() -> splash.setVisibility(View.GONE)).start();
        if ("url".equals(cfg.optString("sourceType"))) web.loadUrl(cfg.optString("url"));
        else web.loadUrl(HOST + "/assets/www/index.html");
    }

    /* ---------------- permissions ---------------- */
    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        JSONArray arr = cfg.optJSONArray("permissions");
        if (arr == null) return;
        List<String> need = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String p = arr.optString(i);
            if (p.equals(Manifest.permission.CAMERA) || p.equals(Manifest.permission.RECORD_AUDIO) || p.equals(Manifest.permission.ACCESS_FINE_LOCATION)
                    || p.equals(Manifest.permission.ACCESS_COARSE_LOCATION) || p.equals(Manifest.permission.READ_PHONE_STATE) || p.equals("android.permission.ACTIVITY_RECOGNITION")
                    || (Build.VERSION.SDK_INT >= 33 && (p.equals("android.permission.POST_NOTIFICATIONS") || p.startsWith("android.permission.READ_MEDIA_")))
                    || (Build.VERSION.SDK_INT < 33 && (p.equals(Manifest.permission.READ_EXTERNAL_STORAGE) || p.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE))))
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    /* ---------------- misc ---------------- */
    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) { fileCb.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null; }
        else super.onActivityResult(req, res, data);
    }

    @Override public void onBackPressed() {
        if (customView != null) { chrome.onHideCustomView(); return; }
        if (wv.optBoolean("backNavigation", true) && web.canGoBack()) { web.goBack(); return; }
        if (cfg.optBoolean("exitConfirm") || wv.optBoolean("exitConfirm")) {
            long now = System.currentTimeMillis();
            if (now - lastBack > 2000) { lastBack = now; Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show(); return; }
        }
        super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); }
    @Override protected void onDestroy() { if (web != null) { root.removeAllViews(); web.destroy(); } super.onDestroy(); }

    /** JavaScript API: AndroidDownloader.shareApp(), rateApp(), toast(msg), vibrate(ms), download(url) */
    private class Bridge {
        @JavascriptInterface public void shareApp() {
            String link = cfg.optString("shareLink");
            if (link.isEmpty()) link = "https://play.google.com/store/apps/details?id=" + getPackageName();
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, cfg.optString("shareMessage", "Check out this app!") + " " + link);
            startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        @JavascriptInterface public void rateApp() {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        }
        @JavascriptInterface public void toast(final String m) { runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show()); }
        @JavascriptInterface public void vibrate(long ms) { try { ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(Math.min(ms, 2000)); } catch (Exception ignored) { } }
        @JavascriptInterface public void download(final String url) { runOnUiThread(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))); }
    }
}
