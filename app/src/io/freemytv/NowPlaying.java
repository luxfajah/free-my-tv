package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;

    public static final class MediaInfo {
        public final String title;
        public final String artist;
        public final String album;
        public final String app;
        public final Bitmap art;
        public final long duration;
        public final long position;
        public final long positionAt;
        public final boolean isPlaying;
        public final MediaController controller;

        public MediaInfo(String title, String artist, String album, String app, Bitmap art,
                         long duration, long position, boolean isPlaying, MediaController controller) {
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.app = app != null ? app : "";
            this.art = art;
            this.duration = duration;
            this.position = position;
            this.positionAt = SystemClock.elapsedRealtime();
            this.isPlaying = isPlaying;
            this.controller = controller;
        }

        public long currentPositionMs() {
            if (!isPlaying) return position;
            long elapsed = SystemClock.elapsedRealtime() - positionAt;
            long pos = position + elapsed;
            return duration > 0 ? Math.min(pos, duration) : pos;
        }
    }

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;
        public volatile MediaInfo mediaInfo;

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Free My TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            String now = null, nowTitle = null;
            MediaInfo newInfo = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                boolean playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING;
                if (playing) {
                    now = describe(mc);
                    MediaMetadata m = mc.getMetadata();
                    MediaDescription d = m == null ? null : m.getDescription();
                    CharSequence t = d == null ? null : d.getTitle();
                    nowTitle = now == null ? null : TextUtils.isEmpty(t) ? mc.getPackageName() : t.toString().trim();

                    String trackTitle = d != null && !TextUtils.isEmpty(d.getTitle()) ? d.getTitle().toString().trim() : (m != null ? m.getString(MediaMetadata.METADATA_KEY_TITLE) : "");
                    String trackArtist = d != null && !TextUtils.isEmpty(d.getSubtitle()) ? d.getSubtitle().toString().trim() : (m != null ? m.getString(MediaMetadata.METADATA_KEY_ARTIST) : "");
                    String trackAlbum = m != null ? m.getString(MediaMetadata.METADATA_KEY_ALBUM) : "";
                    Bitmap trackArt = d != null ? d.getIconBitmap() : null;
                    if (trackArt == null && m != null) {
                        trackArt = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                        if (trackArt == null) trackArt = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    }
                    long duration = m != null ? m.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
                    long position = s != null ? s.getPosition() : 0;
                    String appName = labels.get(mc.getPackageName());
                    if (appName == null) appName = mc.getPackageName();
                    newInfo = new MediaInfo(trackTitle, trackArtist, trackAlbum, appName, trackArt, duration, position, state == PlaybackState.STATE_PLAYING, mc);
                    break;
                }
            }
            mediaInfo = newInfo;
            if (Objects.equals(now, text)) {
                changed.run();
                return;
            }
            title = nowTitle;
            text = now;
            changed.run();
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
