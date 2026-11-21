package io.freemytv;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LyricsEngine {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)");

    static final class Line {
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text != null ? text.trim() : "";
        }
    }

    interface Callback {
        void onLyrics(String track, String artist, List<Line> lines, boolean synced);
    }

    private static String lastKey = "";
    private static List<Line> lastLines = Collections.emptyList();
    private static boolean lastSynced = false;

    static void fetch(String title, String artist, Callback callback) {
        if (TextUtils.isEmpty(title)) {
            callback.onLyrics("", "", Collections.emptyList(), false);
            return;
        }
        final String cleanTitle = cleanTrackTitle(title);
        final String cleanArtist = artist == null ? "" : artist.trim();
        final String key = cleanTitle.toLowerCase() + "###" + cleanArtist.toLowerCase();

        if (key.equals(lastKey)) {
            callback.onLyrics(cleanTitle, cleanArtist, lastLines, lastSynced);
            return;
        }

        EXECUTOR.execute(() -> {
            List<Line> parsed = new ArrayList<>();
            boolean isSynced = false;
            try {
                StringBuilder urlBuilder = new StringBuilder("https://lrclib.net/api/get?");
                urlBuilder.append("track_name=").append(URLEncoder.encode(cleanTitle, "UTF-8"));
                if (!cleanArtist.isEmpty()) {
                    urlBuilder.append("&artist_name=").append(URLEncoder.encode(cleanArtist, "UTF-8"));
                }
                URL url = new URL(urlBuilder.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "AppleTV-BTV/1.0");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(4000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String syncedLyrics = json.optString("syncedLyrics", "");
                    String plainLyrics = json.optString("plainLyrics", "");

                    if (!TextUtils.isEmpty(syncedLyrics)) {
                        for (String l : syncedLyrics.split("\n")) {
                            Matcher m = LRC_PATTERN.matcher(l.trim());
                            if (m.matches()) {
                                long min = Long.parseLong(m.group(1));
                                long sec = Long.parseLong(m.group(2));
                                String msStr = m.group(3);
                                long ms = Long.parseLong(msStr);
                                if (msStr.length() == 2) ms *= 10;
                                long totalMs = min * 60000 + sec * 1000 + ms;
                                String text = m.group(4);
                                if (!text.trim().isEmpty()) {
                                    parsed.add(new Line(totalMs, text));
                                }
                            }
                        }
                        isSynced = !parsed.isEmpty();
                    }
                    if (parsed.isEmpty() && !TextUtils.isEmpty(plainLyrics)) {
                        long dummyTime = 0;
                        for (String l : plainLyrics.split("\n")) {
                            if (!l.trim().isEmpty()) {
                                parsed.add(new Line(dummyTime, l));
                                dummyTime += 4000;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.w("Lyrics fetch failed: " + e.getMessage());
            }

            final List<Line> resultLines = parsed;
            final boolean resultSynced = isSynced;
            MAIN.post(() -> {
                lastKey = key;
                lastLines = resultLines;
                lastSynced = resultSynced;
                callback.onLyrics(cleanTitle, cleanArtist, resultLines, resultSynced);
            });
        });
    }

    private static String cleanTrackTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("\\(.*?\\)|\\[.*?\\]", "").trim();
    }
}
