package com.example.desktoppet.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/** Lightweight, code-native ambient background used by the main activity. */
final class DeepSeaBackdropView extends View {
    private static final int PARTICLE_COUNT = 42;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private final float[] particleX = new float[PARTICLE_COUNT];
    private final float[] particleY = new float[PARTICLE_COUNT];
    private final float[] particleSize = new float[PARTICLE_COUNT];
    private final float[] particleAlpha = new float[PARTICLE_COUNT];
    private ValueAnimator animator;
    private float phase;

    DeepSeaBackdropView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.7f));
        gridPaint.setColor(Color.argb(24, 62, 219, 242));
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        particlePaint.setStyle(Paint.Style.FILL);

        Random random = new Random(0xFE1A5EAL);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            particleX[i] = random.nextFloat();
            particleY[i] = random.nextFloat();
            particleSize[i] = dp(0.7f + random.nextFloat() * 1.6f);
            particleAlpha[i] = 0.25f + random.nextFloat() * 0.6f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        backgroundPaint.setShader(new LinearGradient(
                0, 0, w, h,
                new int[]{Color.rgb(2, 7, 20), Color.rgb(5, 20, 43), Color.rgb(3, 9, 26)},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float width = getWidth();
        final float height = getHeight();
        canvas.drawRect(0, 0, width, height, backgroundPaint);

        float grid = dp(44f);
        for (float x = -grid + (phase * grid); x < width; x += grid) {
            canvas.drawLine(x, 0, x, height, gridPaint);
        }
        for (float y = 0; y < height; y += grid) {
            canvas.drawLine(0, y, width, y, gridPaint);
        }

        drawWave(canvas, height * 0.18f, dp(64), Color.argb(105, 33, 224, 245), dp(2.2f), phase);
        drawWave(canvas, height * 0.48f, dp(92), Color.argb(70, 72, 105, 255), dp(3.4f), 1f - phase);
        drawWave(canvas, height * 0.78f, dp(72), Color.argb(65, 34, 210, 235), dp(2.2f), phase * 0.7f);

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            float drift = (phase * (i % 4 + 1) * dp(10f)) % (width + dp(20));
            float x = (particleX[i] * width + drift) % width;
            float y = particleY[i] * height;
            int alpha = Math.round(150 * particleAlpha[i] * (0.72f + 0.28f * (float) Math.sin(phase * Math.PI * 2 + i)));
            particlePaint.setColor(Color.argb(Math.max(18, alpha), 95, 235, 255));
            canvas.drawCircle(x, y, particleSize[i], particlePaint);
        }
    }

    private void drawWave(Canvas canvas, float centerY, float amplitude, int color, float stroke, float offset) {
        float width = getWidth();
        float shift = offset * width * 0.3f;
        wavePath.reset();
        wavePath.moveTo(-width * 0.35f + shift, centerY);
        wavePath.cubicTo(
                width * 0.05f + shift, centerY - amplitude,
                width * 0.30f + shift, centerY + amplitude,
                width * 0.62f + shift, centerY);
        wavePath.cubicTo(
                width * 0.92f + shift, centerY - amplitude,
                width * 1.18f + shift, centerY + amplitude,
                width * 1.55f + shift, centerY);
        wavePaint.setStrokeWidth(stroke * 3.2f);
        wavePaint.setColor(Color.argb(Color.alpha(color) / 4, Color.red(color), Color.green(color), Color.blue(color)));
        canvas.drawPath(wavePath, wavePaint);
        wavePaint.setStrokeWidth(stroke);
        wavePaint.setColor(color);
        canvas.drawPath(wavePath, wavePaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(12000L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(value -> {
                phase = (float) value.getAnimatedValue();
                postInvalidateOnAnimation();
            });
        }
        if (!animator.isStarted()) animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
