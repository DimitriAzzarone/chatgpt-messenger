package com.dimitriazzarone.chatgptmessenger;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Segmented cyan rings ported from Heiwa's orbCanvas; the microphone stays on top. */
final class DanHudMicDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean active;
    private int alpha = 255;
    DanHudMicDrawable(boolean active) { this.active = active; }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float cx = bounds.exactCenterX(), cy = bounds.exactCenterY();
        float base = Math.min(bounds.width(), bounds.height()) * 0.46f;
        if (base <= 0) return;
        float glow = active ? 1f : .58f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, base,
                new int[]{Color.argb((int)(alpha * glow * .32f), 0, 200, 255),
                        Color.rgb(3, 13, 26)}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, base, paint);
        paint.setShader(null);
        float[] radii = {1f, .84f, .70f, .57f};
        int[] segments = {24, 16, 32, 8};
        float[] dashes = {.6f, .7f, .4f, .8f};
        float[] widths = {1.5f, 1f, .8f, 2f};
        paint.setStyle(Paint.Style.STROKE);
        for (int ring = 0; ring < radii.length; ring++) {
            float radius = base * radii[ring];
            RectF oval = new RectF(cx-radius, cy-radius, cx+radius, cy+radius);
            paint.setStrokeWidth(Math.max(1f, base / 30f * widths[ring]));
            float step = 360f / segments[ring];
            for (int i = 0; i < segments[ring]; i++) {
                paint.setColor(Color.argb((int)(alpha * glow * (.5f + .5f * Math.sin(i*.7f))), 0, 200, 255));
                canvas.drawArc(oval, i*step+ring*17, step*dashes[ring], false, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(3, 13, 26));
        canvas.drawCircle(cx, cy, base*.48f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, base/30f));
        paint.setColor(Color.argb((int)(alpha*glow), 0, 200, 255));
        canvas.drawCircle(cx, cy, base*.48f, paint);
    }
    @Override public void setAlpha(int alpha) { this.alpha=alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
