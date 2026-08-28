package com.example.instastylepe.stories.analytics;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.clevertap.android.sdk.ActivityLifecycleCallback;
import com.clevertap.android.sdk.CleverTapAPI;
import com.clevertap.android.sdk.displayunits.DisplayUnitListener;
import com.clevertap.android.sdk.displayunits.model.CleverTapDisplayUnit;

import java.util.ArrayList;
import java.util.Map;

/**
 * Single point of contact with the CleverTap SDK.
 *
 * <p>Everything the rest of the app does to CleverTap goes through here, for two reasons: the
 * instance can legitimately be {@code null} (a demo build with no credentials filled in yet must
 * still run), and a demo is much easier to read when every SDK call sits in one file you can
 * point at during a walkthrough.</p>
 */
public final class CleverTapManager {

    public static final String TAG = "InstaStyleCT";

    @Nullable
    private static CleverTapAPI cleverTap;

    private CleverTapManager() {
    }

    /**
     * Must be called from {@link Application#attachBaseContext(Context)}.
     *
     * <p>Registering the activity lifecycle callback before {@code super.onCreate()} is what lets
     * the SDK raise <em>App Launched</em> and run its in-app/Native Display fetch on its own. If
     * this moves later in startup, the very first launch silently loses that fetch.</p>
     */
    public static void registerLifecycle(@NonNull Application application) {
        ActivityLifecycleCallback.register(application);
    }

    /** Called from {@link Application#onCreate()}, after {@link #registerLifecycle}. */
    public static void init(@NonNull Context context) {
        CleverTapAPI.setDebugLevel(CleverTapAPI.LogLevel.VERBOSE);
        cleverTap = CleverTapAPI.getDefaultInstance(context);
        if (cleverTap == null) {
            Log.e(TAG, "CleverTap instance is null. Set CLEVERTAP_ACCOUNT_ID and CLEVERTAP_TOKEN "
                    + "in gradle.properties, then rebuild. The app will run on the bundled "
                    + "sample tray until then.");
        } else {
            Log.i(TAG, "CleverTap initialised.");
        }
    }

    public static boolean isReady() {
        return cleverTap != null;
    }

    @Nullable
    public static CleverTapAPI instance() {
        return cleverTap;
    }

    // ------------------------------------------------------------------ Native Display

    /**
     * Registers the Native Display callback. The listener fires when the SDK finishes processing
     * a response that carried display units - which is <em>not</em> synchronous with the event
     * that triggered the campaign, so the UI must never block waiting on it.
     */
    public static void setDisplayUnitListener(@Nullable DisplayUnitListener listener) {
        if (cleverTap == null) {
            return;
        }
        cleverTap.setDisplayUnitListener(listener);
    }

    /**
     * Display units the SDK already holds, including ones cached from a previous launch. Reading
     * this on screen entry is what makes the tray appear instantly on the second launch instead
     * of after a network round trip.
     */
    @Nullable
    public static ArrayList<CleverTapDisplayUnit> cachedDisplayUnits() {
        if (cleverTap == null) {
            return null;
        }
        return cleverTap.getAllDisplayUnits();
    }

    /**
     * Reports the display unit as seen. This is what fills in <b>Impressions</b> on the Native
     * Display campaign report - the custom events in {@link StoryAnalytics} do not do it.
     */
    public static void pushDisplayUnitViewed(@Nullable String unitId) {
        if (cleverTap == null || unitId == null || unitId.isEmpty()) {
            return;
        }
        cleverTap.pushDisplayUnitViewedEventForID(unitId);
        Log.d(TAG, "Display unit viewed: " + unitId);
    }

    /**
     * Reports a click on the display unit, filling in <b>Clicks</b> / CTR on the campaign report.
     */
    public static void pushDisplayUnitClicked(@Nullable String unitId) {
        if (cleverTap == null || unitId == null || unitId.isEmpty()) {
            return;
        }
        cleverTap.pushDisplayUnitClickedEventForID(unitId);
        Log.d(TAG, "Display unit clicked: " + unitId);
    }

    // ------------------------------------------------------------------------- events

    public static void pushEvent(@NonNull String name, @NonNull Map<String, Object> properties) {
        Log.d(TAG, "Event: " + name + " " + properties);
        if (cleverTap == null) {
            return;
        }
        cleverTap.pushEvent(name, properties);
    }

    public static void pushEvent(@NonNull String name) {
        Log.d(TAG, "Event: " + name);
        if (cleverTap == null) {
            return;
        }
        cleverTap.pushEvent(name);
    }

    // ----------------------------------------------------------------------- profile

    /**
     * Identifies the demo user so the walkthrough can show the profile's Events and User
     * Properties tabs filling up live.
     */
    public static void onUserLogin(@NonNull Map<String, Object> profile) {
        if (cleverTap == null) {
            return;
        }
        cleverTap.onUserLogin(profile);
    }

    public static void incrementValue(@NonNull String key, int by) {
        Log.d(TAG, "Profile increment: " + key + " +" + by);
        if (cleverTap == null) {
            return;
        }
        cleverTap.incrementValue(key, by);
    }

    public static void decrementValue(@NonNull String key, int by) {
        Log.d(TAG, "Profile decrement: " + key + " -" + by);
        if (cleverTap == null) {
            return;
        }
        cleverTap.decrementValue(key, by);
    }

    public static void addMultiValue(@NonNull String key, @NonNull String value) {
        Log.d(TAG, "Profile multi-value add: " + key + " += " + value);
        if (cleverTap == null) {
            return;
        }
        cleverTap.addMultiValueForKey(key, value);
    }

    public static void removeMultiValue(@NonNull String key, @NonNull String value) {
        Log.d(TAG, "Profile multi-value remove: " + key + " -= " + value);
        if (cleverTap == null) {
            return;
        }
        cleverTap.removeMultiValueForKey(key, value);
    }
}
