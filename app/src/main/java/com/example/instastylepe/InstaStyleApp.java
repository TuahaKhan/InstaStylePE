package com.example.instastylepe;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.example.instastylepe.stories.analytics.CleverTapManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Application entry point. Two things happen here and nowhere else: the CleverTap activity
 * lifecycle callback is registered before anything else runs, and the demo user is identified.
 */
public class InstaStyleApp extends Application {

    private static final String PREFS = "instastyle_demo_user";
    private static final String KEY_IDENTITY = "identity";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Must be the first CleverTap call in the process. Registering here is what lets the SDK
        // see the very first Activity creation and run its own App Launched / campaign fetch.
        CleverTapManager.registerLifecycle(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CleverTapManager.init(this);
        identifyDemoUser();
    }

    /**
     * Gives the demo a stable profile so a walkthrough can open the same user on the dashboard
     * across launches and watch its events and user properties accumulate.
     */
    private void identifyDemoUser() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String identity = prefs.getString(KEY_IDENTITY, null);
        if (identity == null) {
            identity = "instastyle_demo_" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(KEY_IDENTITY, identity).apply();
        }
        Map<String, Object> profile = new HashMap<>();
        profile.put("Identity", identity);
        profile.put("Name", "InstaStyle Demo User");
        profile.put("Email", identity + "@example.com");
        profile.put("demo_app", "InstaStyle Stories");
        CleverTapManager.onUserLogin(profile);
    }
}
