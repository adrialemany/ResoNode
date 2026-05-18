package com.example.resonode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class MiniEqView extends View {
    private Paint paint;
    private boolean isPlaying = false;
    private long startTime = 0;

    public MiniEqView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setColor(0xFFF2B327);
        paint.setStrokeWidth(8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void setPlaying(boolean playing) {
        if (this.isPlaying != playing) {
            this.isPlaying = playing;
            if (playing) {
                startTime = System.currentTimeMillis();
                invalidate();
            } else {
                invalidate();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int spacing = w / 4;

        if (isPlaying) {
            long time = System.currentTimeMillis() - startTime;
            for (int i = 0; i < 3; i++) {
                float speed = 0.004f + (i * 0.0015f);
                float phase = i * (float)Math.PI / 1.5f;

                float heightPercent = 0.5f + 0.4f * (float)Math.sin(time * speed + phase);
                int barH = (int) (h * heightPercent);
                canvas.drawLine(spacing * (i + 1), h, spacing * (i + 1), h - barH, paint);
            }
            invalidate();
        } else {
            canvas.drawLine(spacing * 1, h, spacing * 1, h - (h / 5), paint);
            canvas.drawLine(spacing * 2, h, spacing * 2, h - (h / 3), paint);
            canvas.drawLine(spacing * 3, h, spacing * 3, h - (h / 4), paint);
        }
    }
}