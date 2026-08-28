package com.example.instastylepe.stories.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.clevertap.android.sdk.displayunits.DisplayUnitListener;
import com.clevertap.android.sdk.displayunits.model.CleverTapDisplayUnit;
import com.clevertap.android.sdk.displayunits.model.CleverTapDisplayUnitContent;
import com.example.instastylepe.BuildConfig;
import com.example.instastylepe.stories.analytics.CleverTapManager;
import com.example.instastylepe.stories.model.Story;
import com.example.instastylepe.stories.model.StoryCircle;
import com.example.instastylepe.stories.model.StoryTray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the Native Display side of the feature: gets display units out of the SDK, turns them into
 * a tray, and tells the UI. Nothing above this class knows CleverTap types exist.
 *
 * <p>Three things about Native Display shape this class:</p>
 *
 * <ul>
 *   <li><b>Delivery is asynchronous and not tied to the triggering event.</b> Raising the trigger
 *       event does not make units available on the next line; they arrive on
 *       {@link DisplayUnitListener} whenever the SDK's next response carries them. So the UI is
 *       driven by a callback and never blocks.</li>
 *   <li><b>Units are cached on device.</b> {@code getAllDisplayUnits()} returns what the SDK
 *       already holds, so a returning user sees the tray immediately, offline included.</li>
 *   <li><b>There can be more than one unit.</b> Rather than assume the one-campaign-per-event
 *       shape, every unit that parses as a story tray is merged into a single tray, ordered by the
 *       marketer's own {@code order} field. One campaign works; three also work, and the client
 *       can split their tray across campaigns with different audiences if they ever want to.</li>
 * </ul>
 */
public class NativeDisplayRepository implements DisplayUnitListener {

    /**
     * How long to wait for a live campaign before falling back to the bundled sample tray. Only
     * matters for the demo - a real app would simply render nothing until units arrive.
     */
    private static final long FALLBACK_DELAY_MS = 2500L;

    private static final String SAMPLE_ASSET = "sample_tray.json";
    private static final String TAG = CleverTapManager.TAG;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor ioExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean deliveredFromCampaign = new AtomicBoolean(false);

    @Nullable
    private Callback callback;
    private long startedAtUptimeMs;

    public NativeDisplayRepository(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface Callback {

        /**
         * @param tray      the merged tray, never null but possibly empty
         * @param unitCount how many display units contributed to it (0 for the sample tray)
         * @param latencyMs time from {@link #start} to this callback
         */
        @MainThread
        void onTrayReady(@NonNull StoryTray tray, int unitCount, long latencyMs);
    }

    /**
     * Registers for display units, replays anything cached, raises the campaign's trigger event,
     * and arms the sample-tray fallback.
     */
    @MainThread
    public void start(@NonNull Callback callback) {
        this.callback = callback;
        this.startedAtUptimeMs = SystemClock.uptimeMillis();
        deliveredFromCampaign.set(false);

        CleverTapManager.setDisplayUnitListener(this);

        // Cached units first: on any launch after the first, this paints the tray with no wait.
        ArrayList<CleverTapDisplayUnit> cached = CleverTapManager.cachedDisplayUnits();
        if (cached != null && !cached.isEmpty()) {
            Log.i(TAG, "Found " + cached.size() + " cached display unit(s)");
            handleUnits(cached);
        }

        // Trigger event for the Native Display campaign. Raised after registering the listener so
        // the resulting units cannot arrive before anyone is listening.
        CleverTapManager.pushEvent(BuildConfig.STORY_TRIGGER_EVENT);

        mainHandler.postDelayed(this::deliverFallbackIfNeeded, FALLBACK_DELAY_MS);
    }

    @MainThread
    public void stop() {
        callback = null;
        CleverTapManager.setDisplayUnitListener(null);
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------- DisplayUnitListener

    @Override
    public void onDisplayUnitsLoaded(ArrayList<CleverTapDisplayUnit> units) {
        // Not guaranteed to be the main thread, so bounce before touching UI state.
        mainHandler.post(() -> handleUnits(units));
    }

    @MainThread
    private void handleUnits(@Nullable ArrayList<CleverTapDisplayUnit> units) {
        if (units == null || units.isEmpty()) {
            Log.i(TAG, "onDisplayUnitsLoaded with no units");
            return;
        }
        // Circle id is the merge key, so the same circle arriving twice (cache plus a fresh
        // response) updates in place instead of showing up as a duplicate ring.
        Map<String, StoryCircle> byId = new LinkedHashMap<>();
        int contributingUnits = 0;

        for (CleverTapDisplayUnit unit : units) {
            if (unit == null || !TextUtils.isEmpty(unit.getError())) {
                Log.w(TAG, "Skipping display unit with error: "
                        + (unit == null ? "null" : unit.getError()));
                continue;
            }
            List<StoryCircle> circles = TrayPayloadParser.parse(unit.getCustomExtras(),
                    unit.getUnitID());
            if (circles.isEmpty()) {
                // The marketer may have built the campaign with an image-carousel template
                // instead of custom key-values; treat its contents as one circle.
                circles = circlesFromContents(unit);
            }
            if (circles.isEmpty()) {
                Log.w(TAG, "Display unit " + unit.getUnitID() + " is not a story tray");
                continue;
            }
            contributingUnits++;
            for (StoryCircle circle : circles) {
                byId.put(circle.id, circle);
            }
        }

        if (byId.isEmpty()) {
            return;
        }
        List<StoryCircle> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparingInt(circle -> circle.order));

        deliveredFromCampaign.set(true);
        deliver(new StoryTray(merged, StoryTray.SOURCE_NATIVE_DISPLAY), contributingUnits);
    }

    /**
     * Builds a single circle out of a display unit's {@code content} array. Lets the demo survive
     * a campaign authored with a carousel-image template rather than custom key-values.
     */
    @NonNull
    private List<StoryCircle> circlesFromContents(@NonNull CleverTapDisplayUnit unit) {
        List<StoryCircle> circles = new ArrayList<>();
        ArrayList<CleverTapDisplayUnitContent> contents = unit.getContents();
        if (contents == null || contents.isEmpty()) {
            return circles;
        }
        List<Story> stories = new ArrayList<>();
        String circleName = null;
        for (int i = 0; i < contents.size(); i++) {
            CleverTapDisplayUnitContent content = contents.get(i);
            if (content == null || !content.mediaIsImage()) {
                continue;
            }
            String media = content.getMedia();
            if (TextUtils.isEmpty(media)) {
                continue;
            }
            if (circleName == null && !TextUtils.isEmpty(content.getTitle())) {
                circleName = content.getTitle();
            }
            stories.add(new Story(unit.getUnitID() + "_s" + (i + 1), media,
                    Story.DEFAULT_DURATION_SECONDS, content.getActionUrl(), content.getMessage(),
                    true, true, 0));
        }
        if (stories.isEmpty()) {
            return circles;
        }
        String campaignId = TrayPayloadParser.campaignIdFromUnitId(unit.getUnitID());
        circles.add(new StoryCircle(unit.getUnitID(),
                circleName == null ? "Featured" : circleName, null, 1, null, unit.getUnitID(),
                campaignId, stories));
        return circles;
    }

    // ----------------------------------------------------------------------- fallback

    @MainThread
    private void deliverFallbackIfNeeded() {
        if (deliveredFromCampaign.get() || callback == null) {
            return;
        }
        Log.w(TAG, "No Native Display campaign delivered within " + FALLBACK_DELAY_MS
                + "ms - rendering bundled sample tray. Check that a Native Display campaign is "
                + "live and triggered on '" + BuildConfig.STORY_TRIGGER_EVENT + "'.");
        ioExecutor.execute(() -> {
            String json = readAsset(SAMPLE_ASSET);
            List<StoryCircle> circles = TrayPayloadParser.parseJson(json, "sample", "sample");
            mainHandler.post(() -> {
                if (deliveredFromCampaign.get()) {
                    // A campaign landed while we were reading the asset; it wins.
                    return;
                }
                deliver(new StoryTray(circles, StoryTray.SOURCE_FALLBACK), 0);
            });
        });
    }

    @Nullable
    private String readAsset(@NonNull String name) {
        try (InputStream in = appContext.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Could not read asset " + name + ": " + e.getMessage());
            return null;
        }
    }

    @MainThread
    private void deliver(@NonNull StoryTray tray, int unitCount) {
        if (callback == null) {
            return;
        }
        callback.onTrayReady(tray, unitCount, SystemClock.uptimeMillis() - startedAtUptimeMs);
    }
}
