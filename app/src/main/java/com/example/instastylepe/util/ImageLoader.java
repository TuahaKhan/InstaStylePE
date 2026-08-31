package com.example.instastylepe.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small memory + disk image cache with prefetch.
 *
 * <p>Deliberately hand-rolled instead of pulling in Glide or Coil. A stories player needs one
 * thing a generic loader does not give you for free: the <em>next</em> frame decoded before the
 * current one runs out, or every advance shows a blank screen for a moment. Having the loader in
 * the project also keeps the demo's dependency list down to CleverTap plus AndroidX, which makes
 * it obvious during a walkthrough that nothing here is doing the interesting work except
 * CleverTap.</p>
 */
public final class ImageLoader {

    private static final String TAG = "StoryImages";
    private static final String CACHE_DIR = "story_images";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int DOWNLOAD_ATTEMPTS = 2;
    /** Floor and ceiling for the decode target, in case a display reports something odd. */
    private static final int MIN_TARGET_LONG_EDGE_PX = 1080;
    private static final int MAX_TARGET_LONG_EDGE_PX = 2560;

    @Nullable
    private static volatile ImageLoader instance;

    private final File cacheDir;
    /**
     * Long edge to decode towards - the screen's own long edge. A story frame fills the display,
     * so decoding to anything less than this is visible as softness, and anything more is memory
     * spent on pixels that cannot be shown.
     */
    private final int targetLongEdgePx;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Latest requested url per view, so a recycled row cannot be painted by a stale response. */
    private final Map<ImageView, String> pendingByView =
            Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Callbacks waiting on a url that is already downloading, so a prefetch and a real load share
     * one fetch. This matters for the story player: the next frame is usually already being
     * prefetched by the time the user advances to it, and that arrival has to reach the view.
     */
    private final Map<String, List<Callback>> waiting = new HashMap<>();

    private ImageLoader(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        cacheDir = new File(appContext.getCacheDir(), CACHE_DIR);

        DisplayMetrics metrics = appContext.getResources().getDisplayMetrics();
        int screenLongEdge = Math.max(metrics.widthPixels, metrics.heightPixels);
        targetLongEdgePx = Math.max(MIN_TARGET_LONG_EDGE_PX,
                Math.min(MAX_TARGET_LONG_EDGE_PX, screenLongEdge));
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.w(TAG, "Could not create image cache dir");
        }
        int limitBytes = (int) (Runtime.getRuntime().maxMemory() / 6);
        memoryCache = new LruCache<String, Bitmap>(limitBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public static ImageLoader get(@NonNull Context context) {
        ImageLoader local = instance;
        if (local == null) {
            synchronized (ImageLoader.class) {
                local = instance;
                if (local == null) {
                    local = new ImageLoader(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public interface Callback {

        @MainThread
        void onImageReady(@NonNull String url, @Nullable Bitmap bitmap);
    }

    @Nullable
    public Bitmap fromMemory(@Nullable String url) {
        return url == null ? null : memoryCache.get(url);
    }

    /**
     * Loads into an ImageView, tolerating view recycling.
     */
    @MainThread
    public void load(@Nullable String url, @NonNull ImageView target) {
        if (url == null || url.isEmpty()) {
            target.setImageDrawable(null);
            return;
        }
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            pendingByView.remove(target);
            target.setImageBitmap(cached);
            return;
        }
        pendingByView.put(target, url);
        target.setImageDrawable(null);
        fetch(url, (loadedUrl, bitmap) -> {
            if (bitmap == null) {
                return;
            }
            // Only paint if this view still wants this url.
            if (loadedUrl.equals(pendingByView.get(target))) {
                pendingByView.remove(target);
                target.setImageBitmap(bitmap);
            }
        });
    }

    /**
     * Loads and hands the bitmap back, for callers that draw it themselves.
     */
    @MainThread
    public void load(@Nullable String url, @NonNull Callback callback) {
        if (url == null || url.isEmpty()) {
            callback.onImageReady("", null);
            return;
        }
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            callback.onImageReady(url, cached);
            return;
        }
        fetch(url, callback);
    }

    /**
     * Warms the cache without a target. Called for the next story frame while the current one is
     * still on screen.
     */
    public void prefetch(@Nullable String url) {
        if (url == null || url.isEmpty() || memoryCache.get(url) != null) {
            return;
        }
        fetch(url, null);
    }

    private void fetch(@NonNull String url, @Nullable Callback callback) {
        boolean startDownload;
        synchronized (waiting) {
            List<Callback> queue = waiting.get(url);
            startDownload = queue == null;
            if (startDownload) {
                queue = new ArrayList<>(2);
                waiting.put(url, queue);
            }
            if (callback != null) {
                queue.add(callback);
            }
        }
        if (!startDownload) {
            // Someone is already downloading this url; the callback added above will be notified
            // along with theirs.
            return;
        }
        executor.execute(() -> {
            Bitmap loaded = null;
            try {
                loaded = loadFromDiskOrNetwork(url);
                if (loaded != null) {
                    memoryCache.put(url, loaded);
                }
            } catch (Exception e) {
                Log.w(TAG, "Image load failed for " + url + ": " + e.getMessage());
            }
            List<Callback> queue;
            synchronized (waiting) {
                queue = waiting.remove(url);
            }
            if (queue == null || queue.isEmpty()) {
                return;
            }
            Bitmap result = loaded;
            mainHandler.post(() -> {
                for (Callback waiter : queue) {
                    waiter.onImageReady(url, result);
                }
            });
        });
    }

    @Nullable
    private Bitmap loadFromDiskOrNetwork(@NonNull String url) throws Exception {
        File file = cacheFileFor(url);
        if (file.exists() && file.length() > 0) {
            Bitmap fromDisk = decode(readFile(file));
            if (fromDisk != null) {
                return fromDisk;
            }
            // Corrupt entry; drop it and re-download.
            if (!file.delete()) {
                Log.w(TAG, "Could not delete corrupt cache entry " + file.getName());
            }
        }
        byte[] bytes = downloadWithRetry(url);
        if (bytes == null) {
            return null;
        }
        writeFile(file, bytes);
        return decode(bytes);
    }

    /**
     * One retry, because the first image request of a run routinely eats a cold DNS lookup and TLS
     * handshake on an emulator and times out on its own. A second attempt is nearly free and turns
     * a black story frame back into a picture. A genuinely unreachable host still fails, twice.
     */
    @Nullable
    private byte[] downloadWithRetry(@NonNull String url) throws Exception {
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                return download(url);
            } catch (Exception e) {
                if (attempt == DOWNLOAD_ATTEMPTS) {
                    throw e;
                }
                Log.w(TAG, "Attempt " + attempt + " failed for " + url + " (" + e.getMessage()
                        + "), retrying");
            }
        }
        return null;
    }

    @Nullable
    private byte[] download(@NonNull String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "HTTP " + status + " for " + url);
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Decodes downsampled, so a 4000px marketing asset does not blow up the heap.
     *
     * <p>The sample size is the largest power of two that still leaves the image <b>at or above</b>
     * the screen's long edge - never below it. Halving one step too far is the classic version of
     * this bug: with a 1440px cap, a 1080x1920 story asset would decode at 540x960 and look
     * visibly soft stretched back over a 1080p screen.</p>
     */
    @Nullable
    private Bitmap decode(@NonNull byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / (sample * 2) >= targetLongEdgePx) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        // Stories are full-bleed opaque photographs, so dropping the alpha channel halves the
        // memory each cached frame costs. Assets that rely on transparency will composite onto
        // black - send opaque images.
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private File cacheFileFor(@NonNull String url) {
        return new File(cacheDir, hash(url));
    }

    private static String hash(@NonNull String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(value.hashCode());
        }
    }

    private static byte[] readFile(@NonNull File file) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void writeFile(@NonNull File file, @NonNull byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (Exception e) {
            Log.w(TAG, "Could not cache image to disk: " + e.getMessage());
        }
    }
}
