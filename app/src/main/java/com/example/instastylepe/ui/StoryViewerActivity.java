package com.example.instastylepe.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;

import com.example.instastylepe.R;
import com.example.instastylepe.stories.analytics.StoryAnalytics;
import com.example.instastylepe.stories.data.StoryStateStore;
import com.example.instastylepe.stories.model.Story;
import com.example.instastylepe.stories.model.StoryCircle;
import com.example.instastylepe.stories.model.StoryTray;
import com.example.instastylepe.util.ImageLoader;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The full-screen story player.
 *
 * <p>Behaviour matches Instagram closely enough that a client recognises it without explanation:
 * frames auto-advance on a per-frame timer, finishing a circle rolls straight into the next
 * circle, tapping the right side skips forward and the left side goes back, holding pauses, and
 * swiping down closes.</p>
 *
 * <p>Every one of those transitions ends the current frame with a specific reason, and that reason
 * is what makes the analytics useful: {@code auto_complete} means the frame held attention for its
 * full duration, {@code tap_forward} means it did not. Both are the same
 * {@link StoryAnalytics#EVENT_STORY_VIEWED} event with a different {@code exit_reason}.</p>
 */
public class StoryViewerActivity extends AppCompatActivity {

    private static final String EXTRA_TRAY = "extra_tray";
    private static final String EXTRA_CIRCLE_INDEX = "extra_circle_index";

    /** ~60fps progress repaint. */
    private static final long TICK_MS = 16L;
    /** Touches shorter than this are taps; anything longer was a hold-to-pause. */
    private static final long TAP_MAX_MS = 250L;
    private static final int SWIPE_THRESHOLD_DP = 72;
    /** Left third goes back, the rest goes forward - same split Instagram uses. */
    private static final float BACK_ZONE_FRACTION = 0.32f;

    private final Handler ticker = new Handler(Looper.getMainLooper());

    private ImageView storyImage;
    private StoryProgressView progressView;
    private TextView circleNameView;
    private TextView storyCounterView;
    private TextView captionView;
    private TextView ctaView;
    private TextView likeCountView;
    private ImageView likeButton;
    private ImageView shareButton;

    private StoryTray tray;
    private StoryStateStore stateStore;
    private ImageLoader imageLoader;

    private int circleIndex;
    private int storyIndex;

    // Frame timing. elapsed accumulates only while playing; shownAt is wall clock, for dwell.
    private long storyElapsedMs;
    private long lastTickUptimeMs;
    private long storyShownAtUptimeMs;
    private boolean playing;
    private boolean currentStoryRecorded;

    // Session-scoped depth counters, reported as properties so the funnel needs no extra events.
    private int circleSeqInSession;
    private int storySeqInSession;

    private float downX;
    private float downY;
    private long downAtMs;
    private int swipeThresholdPx;
    private int touchSlopPx;

    public static Intent intentFor(@NonNull Context context,
            @NonNull StoryTray tray,
            int circleIndex) {
        Intent intent = new Intent(context, StoryViewerActivity.class);
        intent.putExtra(EXTRA_TRAY, tray);
        intent.putExtra(EXTRA_CIRCLE_INDEX, circleIndex);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_story_viewer);

        tray = IntentCompat.getParcelableExtra(getIntent(), EXTRA_TRAY, StoryTray.class);
        int startIndex = getIntent().getIntExtra(EXTRA_CIRCLE_INDEX, 0);
        if (tray == null || tray.isEmpty() || tray.circleAt(startIndex) == null) {
            finish();
            return;
        }

        stateStore = new StoryStateStore(this);
        imageLoader = ImageLoader.get(this);
        float density = getResources().getDisplayMetrics().density;
        swipeThresholdPx = (int) (SWIPE_THRESHOLD_DP * density);
        touchSlopPx = ViewConfiguration.get(this).getScaledTouchSlop();

        storyImage = findViewById(R.id.story_image);
        progressView = findViewById(R.id.story_progress);
        circleNameView = findViewById(R.id.circle_name);
        storyCounterView = findViewById(R.id.story_counter);
        captionView = findViewById(R.id.story_caption);
        ctaView = findViewById(R.id.story_cta);
        likeCountView = findViewById(R.id.like_count);
        likeButton = findViewById(R.id.like_button);
        shareButton = findViewById(R.id.share_button);

        findViewById(R.id.close_button).setOnClickListener(
                view -> closeViewer(StoryAnalytics.EXIT_CLOSED));
        likeButton.setOnClickListener(view -> onLikeTapped());
        shareButton.setOnClickListener(view -> onShareTapped());
        ctaView.setOnClickListener(view -> onCtaTapped());
        findViewById(R.id.viewer_root).setOnTouchListener(this::onViewerTouch);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                closeViewer(StoryAnalytics.EXIT_CLOSED);
            }
        });

        openCircle(startIndex, 0, StoryAnalytics.OPEN_SOURCE_TAP);
    }

    // ------------------------------------------------------------------- navigation

    private void openCircle(int index, int startStoryIndex, @NonNull String openSource) {
        StoryCircle circle = tray.circleAt(index);
        if (circle == null || circle.storyCount() == 0) {
            closeViewer(StoryAnalytics.EXIT_CLOSED);
            return;
        }
        circleIndex = index;
        circleSeqInSession++;

        List<String> storyIds = new ArrayList<>(circle.storyCount());
        for (Story story : circle.stories()) {
            storyIds.add(story.id);
        }
        boolean wasSeenBefore = stateStore.areAllSeen(storyIds);
        StoryAnalytics.circleOpened(tray, index, openSource, circleSeqInSession, wasSeenBefore);

        circleNameView.setText(circle.name);
        progressView.setSegments(circle.storyCount(), startStoryIndex);
        showStory(startStoryIndex);
    }

    private void showStory(int index) {
        StoryCircle circle = tray.circleAt(circleIndex);
        if (circle == null) {
            closeViewer(StoryAnalytics.EXIT_CLOSED);
            return;
        }
        Story story = circle.storyAt(index);
        if (story == null) {
            closeViewer(StoryAnalytics.EXIT_CLOSED);
            return;
        }
        storyIndex = index;
        storySeqInSession++;
        currentStoryRecorded = false;
        storyElapsedMs = 0L;
        storyShownAtUptimeMs = SystemClock.uptimeMillis();

        storyCounterView.setText(getString(R.string.story_counter_format, index + 1,
                circle.storyCount()));
        progressView.setActiveProgress(index, 0f);
        bindImage(story);
        bindCaptionAndCta(story);
        bindLikeAndShare(story);
        prefetchNeighbours();
        play();
    }

    private void bindImage(@NonNull Story story) {
        android.graphics.Bitmap cached = imageLoader.fromMemory(story.imageUrl);
        if (cached != null) {
            storyImage.setImageBitmap(cached);
            return;
        }
        // Clear first so the previous frame is not left on screen under the new one's chrome.
        storyImage.setImageDrawable(null);
        String expectedUrl = story.imageUrl;
        imageLoader.load(expectedUrl, (url, bitmap) -> {
            Story current = currentStory();
            if (current != null && expectedUrl.equals(current.imageUrl) && bitmap != null) {
                storyImage.setImageBitmap(bitmap);
            }
        });
    }

    private void bindCaptionAndCta(@NonNull Story story) {
        if (TextUtils.isEmpty(story.caption)) {
            captionView.setVisibility(View.GONE);
        } else {
            captionView.setVisibility(View.VISIBLE);
            captionView.setText(story.caption);
        }
        ctaView.setVisibility(TextUtils.isEmpty(story.deeplink) ? View.GONE : View.VISIBLE);
    }

    private void bindLikeAndShare(@NonNull Story story) {
        likeButton.setVisibility(story.likeEnabled ? View.VISIBLE : View.GONE);
        likeCountView.setVisibility(story.likeEnabled ? View.VISIBLE : View.GONE);
        shareButton.setVisibility(story.shareEnabled ? View.VISIBLE : View.GONE);
        if (story.likeEnabled) {
            renderLikeState(story, stateStore.isLiked(story.id));
        }
    }

    private void renderLikeState(@NonNull Story story, boolean liked) {
        likeButton.setImageResource(liked ? R.drawable.ic_heart_filled
                : R.drawable.ic_heart_outline);
        int count = story.baseLikeCount + (liked ? 1 : 0);
        likeCountView.setText(NumberFormat.getIntegerInstance(Locale.getDefault()).format(count));
    }

    /** Decodes the next frame ahead of time so an advance never shows a blank screen. */
    private void prefetchNeighbours() {
        StoryCircle circle = tray.circleAt(circleIndex);
        if (circle == null) {
            return;
        }
        Story next = circle.storyAt(storyIndex + 1);
        if (next != null) {
            imageLoader.prefetch(next.imageUrl);
            return;
        }
        // Last frame of this circle: warm the first frame of the circle that auto-advances next.
        StoryCircle nextCircle = tray.circleAt(circleIndex + 1);
        if (nextCircle != null) {
            Story first = nextCircle.storyAt(0);
            if (first != null) {
                imageLoader.prefetch(first.imageUrl);
            }
        }
    }

    private void goToNextStory(@NonNull String exitReason) {
        recordCurrentStory(exitReason, StoryAnalytics.EXIT_AUTO_COMPLETE.equals(exitReason));
        StoryCircle circle = tray.circleAt(circleIndex);
        if (circle != null && circle.storyAt(storyIndex + 1) != null) {
            showStory(storyIndex + 1);
            return;
        }
        // Circle exhausted. This is the auto-advance the client specifically asked for: the next
        // circle starts by itself, and its Story Circle Opened event says so via open_source.
        if (tray.circleAt(circleIndex + 1) != null) {
            openCircle(circleIndex + 1, 0, StoryAnalytics.OPEN_SOURCE_AUTO_ADVANCE);
        } else {
            closeViewer(null);
        }
    }

    private void goToPreviousStory() {
        recordCurrentStory(StoryAnalytics.EXIT_TAP_BACK, false);
        if (storyIndex > 0) {
            showStory(storyIndex - 1);
            return;
        }
        if (circleIndex > 0) {
            openCircle(circleIndex - 1, 0, StoryAnalytics.OPEN_SOURCE_SWIPE);
            return;
        }
        // Already at the very first frame: restart it rather than closing.
        showStory(0);
    }

    private void goToCircle(int delta, @NonNull String exitReason) {
        int target = circleIndex + delta;
        if (tray.circleAt(target) == null) {
            resume();
            return;
        }
        recordCurrentStory(exitReason, false);
        openCircle(target, 0, StoryAnalytics.OPEN_SOURCE_SWIPE);
    }

    private void closeViewer(@Nullable String exitReason) {
        pause();
        if (exitReason != null) {
            recordCurrentStory(exitReason, false);
        } else {
            // Reached the end of the last circle: the final frame completed.
            recordCurrentStory(StoryAnalytics.EXIT_AUTO_COMPLETE, true);
        }
        finish();
    }

    // ----------------------------------------------------------------------- timing

    private void play() {
        playing = true;
        lastTickUptimeMs = SystemClock.uptimeMillis();
        ticker.removeCallbacksAndMessages(null);
        ticker.postDelayed(this::tick, TICK_MS);
    }

    private void pause() {
        if (playing) {
            storyElapsedMs += SystemClock.uptimeMillis() - lastTickUptimeMs;
            playing = false;
        }
        ticker.removeCallbacksAndMessages(null);
    }

    private void resume() {
        if (!playing) {
            play();
        }
    }

    private void tick() {
        if (!playing) {
            return;
        }
        Story story = currentStory();
        if (story == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        storyElapsedMs += now - lastTickUptimeMs;
        lastTickUptimeMs = now;

        float fraction = (float) storyElapsedMs / (float) story.durationMillis();
        if (fraction >= 1f) {
            progressView.setActiveProgress(storyIndex, 1f);
            playing = false;
            goToNextStory(StoryAnalytics.EXIT_AUTO_COMPLETE);
            return;
        }
        progressView.setActiveProgress(storyIndex, fraction);
        ticker.postDelayed(this::tick, TICK_MS);
    }

    // --------------------------------------------------------------------- gestures

    private boolean onViewerTouch(@NonNull View view, @NonNull MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downAtMs = SystemClock.uptimeMillis();
                pause();
                return true;

            case MotionEvent.ACTION_UP:
                handleTouchRelease(view, event);
                return true;

            case MotionEvent.ACTION_CANCEL:
                resume();
                return true;

            default:
                return true;
        }
    }

    private void handleTouchRelease(@NonNull View view, @NonNull MotionEvent event) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        long heldMs = SystemClock.uptimeMillis() - downAtMs;

        boolean verticalSwipe = Math.abs(dy) > swipeThresholdPx && Math.abs(dy) > Math.abs(dx);
        if (verticalSwipe) {
            if (dy > 0) {
                closeViewer(StoryAnalytics.EXIT_CLOSED);
            } else {
                resume();
            }
            return;
        }
        if (Math.abs(dx) > swipeThresholdPx) {
            if (dx < 0) {
                goToCircle(1, StoryAnalytics.EXIT_SWIPE_NEXT_CIRCLE);
            } else {
                goToCircle(-1, StoryAnalytics.EXIT_SWIPE_PREV_CIRCLE);
            }
            return;
        }
        boolean wasTap = heldMs <= TAP_MAX_MS
                && Math.abs(dx) <= touchSlopPx
                && Math.abs(dy) <= touchSlopPx;
        if (wasTap) {
            if (event.getX() < view.getWidth() * BACK_ZONE_FRACTION) {
                goToPreviousStory();
            } else {
                goToNextStory(StoryAnalytics.EXIT_TAP_FORWARD);
            }
            return;
        }
        // Held to pause, then released: carry on from where the frame stopped.
        resume();
    }

    // ----------------------------------------------------------------- interactions

    private void onLikeTapped() {
        Story story = currentStory();
        if (story == null || !story.likeEnabled) {
            return;
        }
        boolean likedAfter = stateStore.toggleLike(story.id);
        renderLikeState(story, likedAfter);

        likeButton.animate().cancel();
        likeButton.setScaleX(0.8f);
        likeButton.setScaleY(0.8f);
        likeButton.animate().scaleX(1f).scaleY(1f).setDuration(160L).start();

        StoryAnalytics.storyInteracted(
                likedAfter ? StoryAnalytics.ACTION_LIKE : StoryAnalytics.ACTION_LIKE_REMOVED,
                tray, circleIndex, storyIndex, storyElapsedMs, likedAfter,
                stateStore.likedCount(), null);
        StoryAnalytics.syncLikeToProfile(story, likedAfter);
    }

    private void onShareTapped() {
        Story story = currentStory();
        if (story == null || !story.shareEnabled) {
            return;
        }
        Toast.makeText(this, R.string.shared_toast, Toast.LENGTH_SHORT).show();
        StoryAnalytics.storyInteracted(StoryAnalytics.ACTION_SHARE, tray, circleIndex, storyIndex,
                storyElapsedMs, null, null, "demo_sheet");
    }

    private void onCtaTapped() {
        Story story = currentStory();
        if (story == null || TextUtils.isEmpty(story.deeplink)) {
            return;
        }
        // Dummy on purpose: the demo proves the click is captured, it does not navigate anywhere.
        Toast.makeText(this, getString(R.string.deeplink_toast, story.deeplink),
                Toast.LENGTH_SHORT).show();
        StoryAnalytics.storyInteracted(StoryAnalytics.ACTION_LINK_CLICK, tray, circleIndex,
                storyIndex, storyElapsedMs, null, null, null);
    }

    // -------------------------------------------------------------------- reporting

    /**
     * Ends the current frame exactly once.
     *
     * <p>Marking the story seen here rather than on entry means a frame only greys out its ring
     * after it has actually been on screen.</p>
     */
    private void recordCurrentStory(@NonNull String exitReason, boolean completed) {
        if (currentStoryRecorded) {
            return;
        }
        Story story = currentStory();
        if (story == null) {
            return;
        }
        currentStoryRecorded = true;
        long dwellMs = SystemClock.uptimeMillis() - storyShownAtUptimeMs;
        stateStore.markStorySeen(story.id);
        StoryAnalytics.storyViewed(tray, circleIndex, storyIndex, dwellMs, completed, exitReason,
                stateStore.isLiked(story.id), storySeqInSession);
    }

    @Nullable
    private Story currentStory() {
        StoryCircle circle = tray == null ? null : tray.circleAt(circleIndex);
        return circle == null ? null : circle.storyAt(storyIndex);
    }

    // ------------------------------------------------------------------- lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        pause();
        if (!isFinishing()) {
            // Backgrounded mid-frame. Recorded now rather than dropped, so the funnel does not
            // silently lose views; onResume then counts the return as a fresh view of the frame.
            recordCurrentStory(StoryAnalytics.EXIT_BACKGROUNDED, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentStory() == null) {
            return;
        }
        if (currentStoryRecorded) {
            // Returning from background: the frame is on screen again, so start a new dwell
            // window while keeping the progress the frame had already made.
            currentStoryRecorded = false;
            storyShownAtUptimeMs = SystemClock.uptimeMillis();
        }
        play();
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
