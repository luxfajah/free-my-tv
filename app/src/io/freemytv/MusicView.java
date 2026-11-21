package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
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
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integrated Apple Music TV screen (ported from megarohas/apple-music-android-tv) inside FreeMyTV.
 * Slides in from the right of HomeView, defaults to Brazilian Portuguese (pt-BR), uses official
 * Apple Music web player + Widevine DRM + official Apple ID login popup (idmsa.apple.com).
 */
final class MusicView extends FrameLayout {
    static final String TAG = "amtv";
    private static final String HOME_DEFAULT = "https://music.apple.com/br/search";
    private static final String DEFAULT_USER_TOKEN = "";
    private static final Pattern STOREFRONT = Pattern.compile("[a-z]{2}");
    private static final LayoutParams FILL = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

    private static final String STATUS_STARTING = "Iniciando…";
    private static final String STATUS_OPENING = "Abrindo o Apple Music…";
    private static final String STATUS_INTERFACE = "Carregando a interface…";
    private static final String STATUS_REGION = "Abrindo o Apple Music do seu país…";
    private static final String STATUS_PLAY = "Iniciando a música…";
    private static final String ERROR_OPEN = "Não foi possível abrir %1$s";
    private static final String ERROR_RETRY = "Pressione OK para tentar novamente";

    private final Activity activity;
    private final Runnable onExitToHome;

    private boolean initialized;
    private FrameLayout pages;
    private WebView web;
    private final ArrayList<WebView> popups = new ArrayList<>();
    private MusicCursor cursor;
    private String pageJs;
    private String signinJs;
    private boolean siOkDown;
    private boolean tv;
    private boolean okDown, okHeld;
    private boolean pageReady;
    private View splash;
    private TextView splashStatus;
    private final Runnable splashTimeout = this::hideSplash;
    private int splashGen;
    private volatile boolean screenReader;
    private String storefrontReload;
    private String pendingPlay;
    private WidevineAudioPlayer wvPlayer;
    private volatile boolean mediaPlaying;
    private final Runnable idleReleaseTask = this::releaseIfIdle;

    private final ArrayList<Animator> splashAnims = new ArrayList<>();
    private final ArrayDeque<String> statusQueue = new ArrayDeque<>();
    private long statusSince;
    private boolean statusFading;
    private final Runnable statusPump = this::pumpStatus;

    MusicView(Activity activity, Runnable onExitToHome) {
        super(activity);
        this.activity = activity;
        this.onExitToHome = onExitToHome;
        setBackgroundColor(Color.BLACK);
    }

    /** Initializes and loads the Apple Music WebView on first open (or pre-warm). */
    void ensureLoaded() {
        if (initialized) return;
        initialized = true;
        try {
            WebView.setWebContentsDebuggingEnabled(true);
        } catch (Throwable ignored) {}
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        android.content.SharedPreferences prefs = activity.getSharedPreferences("amtv", Context.MODE_PRIVATE);
        String savedToken = prefs.getString("media_user_token", DEFAULT_USER_TOKEN);
        if (savedToken != null && !savedToken.trim().isEmpty()) {
            String tok = savedToken.trim();
            try {
                cm.setCookie("https://music.apple.com/", "media-user-token=" + tok + "; Domain=.apple.com; Path=/; Secure; Max-Age=31536000");
                cm.setCookie("https://music.apple.com/", "media-user-token=" + tok + "; Domain=.music.apple.com; Path=/; Secure; Max-Age=31536000");
                cm.setCookie("https://music.apple.com/", "media-user-token=" + tok + "; Domain=music.apple.com; Path=/; Secure; Max-Age=31536000");
                cm.flush();
            } catch (Throwable ignored) {}
        }
        checkScreenReader();

        // Ensure default language is Portuguese ('pt')
        if (prefs.getString("lang", "").isEmpty()) {
            prefs.edit().putString("lang", "pt").apply();
        }

        pages = new FrameLayout(activity);
        pages.setBackgroundColor(Color.BLACK);
        web = newPage();
        pages.addView(web, FILL);
        web.addJavascriptInterface(new Bridge(), "AmtvNative");
        addView(pages, FILL);

        cursor = new MusicCursor(activity, this::top, () -> web.evaluateJavascript("window.__amtv&&__amtv.wake(2500)", null));
        cursor.setVisibility(View.INVISIBLE);
        addView(cursor, FILL);

        tv = true;

        signinJs = raw("amtv_signin");
        pageJs = "window.__amtvVersion='1.3';\n"
                + "window.__amtvLang='pt';\n"
                + "try{if(!localStorage.getItem('amtv.lang'))localStorage.setItem('amtv.lang','pt');}catch(e){}\n"
                + "if(!window.__amtvLoaded){window.__amtvLoaded=true;\n"
                + raw("amtv_page") + "\n"
                + raw("amtv_brand") + "\n"
                + raw("amtv_tv_i18n") + "\n"
                + raw("amtv_tv_core") + "\n"
                + raw("amtv_tv_screens") + "\n"
                + raw("amtv_tv_player") + "\n"
                + "window.__tv&&__tv.boot();\n}";
        web.loadUrl(home());
    }

    boolean isInitialized() {
        return initialized;
    }

    private View makeSplash() {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(activity);
        f.setBackgroundColor(Color.BLACK);

        GradientDrawable glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setColors(new int[] { 0x66FA233B, 0x22FB5C74, 0x00000000 });
        glowBg.setGradientRadius(260 * dp);
        View glow = new View(activity);
        glow.setBackground(glowBg);
        f.addView(glow, new LayoutParams((int) (520 * dp), (int) (520 * dp), Gravity.CENTER));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        f.addView(col, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        f.setClipChildren(false);
        col.setClipChildren(false);
        col.setClipToPadding(false);

        FrameLayout tile = new FrameLayout(activity);
        GradientDrawable tileBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { 0xFFFB5C74, 0xFFFA233B });
        tileBg.setCornerRadius(30 * dp);
        tile.setBackground(tileBg);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 30 * dp);
            }
        });
        tile.setClipToOutline(true);

        ImageView icon = new ImageView(activity);
        try (InputStream in = activity.getAssets().open("logos/applemusic.png")) {
            Bitmap bm = BitmapFactory.decodeStream(in);
            if (bm != null) {
                icon.setImageBitmap(bm);
                icon.setColorFilter(Color.WHITE);
            }
        } catch (IOException ignored) {}
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LayoutParams iconLp = new LayoutParams((int) (86 * dp), (int) (54 * dp), Gravity.CENTER);
        tile.addView(icon, iconLp);
        col.addView(tile, new LinearLayout.LayoutParams((int) (128 * dp), (int) (128 * dp)));

        TextView title = new TextView(activity);
        title.setText("Apple Music");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = (int) (20 * dp);
        col.addView(title, tp);

        LinearLayout dots = new LinearLayout(activity);
        LinearLayout.LayoutParams dp0 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dp0.topMargin = (int) (28 * dp);
        col.addView(dots, dp0);
        for (int i = 0; i < 3; i++) {
            GradientDrawable c = new GradientDrawable();
            c.setShape(GradientDrawable.OVAL);
            c.setColor(i == 1 ? 0xFFFB5C74 : 0xFFFA233B);
            View d = new View(activity);
            d.setBackground(c);
            d.setAlpha(0.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (9 * dp), (int) (9 * dp));
            lp.leftMargin = lp.rightMargin = (int) (6 * dp);
            dots.addView(d, lp);
            loop(ObjectAnimator.ofFloat(d, View.ALPHA, 0.25f, 1f), 520, i * 170L);
        }
        splashStatus = new TextView(activity);
        splashStatus.setTextColor(0xFF8A8A8E);
        splashStatus.setTextSize(14);
        splashStatus.setGravity(Gravity.CENTER);
        splashStatus.setAlpha(0f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) (420 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = (int) (18 * dp);
        col.addView(splashStatus, sp);

        col.setAlpha(0f);
        col.setScaleX(0.9f);
        col.setScaleY(0.9f);
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(550)
                .setInterpolator(new DecelerateInterpolator()).start();
        loop(ObjectAnimator.ofPropertyValuesHolder(glow,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.08f)), 1600, 0);
        loop(ObjectAnimator.ofPropertyValuesHolder(tile,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f)), 1600, 0);
        return f;
    }

    private void loop(ObjectAnimator a, long ms, long delay) {
        a.setDuration(ms);
        a.setStartDelay(delay);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.start();
        splashAnims.add(a);
    }

    private void showSplash(String status) {
        splashGen++;
        pages.removeCallbacks(splashTimeout);
        pages.postDelayed(splashTimeout, 30000);
        if (splash != null) { setStatus(status); return; }
        splash = makeSplash();
        addView(splash, FILL);
        setStatus(status);
    }

    private void setStatus(String text) {
        if (splashStatus == null || text == null) return;
        String last = statusQueue.isEmpty() ? splashStatus.getText().toString() : statusQueue.peekLast();
        if (text.equals(last)) return;
        while (statusQueue.size() >= 3) statusQueue.pollFirst();
        statusQueue.add(text);
        pumpStatus();
    }

    private void pumpStatus() {
        TextView t = splashStatus;
        if (t == null || statusFading || statusQueue.isEmpty()) return;
        long wait = statusSince + 1000 - SystemClock.uptimeMillis();
        if (t.getText().length() > 0 && wait > 0) {
            pages.removeCallbacks(statusPump);
            pages.postDelayed(statusPump, wait);
            return;
        }
        String next = statusQueue.poll();
        statusFading = true;
        t.animate().cancel();
        t.animate().alpha(0f).setDuration(t.getText().length() > 0 ? 180 : 0).withEndAction(() -> {
            t.setText(next);
            statusSince = SystemClock.uptimeMillis();
            t.animate().alpha(1f).setDuration(280).withEndAction(() -> { statusFading = false; pumpStatus(); });
        });
    }

    private void hideSplash() {
        if (splash == null) return;
        long wait = splashStatus != null && splashStatus.getText().length() > 0
                ? statusSince + 1000 - SystemClock.uptimeMillis() : 0;
        if (statusFading) wait = Math.max(wait, 300);
        if (wait > 0) {
            statusQueue.clear();
            pages.removeCallbacks(splashTimeout);
            pages.postDelayed(this::hideSplash, wait);
            return;
        }
        View v = splash;
        splash = null;
        splashStatus = null;
        statusQueue.clear();
        statusFading = false;
        pages.removeCallbacks(statusPump);
        pages.removeCallbacks(splashTimeout);
        if (!tv) { cursor.setVisibility(View.VISIBLE); cursor.show(); }
        v.animate().alpha(0).setDuration(300).withEndAction(() -> {
            for (Animator a : splashAnims) a.cancel();
            splashAnims.clear();
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        });
        Log.i(TAG, "splash off");
    }

    private String raw(String name) {
        int id = getResources().getIdentifier(name, "raw", activity.getPackageName());
        if (id == 0) return "";
        try (InputStream in = getResources().openRawResource(id)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private WebView top() {
        return popups.isEmpty() ? web : popups.get(popups.size() - 1);
    }

    private String home() {
        String sf = activity.getSharedPreferences("amtv", Context.MODE_PRIVATE).getString("storefront", "br");
        return STOREFRONT.matcher(sf).matches() ? "https://music.apple.com/" + sf + "/search" : HOME_DEFAULT;
    }

    void checkScreenReader() {
        AccessibilityManager am = (AccessibilityManager) activity.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return;
        screenReader = am.isTouchExplorationEnabled() || !am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_SPOKEN | AccessibilityServiceInfo.FEEDBACK_BRAILLE).isEmpty();
    }

    private WebView newPage() {
        WebView v = new WebView(activity) {
            @Override
            public AccessibilityNodeProvider getAccessibilityNodeProvider() {
                return screenReader ? super.getAccessibilityNodeProvider() : null;
            }
        };
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(desktop(s.getUserAgentString()));
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true);
        v.setBackgroundColor(Color.BLACK);
        v.setWebViewClient(client);
        v.setWebChromeClient(chrome);
        return v;
    }

    private static String desktop(String ua) {
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(ua);
        String major = m.find() ? m.group(1) : "140";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major + ".0.0.0 Safari/537.36";
    }

    private static boolean apple(Uri u) {
        String h = u == null ? null : u.getHost();
        return h != null && (h.equals("apple.com") || h.endsWith(".apple.com"));
    }

    private static boolean useless(Uri u) {
        String host = u.getHost(), path = u.getPath();
        if (host == null || path == null) return false;
        if (host.equals("mvod.itunes.apple.com") && path.startsWith("/itunes-assets/")) return true;
        if (host.endsWith("mzstatic.com") && path.endsWith(".webp")) return true;
        return host.equals("xp.apple.com");
    }

    private WebResourceResponse empty() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(204, "No Content");
        return r;
    }

    private WebResourceResponse asset(String path) {
        try {
            String name = path.substring("/amtv/".length());
            if (!name.matches("[A-Za-z0-9._-]+")) return null;
            String type = name.endsWith(".woff2") ? "font/woff2" : name.endsWith(".ttf") ? "font/ttf" : "application/octet-stream";
            WebResourceResponse r = new WebResourceResponse(type, null, activity.getAssets().open("fonts/" + name));
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "max-age=31536000");
            h.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(h);
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    private void flushPlay() {
        if (pendingPlay == null || !pageReady) return;
        web.evaluateJavascript("window.__amtvPlay='" + pendingPlay + "';window.__tv&&__tv.flushPlay()", null);
    }

    /**
     * Apple's sign-in widget (appleid.cdn-apple.com/appleauth/static/module-assets/*.js) uses ES2021 `??=new Map`
     * (logical nullish assignment, Chrome 85+), which throws `SyntaxError: Unexpected token ?` on Android TV
     * WebViews based on Chrome 66–84 and leaves the login popup white. Rewriting `x??=new Map` to `(x||(x=new Map))`
     * at the resource stream layer lets Apple's official login widget execute unmodified in older WebViews.
     */
    private WebResourceResponse patchAppleAuthJs(String urlStr) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36");
            int code = conn.getResponseCode();
            if (code != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            String jsText = out.toString("UTF-8");
            if (jsText.contains("??=")) {
                jsText = jsText.replaceAll("([A-Za-z0-9_$]+(?:\\[[A-Za-z0-9_$]+\\]|\\.[A-Za-z0-9_$]+)*)\\?\\?=new Map", "($1||($1=new Map))");
                Log.i(TAG, "patched ??= in " + urlStr);
            }
            WebResourceResponse r = new WebResourceResponse("application/javascript", "UTF-8",
                    new ByteArrayInputStream(jsText.getBytes("UTF-8")));
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cache-Control", "max-age=3600");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            Log.w(TAG, "patchAppleAuthJs failed: " + e);
            return null;
        }
    }

    private final WebViewClient client = new WebViewClient() {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if ("music.apple.com".equals(u.getHost()) && u.getPath() != null && u.getPath().startsWith("/amtv/")) return asset(u.getPath());
            if ("appleid.cdn-apple.com".equals(u.getHost()) && u.getPath() != null && u.getPath().contains("/module-assets/") && u.getPath().endsWith(".js")) {
                WebResourceResponse patched = patchAppleAuthJs(u.toString());
                if (patched != null) return patched;
            }
            if (useless(u)) return empty();
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            if (r.isForMainFrame()) {
                Log.i(TAG, "navigate " + r.getUrl() + " redirect=" + r.isRedirect() + " gesture=" + r.hasGesture());
                if (!pageReady) setStatus(STATUS_OPENING);
            }
            String scheme = r.getUrl().getScheme();
            if ("https".equals(scheme) || "http".equals(scheme)) return false;
            Log.i(TAG, "not opening " + r.getUrl());
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap favicon) {
            if (v == web && apple(Uri.parse(url))) {
                v.evaluateJavascript("try{var s=document.createElement('style');s.textContent='html,body{background:#070709!important;overflow:hidden!important}.body-container{display:none!important}';(document.head||document.documentElement).appendChild(s);}catch(e){}", null);
            }
        }

        @Override
        public void onPageCommitVisible(WebView v, String url) {
            if (v == web && apple(Uri.parse(url))) {
                v.evaluateJavascript(pageJs, null);
            }
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            if (v != web) {
                if (signinPage(url) && !signinJs.isEmpty()) {
                    String lang = activity.getSharedPreferences("amtv", Context.MODE_PRIVATE).getString("lang", "pt");
                    String qrUrl = getMusicLoginUrl();
                    String qrSvg = buildQrSvg(qrUrl);
                    v.evaluateJavascript("window.__amtvLang=" + js(lang.isEmpty() ? "pt" : lang)
                            + ";window.__amtvQrUrl=" + js(qrUrl)
                            + ";window.__amtvQrSvg=" + js(qrSvg)
                            + ";\n" + signinJs, null);
                    cursor.setVisibility(View.INVISIBLE);
                }
                return;
            }
            if (!apple(Uri.parse(url))) return;
            Log.i(TAG, "page finished " + url);
            v.evaluateJavascript(pageJs, null);
            pageReady = true;
            hideSplash();
            flushPlay();
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame()) return;
            Log.w(TAG, "load failed: " + e.getDescription() + " " + r.getUrl());
            String url = r.getUrl().toString();
            String host = r.getUrl().getHost() != null ? r.getUrl().getHost() : "music.apple.com";
            String html = "<html><body style='background:#000;color:#ddd;font:22px sans-serif;"
                    + "display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0'"
                    + " onclick=\"location.replace(" + TextUtils.htmlEncode(js(url)) + ")\">"
                    + "<p>" + TextUtils.htmlEncode(String.format(ERROR_OPEN, host)) + "</p>"
                    + "<p style='color:#888;font-size:16px'>" + TextUtils.htmlEncode(String.valueOf(e.getDescription())) + "</p>"
                    + "<p>" + TextUtils.htmlEncode(ERROR_RETRY) + "</p></body></html>";
            v.loadDataWithBaseURL(null, html, "text/html", "utf-8", url);
        }

        @Override
        public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
            Log.w(TAG, "renderer gone, crashed=" + d.didCrash());
            if (v == web) {
                pageReady = false;
                for (WebView p : popups) { pages.removeView(p); p.destroy(); }
                popups.clear();
                pages.removeView(web);
                web.destroy();
                web = newPage();
                web.addJavascriptInterface(new Bridge(), "AmtvNative");
                pages.addView(web, 0, FILL);
                web.loadUrl(home());
            } else {
                close(v);
            }
            return true;
        }
    };

    private final WebChromeClient chrome = new WebChromeClient() {
        @Override
        public void onPermissionRequest(PermissionRequest r) {
            // Widevine for official Apple Music DRM playback
            for (String res : r.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res) && apple(r.getOrigin())) {
                    r.grant(new String[] { res });
                    return;
                }
            }
            Log.i(TAG, "denied " + TextUtils.join(",", r.getResources()) + " for " + r.getOrigin());
            r.deny();
        }

        @Override
        public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message result) {
            WebView p = newPage();
            popups.add(p);
            pages.addView(p, FILL);
            ((WebView.WebViewTransport) result.obj).setWebView(p);
            result.sendToTarget();
            Log.i(TAG, "popup opened");
            return true;
        }

        @Override
        public void onCloseWindow(WebView w) {
            close(w);
        }
    };

    private final class Bridge {
        @JavascriptInterface
        public void mode(String m) {
            activity.runOnUiThread(() -> setTv("tv".equals(m)));
        }

        @JavascriptInterface
        public void keyboard() {
            activity.runOnUiThread(() -> {
                WebView target = top();
                if (target != null) {
                    target.requestFocus();
                    InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(target, 0);
                }
            });
        }

        @JavascriptInterface
        public void ready() {
            activity.runOnUiThread(() -> {
                if (!pageReady) return;
                int gen = splashGen;
                web.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                    @Override
                    public void onComplete(long id) {
                        if (gen == splashGen) hideSplash();
                    }
                });
            });
        }

        @JavascriptInterface
        public void status(String text) {
            if (text == null) return;
            String t = text.length() > 80 ? text.substring(0, 80) + "…" : text;
            activity.runOnUiThread(() -> setStatus(t));
        }

        @JavascriptInterface
        public void played() {
            activity.runOnUiThread(() -> pendingPlay = null);
        }

        @JavascriptInterface
        public void playing(boolean on) {
            mediaPlaying = on;
            activity.runOnUiThread(() -> {
                if (on) {
                    cancelIdleRelease();
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (getVisibility() != VISIBLE) scheduleIdleRelease();
                }
                Log.i(TAG, on ? "keep screen on" : "screen may sleep");
            });
        }

        @JavascriptInterface
        public void lang(String l) {
            if (l != null && l.matches("|[a-z]{2}")) {
                activity.getSharedPreferences("amtv", Context.MODE_PRIVATE).edit().putString("lang", l.isEmpty() ? "pt" : l).apply();
            }
        }

        @JavascriptInterface
        public void storefront(String account, String page) {
            if (account == null || !STOREFRONT.matcher(account).matches()) return;
            activity.getSharedPreferences("amtv", Context.MODE_PRIVATE).edit().putString("storefront", account).apply();
            if (account.equals(page)) return;
            activity.runOnUiThread(() -> {
                if (account.equals(storefrontReload)) return;
                storefrontReload = account;
                Log.i(TAG, "storefront " + page + " -> " + account + ": reopening the site");
                pageReady = false;
                web.loadUrl(home());
            });
        }

        @JavascriptInterface
        public void saveProfileAvatar(String url) {
            if (url != null && !url.trim().isEmpty()) {
                ProjectorHardware.downloadProfileAvatar(activity, url);
            }
        }

        @JavascriptInterface
        public void reload() {
            activity.runOnUiThread(() -> {
                pageReady = false;
                web.reload();
            });
        }

        @JavascriptInterface
        public void exit() {
            activity.runOnUiThread(onExitToHome);
        }

        private WidevineAudioPlayer ensureWv() {
            if (wvPlayer == null) {
                wvPlayer = new WidevineAudioPlayer((ev, pos, dur, detail) -> activity.runOnUiThread(() -> {
                    if (web != null) {
                        web.evaluateJavascript("window.__amtvWvEvent&&__amtvWvEvent("
                                + js(ev) + "," + pos + "," + dur + "," + (detail != null ? js(detail) : "null") + ")", null);
                    }
                }));
            }
            return wvPlayer;
        }

        @JavascriptInterface
        public void wvPlay(String m3u8Url, String licenseUrl, String devToken, String userToken,
                           String songId, boolean isLibrary, double durSec) {
            ensureWv().play(m3u8Url, licenseUrl, devToken, userToken, songId, isLibrary, durSec);
        }

        @JavascriptInterface
        public void wvPause() { if (wvPlayer != null) wvPlayer.pause(); }

        @JavascriptInterface
        public void wvResume() { if (wvPlayer != null) wvPlayer.resume(); }

        @JavascriptInterface
        public void wvStop() { if (wvPlayer != null) wvPlayer.stop(); }

        @JavascriptInterface
        public void wvSeek(double sec) { if (wvPlayer != null) wvPlayer.seekTo(sec); }

        @JavascriptInterface
        public void wvVolume(float vol) { ensureWv().setVolume(vol); }

        @JavascriptInterface
        public double wvPos() { return wvPlayer != null ? wvPlayer.getPositionSec() : 0; }

        @JavascriptInterface
        public double wvDur() { return wvPlayer != null ? wvPlayer.getDurationSec() : 0; }

        @JavascriptInterface
        public int wvState() { return wvPlayer != null ? wvPlayer.getState() : 0; }
    }

    private void setTv(boolean on) {
        tv = on;
        cursor.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        if (!on) cursor.show();
        Log.i(TAG, on ? "tv mode" : "site mode");
    }

    private void tvKey(String name) {
        web.evaluateJavascript("window.__tv&&__tv.key('" + name + "')", null);
    }

    private boolean tvDispatch(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        String dir = k == KeyEvent.KEYCODE_DPAD_UP ? "up" : k == KeyEvent.KEYCODE_DPAD_DOWN ? "down"
                : k == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : k == KeyEvent.KEYCODE_DPAD_RIGHT ? "right" : null;
        if (dir != null) {
            if (down) tvKey(dir);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (down && e.getRepeatCount() == 0) { okDown = true; okHeld = false; }
            else if (down && e.getRepeatCount() == 1 && okDown) { okHeld = true; tvKey("hold"); }
            else if (!down && okDown) { okDown = false; if (!okHeld) tvKey("ok"); }
            return true;
        }
        if (k == KeyEvent.KEYCODE_MENU) {
            if (down && e.getRepeatCount() == 0) tvKey("hold");
            return true;
        }
        return false;
    }

    private boolean signinOnTop() {
        if (popups.isEmpty() || signinJs == null || signinJs.isEmpty()) return false;
        return signinPage(top().getUrl());
    }

    private static boolean signinPage(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        return "idmsa.apple.com".equals(h) || "authorize.music.apple.com".equals(h);
    }

    private boolean signinDispatch(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        WebView p = top();
        String dir = k == KeyEvent.KEYCODE_DPAD_UP ? "up" : k == KeyEvent.KEYCODE_DPAD_DOWN ? "down"
                : k == KeyEvent.KEYCODE_DPAD_LEFT ? "left" : k == KeyEvent.KEYCODE_DPAD_RIGHT ? "right" : null;
        if (dir != null) {
            if (down) p.evaluateJavascript("window.__amtvSignin&&__amtvSignin.key('" + dir + "')", null);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && e.getRepeatCount() == 0) siOkDown = true;
            else if (!down && siOkDown) {
                siOkDown = false;
                p.evaluateJavascript("window.__amtvSignin?__amtvSignin.key('ok'):''", r -> {
                    Matcher m = Pattern.compile("tap:(\\d+),(\\d+)").matcher(r == null ? "" : r);
                    if (m.find()) tapAt(p, Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2)));
                });
            }
            return true;
        }
        return false;
    }

    private void tapAt(WebView v, float cssX, float cssY) {
        float d = getResources().getDisplayMetrics().density;
        float x = cssX * d, y = cssY * d;
        long t = SystemClock.uptimeMillis();
        v.requestFocus();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(down);
        down.recycle();
        v.postDelayed(() -> {
            MotionEvent up = MotionEvent.obtain(t, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            v.dispatchTouchEvent(up);
            up.recycle();
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(v, 0);
        }, 40);
    }

    private void close(WebView p) {
        if (!popups.remove(p)) return;
        pages.removeView(p);
        p.destroy();
        if (!tv && popups.isEmpty()) cursor.setVisibility(View.VISIBLE);
        Log.i(TAG, "popup closed");
        if (popups.isEmpty()) keepSession();
    }

    private static final String[] SESSION = { "media-user-token", "itre", "itspod", "itua", "pldfltcid", "pltvcid" };
    private static final String SIGNIN_HOST = "https://authorize.music.apple.com/", SITE = "https://music.apple.com/";

    private void keepSession() {
        CookieManager cm = CookieManager.getInstance();
        String from = cm.getCookie(SIGNIN_HOST), site = cm.getCookie(SITE);
        if (cookie(from, SESSION[0]) == null || cookie(site, SESSION[0]) != null) return;
        ArrayList<String> copied = new ArrayList<>();
        for (String name : SESSION) {
            String v = cookie(from, name);
            if (v == null || cookie(site, name) != null) continue;
            cm.setCookie(SITE, name + "=" + v + "; Domain=.apple.com; Path=/; Secure; Max-Age=15552000");
            copied.add(name);
        }
        cm.flush();
        Log.i(TAG, "session cookies copied to apple.com " + copied + ": reopening the site");
        pageReady = false;
        web.evaluateJavascript("window.__amtv&&__amtv.diag({ev:'session-cookies-copied',names:"
                + js(TextUtils.join(",", copied)) + "})", r -> web.loadUrl(home()));
    }

    private static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                String v = part.substring(eq + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** Handles remote key events while the Music screen is open. */
    boolean dispatchMusicKey(KeyEvent e) {
        if (!initialized) return false;
        int k = e.getKeyCode();
        if (splash != null && k != KeyEvent.KEYCODE_BACK) return true;
        if (signinOnTop() && signinDispatch(e)) return true;
        if (tv && tvDispatch(e)) return true;
        if (!tv && MusicCursor.handles(k)) return cursor.onKey(e);
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back();
            return true;
        }
        if (dispatchMediaKey(e)) return true;
        return top().dispatchKeyEvent(e);
    }

    /** Media keys (Play/Pause/Next/Prev) work even when the user scrolled back to the Home screen. */
    boolean dispatchMediaKey(KeyEvent e) {
        if (!initialized || web == null) return false;
        String js = media(e.getKeyCode());
        if (js != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                web.evaluateJavascript("(function(){try{var m=MusicKit.getInstance();" + js
                        + "}catch(e){console.log('amtv media: '+e)}})()", null);
            }
            return true;
        }
        return false;
    }

    private static String media(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_HEADSETHOOK:
                return "m.isPlaying?m.pause():m.play()";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "m.play()";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_MEDIA_STOP: return "m.pause()";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "m.skipToNextItem()";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "m.skipToPreviousItem()";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "m.seekToTime(m.currentPlaybackTime+15)";
            case KeyEvent.KEYCODE_MEDIA_REWIND: return "m.seekToTime(Math.max(0,m.currentPlaybackTime-15))";
        }
        return null;
    }

    private void back() {
        if (splash != null) {
            onExitToHome.run();
            return;
        }
        if (!popups.isEmpty()) {
            WebView p = top();
            if (p.canGoBack()) p.goBack();
            else close(p);
            return;
        }
        if (tv) {
            web.evaluateJavascript("window.__tv?__tv.key('back'):false", r -> {
                if (!"true".equals(r)) onExitToHome.run();
            });
            return;
        }
        cursor.show();
        web.evaluateJavascript("window.__tv?(__tv.show(),true):false", r -> {
            if ("true".equals(r)) return;
            if (web.canGoBack()) web.goBack();
            else onExitToHome.run();
        });
    }

    void flushCookies() {
        if (initialized) {
            try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
        }
    }

    static boolean isLowRamDevice(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                // Check physical RAM directly (<= 1.5 GB), since some 2 GB TV boxes still set ro.config.low_ram=true
                return mi.totalMem > 0 && mi.totalMem <= 1536L * 1024L * 1024L;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    void cancelIdleRelease() {
        removeCallbacks(idleReleaseTask);
    }

    void scheduleIdleRelease() {
        removeCallbacks(idleReleaseTask);
        if (!initialized || !isLowRamDevice(activity)) return;
        postDelayed(idleReleaseTask, 5000);
    }

    boolean isMusicPlaying() {
        return mediaPlaying || (wvPlayer != null && (wvPlayer.getState() == 1 || wvPlayer.getState() == 2));
    }

    private void releaseIfIdle() {
        if (!initialized || getVisibility() == VISIBLE || isMusicPlaying()) return;
        Log.i(TAG, "releasing idle Apple Music WebView to free RAM");
        flushCookies();
        if (wvPlayer != null) { wvPlayer.stop(); wvPlayer = null; }
        for (Animator a : splashAnims) a.cancel();
        splashAnims.clear();
        for (WebView p : popups) {
            if (pages != null) pages.removeView(p);
            p.destroy();
        }
        popups.clear();
        if (web != null) {
            if (pages != null) pages.removeView(web);
            web.stopLoading();
            web.destroy();
            web = null;
        }
        removeAllViews();
        pages = null;
        cursor = null;
        splash = null;
        splashStatus = null;
        statusQueue.clear();
        pageReady = false;
        initialized = false;
    }

    void destroyMusic() {
        cancelIdleRelease();
        if (wvPlayer != null) { wvPlayer.stop(); wvPlayer = null; }
        if (!initialized) return;
        for (WebView p : popups) p.destroy();
        popups.clear();
        if (web != null) { web.destroy(); web = null; }
        initialized = false;
    }

    private static String js(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "").replace("<", "\\x3c") + "'";
    }

    String getMusicLoginUrl() {
        WebRemoteServer srv = WebRemoteServer.get();
        String base = srv != null ? srv.getUrl() : "http://localhost:8090/";
        if (!base.endsWith("/")) base += "/";
        return base + "applemusic";
    }

    private static String buildQrSvg(String url) {
        try {
            boolean[][] m = Qr.encode(url);
            int n = m.length;
            StringBuilder sb = new StringBuilder();
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(n).append(" ").append(n)
              .append("\" width=\"164\" height=\"164\" shape-rendering=\"crispEdges\">");
            sb.append("<path fill=\"#000\" d=\"");
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (m[y][x]) sb.append("M").append(x).append(",").append(y).append("h1v1h-1z");
                }
            }
            sb.append("\"/></svg>");
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    void ensureSigninPopupOpen() {
        activity.runOnUiThread(() -> {
            ensureLoaded();
            if (popups.isEmpty() && web != null) {
                web.evaluateJavascript("window.__tv&&__tv.signinPopup&&__tv.signinPopup()", null);
            }
        });
    }

    void submitPhoneLogin(String email, String password) {
        activity.runOnUiThread(() -> {
            ensureLoaded();
            if (popups.isEmpty() && web != null) {
                web.evaluateJavascript("window.__tv&&__tv.signinPopup&&__tv.signinPopup()", null);
            }
            postDelayed(() -> {
                WebView target = top();
                if (target != null && signinPage(target.getUrl())) {
                    String qrUrl = getMusicLoginUrl();
                    String qrSvg = buildQrSvg(qrUrl);
                    target.evaluateJavascript("window.__amtvQrUrl=" + js(qrUrl) + ";window.__amtvQrSvg=" + js(qrSvg)
                            + ";\n" + signinJs + "\nwindow.__amtvSignin&&__amtvSignin.fillLogin("
                            + js(email != null ? email : "") + "," + js(password != null ? password : "") + ")", null);
                }
            }, popups.isEmpty() ? 2200 : 100);
        });
    }

    void submitPhone2fa(String code) {
        activity.runOnUiThread(() -> {
            WebView target = top();
            if (target != null && signinPage(target.getUrl())) {
                target.evaluateJavascript("window.__amtvSignin&&__amtvSignin.fill2fa(" + js(code != null ? code : "") + ")", null);
            }
        });
    }
}
