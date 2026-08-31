package com.example.instastylepe.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The row of segmented bars at the top of a story - one segment per story in the current circle,
 * filling left to right.
 *
 * <p>Drawn as a single view rather than N child views: the active segment repaints roughly 60
 * times a second, and one {@code invalidate()} on one view is cheaper and jitters less than
 * animating a child's layout params.</p>
 */
public class StoryProgressView extends View {

    private static final int TRACK_COLOR = 0x59FFFFFF;
    private static final int FILL_COLOR = 0xFFFFFFFF;
    private static final int BAR_HEIGHT_DP = 3;
    private static final int BAR_SPACING_DP = 3;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final float barHeightPx;
    private final float spacingPx;

    private int segmentCount;
    private int activeIndex;
    private float activeFraction;

    public StoryProgressView(Context context) {
        this(context, null);
    }

    public StoryProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        barHeightPx = BAR_HEIGHT_DP * density;
        spacingPx = BAR_SPACING_DP * density;
        trackPaint.setColor(TRACK_COLOR);
        fillPaint.setColor(FILL_COLOR);
    }

    /** Rebuilds the row for a new circle. Segments before {@code activeIndex} render full. */
    public void setSegments(int count, int activeIndex) {
        this.segmentCount = Math.max(0, count);
        this.activeIndex = activeIndex;
        this.activeFraction = 0f;
        invalidate();
    }

    /** @param fraction 0..1 progress through the currently playing story */
    public void setActiveProgress(int activeIndex, float fraction) {
        this.activeIndex = activeIndex;
        this.activeFraction = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        setMeasuredDimension(width, (int) Math.ceil(barHeightPx));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (segmentCount <= 0) {
            return;
        }
        float totalSpacing = spacingPx * (segmentCount - 1);
        float segmentWidth = (getWidth() - totalSpacing) / segmentCount;
        if (segmentWidth <= 0) {
            return;
        }
        float radius = barHeightPx / 2f;

        for (int i = 0; i < segmentCount; i++) {
            float left = i * (segmentWidth + spacingPx);
            rect.set(left, 0f, left + segmentWidth, barHeightPx);
            canvas.drawRoundRect(rect, radius, radius, trackPaint);

            float filledWidth;
            if (i < activeIndex) {
                filledWidth = segmentWidth;
            } else if (i == activeIndex) {
                filledWidth = segmentWidth * activeFraction;
            } else {
                filledWidth = 0f;
            }
            if (filledWidth > 0f) {
                rect.set(left, 0f, left + filledWidth, barHeightPx);
                canvas.drawRoundRect(rect, radius, radius, fillPaint);
            }
        }
    }
}
