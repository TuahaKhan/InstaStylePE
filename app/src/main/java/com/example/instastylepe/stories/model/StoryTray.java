package com.example.instastylepe.stories.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The whole tray the home screen renders: an ordered list of circles plus provenance.
 */
public class StoryTray implements Parcelable {

    /** Tray came from a live CleverTap Native Display campaign. */
    public static final String SOURCE_NATIVE_DISPLAY = "native_display";
    /** Tray came from the bundled sample payload because no campaign was live. */
    public static final String SOURCE_FALLBACK = "fallback";

    public static final Creator<StoryTray> CREATOR = new Creator<StoryTray>() {
        @Override
        public StoryTray createFromParcel(Parcel in) {
            return new StoryTray(in);
        }

        @Override
        public StoryTray[] newArray(int size) {
            return new StoryTray[size];
        }
    };

    private final ArrayList<StoryCircle> circles;
    public final String source;

    public StoryTray(List<StoryCircle> circles, String source) {
        this.circles = circles == null ? new ArrayList<>() : new ArrayList<>(circles);
        this.source = source;
    }

    protected StoryTray(Parcel in) {
        circles = in.createTypedArrayList(StoryCircle.CREATOR);
        source = in.readString();
    }

    public static StoryTray empty() {
        return new StoryTray(new ArrayList<>(), SOURCE_NATIVE_DISPLAY);
    }

    public List<StoryCircle> circles() {
        return Collections.unmodifiableList(circles);
    }

    public boolean isEmpty() {
        return circles.isEmpty();
    }

    public int circleCount() {
        return circles.size();
    }

    public StoryCircle circleAt(int index) {
        if (index < 0 || index >= circles.size()) {
            return null;
        }
        return circles.get(index);
    }

    public int totalStoryCount() {
        int total = 0;
        for (StoryCircle circle : circles) {
            total += circle.storyCount();
        }
        return total;
    }

    /** Comma-joined display unit ids, for the tray-level analytics event. */
    public String unitIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (StoryCircle circle : circles) {
            if (circle.unitId != null && !circle.unitId.isEmpty()) {
                ids.add(circle.unitId);
            }
        }
        return join(ids);
    }

    /** Comma-joined campaign ids, for the tray-level analytics event. */
    public String campaignIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (StoryCircle circle : circles) {
            if (circle.campaignId != null && !circle.campaignId.isEmpty()) {
                ids.add(circle.campaignId);
            }
        }
        return join(ids);
    }

    private static String join(Iterable<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(circles);
        dest.writeString(source);
    }
}
