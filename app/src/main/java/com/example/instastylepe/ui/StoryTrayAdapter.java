package com.example.instastylepe.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.instastylepe.R;
import com.example.instastylepe.stories.data.StoryStateStore;
import com.example.instastylepe.stories.model.Story;
import com.example.instastylepe.stories.model.StoryCircle;
import com.example.instastylepe.stories.model.StoryTray;
import com.example.instastylepe.util.ImageLoader;

import java.util.ArrayList;
import java.util.List;

/** The horizontal row of story circles at the top of the home screen. */
public class StoryTrayAdapter extends RecyclerView.Adapter<StoryTrayAdapter.CircleHolder> {

    public interface OnCircleClickListener {

        void onCircleClicked(int circleIndex);
    }

    private final StoryStateStore stateStore;
    private final OnCircleClickListener listener;
    private StoryTray tray = StoryTray.empty();

    public StoryTrayAdapter(@NonNull StoryStateStore stateStore,
            @NonNull OnCircleClickListener listener) {
        this.stateStore = stateStore;
        this.listener = listener;
    }

    public void setTray(@NonNull StoryTray tray) {
        this.tray = tray;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CircleHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_story_circle, parent, false);
        return new CircleHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CircleHolder holder, int position) {
        StoryCircle circle = tray.circleAt(position);
        if (circle == null) {
            return;
        }
        holder.bind(circle, stateStore, listener);
    }

    @Override
    public int getItemCount() {
        return tray.circleCount();
    }

    static class CircleHolder extends RecyclerView.ViewHolder {

        private final StoryRingView ring;
        private final TextView label;

        CircleHolder(@NonNull View itemView) {
            super(itemView);
            ring = itemView.findViewById(R.id.story_ring);
            label = itemView.findViewById(R.id.story_label);
        }

        void bind(@NonNull StoryCircle circle,
                @NonNull StoryStateStore stateStore,
                @NonNull OnCircleClickListener listener) {
            label.setText(circle.name);
            ring.setAccentColor(circle.ringColor);

            List<String> storyIds = new ArrayList<>(circle.storyCount());
            for (Story story : circle.stories()) {
                storyIds.add(story.id);
            }
            ring.setSeen(stateStore.areAllSeen(storyIds));

            String coverUrl = circle.coverUrl();
            ImageLoader loader = ImageLoader.get(itemView.getContext());
            ring.setImage(loader.fromMemory(coverUrl));
            if (coverUrl != null) {
                // Guard against recycling: only paint if this holder is still showing this circle.
                itemView.setTag(coverUrl);
                loader.load(coverUrl, (url, bitmap) -> {
                    if (url.equals(itemView.getTag())) {
                        ring.setImage(bitmap);
                    }
                });
            }

            itemView.setContentDescription(
                    itemView.getContext().getString(R.string.story_circle_description,
                            circle.name, circle.storyCount()));
            itemView.setOnClickListener(view -> {
                int position = getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    listener.onCircleClicked(position);
                }
            });
        }
    }
}
