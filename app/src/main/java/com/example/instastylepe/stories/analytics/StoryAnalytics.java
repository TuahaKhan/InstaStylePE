package com.example.instastylepe.stories.analytics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.instastylepe.stories.model.Story;
import com.example.instastylepe.stories.model.StoryCircle;
import com.example.instastylepe.stories.model.StoryTray;

import java.util.HashMap;
import java.util.Map;

/**
 * The entire analytics contract for the stories feature: <b>four</b> custom events.
 *
 * <pre>
 *   Story Tray Rendered   - the tray was painted on the home screen        (funnel step 1)
 *   Story Circle Opened   - a circle was opened, by tap or by auto-advance (funnel step 2)
 *   Story Viewed          - one story frame was shown                      (funnel step 3)
 *   Story Interacted      - like / like removed / share / link click       (funnel step 4)
 * </pre>
 *
 * <p><b>Why so few.</b> Distinct event names are the scarce resource on a CleverTap account -
 * they clutter every dropdown, they are capped per account, and they cannot be merged after the
 * fact. Event <em>properties</em> are not scarce and can be pivoted freely on a board. So the
 * fan-out lives in properties: one {@code Story Viewed} event carries which circle, which
 * position, how long it was watched and how it ended, and one {@code Story Interacted} event
 * carries {@code action = like | like_removed | share | link_click}. Adding a fifth interaction
 * type later - save, follow, mute - costs a new property value, not a new event.</p>
 *
 * <p><b>What is deliberately not sent.</b> Image URLs. They are long, they change every campaign,
 * and as a property value they would produce an unbounded set of distinct values that is useless
 * to segment on. {@code story_id} identifies the frame; the URL stays in the payload.</p>
 *
 * <p>These four are on top of the Native Display campaign's own impression and click counters,
 * which are raised through {@link CleverTapManager#pushDisplayUnitViewed} and
 * {@link CleverTapManager#pushDisplayUnitClicked} and are what populate the campaign report.</p>
 */
public final class StoryAnalytics {

    // ------------------------------------------------------------------ event names

    public static final String EVENT_TRAY_RENDERED = "Story Tray Rendered";
    public static final String EVENT_CIRCLE_OPENED = "Story Circle Opened";
    public static final String EVENT_STORY_VIEWED = "Story Viewed";
    public static final String EVENT_STORY_INTERACTED = "Story Interacted";

    // ------------------------------------------------- Story Interacted: action values

    public static final String ACTION_LIKE = "like";
    /**
     * A like being taken back. Named "like_removed" rather than "unlike" on purpose: nothing is
     * being un-done on CleverTap's side, a second, separate event is being recorded.
     */
    public static final String ACTION_LIKE_REMOVED = "like_removed";
    public static final String ACTION_SHARE = "share";
    public static final String ACTION_LINK_CLICK = "link_click";

    // ------------------------------------------------ Story Viewed: exit_reason values

    public static final String EXIT_AUTO_COMPLETE = "auto_complete";
    public static final String EXIT_TAP_FORWARD = "tap_forward";
    public static final String EXIT_TAP_BACK = "tap_back";
    public static final String EXIT_SWIPE_NEXT_CIRCLE = "swipe_next_circle";
    public static final String EXIT_SWIPE_PREV_CIRCLE = "swipe_prev_circle";
    public static final String EXIT_CLOSED = "closed";
    public static final String EXIT_BACKGROUNDED = "backgrounded";

    // --------------------------------------------- Story Circle Opened: open_source values

    public static final String OPEN_SOURCE_TAP = "tray_tap";
    /** The previous circle ran out of stories and this one started by itself. */
    public static final String OPEN_SOURCE_AUTO_ADVANCE = "auto_advance";
    public static final String OPEN_SOURCE_SWIPE = "swipe";

    // ------------------------------------------------------------- profile properties

    /**
     * Net likes, incremented on a like and decremented when a like is taken back. Profile
     * arithmetic is the only place on CleverTap where a count can legitimately go down.
     */
    public static final String PROFILE_NET_LIKES = "story_likes_net";
    /**
     * The set of stories the user likes <em>right now</em>. {@code removeMultiValueForKey} takes a
     * value out again, so a segment built on this shrinks when a like is taken back.
     */
    public static final String PROFILE_LIKED_STORY_IDS = "liked_story_ids";
    public static final String PROFILE_STORIES_VIEWED = "stories_viewed_total";

    private StoryAnalytics() {
    }

    // -------------------------------------------------------------------- funnel step 1

    /**
     * @param latencyMs how long after screen entry the tray became paintable - the number that
     *                  tells you whether Native Display is fast enough to sit above the fold
     */
    public static void trayRendered(@NonNull StoryTray tray, long latencyMs, int unitCount) {
        Map<String, Object> props = new HashMap<>();
        props.put("payload_source", tray.source);
        props.put("tray_size", tray.circleCount());
        props.put("total_stories", tray.totalStoryCount());
        props.put("display_unit_count", unitCount);
        props.put("display_unit_ids", tray.unitIds());
        props.put("campaign_ids", tray.campaignIds());
        props.put("render_latency_ms", latencyMs);
        CleverTapManager.pushEvent(EVENT_TRAY_RENDERED, props);
    }

    // -------------------------------------------------------------------- funnel step 2

    /**
     * @param circleSeqInSession 1 for the first circle opened in this viewing session, 2 for the
     *                           next, and so on. This is the property that answers "how many
     *                           people went past the first circle" without a second event.
     */
    public static void circleOpened(@NonNull StoryTray tray,
            int circleIndex,
            @NonNull String openSource,
            int circleSeqInSession,
            boolean wasSeenBefore) {
        StoryCircle circle = tray.circleAt(circleIndex);
        if (circle == null) {
            return;
        }
        Map<String, Object> props = baseProps(tray, circle);
        props.put("open_source", openSource);
        props.put("circle_seq_in_session", circleSeqInSession);
        props.put("was_seen_before", wasSeenBefore);
        CleverTapManager.pushEvent(EVENT_CIRCLE_OPENED, props);
    }

    // -------------------------------------------------------------------- funnel step 3

    /**
     * One story frame finished being on screen.
     *
     * <p>Raised on <em>exit</em> rather than on entry, so it can carry how long the frame was
     * actually watched and how the user left it. That single event supports both "how many people
     * saw story 3 of circle 2" and "where do people drop off", which would otherwise be two
     * events.</p>
     */
    public static void storyViewed(@NonNull StoryTray tray,
            int circleIndex,
            int storyIndex,
            long dwellMs,
            boolean completed,
            @NonNull String exitReason,
            boolean likedAtExit,
            int storySeqInSession) {
        StoryCircle circle = tray.circleAt(circleIndex);
        if (circle == null) {
            return;
        }
        Story story = circle.storyAt(storyIndex);
        if (story == null) {
            return;
        }
        Map<String, Object> props = baseProps(tray, circle);
        props.put("story_id", story.id);
        props.put("story_position", storyIndex + 1);
        props.put("story_duration_secs", story.durationSeconds);
        props.put("dwell_ms", dwellMs);
        props.put("completed", completed);
        props.put("exit_reason", exitReason);
        props.put("liked", likedAtExit);
        props.put("is_last_in_circle", storyIndex == circle.storyCount() - 1);
        props.put("story_seq_in_session", storySeqInSession);
        CleverTapManager.pushEvent(EVENT_STORY_VIEWED, props);

        CleverTapManager.incrementValue(PROFILE_STORIES_VIEWED, 1);
    }

    // -------------------------------------------------------------------- funnel step 4

    /**
     * Every in-story interaction, discriminated by {@code action}.
     *
     * @param action one of {@link #ACTION_LIKE}, {@link #ACTION_LIKE_REMOVED},
     *               {@link #ACTION_SHARE}, {@link #ACTION_LINK_CLICK}
     */
    public static void storyInteracted(@NonNull String action,
            @NonNull StoryTray tray,
            int circleIndex,
            int storyIndex,
            long timeIntoStoryMs,
            @Nullable Boolean likeStateAfter,
            @Nullable Integer netLikesAfter,
            @Nullable String shareChannel) {
        StoryCircle circle = tray.circleAt(circleIndex);
        if (circle == null) {
            return;
        }
        Story story = circle.storyAt(storyIndex);
        if (story == null) {
            return;
        }
        Map<String, Object> props = baseProps(tray, circle);
        props.put("action", action);
        props.put("story_id", story.id);
        props.put("story_position", storyIndex + 1);
        props.put("time_into_story_ms", timeIntoStoryMs);
        if (likeStateAfter != null) {
            props.put("like_state_after", likeStateAfter);
        }
        if (netLikesAfter != null) {
            props.put("net_likes_after", netLikesAfter);
        }
        if (shareChannel != null) {
            props.put("share_channel", shareChannel);
        }
        if (ACTION_LINK_CLICK.equals(action) && story.deeplink != null
                && !story.deeplink.isEmpty()) {
            props.put("deeplink", story.deeplink);
        }
        CleverTapManager.pushEvent(EVENT_STORY_INTERACTED, props);
    }

    /**
     * The decrementable half of the like feature.
     *
     * <p>Events can only ever be appended, so {@code count(action=like)} never goes down. The
     * user's <em>current</em> like state therefore lives on the profile instead: a counter that
     * increments and decrements, and a multi-value property that stories are added to and removed
     * from. A segment on either one shrinks when a like is taken back, which is what the client
     * actually wants when they ask for the count to go down.</p>
     */
    public static void syncLikeToProfile(@NonNull Story story, boolean likedAfter) {
        if (likedAfter) {
            CleverTapManager.incrementValue(PROFILE_NET_LIKES, 1);
            CleverTapManager.addMultiValue(PROFILE_LIKED_STORY_IDS, story.id);
        } else {
            CleverTapManager.decrementValue(PROFILE_NET_LIKES, 1);
            CleverTapManager.removeMultiValue(PROFILE_LIKED_STORY_IDS, story.id);
        }
    }

    // ---------------------------------------------------------------------- shared props

    /**
     * Properties every story event carries, so any event can be sliced by campaign or by circle
     * without joining anything.
     */
    private static Map<String, Object> baseProps(@NonNull StoryTray tray,
            @NonNull StoryCircle circle) {
        Map<String, Object> props = new HashMap<>();
        props.put("payload_source", tray.source);
        props.put("campaign_id", circle.campaignId == null ? "" : circle.campaignId);
        props.put("display_unit_id", circle.unitId == null ? "" : circle.unitId);
        props.put("circle_id", circle.id);
        props.put("circle_name", circle.name);
        props.put("circle_position", circle.order);
        props.put("circle_story_count", circle.storyCount());
        props.put("tray_size", tray.circleCount());
        return props;
    }
}
