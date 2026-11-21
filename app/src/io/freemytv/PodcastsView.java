package io.freemytv;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integrated Apple Podcasts TV screen inside FreeMyTV.
 * Slides in from the LEFT of HomeView (opposite Apple Music on the right),
 * uses the user's Apple Media-User-Token against amp-api.podcasts.apple.com,
 * and renders a custom Apple TV dark-glass UI with purple accents (no lyrics).
 */
final class PodcastsView extends FrameLayout {
    static final String TAG = "aptv";
    private static final String BASE_URL = "https://podcasts.apple.com/br/new";
    private static final String AMP_HOST = "https://amp-api.podcasts.apple.com";
    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String DEFAULT_USER_TOKEN = "";
    private static final Pattern SCRIPT_RE = Pattern.compile("src=\"(/assets/index~[^\"]+\\.js)\"");
    private static final Pattern JWT_RE = Pattern.compile("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}");
    private static final LayoutParams FILL = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

    private final Activity activity;
    private final Runnable onExitToHome;
    private final ExecutorService net = Executors.newFixedThreadPool(3);
    private final Object tokenLock = new Object();

    private boolean initialized;
    private WebView web;
    private View splash;
    private TextView splashStatus;
    private final ArrayList<Animator> splashAnims = new ArrayList<>();
    private final Runnable splashTimeout = this::hideSplash;
    private final Runnable idleReleaseTask = this::releaseIfIdle;
    private volatile boolean mediaPlaying;
    private volatile String devToken;
    private boolean okDown;

    PodcastsView(Activity activity, Runnable onExitToHome) {
        super(activity);
        this.activity = activity;
        this.onExitToHome = onExitToHome;
        setBackgroundColor(0xFF0A0810);
    }

    void ensureLoaded() {
        if (initialized) return;
        initialized = true;

        SharedPreferences prefs = activity.getSharedPreferences("aptv", Context.MODE_PRIVATE);
        long savedAt = prefs.getLong("dev_token_at", 0L);
        if (System.currentTimeMillis() - savedAt < 12L * 3600L * 1000L) {
            devToken = prefs.getString("dev_token", null);
        }

        // Pre-warm developer JWT in background immediately
        net.execute(() -> getDevToken(false));

        web = new WebView(activity);
        web.setBackgroundColor(0xFF0A0810);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(UA);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "AptvNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m != null && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "JS: " + m.message());
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                String js = raw("aptv_app");
                if (!js.isEmpty()) {
                    view.evaluateJavascript(js, null);
                }
            }
        });

        addView(web, FILL);
        showSplash("Abrindo o Apple Podcasts…");

        String shellHtml = "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Apple Podcasts</title></head>"
                + "<body style=\"margin:0;background:#0a0810;overflow:hidden;\"></body></html>";
        web.loadDataWithBaseURL(BASE_URL, shellHtml, "text/html", "UTF-8", null);
    }

    boolean isInitialized() {
        return initialized;
    }

    boolean isPodcastsPlaying() {
        return mediaPlaying;
    }

    void cancelIdleRelease() {
        removeCallbacks(idleReleaseTask);
    }

    void scheduleIdleRelease() {
        removeCallbacks(idleReleaseTask);
        if (!initialized || !MusicView.isLowRamDevice(activity)) return;
        postDelayed(idleReleaseTask, 6000);
    }

    private void releaseIfIdle() {
        if (!initialized || getVisibility() == VISIBLE || mediaPlaying) return;
        Log.i(TAG, "releasing idle Apple Podcasts WebView to free RAM");
        for (Animator a : splashAnims) a.cancel();
        splashAnims.clear();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        removeAllViews();
        splash = null;
        splashStatus = null;
        initialized = false;
    }

    void destroyPodcasts() {
        cancelIdleRelease();
        for (Animator a : splashAnims) a.cancel();
        splashAnims.clear();
        if (web != null) {
            web.destroy();
            web = null;
        }
        initialized = false;
    }

    // ---------------------------------------------------------------- Splash (Apple Podcasts Royal Purple)

    private View makeSplash() {
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout f = new FrameLayout(activity);
        f.setBackgroundColor(0xFF0A0810);

        GradientDrawable glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setColors(new int[] { 0x66A855F7, 0x227E22CE, 0x00000000 });
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
                new int[] { 0xFFC06CF5, 0xFF7E22CE });
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
        try (InputStream in = activity.getAssets().open("logos/applepodcasts.png")) {
            Bitmap bm = BitmapFactory.decodeStream(in);
            if (bm != null) {
                icon.setImageBitmap(bm);
                icon.setColorFilter(Color.WHITE);
            }
        } catch (IOException ignored) {}
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LayoutParams((int) (82 * dp), (int) (82 * dp), Gravity.CENTER));
        col.addView(tile, new LinearLayout.LayoutParams((int) (128 * dp), (int) (128 * dp)));

        TextView title = new TextView(activity);
        title.setText("Apple Podcasts");
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
            c.setColor(i == 1 ? 0xFFC084FC : 0xFFA855F7);
            View d = new View(activity);
            d.setBackground(c);
            d.setAlpha(0.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (9 * dp), (int) (9 * dp));
            lp.leftMargin = lp.rightMargin = (int) (6 * dp);
            dots.addView(d, lp);
            loop(ObjectAnimator.ofFloat(d, View.ALPHA, 0.25f, 1f), 520, i * 170L);
        }

        splashStatus = new TextView(activity);
        splashStatus.setTextColor(0xFF9A94AA);
        splashStatus.setTextSize(14);
        splashStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) (420 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = (int) (18 * dp);
        col.addView(splashStatus, sp);

        col.setAlpha(0f);
        col.setScaleX(0.9f);
        col.setScaleY(0.9f);
        col.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
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
        removeCallbacks(splashTimeout);
        postDelayed(splashTimeout, 12000);
        if (splash == null) {
            splash = makeSplash();
            addView(splash, FILL);
        }
        if (splashStatus != null && status != null) splashStatus.setText(status);
    }

    private void hideSplash() {
        removeCallbacks(splashTimeout);
        final View s = splash;
        if (s == null) return;
        splash = null;
        splashStatus = null;
        for (Animator a : splashAnims) a.cancel();
        splashAnims.clear();
        s.animate().alpha(0f).setDuration(260).withEndAction(() -> removeView(s)).start();
    }

    // ---------------------------------------------------------------- Key Handling

    private void ptvKey(String name) {
        if (web != null) {
            web.evaluateJavascript("window.__ptv&&__ptv.key('" + name + "')", null);
        }
    }

    boolean dispatchPodcastsKey(KeyEvent e) {
        if (!initialized) return false;
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                if (splash != null) {
                    onExitToHome.run();
                } else if (web != null) {
                    web.evaluateJavascript("window.__ptv?__ptv.key('back'):false", r -> {
                        if (!"true".equals(r)) onExitToHome.run();
                    });
                } else {
                    onExitToHome.run();
                }
            }
            return true;
        }

        if (splash != null) return true;

        String dir = k == KeyEvent.KEYCODE_DPAD_UP ? "up"
                : k == KeyEvent.KEYCODE_DPAD_DOWN ? "down"
                : k == KeyEvent.KEYCODE_DPAD_LEFT ? "left"
                : k == KeyEvent.KEYCODE_DPAD_RIGHT ? "right" : null;
        if (dir != null) {
            if (down) ptvKey(dir);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            if (down && e.getRepeatCount() == 0) okDown = true;
            else if (!down && okDown) { okDown = false; ptvKey("ok"); }
            return true;
        }
        if (dispatchMediaKey(e)) return true;
        return web != null && web.dispatchKeyEvent(e);
    }

    boolean dispatchMediaKey(KeyEvent e) {
        if (!initialized || web == null) return false;
        String cmd = mediaCmd(e.getKeyCode());
        if (cmd != null) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                web.evaluateJavascript("window.__ptv&&__ptv.media('" + cmd + "')", null);
            }
            return true;
        }
        return false;
    }

    private static String mediaCmd(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return "toggle";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP: return "pause";
            case KeyEvent.KEYCODE_MEDIA_NEXT: return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "prev";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "fwd";
            case KeyEvent.KEYCODE_MEDIA_REWIND: return "rew";
            default: return null;
        }
    }

    // ---------------------------------------------------------------- Apple Podcasts AMP API + Auto JWT

    private String getDevToken(boolean forceRefresh) {
        if (!forceRefresh && devToken != null && !devToken.isEmpty()) return devToken;
        synchronized (tokenLock) {
            if (!forceRefresh && devToken != null && !devToken.isEmpty()) return devToken;
            try {
                String html = httpGetText(BASE_URL, null);
                Matcher sm = SCRIPT_RE.matcher(html);
                if (!sm.find()) {
                    Log.w(TAG, "Podcasts index script not found");
                    return devToken;
                }
                String jsUrl = "https://podcasts.apple.com" + sm.group(1);
                String js = httpGetText(jsUrl, null);
                Matcher jm = JWT_RE.matcher(js);
                if (jm.find()) {
                    devToken = jm.group(0);
                    activity.getSharedPreferences("aptv", Context.MODE_PRIVATE)
                            .edit()
                            .putString("dev_token", devToken)
                            .putLong("dev_token_at", System.currentTimeMillis())
                            .apply();
                    Log.i(TAG, "Fetched fresh Apple Podcasts developer JWT");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed fetching developer JWT", e);
            }
            return devToken;
        }
    }

    private String callAmpApi(String path, boolean retried) throws IOException {
        String token = getDevToken(retried);
        if (token == null || token.isEmpty()) throw new IOException("Developer token indisponível");
        String fullUrl = path.startsWith("http") ? path : (AMP_HOST + path);
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        String userToken = resolveMediaUserToken();
        if (!userToken.isEmpty()) {
            conn.setRequestProperty("Media-User-Token", userToken);
        }
        conn.setRequestProperty("Origin", "https://podcasts.apple.com");
        conn.setRequestProperty("Referer", "https://podcasts.apple.com/");
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code == 401 && !retried) {
            conn.disconnect();
            return callAmpApi(path, true);
        }
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = readStream(in);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        return body;
    }

    private String resolveMediaUserToken() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences("amtv", Context.MODE_PRIVATE);
            String saved = prefs.getString("media_user_token", "");
            if (saved != null && !saved.trim().isEmpty()) return saved.trim();
            String cookies = android.webkit.CookieManager.getInstance().getCookie("https://music.apple.com/");
            if (cookies != null) {
                for (String part : cookies.split(";")) {
                    String kv = part.trim();
                    if (kv.startsWith("media-user-token=")) {
                        return kv.substring("media-user-token=".length()).trim();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return DEFAULT_USER_TOKEN;
    }

    private static String httpGetText(String urlStr, String auth) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(12000);
        conn.setRequestProperty("User-Agent", UA);
        if (auth != null) conn.setRequestProperty("Authorization", auth);
        try (InputStream in = conn.getInputStream()) {
            return readStream(in);
        } finally {
            conn.disconnect();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private String raw(String name) {
        int id = getResources().getIdentifier(name, "raw", activity.getPackageName());
        if (id == 0) return "";
        try (InputStream in = getResources().openRawResource(id)) {
            return readStream(in);
        } catch (IOException e) {
            return "";
        }
    }

    private static String jsQuote(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                case '<': sb.append("\\x3c"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default: sb.append(c);
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    // ---------------------------------------------------------------- JS Bridge

    private final class Bridge {
        @JavascriptInterface
        public void api(final int reqId, final String path) {
            net.execute(() -> {
                try {
                    String json = callAmpApi(path, false);
                    post(() -> {
                        if (web != null) {
                            web.evaluateJavascript("window.__ptvApiCb&&__ptvApiCb(" + reqId + ",true," + jsQuote(json) + ")", null);
                        }
                    });
                } catch (Exception e) {
                    String err = e.getMessage() != null ? e.getMessage() : "Erro de rede";
                    post(() -> {
                        if (web != null) {
                            web.evaluateJavascript("window.__ptvApiCb&&__ptvApiCb(" + reqId + ",false," + jsQuote(err) + ")", null);
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void ready() {
            post(() -> hideSplash());
        }

        @JavascriptInterface
        public void exit() {
            post(onExitToHome);
        }

        @JavascriptInterface
        public void playing(final boolean on) {
            mediaPlaying = on;
            post(() -> {
                if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void keyboard(final boolean show) {
            post(() -> {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm == null || web == null) return;
                if (show) {
                    web.requestFocus();
                    imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
                } else {
                    imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
                }
            });
        }

        @JavascriptInterface
        public float getVolume() {
            try {
                AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    if (max > 0) return (float) cur / max;
                }
            } catch (Throwable ignored) {}
            return 0.65f;
        }

        @JavascriptInterface
        public void setVolume(float v) {
            post(() -> {
                try {
                    AudioManager am = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int target = Math.max(0, Math.min(max, Math.round(v * max)));
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                    }
                } catch (Throwable ignored) {}
            });
        }
    }
}
