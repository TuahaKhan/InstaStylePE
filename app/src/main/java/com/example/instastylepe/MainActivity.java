package com.example.instastylepe;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.instastylepe.stories.analytics.CleverTapManager;
import com.example.instastylepe.stories.analytics.StoryAnalytics;
import com.example.instastylepe.stories.data.NativeDisplayRepository;
import com.example.instastylepe.stories.data.StoryStateStore;
import com.example.instastylepe.stories.model.Story;
import com.example.instastylepe.stories.model.StoryCircle;
import com.example.instastylepe.stories.model.StoryTray;
import com.example.instastylepe.ui.FeedAdapter;
import com.example.instastylepe.ui.StoryTrayAdapter;
import com.example.instastylepe.ui.StoryViewerActivity;
import com.example.instastylepe.util.ImageLoader;

import java.util.HashSet;
import java.util.Set;

/**
 * Home screen: the story tray, fed entirely by a CleverTap Native Display campaign, over a filler
 * feed.
 */
public class MainActivity extends AppCompatActivity
        implements NativeDisplayRepository.Callback, StoryTrayAdapter.OnCircleClickListener {

    private NativeDisplayRepository repository;
    private StoryStateStore stateStore;
    private StoryTrayAdapter trayAdapter;
    private TextView sourceBanner;
    private TextView emptyLabel;

    private StoryTray tray = StoryTray.empty();
    /** Display units already reported as seen in this app run, so impressions are not double-counted. */
    private final Set<String> reportedImpressions = new HashSet<>();
    /** Guards against Story Tray Rendered firing twice when the cache and a live response agree. */
    private String lastRenderedSignature;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        stateStore = new StoryStateStore(this);
        repository = new NativeDisplayRepository(this);
        sourceBanner = findViewById(R.id.payload_source_banner);
        emptyLabel = findViewById(R.id.tray_empty_label);

        trayAdapter = new StoryTrayAdapter(stateStore, this);
        RecyclerView trayView = findViewById(R.id.story_tray);
        trayView.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        trayView.setAdapter(trayAdapter);

        RecyclerView feedView = findViewById(R.id.feed);
        feedView.setLayoutManager(new LinearLayoutManager(this));
        feedView.setAdapter(new FeedAdapter());

        // Demo affordance: puts every ring back to unwatched and clears likes, so the same build
        // can be walked through repeatedly without reinstalling.
        findViewById(R.id.reset_demo).setOnClickListener(view -> {
            stateStore.clearAll();
            trayAdapter.notifyDataSetChanged();
            Toast.makeText(this, R.string.demo_state_reset, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Registers the display unit listener, replays the cache, and raises the campaign's
        // trigger event. Doing it per visible session keeps the tray fresh if the marketer
        // publishes a change while the app is backgrounded.
        repository.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Rings may have been watched in the viewer we are returning from.
        trayAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onStop() {
        repository.stop();
        super.onStop();
    }

    // ------------------------------------------------------- NativeDisplayRepository.Callback

    @Override
    public void onTrayReady(@NonNull StoryTray newTray, int unitCount, long latencyMs) {
        tray = newTray;
        trayAdapter.setTray(newTray);

        boolean empty = newTray.isEmpty();
        emptyLabel.setVisibility(empty ? View.VISIBLE : View.GONE);
        sourceBanner.setText(describeSource(newTray, unitCount, latencyMs));

        if (empty) {
            return;
        }

        String signature = newTray.source + "|" + newTray.unitIds() + "|" + newTray.circleCount();
        if (!signature.equals(lastRenderedSignature)) {
            lastRenderedSignature = signature;
            StoryAnalytics.trayRendered(newTray, latencyMs, unitCount);
        }

        // Native Display's own impression counter. This, not the custom events, is what makes the
        // campaign report show Impressions.
        for (StoryCircle circle : newTray.circles()) {
            if (circle.unitId != null && reportedImpressions.add(circle.unitId)) {
                CleverTapManager.pushDisplayUnitViewed(circle.unitId);
            }
        }

        prefetchCovers(newTray);
    }

    /**
     * Warms the first frame of every circle plus the whole first circle, so opening a story shows
     * an image immediately rather than a black screen.
     */
    private void prefetchCovers(@NonNull StoryTray newTray) {
        ImageLoader loader = ImageLoader.get(this);
        for (int i = 0; i < newTray.circleCount(); i++) {
            StoryCircle circle = newTray.circleAt(i);
            if (circle == null) {
                continue;
            }
            loader.prefetch(circle.coverUrl());
            if (i == 0) {
                for (Story story : circle.stories()) {
                    loader.prefetch(story.imageUrl);
                }
            } else {
                Story first = circle.storyAt(0);
                if (first != null) {
                    loader.prefetch(first.imageUrl);
                }
            }
        }
    }

    private String describeSource(@NonNull StoryTray newTray, int unitCount, long latencyMs) {
        if (newTray.isEmpty()) {
            return getString(R.string.source_none);
        }
        if (StoryTray.SOURCE_FALLBACK.equals(newTray.source)) {
            return getString(R.string.source_fallback);
        }
        return getString(R.string.source_native_display, unitCount, newTray.campaignIds(),
                newTray.circleCount(), newTray.totalStoryCount(), latencyMs);
    }

    // ------------------------------------------------------- StoryTrayAdapter click listener

    @Override
    public void onCircleClicked(int circleIndex) {
        StoryCircle circle = tray.circleAt(circleIndex);
        if (circle == null) {
            return;
        }
        // Native Display's own click counter, feeding the campaign report's CTR.
        CleverTapManager.pushDisplayUnitClicked(circle.unitId);

        startActivity(StoryViewerActivity.intentFor(this, tray, circleIndex));
    }
}
