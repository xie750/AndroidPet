package com.example.desktoppet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

@SuppressLint("ViewConstructor")
final class PetView extends View {
    interface DragListener {
        void moveBy(int dx, int dy);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF shadowRect = new RectF();
    private final RectF leftWingRect = new RectF();
    private final RectF rightWingRect = new RectF();
    private final RectF smileRect = new RectF();
    private final RectF bubbleRect = new RectF();
    private final Path tailPath = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final DragListener dragListener;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean moved;
    private boolean longPressed;
    private String message = "点我互动 · 长按记事";
    private long messageUntil = Long.MAX_VALUE;

    PetView(Context context, DragListener dragListener) {
        super(context);
        this.dragListener = dragListener;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentDescription("费雾桌宠，点击互动，长按打开待办");
    }

    void showMessage(String text) {
        message = text;
        messageUntil = System.currentTimeMillis() + 6000L;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        double phase = (System.currentTimeMillis() % 2400L) / 2400.0 * Math.PI * 2.0;
        float bob = (float) Math.sin(phase) * 4f * density;
        float wing = (float) Math.sin(phase * 4.0) * 10f * density;

        if (System.currentTimeMillis() < messageUntil || messageUntil == Long.MAX_VALUE) {
            drawBubble(canvas, message, w, density);
        }

        float cx = w / 2f;
        float cy = h * 0.67f + bob;
        float radius = 42f * density;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(80, 80, 50, 20));
        shadowRect.set(cx - radius * .75f, cy + radius * .82f,
                cx + radius * .75f, cy + radius * 1.08f);
        canvas.drawOval(shadowRect, paint);

        paint.setColor(Color.rgb(255, 222, 125));
        canvas.save();
        canvas.rotate(-18f + wing, cx - radius, cy);
        leftWingRect.set(cx - radius * 1.45f, cy - radius * .55f,
                cx - radius * .35f, cy + radius * .45f);
        canvas.drawOval(leftWingRect, paint);
        canvas.restore();
        canvas.save();
        canvas.rotate(18f - wing, cx + radius, cy);
        rightWingRect.set(cx + radius * .35f, cy - radius * .55f,
                cx + radius * 1.45f, cy + radius * .45f);
        canvas.drawOval(rightWingRect, paint);
        canvas.restore();

        paint.setColor(Color.rgb(255, 151, 57));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setColor(Color.rgb(255, 181, 76));
        canvas.drawCircle(cx - radius * .22f, cy - radius * .25f, radius * .67f, paint);

        paint.setStrokeWidth(5f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.rgb(70, 45, 35));
        canvas.drawLine(cx - radius * .48f, cy - radius * .76f,
                cx - radius * .72f, cy - radius * 1.08f, paint);
        canvas.drawLine(cx + radius * .48f, cy - radius * .76f,
                cx + radius * .72f, cy - radius * 1.08f, paint);
        paint.setColor(Color.rgb(117, 204, 116));
        canvas.drawCircle(cx - radius * .76f, cy - radius * 1.12f, 8f * density, paint);
        canvas.drawCircle(cx + radius * .76f, cy - radius * 1.12f, 8f * density, paint);

        boolean blink = (System.currentTimeMillis() % 4200L) > 3950L;
        paint.setColor(Color.rgb(58, 39, 34));
        paint.setStrokeWidth(4f * density);
        if (blink) {
            canvas.drawLine(cx - radius * .42f, cy - radius * .08f,
                    cx - radius * .18f, cy - radius * .08f, paint);
            canvas.drawLine(cx + radius * .18f, cy - radius * .08f,
                    cx + radius * .42f, cy - radius * .08f, paint);
        } else {
            canvas.drawCircle(cx - radius * .30f, cy - radius * .10f, 5.5f * density, paint);
            canvas.drawCircle(cx + radius * .30f, cy - radius * .10f, 5.5f * density, paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f * density);
        smileRect.set(cx - radius * .24f, cy + radius * .03f,
                cx + radius * .24f, cy + radius * .40f);
        canvas.drawArc(smileRect, 15f, 150f, false, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(255, 111, 101));
        canvas.drawCircle(cx - radius * .62f, cy + radius * .17f, 6f * density, paint);
        canvas.drawCircle(cx + radius * .62f, cy + radius * .17f, 6f * density, paint);

        postInvalidateDelayed(50L);
    }

    private void drawBubble(Canvas canvas, String text, float width, float density) {
        float left = 6f * density;
        float top = 4f * density;
        float right = width - 6f * density;
        float bottom = 52f * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(238, 255, 255, 255));
        bubbleRect.set(left, top, right, bottom);
        canvas.drawRoundRect(bubbleRect, 15f * density, 15f * density, paint);
        tailPath.reset();
        tailPath.moveTo(width / 2f - 10f * density, bottom - 1f);
        tailPath.lineTo(width / 2f, bottom + 12f * density);
        tailPath.lineTo(width / 2f + 10f * density, bottom - 1f);
        tailPath.close();
        canvas.drawPath(tailPath, paint);
        paint.setColor(Color.rgb(70, 55, 48));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(13f * density);
        String display = text.length() > 16 ? text.substring(0, 15) + "…" : text;
        canvas.drawText(display, width / 2f, top + 30f * density, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = event.getRawX();
                downRawY = lastRawY = event.getRawY();
                moved = false;
                longPressed = false;
                handler.postDelayed(longPressRunnable, 650L);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - lastRawX;
                float dy = event.getRawY() - lastRawY;
                if (Math.hypot(event.getRawX() - downRawX, event.getRawY() - downRawY) > 12) {
                    moved = true;
                    handler.removeCallbacks(longPressRunnable);
                }
                dragListener.moveBy(Math.round(dx), Math.round(dy));
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (!moved && !longPressed && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    String[] phrases = {"今天也要加油呀", "记得喝水", "有事长按告诉我", "完成一件就很棒", "我会帮你守着待办"};
                    showMessage(phrases[random.nextInt(phrases.length)]);
                    performClick();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private final Runnable longPressRunnable = () -> {
        if (moved) return;
        longPressed = true;
        Intent intent = new Intent(getContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_QUICK_ADD, true);
        getContext().startActivity(intent);
    };
}
