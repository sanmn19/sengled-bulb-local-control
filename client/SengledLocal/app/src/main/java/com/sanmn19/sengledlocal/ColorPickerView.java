package com.sanmn19.sengledlocal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ColorPickerView extends View {
    public interface Listener {
        void onColorPicked(int r, int g, int b);
    }

    private Listener listener;
    private boolean interactive = true;
    private float hue = 0f;      // 0..360
    private float sat = 1f;      // 0..1
    private boolean dragging = false;
    private Bitmap disc;
    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorRing = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ColorPickerView(Context c) {
        super(c);
        init();
    }

    public ColorPickerView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        cursor.setColor(Color.WHITE);
        cursor.setStyle(Paint.Style.FILL);
        cursor.setStyle(Paint.Style.STROKE);
        cursor.setStrokeWidth(4f);
        cursorRing.setColor(0x66000000);
        cursorRing.setStyle(Paint.Style.FILL);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Restores a previous position (hue degrees 0-360, sat 0-1) without emitting. */
    public void setHSV(float hueDeg, float satVal) {
        this.hue = hueDeg;
        this.sat = satVal;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildDisc(w, h);
    }

    private void buildDisc(int w, int h) {
        int d = Math.max(1, Math.min(w, h));
        Bitmap bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888);
        int[] px = new int[d * d];
        float cx = (d - 1) / 2f;
        float cy = (d - 1) / 2f;
        float radius = d / 2f - 1;
        for (int y = 0; y < d; y++) {
            for (int x = 0; x < d; x++) {
                float dx = x - cx;
                float dy = y - cy;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > radius + 1) {
                    px[y * d + x] = 0x00000000;
                    continue;
                }
                double angle = Math.toDegrees(Math.atan2(dx, -dy)); // 0 at top, clockwise
                float hue = (float) ((angle + 360) % 360);
                float sat = Math.min(1f, dist / radius);
                px[y * d + x] = Color.HSVToColor(new float[]{hue, sat, 1f});
            }
        }
        bmp.setPixels(px, 0, d, 0, 0, d, d);
        disc = bmp;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (disc == null) return;
        int side = Math.min(getWidth(), getHeight());
        float left = (getWidth() - side) / 2f;
        float top = (getHeight() - side) / 2f;
        canvas.drawBitmap(disc, left, top, discPaint);
        float r = side / 2f - 1;
        float cx = left + side / 2f;
        float cy = top + side / 2f;
        double rad = Math.toRadians(hue - 90);
        float x = cx + (float) (r * sat * Math.cos(rad));
        float y = cy + (float) (r * sat * Math.sin(rad));
        canvas.drawCircle(x, y, 18f, cursorRing);
        canvas.drawCircle(x, y, 18f, cursor);
        canvas.drawCircle(x, y, 16f, cursorRing);
        canvas.drawCircle(x, y, 16f, cursor);
    }

    /** Buttons that merely SHOW the wheel set this false; touches pass through. */
    public void setInteractive(boolean b) {
        interactive = b;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!interactive) {
            return false;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - 1;
        float dx = ev.getX() - cx;
        float dy = ev.getY() - cy;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                // fall through
            case MotionEvent.ACTION_MOVE:
                double dist = Math.sqrt(dx * dx + dy * dy);
                double d = Math.toDegrees(Math.atan2(dx, -dy));
                hue = (float) ((d + 360) % 360);
                sat = (float) Math.min(1.0, Math.max(0.0, dist / r));
                invalidate();
                emit();
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                emit();
                return true;
        }
        return true;
    }

    private void emit() {
        if (listener != null) {
            int[] rgb = rgb();
            listener.onColorPicked(rgb[0], rgb[1], rgb[2]);
        }
    }

    public int[] rgb() {
        int c = Color.HSVToColor(new float[]{hue, sat, 1f});
        return new int[]{Color.red(c), Color.green(c), Color.blue(c)};
    }

    public boolean isDragging() {
        return dragging;
    }
}
