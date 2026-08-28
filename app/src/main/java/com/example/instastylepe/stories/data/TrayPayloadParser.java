package com.example.instastylepe.stories.data;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.instastylepe.stories.model.Story;
import com.example.instastylepe.stories.model.StoryCircle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Native Display custom key-value payload into story circles.
 *
 * <p>CleverTap hands custom KV to the SDK as a <em>flat</em> {@code String -> String} map
 * ({@code CleverTapDisplayUnit#getCustomExtras()}), so a nested tray has to be expressed one of
 * two ways. Both are supported here, because they suit different marketers:</p>
 *
 * <ol>
 *   <li><b>Single JSON key</b> - one KV pair, {@code st_tray}, whose value is the whole tray as a
 *       JSON string. Best when the tray is authored/generated once and pasted in.</li>
 *   <li><b>Flat indexed keys</b> - {@code c1_name}, {@code c1_s1_img}, {@code c1_s1_dur}, ... One
 *       KV row per field. Best when a marketer edits individual stories by hand in the dashboard,
 *       because a typo breaks one field instead of the entire payload.</li>
 * </ol>
 *
 * <p>Parsing is deliberately forgiving: unknown keys are ignored, aliases are accepted, and a
 * circle with no usable image is dropped rather than rendered blank. A marketer mistake should
 * cost one circle, never the whole screen.</p>
 */
public final class TrayPayloadParser {

    private static final String TAG = "StoryPayload";

    /** Keys that may carry the whole tray as a JSON string. First match wins. */
    private static final String[] JSON_KEYS = {"st_tray", "story_tray", "stories", "tray"};

    /**
     * Matches {@code c1_...} / {@code circle1_...} / {@code circle_1_...}, optionally followed by
     * {@code s2_...} / {@code story2_...} / {@code story_2_...}, then the field name.
     */
    private static final Pattern FLAT_KEY = Pattern.compile(
            "^c(?:ircle)?_?(\\d+)_(?:s(?:tory)?_?(\\d+)_)?(.+)$");

    private TrayPayloadParser() {
    }

    /**
     * @param customExtras the display unit's custom KV map
     * @param unitId       display unit id ({@code wzrk_id}), carried into every event
     * @return circles authored by this unit, ordered; empty when the payload is not a story tray
     */
    @NonNull
    public static List<StoryCircle> parse(@Nullable Map<String, String> customExtras,
            @Nullable String unitId) {
        if (customExtras == null || customExtras.isEmpty()) {
            return new ArrayList<>();
        }
        String campaignId = campaignIdFromUnitId(unitId);

        for (String jsonKey : JSON_KEYS) {
            String raw = valueForKey(customExtras, jsonKey);
            if (!isBlank(raw)) {
                List<StoryCircle> circles = parseJson(raw, unitId, campaignId);
                if (!circles.isEmpty()) {
                    return circles;
                }
                Log.w(TAG, "Key '" + jsonKey + "' was present but yielded no circles");
            }
        }
        return parseFlatKeys(customExtras, unitId, campaignId);
    }

    /**
     * A display unit id is {@code <campaignId>_<yyyyMMdd>}. The campaign id is the more useful
     * half for reporting, because it is stable across the daily id rotation.
     */
    public static String campaignIdFromUnitId(@Nullable String unitId) {
        if (isBlank(unitId)) {
            return "";
        }
        int separator = unitId.indexOf('_');
        return separator > 0 ? unitId.substring(0, separator) : unitId;
    }

    // ------------------------------------------------------------------ JSON schema

    /**
     * Accepts either {@code {"circles":[...]}} or a bare {@code [...]} array of circles.
     */
    @NonNull
    public static List<StoryCircle> parseJson(@Nullable String raw,
            @Nullable String unitId,
            @Nullable String campaignId) {
        List<StoryCircle> circles = new ArrayList<>();
        if (isBlank(raw)) {
            return circles;
        }
        try {
            String trimmed = raw.trim();
            JSONArray array;
            if (trimmed.startsWith("[")) {
                array = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                array = root.optJSONArray("circles");
                if (array == null) {
                    array = root.optJSONArray("items");
                }
            }
            if (array == null) {
                Log.w(TAG, "Tray JSON has no 'circles' array");
                return circles;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject circleJson = array.optJSONObject(i);
                if (circleJson == null) {
                    continue;
                }
                StoryCircle circle = circleFromJson(circleJson, i, unitId, campaignId);
                if (circle != null) {
                    circles.add(circle);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Malformed tray JSON: " + e.getMessage());
            return new ArrayList<>();
        }
        sortByOrder(circles);
        return circles;
    }

    @Nullable
    private static StoryCircle circleFromJson(JSONObject json,
            int index,
            @Nullable String unitId,
            @Nullable String campaignId) {
        String id = firstNonEmpty(json.optString("id"), "circle_" + (index + 1));
        String name = firstNonEmpty(json.optString("name"), json.optString("title"), id);
        String avatar = firstNonEmpty(json.optString("avatar"), json.optString("cover"),
                json.optString("icon"));
        int order = json.optInt("order", index + 1);
        String ring = firstNonEmpty(json.optString("ring"), json.optString("ring_color"),
                json.optString("color"));

        JSONArray storiesJson = json.optJSONArray("stories");
        if (storiesJson == null) {
            storiesJson = json.optJSONArray("frames");
        }
        List<Story> stories = new ArrayList<>();
        if (storiesJson != null) {
            for (int i = 0; i < storiesJson.length(); i++) {
                JSONObject storyJson = storiesJson.optJSONObject(i);
                if (storyJson == null) {
                    continue;
                }
                String image = firstNonEmpty(storyJson.optString("image"),
                        storyJson.optString("img"), storyJson.optString("media"),
                        storyJson.optString("image_url"));
                if (isBlank(image)) {
                    Log.w(TAG, "Skipping story " + (i + 1) + " of circle '" + id + "': no image");
                    continue;
                }
                stories.add(new Story(
                        firstNonEmpty(storyJson.optString("id"), id + "_s" + (i + 1)),
                        image,
                        storyJson.optInt("duration", storyJson.optInt("dur",
                                Story.DEFAULT_DURATION_SECONDS)),
                        firstNonEmpty(storyJson.optString("deeplink"), storyJson.optString("link"),
                                storyJson.optString("action_url")),
                        firstNonEmpty(storyJson.optString("caption"), storyJson.optString("text")),
                        storyJson.optBoolean("like", true),
                        storyJson.optBoolean("share", true),
                        storyJson.optInt("likes", 0)));
            }
        }
        if (stories.isEmpty()) {
            Log.w(TAG, "Dropping circle '" + id + "': no usable stories");
            return null;
        }
        return new StoryCircle(id, name, avatar, order, ring, unitId, campaignId, stories);
    }

    // ------------------------------------------------------------ flat indexed schema

    @NonNull
    private static List<StoryCircle> parseFlatKeys(@NonNull Map<String, String> kv,
            @Nullable String unitId,
            @Nullable String campaignId) {
        // TreeMap keeps circles and stories in numeric-ish key order while we accumulate them.
        Map<Integer, CircleBuilder> builders = new TreeMap<>();

        for (Map.Entry<String, String> entry : kv.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String key = entry.getKey().trim().toLowerCase(Locale.US);
            Matcher matcher = FLAT_KEY.matcher(key);
            if (!matcher.matches()) {
                continue;
            }
            int circleIndex = parseIntOr(matcher.group(1), -1);
            if (circleIndex < 0) {
                continue;
            }
            String storyGroup = matcher.group(2);
            String field = matcher.group(3);
            String value = entry.getValue().trim();

            CircleBuilder builder = builders.get(circleIndex);
            if (builder == null) {
                builder = new CircleBuilder(circleIndex);
                builders.put(circleIndex, builder);
            }
            if (storyGroup == null) {
                builder.applyCircleField(field, value);
            } else {
                builder.applyStoryField(parseIntOr(storyGroup, -1), field, value);
            }
        }

        List<StoryCircle> circles = new ArrayList<>();
        for (CircleBuilder builder : builders.values()) {
            StoryCircle circle = builder.build(unitId, campaignId);
            if (circle != null) {
                circles.add(circle);
            }
        }
        sortByOrder(circles);
        return circles;
    }

    /** Accumulates the flat KV rows belonging to one circle. */
    private static final class CircleBuilder {

        private final int index;
        private final Map<Integer, StoryBuilder> stories = new TreeMap<>();
        private String id;
        private String name;
        private String avatar;
        private String ring;
        private int order = Integer.MIN_VALUE;

        CircleBuilder(int index) {
            this.index = index;
        }

        void applyCircleField(String field, String value) {
            switch (field) {
                case "id":
                    id = value;
                    break;
                case "name":
                case "title":
                case "label":
                    name = value;
                    break;
                case "avatar":
                case "cover":
                case "icon":
                case "img":
                case "image":
                    avatar = value;
                    break;
                case "ring":
                case "ring_color":
                case "color":
                    ring = value;
                    break;
                case "order":
                case "pos":
                case "position":
                    order = parseIntOr(value, Integer.MIN_VALUE);
                    break;
                default:
                    // count/story_count and anything else is derived or simply not ours.
                    break;
            }
        }

        void applyStoryField(int storyIndex, String field, String value) {
            if (storyIndex < 0) {
                return;
            }
            StoryBuilder builder = stories.get(storyIndex);
            if (builder == null) {
                builder = new StoryBuilder(storyIndex);
                stories.put(storyIndex, builder);
            }
            builder.apply(field, value);
        }

        @Nullable
        StoryCircle build(@Nullable String unitId, @Nullable String campaignId) {
            String circleId = firstNonEmpty(id, "circle_" + index);
            List<Story> built = new ArrayList<>();
            for (StoryBuilder storyBuilder : stories.values()) {
                Story story = storyBuilder.build(circleId);
                if (story != null) {
                    built.add(story);
                }
            }
            if (built.isEmpty()) {
                Log.w(TAG, "Dropping circle '" + circleId + "': no usable stories");
                return null;
            }
            return new StoryCircle(circleId, firstNonEmpty(name, circleId), avatar,
                    order == Integer.MIN_VALUE ? index : order, ring, unitId, campaignId, built);
        }
    }

    /** Accumulates the flat KV rows belonging to one story frame. */
    private static final class StoryBuilder {

        private final int index;
        private String id;
        private String image;
        private String deeplink;
        private String caption;
        private int duration = Story.DEFAULT_DURATION_SECONDS;
        private int likes;
        private boolean likeEnabled = true;
        private boolean shareEnabled = true;

        StoryBuilder(int index) {
            this.index = index;
        }

        void apply(String field, String value) {
            switch (field) {
                case "id":
                    id = value;
                    break;
                case "img":
                case "image":
                case "image_url":
                case "media":
                case "photo":
                    image = value;
                    break;
                case "dur":
                case "duration":
                case "duration_secs":
                case "seconds":
                case "time":
                    duration = parseIntOr(value, Story.DEFAULT_DURATION_SECONDS);
                    break;
                case "link":
                case "deeplink":
                case "deep_link":
                case "action":
                case "action_url":
                case "cta":
                    deeplink = value;
                    break;
                case "cap":
                case "caption":
                case "text":
                case "message":
                    caption = value;
                    break;
                case "like":
                case "like_enabled":
                case "show_like":
                    likeEnabled = parseBool(value, true);
                    break;
                case "share":
                case "share_enabled":
                case "show_share":
                    shareEnabled = parseBool(value, true);
                    break;
                case "likes":
                case "like_count":
                case "base_likes":
                    likes = parseIntOr(value, 0);
                    break;
                default:
                    break;
            }
        }

        @Nullable
        Story build(String circleId) {
            if (isBlank(image)) {
                Log.w(TAG, "Skipping story " + index + " of circle '" + circleId + "': no image");
                return null;
            }
            return new Story(firstNonEmpty(id, circleId + "_s" + index), image, duration,
                    deeplink, caption, likeEnabled, shareEnabled, likes);
        }
    }

    // ---------------------------------------------------------------------- helpers

    private static void sortByOrder(List<StoryCircle> circles) {
        // Stable sort, so circles the marketer gave the same order to keep payload order.
        circles.sort(Comparator.comparingInt(circle -> circle.order));
    }

    /** Case-insensitive lookup, because dashboard KV keys get typed by hand. */
    @Nullable
    private static String valueForKey(Map<String, String> kv, String key) {
        String direct = kv.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Pure-Java stand-in for {@code TextUtils.isEmpty}, so the parser has no Android framework
     * dependency beyond logging and can be unit tested on the JVM.
     */
    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"null".equals(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean parseBool(String value, boolean fallback) {
        if (isBlank(value)) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return fallback;
    }
}
