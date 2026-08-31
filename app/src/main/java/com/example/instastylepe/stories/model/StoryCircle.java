package com.example.instastylepe.stories.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One circle in the tray - the equivalent of an Instagram "story ring".
 *
 * <p>{@link #unitId} is the CleverTap Display Unit this circle arrived in. It is carried all
 * the way down to the story frame so that every analytics event can be attributed back to the
 * exact Native Display campaign that authored it, even when the tray was assembled by merging
 * several display units.</p>
 */
public class StoryCircle implements Parcelable {

    public static final Creator<StoryCircle> CREATOR = new Creator<StoryCircle>() {
        @Override
        public StoryCircle createFromParcel(Parcel in) {
            return new StoryCircle(in);
        }

        @Override
        public StoryCircle[] newArray(int size) {
            return new StoryCircle[size];
        }
    };

    public final String id;
    public final String name;
    public final String avatarUrl;
    /** Marketer-controlled sort key. Lower renders further left. */
    public final int order;
    /** Ring accent colour as authored, e.g. {@code #E1306C}. May be null. */
    public final String ringColor;
    public final String unitId;
    public final String campaignId;
    private final ArrayList<Story> stories;

    public StoryCircle(String id,
            String name,
            String avatarUrl,
            int order,
            String ringColor,
            String unitId,
            String campaignId,
            List<Story> stories) {
        this.id = id;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.order = order;
        this.ringColor = ringColor;
        this.unitId = unitId;
        this.campaignId = campaignId;
        this.stories = stories == null ? new ArrayList<>() : new ArrayList<>(stories);
    }

    protected StoryCircle(Parcel in) {
        id = in.readString();
        name = in.readString();
        avatarUrl = in.readString();
        order = in.readInt();
        ringColor = in.readString();
        unitId = in.readString();
        campaignId = in.readString();
        stories = in.createTypedArrayList(Story.CREATOR);
    }

    public List<Story> stories() {
        return Collections.unmodifiableList(stories);
    }

    public int storyCount() {
        return stories.size();
    }

    public Story storyAt(int index) {
        if (index < 0 || index >= stories.size()) {
            return null;
        }
        return stories.get(index);
    }

    /** Falls back to the circle's own avatar so a circle is never blank. */
    public String coverUrl() {
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            return avatarUrl;
        }
        Story first = storyAt(0);
        return first == null ? null : first.imageUrl;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(name);
        dest.writeString(avatarUrl);
        dest.writeInt(order);
        dest.writeString(ringColor);
        dest.writeString(unitId);
        dest.writeString(campaignId);
        dest.writeTypedList(stories);
    }
}
