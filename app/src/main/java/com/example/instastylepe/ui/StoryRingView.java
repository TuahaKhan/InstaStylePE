package com.example.instastylepe.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The tray circle: a circular crop of the cover image inside an accent ring.
 *
 * <p>The ring is the whole point of the widget - it carries the "unwatched vs. watched" state that
 * makes a story tray feel like a story tray. Unwatched draws in the marketer's accent colour,
 * watched drops to grey, exactly as Instagram does it.</p>
 */
public class StoryRingView extends View {

    private static final int RING_WIDTH_DP = 3;
    private static final int RING_GAP_DP = 3;
    private static final int SEEN_RING_COLOR = 0xFFBDBDBD;
    private static final int DEFAULT_RING_COLOR = 0xFFE1306C;
    private static final int PLACEHOLDER_COLOR = 0xFF2A2A2A;

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();

    private float ringWidthPx;
    private float ringGapPx;
    private int accentColor = DEFAULT_RING_COLOR;
    private boolean seen;
    @Nullable
    private Bitmap bitmap;

    public StoryRingView(Context context) {
        this(context, null);
    }

    public StoryRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        ringWidthPx = RING_WIDTH_DP * density;
        ringGapPx = RING_GAP_DP * density;

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringWidthPx);
        placeholderPaint.setColor(PLACEHOLDER_COLOR);
    }

    /** @param color a colour string as authored by the marketer, e.g. {@code #E1306C} */
    public void setAccentColor(@Nullable String color) {
        accentColor = parseColor(color, DEFAULT_RING_COLOR);
        invalidate();
    }

    public void setSeen(boolean seen) {
        if (this.seen != seen) {
            this.seen = seen;
            invalidate();
        }
    }

    public void setImage(@Nullable Bitmap bitmap) {
        this.bitmap = bitmap;
        if (bitmap == null) {
            imagePaint.setShader(null);
        } else {
            imagePaint.setShader(new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP));
        }
        invalidate();
    }

    private static int parseColor(@Nullable String color, int fallback) {
        if (color == null || color.trim().isEmpty()) {
            return fallback;
        }
        try {
            String value = color.trim();
            return Color.parseColor(value.startsWith("#") ? value : "#" + value);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) {
            return;
        }
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        float ringRadius = (size - ringWidthPx) / 2f;
        ringPaint.setColor(seen ? SEEN_RING_COLOR : accentColor);
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint);

        float imageRadius = ringRadius - ringWidthPx / 2f - ringGapPx;
        if (imageRadius <= 0) {
            return;
        }
        if (bitmap == null) {
            canvas.drawCircle(centerX, centerY, imageRadius, placeholderPaint);
            return;
        }
        // Centre-crop the bitmap into the circle: scale by the larger ratio, then centre it.
        float diameter = imageRadius * 2f;
        float scale = Math.max(diameter / bitmap.getWidth(), diameter / bitmap.getHeight());
        shaderMatrix.reset();
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(centerX - bitmap.getWidth() * scale / 2f,
                centerY - bitmap.getHeight() * scale / 2f);
        Shader shader = imagePaint.getShader();
        if (shader != null) {
            shader.setLocalMatrix(shaderMatrix);
        }
        canvas.drawCircle(centerX, centerY, imageRadius, imagePaint);
    }
}
