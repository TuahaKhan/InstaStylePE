package com.example.instastylepe.stories.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Local, device-side story state: which circles have been watched (grey vs. gradient ring) and
 * which stories the user currently likes.
 *
 * <p>The like set is mirrored to the CleverTap profile as a multi-value property, so the
 * dashboard can answer "how many users <em>currently</em> like story X" - a number that goes down
 * again when a like is removed. This class is the device-side half of that pair; it exists so the
 * heart renders correctly on re-open without waiting on a network round trip.</p>
 */
public class StoryStateStore {

    private static final String PREFS = "instastyle_story_state";
    private static final String KEY_SEEN_STORIES = "seen_stories";
    private static final String KEY_LIKED_STORIES = "liked_stories";

    private final SharedPreferences prefs;

    public StoryStateStore(@NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------- seen state

    public boolean isStorySeen(String storyId) {
        return read(KEY_SEEN_STORIES).contains(storyId);
    }

    public void markStorySeen(String storyId) {
        add(KEY_SEEN_STORIES, storyId);
    }

    /**
     * A circle is "fully watched" - and therefore drawn with a grey ring - only once every story
     * inside it has been seen. Matches Instagram's behaviour.
     */
    public boolean areAllSeen(Iterable<String> storyIds) {
        Set<String> seen = read(KEY_SEEN_STORIES);
        boolean any = false;
        for (String id : storyIds) {
            any = true;
            if (!seen.contains(id)) {
                return false;
            }
        }
        return any;
    }

    // ------------------------------------------------------------------- like state

    public boolean isLiked(String storyId) {
        return read(KEY_LIKED_STORIES).contains(storyId);
    }

    /**
     * Flips the like for a story.
     *
     * @return {@code true} if the story is liked after the toggle
     */
    public boolean toggleLike(String storyId) {
        if (isLiked(storyId)) {
            remove(KEY_LIKED_STORIES, storyId);
            return false;
        }
        add(KEY_LIKED_STORIES, storyId);
        return true;
    }

    /** Net likes this device currently holds - the local mirror of the profile counter. */
    public int likedCount() {
        return read(KEY_LIKED_STORIES).size();
    }

    /**
     * Demo affordance: puts every ring back to unwatched and drops local likes.
     *
     * <p>Only clears device state. The CleverTap profile keeps its counters, which is the honest
     * behaviour to show a client - the analytics record of what happened does not disappear
     * because the app cleared its cache.</p>
     */
    public void clearAll() {
        prefs.edit().remove(KEY_SEEN_STORIES).remove(KEY_LIKED_STORIES).apply();
    }

    // ---------------------------------------------------------------------- plumbing

    private Set<String> read(String key) {
        // SharedPreferences returns its own internal set; copy before handing it out or mutating.
        return new HashSet<>(prefs.getStringSet(key, new HashSet<>()));
    }

    private void add(String key, String value) {
        Set<String> values = read(key);
        if (values.add(value)) {
            prefs.edit().putStringSet(key, values).apply();
        }
    }

    private void remove(String key, String value) {
        Set<String> values = read(key);
        if (values.remove(value)) {
            prefs.edit().putStringSet(key, values).apply();
        }
    }
}
