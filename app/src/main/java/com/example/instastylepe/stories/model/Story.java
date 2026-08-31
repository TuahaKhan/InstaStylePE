package com.example.instastylepe.stories.model;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * A single frame inside a circle. Everything here is authored by the marketer in the
 * Native Display campaign's custom key-value payload.
 */
public class Story implements Parcelable {

    public static final Creator<Story> CREATOR = new Creator<Story>() {
        @Override
        public Story createFromParcel(Parcel in) {
            return new Story(in);
        }

        @Override
        public Story[] newArray(int size) {
            return new Story[size];
        }
    };

    /** Default dwell time when the marketer does not send one. */
    public static final int DEFAULT_DURATION_SECONDS = 5;

    private static final int MIN_DURATION_SECONDS = 1;
    private static final int MAX_DURATION_SECONDS = 30;

    public final String id;
    public final String imageUrl;
    public final int durationSeconds;
    public final String deeplink;
    public final String caption;
    public final boolean likeEnabled;
    public final boolean shareEnabled;
    /** Cosmetic starting number so the demo's like counter looks alive. */
    public final int baseLikeCount;

    public Story(String id,
            String imageUrl,
            int durationSeconds,
            String deeplink,
            String caption,
            boolean likeEnabled,
            boolean shareEnabled,
            int baseLikeCount) {
        this.id = id;
        this.imageUrl = imageUrl;
        this.durationSeconds = clampDuration(durationSeconds);
        this.deeplink = deeplink;
        this.caption = caption;
        this.likeEnabled = likeEnabled;
        this.shareEnabled = shareEnabled;
        this.baseLikeCount = Math.max(0, baseLikeCount);
    }

    protected Story(Parcel in) {
        id = in.readString();
        imageUrl = in.readString();
        durationSeconds = in.readInt();
        deeplink = in.readString();
        caption = in.readString();
        likeEnabled = in.readByte() != 0;
        shareEnabled = in.readByte() != 0;
        baseLikeCount = in.readInt();
    }

    /**
     * A marketer typo should not freeze a story on screen forever or flash it past the eye,
     * so out-of-range durations are pulled back into something watchable.
     */
    private static int clampDuration(int seconds) {
        if (seconds <= 0) {
            return DEFAULT_DURATION_SECONDS;
        }
        return Math.min(MAX_DURATION_SECONDS, Math.max(MIN_DURATION_SECONDS, seconds));
    }

    public long durationMillis() {
        return durationSeconds * 1000L;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(imageUrl);
        dest.writeInt(durationSeconds);
        dest.writeString(deeplink);
        dest.writeString(caption);
        dest.writeByte((byte) (likeEnabled ? 1 : 0));
        dest.writeByte((byte) (shareEnabled ? 1 : 0));
        dest.writeInt(baseLikeCount);
    }
}
