package com.example.instastylepe.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.instastylepe.R;
import com.example.instastylepe.util.ImageLoader;

/**
 * Filler feed under the story tray. Purely so the tray sits on something that looks like an app
 * during the demo - none of it is instrumented.
 */
public class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.FeedHolder> {

    private static final String[] CAPTIONS = {
            "Oversized blazers are back",
            "Six ways to style white sneakers",
            "The capsule wardrobe, revisited",
            "Monsoon-proof fabrics we love",
            "Weekend edit: under 1999",
            "Our most-saved looks this month"
    };

    @NonNull
    @Override
    public FeedHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feed_post, parent, false);
        return new FeedHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FeedHolder holder, int position) {
        holder.bind(position);
    }

    @Override
    public int getItemCount() {
        return CAPTIONS.length;
    }

    static class FeedHolder extends RecyclerView.ViewHolder {

        private final ImageView image;
        private final TextView caption;

        FeedHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.feed_image);
            caption = itemView.findViewById(R.id.feed_caption);
        }

        void bind(int position) {
            caption.setText(CAPTIONS[position]);
            String url = "https://placecats.com/640/640?i=feed" + position;
            ImageLoader.get(itemView.getContext()).load(url, image);
        }
    }
}
