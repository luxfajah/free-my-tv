package io.freemytv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaDrm;
import android.media.MediaFormat;
import android.media.NotProvisionedException;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Native Android Widevine L3 + fMP4 + MediaCodec + AudioTrack audio player.
 * Used as a seamless full-track playback engine on Android TV WebViews (such as AOSP Chrome 66)
 * whose JS EME layer does not expose com.widevine.alpha even though Android's native MediaDrm
 * supports Widevine L3.
 */
final class WidevineAudioPlayer {
    private static final String TAG = "amtv-wv";
    private static final UUID WIDEVINE_UUID = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    interface Listener {
        void onState(String event, double posSec, double durSec, String detail);
    }

    private static final class Segment {
        final String url;
        final int start;
        final int end;
        final double startSec;
        final double durSec;

        Segment(String url, int start, int end, double startSec, double durSec) {
            this.url = url;
            this.start = start;
            this.end = end;
            this.startSec = startSec;
            this.durSec = durSec;
        }
    }

    private final Listener listener;
    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean stopFlag;
    private volatile boolean paused;
    private volatile double seekTargetSec = -1;
    private volatile double currentPosSec = 0;
    private volatile double durationSec = 0;
    private volatile float volume = 1.0f;
    private volatile int state = 0; // 0=none, 1=loading, 2=playing, 3=paused, 4=stopped, 5=ended
    private AudioTrack activeTrack;

    WidevineAudioPlayer(Listener listener) {
        this.listener = listener;
    }

    void play(String m3u8Url, String licenseUrl, String devToken, String userToken,
              String songId, boolean isLibrary, double hintDurationSec) {
        stop();
        synchronized (lock) {
            stopFlag = false;
            paused = false;
            seekTargetSec = -1;
            currentPosSec = 0;
            durationSec = hintDurationSec > 0 ? hintDurationSec : 0;
            state = 1;
            worker = new Thread(() -> runLoop(m3u8Url, licenseUrl, devToken, userToken, songId, isLibrary), "amtv-wv-play");
            worker.start();
        }
        emit("loading", 0, durationSec, null);
    }

    void pause() {
        paused = true;
        if (state == 2) state = 3;
        AudioTrack at = activeTrack;
        if (at != null) {
            try { at.pause(); } catch (Throwable ignored) {}
        }
        emit("paused", currentPosSec, durationSec, null);
    }

    void resume() {
        paused = false;
        if (state == 3) state = 2;
        AudioTrack at = activeTrack;
        if (at != null) {
            try { at.play(); } catch (Throwable ignored) {}
        }
        synchronized (lock) { lock.notifyAll(); }
        emit("playing", currentPosSec, durationSec, null);
    }

    void seekTo(double sec) {
        if (sec < 0) sec = 0;
        if (durationSec > 0 && sec > durationSec - 0.5) sec = Math.max(0, durationSec - 0.5);
        seekTargetSec = sec;
        currentPosSec = sec;
        synchronized (lock) { lock.notifyAll(); }
    }

    void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        AudioTrack at = activeTrack;
        if (at != null) {
            try { at.setVolume(volume); } catch (Throwable ignored) {}
        }
    }

    float getVolume() { return volume; }
    double getPositionSec() { return currentPosSec; }
    double getDurationSec() { return durationSec; }
    int getState() { return state; }

    void stop() {
        Thread prev;
        synchronized (lock) {
            stopFlag = true;
            paused = false;
            state = 4;
            prev = worker;
            worker = null;
            lock.notifyAll();
        }
        AudioTrack at = activeTrack;
        if (at != null) {
            try { at.pause(); at.flush(); } catch (Throwable ignored) {}
        }
        if (prev != null && prev != Thread.currentThread()) {
            prev.interrupt();
            try { prev.join(600); } catch (InterruptedException ignored) {}
        }
    }

    private void emit(String ev, double pos, double dur, String detail) {
        if (listener != null) listener.onState(ev, pos, dur, detail);
    }

    private void runLoop(String m3u8Url, String licenseUrl, String devToken, String userToken,
                         String songId, boolean isLibrary) {
        MediaDrm drm = null;
        byte[] sid = null;
        MediaCrypto crypto = null;
        MediaCodec codec = null;
        AudioTrack track = null;
        try {
            // 1. Fetch and parse m3u8 playlist
            String m3u8 = new String(fetchBytes(m3u8Url, null), "UTF-8");
            if (stopFlag) return;

            String baseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);
            String keyUri = null;
            String mapUrl = null;
            int mapStart = 0, mapLen = 0;
            ArrayList<Segment> segs = new ArrayList<>();
            double curDur = 0;
            double pendingDur = 0;
            int pendingLen = -1, pendingOff = -1;

            for (String rawLine : m3u8.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXT-X-KEY:")) {
                    int u = line.indexOf("URI=\"");
                    if (u >= 0) {
                        int e = line.indexOf('"', u + 5);
                        if (e > u) keyUri = line.substring(u + 5, e);
                    }
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    int u = line.indexOf("URI=\"");
                    if (u >= 0) {
                        int e = line.indexOf('"', u + 5);
                        if (e > u) mapUrl = resolveUrl(baseUrl, line.substring(u + 5, e));
                    }
                    int br = line.indexOf("BYTERANGE=\"");
                    if (br >= 0) {
                        int e = line.indexOf('"', br + 11);
                        if (e > br) {
                            String[] parts = line.substring(br + 11, e).split("@");
                            mapLen = Integer.parseInt(parts[0]);
                            mapStart = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                        }
                    }
                } else if (line.startsWith("#EXTINF:")) {
                    int comma = line.indexOf(',');
                    String num = comma > 8 ? line.substring(8, comma) : line.substring(8);
                    try { pendingDur = Double.parseDouble(num.trim()); } catch (Exception ignored) { pendingDur = 10.0; }
                } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                    String[] parts = line.substring(17).trim().split("@");
                    pendingLen = Integer.parseInt(parts[0]);
                    pendingOff = parts.length > 1 ? Integer.parseInt(parts[1]) : (mapStart + mapLen);
                } else if (!line.startsWith("#")) {
                    String segUrl = resolveUrl(baseUrl, line);
                    if (pendingLen > 0) {
                        segs.add(new Segment(segUrl, pendingOff, pendingOff + pendingLen - 1, curDur, pendingDur));
                        curDur += pendingDur;
                        pendingLen = -1;
                    }
                }
            }
            if (curDur > 0) durationSec = curDur;
            if (segs.isEmpty() || keyUri == null) {
                throw new IllegalStateException("Empty HLS playlist or missing key URI");
            }

            // 2. Parse 16-byte Key ID from URI="data:;base64,..."
            String kidB64 = keyUri.startsWith("data:;base64,") ? keyUri.substring(13) : keyUri;
            byte[] kid = Base64.decode(kidB64, Base64.DEFAULT);
            byte[] pssh = buildWidevinePssh(kid);

            // 3. Open MediaDrm session (auto-provision L3 if needed) and acquire Apple Widevine license
            drm = new MediaDrm(WIDEVINE_UUID);
            try {
                sid = drm.openSession();
            } catch (NotProvisionedException npe) {
                provisionWidevine(drm);
                sid = drm.openSession();
            }
            if (stopFlag) return;

            MediaDrm.KeyRequest kreq = drm.getKeyRequest(sid, pssh, "cenc", MediaDrm.KEY_TYPE_STREAMING, null);
            String challengeB64 = Base64.encodeToString(kreq.getData(), Base64.NO_WRAP);
            byte[] licenseBytes = acquireAppleLicense(licenseUrl, devToken, userToken, songId, isLibrary, keyUri, challengeB64);
            drm.provideKeyResponse(sid, licenseBytes);
            if (stopFlag) return;

            // 4. Parse init segment for AAC sample rate / csd-0 (defaults to 44100Hz stereo AAC-LC)
            int sampleRate = 44100;
            int channels = 2;
            byte[] csd0 = new byte[]{0x12, 0x10};
            if (mapUrl != null && mapLen > 0) {
                try {
                    byte[] initBytes = fetchBytes(mapUrl, "bytes=" + mapStart + "-" + (mapStart + mapLen - 1));
                    byte[] foundCsd = extractEsdsCsd0(initBytes);
                    if (foundCsd != null && foundCsd.length >= 2) {
                        csd0 = foundCsd;
                        int srIdx = ((csd0[0] & 0x07) << 1) | ((csd0[1] & 0x80) >>> 7);
                        int[] srTable = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};
                        if (srIdx >= 0 && srIdx < srTable.length) sampleRate = srTable[srIdx];
                        int chCfg = (csd0[1] & 0x78) >>> 3;
                        if (chCfg == 1 || chCfg == 2) channels = chCfg;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "init parse fallback: " + t);
                }
            }

            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
            fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));

            crypto = new MediaCrypto(WIDEVINE_UUID, sid);
            codec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            codec.configure(fmt, null, crypto, 0);
            codec.start();

            int chMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(chMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    Math.max(minBuf * 3, 32768),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.setVolume(volume);
            track.play();
            activeTrack = track;
            state = 2;
            emit("playing", currentPosSec, durationSec, null);

            MediaCodec.CryptoInfo ci = new MediaCodec.CryptoInfo();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            byte[] pcmScratch = new byte[16384];

            int segIdx = 0;
            while (segIdx < segs.size() && !stopFlag) {
                double seek = seekTargetSec;
                if (seek >= 0) {
                    seekTargetSec = -1;
                    segIdx = findSegmentIndex(segs, seek);
                    try { codec.flush(); } catch (Throwable ignored) {}
                    try { track.pause(); track.flush(); if (!paused) track.play(); } catch (Throwable ignored) {}
                }

                Segment s = segs.get(segIdx);
                byte[] segBytes = fetchBytes(s.url, "bytes=" + s.start + "-" + s.end);
                if (stopFlag) break;

                int[] moof = findBox(segBytes, 0, segBytes.length, "moof");
                int[] mdat = findBox(segBytes, 0, segBytes.length, "mdat");
                if (moof == null || mdat == null) { segIdx++; continue; }
                int[] traf = findBox(segBytes, moof[0], moof[1], "traf");
                if (traf == null) { segIdx++; continue; }
                int[] trun = findBox(segBytes, traf[0], traf[1], "trun");
                int[] senc = findBox(segBytes, traf[0], traf[1], "senc");
                if (trun == null) { segIdx++; continue; }

                int trunFlags = u32(segBytes, trun[0]) & 0xFFFFFF;
                int sampleCount = u32(segBytes, trun[0] + 4);
                int tp = trun[0] + 8;
                if ((trunFlags & 0x1) != 0) tp += 4;
                if ((trunFlags & 0x4) != 0) tp += 4;
                int[] sampleSizes = new int[sampleCount];
                for (int i = 0; i < sampleCount; i++) {
                    if ((trunFlags & 0x100) != 0) tp += 4;
                    if ((trunFlags & 0x200) != 0) { sampleSizes[i] = u32(segBytes, tp); tp += 4; }
                    if ((trunFlags & 0x400) != 0) tp += 4;
                    if ((trunFlags & 0x800) != 0) tp += 4;
                }

                boolean hasSenc = senc != null;
                boolean hasSub = false;
                int sp = 0;
                int ivSize = 8;
                if (hasSenc) {
                    int sencFlags = u32(segBytes, senc[0]) & 0xFFFFFF;
                    sp = senc[0] + 8;
                    hasSub = (sencFlags & 0x2) != 0;
                    ivSize = hasSub ? 8 : Math.max(8, (senc[1] - sp) / Math.max(1, sampleCount));
                }

                int mpos = mdat[0];
                double sampleDurSec = sampleCount > 0 ? (s.durSec / sampleCount) : 0.023219;

                boolean restartedForSeek = false;
                for (int i = 0; i < sampleCount && !stopFlag; i++) {
                    if (seekTargetSec >= 0) {
                        restartedForSeek = true;
                        break;
                    }
                    while (paused && !stopFlag && seekTargetSec < 0) {
                        synchronized (lock) {
                            try { lock.wait(100); } catch (InterruptedException e) { break; }
                        }
                    }
                    if (stopFlag) break;
                    if (seekTargetSec >= 0) { restartedForSeek = true; break; }

                    int ssz = sampleSizes[i];
                    if (ssz <= 0 || mpos + ssz > segBytes.length) break;

                    byte[] iv16 = new byte[16];
                    int[] clearArr = null, encArr = null;
                    int numSub = 1;
                    if (hasSenc) {
                        System.arraycopy(segBytes, sp, iv16, 0, Math.min(16, ivSize));
                        sp += ivSize;
                        if (hasSub) {
                            numSub = u16(segBytes, sp); sp += 2;
                            clearArr = new int[numSub];
                            encArr = new int[numSub];
                            for (int j = 0; j < numSub; j++) {
                                clearArr[j] = u16(segBytes, sp); sp += 2;
                                encArr[j] = u32(segBytes, sp); sp += 4;
                            }
                        } else {
                            clearArr = new int[]{0};
                            encArr = new int[]{ssz};
                        }
                    }

                    long ptsUs = (long) ((s.startSec + i * sampleDurSec) * 1_000_000.0);
                    int inIdx = -1;
                    while (!stopFlag && seekTargetSec < 0 && inIdx < 0) {
                        inIdx = codec.dequeueInputBuffer(10000);
                        drainOutputs(codec, bi, track, pcmScratch);
                    }
                    if (stopFlag || seekTargetSec >= 0) { restartedForSeek = true; break; }

                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(segBytes, mpos, ssz);
                        if (hasSenc) {
                            ci.set(numSub, clearArr, encArr, kid, iv16, MediaCodec.CRYPTO_MODE_AES_CTR);
                            codec.queueSecureInputBuffer(inIdx, 0, ci, ptsUs, 0);
                        } else {
                            codec.queueInputBuffer(inIdx, 0, ssz, ptsUs, 0);
                        }
                    }
                    mpos += ssz;
                    currentPosSec = s.startSec + (i + 1) * sampleDurSec;
                    drainOutputs(codec, bi, track, pcmScratch);
                }

                if (!restartedForSeek) segIdx++;
            }

            if (!stopFlag) {
                state = 5;
                emit("ended", durationSec, durationSec, null);
            }
        } catch (Throwable t) {
            if (!stopFlag) {
                Log.e(TAG, "Widevine playback failed", t);
                state = 4;
                emit("error", currentPosSec, durationSec, String.valueOf(t.getMessage()));
            }
        } finally {
            activeTrack = null;
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (crypto != null) {
                try { crypto.release(); } catch (Throwable ignored) {}
            }
            if (drm != null) {
                if (sid != null) {
                    try { drm.closeSession(sid); } catch (Throwable ignored) {}
                }
                try { drm.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void drainOutputs(MediaCodec codec, MediaCodec.BufferInfo bi, AudioTrack track, byte[] scratch) {
        while (!stopFlag && seekTargetSec < 0) {
            int outIdx = codec.dequeueOutputBuffer(bi, 0);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && bi.size > 0) {
                    outBuf.position(bi.offset);
                    outBuf.limit(bi.offset + bi.size);
                    int rem = bi.size;
                    while (rem > 0 && !stopFlag && seekTargetSec < 0) {
                        int n = Math.min(rem, scratch.length);
                        outBuf.get(scratch, 0, n);
                        track.write(scratch, 0, n);
                        rem -= n;
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    try { track.setPlaybackRate(of.getInteger(MediaFormat.KEY_SAMPLE_RATE)); } catch (Throwable ignored) {}
                }
            } else {
                break;
            }
        }
    }

    private static int findSegmentIndex(ArrayList<Segment> segs, double sec) {
        for (int i = 0; i < segs.size(); i++) {
            Segment s = segs.get(i);
            if (sec < s.startSec + s.durSec) return i;
        }
        return Math.max(0, segs.size() - 1);
    }

    private static void provisionWidevine(MediaDrm drm) throws Exception {
        MediaDrm.ProvisionRequest preq = drm.getProvisionRequest();
        String url = preq.getDefaultUrl() + "&signedRequest=" + new String(preq.getData(), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Widevine CDM v1.0");
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(new byte[0]); }
        byte[] resp = readAll(c.getInputStream());
        drm.provideProvisionResponse(resp);
        Log.i(TAG, "Widevine L3 provisioned successfully (" + resp.length + " bytes)");
    }

    private static byte[] acquireAppleLicense(String licenseUrl, String devToken, String userToken,
                                              String songId, boolean isLibrary, String keyUri, String challengeB64) throws Exception {
        String body = "{\"challenge\":\"" + challengeB64 + "\","
                + "\"uri\":\"" + keyUri.replace("\"", "\\\"") + "\","
                + "\"key-system\":\"com.widevine.alpha\","
                + "\"adamId\":\"" + songId.replace("\"", "\\\"") + "\","
                + "\"isLibrary\":" + (isLibrary ? "true" : "false") + ","
                + "\"user-initiated\":true}";
        HttpURLConnection c = (HttpURLConnection) new URL(licenseUrl).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + devToken);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Apple-Music-User-Token", userToken);
        c.setRequestProperty("X-Apple-Renewal", "true");
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String resp = new String(readAll(in), "UTF-8");
        int idx = resp.indexOf("\"license\":\"");
        if (idx < 0) throw new IllegalStateException("License missing (HTTP " + code + "): " + resp.substring(0, Math.min(200, resp.length())));
        int start = idx + 11;
        int end = resp.indexOf('"', start);
        return Base64.decode(resp.substring(start, end), Base64.DEFAULT);
    }

    private static byte[] buildWidevinePssh(byte[] kid16) {
        byte[] pssh = new byte[52];
        pssh[3] = 52;
        pssh[4] = 'p'; pssh[5] = 's'; pssh[6] = 's'; pssh[7] = 'h';
        byte[] uuid = {(byte)0xed,(byte)0xef,(byte)0x8b,(byte)0xa9,0x79,(byte)0xd6,0x4a,(byte)0xce,
                (byte)0xa3,(byte)0xc8,0x27,(byte)0xdc,(byte)0xd5,0x1d,0x21,(byte)0xed};
        System.arraycopy(uuid, 0, pssh, 12, 16);
        pssh[31] = 20;
        pssh[32] = 0x08; pssh[33] = 0x01; pssh[34] = 0x12; pssh[35] = 0x10;
        System.arraycopy(kid16, 0, pssh, 36, Math.min(16, kid16.length));
        return pssh;
    }

    private static byte[] extractEsdsCsd0(byte[] b) {
        // Locate 'esds' box and find DecoderSpecificInfo tag (0x05)
        for (int i = 0; i < b.length - 12; i++) {
            if (b[i] == 'e' && b[i+1] == 's' && b[i+2] == 'd' && b[i+3] == 's') {
                int end = Math.min(b.length, i + 128);
                for (int j = i + 8; j < end - 4; j++) {
                    if ((b[j] & 0xFF) == 0x05) {
                        int k = j + 1;
                        int len = 0;
                        while (k < end && (b[k] & 0x80) != 0) { len = (len << 7) | (b[k++] & 0x7F); }
                        if (k < end) len = (len << 7) | (b[k++] & 0x7F);
                        if (len >= 2 && len <= 8 && k + len <= end) {
                            byte[] out = new byte[len];
                            System.arraycopy(b, k, out, 0, len);
                            return out;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static byte[] fetchBytes(String urlStr, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        if (range != null) c.setRequestProperty("Range", range);
        return readAll(c.getInputStream());
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (in == null) return new byte[0];
        try {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) bos.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        return bos.toByteArray();
    }

    private static String resolveUrl(String base, String rel) {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel;
        return base + rel;
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o+1] & 0xFF);
    }

    private static int u32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o+1] & 0xFF) << 16) | ((b[o+2] & 0xFF) << 8) | (b[o+3] & 0xFF);
    }

    private static int[] findBox(byte[] b, int start, int end, String type) {
        int p = start;
        while (p + 8 <= end) {
            int sz = u32(b, p);
            if (sz < 8 || p + sz > end) break;
            if (b[p+4] == type.charAt(0) && b[p+5] == type.charAt(1)
                    && b[p+6] == type.charAt(2) && b[p+7] == type.charAt(3)) {
                return new int[]{p + 8, p + sz};
            }
            p += sz;
        }
        return null;
    }
}
