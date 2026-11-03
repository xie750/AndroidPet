package com.example.desktoppet.feature.pet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.example.desktoppet.ui.MainActivity;

import java.io.InputStream;
import java.util.Random;

@SuppressLint("ViewConstructor")
public final class PetView extends View {
    interface DragListener {
        void moveBy(int dx, int dy);
    }

    public enum Action {
        IDLE, TAP, DRAG, LISTEN, THINK, REMIND, CELEBRATE
    }

    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();
    private final RectF bubbleRect = new RectF();
    private final Path tailPath = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final DragListener dragListener;
    private Bitmap skinBitmap;
    private int selectedSkin;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean moved;
    private boolean longPressed;
    private String message = "点我互动 · 长按记事";
    private long messageUntil = Long.MAX_VALUE;
    private Action action = Action.IDLE;
    private long actionStarted;
    private long actionUntil;

    PetView(Context context, DragListener dragListener) {
        super(context);
        this.dragListener = dragListener;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentDescription("费雾桌宠，点击互动，长按打开待办");
        reloadSkin();
    }

    void reloadSkin() {
        Bitmap old = skinBitmap;
        skinBitmap = loadSelectedSkin();
        if (old != null && old != skinBitmap && !old.isRecycled()) old.recycle();
        invalidate();
    }

    void showMessage(String text) {
        showMessage(text, Action.REMIND, 6000L);
    }

    void showMessage(String text, Action nextAction, long duration) {
        message = text == null ? "我在" : text;
        messageUntil = System.currentTimeMillis() + duration;
        setAction(nextAction, duration);
        invalidate();
    }

    void setAction(Action nextAction, long duration) {
        action = nextAction;
        actionStarted = System.currentTimeMillis();
        actionUntil = actionStarted + duration;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        if (skinBitmap != null && !skinBitmap.isRecycled()) skinBitmap.recycle();
        skinBitmap = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        if (now > actionUntil && action != Action.DRAG) action = Action.IDLE;
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        double phase = (now % 2200L) / 2200.0 * Math.PI * 2.0;
        float bob = (float) Math.sin(phase) * 3.5f * density;
        float rotation = (float) Math.sin(phase) * 1.4f;
        float scaleX = 1f;
        float scaleY = 1f;
        float offsetX = 0f;

        float progress = actionUntil > actionStarted
                ? Math.min(1f, Math.max(0f, (now - actionStarted) / (float) (actionUntil - actionStarted)))
                : 0f;
        switch (action) {
            case TAP:
                scaleX = 1f + (float) Math.sin(progress * Math.PI * 4f) * .09f;
                scaleY = 1f - (float) Math.sin(progress * Math.PI * 4f) * .10f;
                rotation = (float) Math.sin(progress * Math.PI * 5f) * 5f;
                break;
            case DRAG:
                rotation = Math.max(-12f, Math.min(12f, (lastRawX - downRawX) / 9f));
                scaleY = .95f;
                break;
            case LISTEN:
                scaleX = scaleY = 1f + (float) Math.sin(phase * 2) * .035f;
                drawListeningGlow(canvas, width / 2f, height * .63f, density, phase);
                break;
            case THINK:
                rotation = (float) Math.sin(phase * 1.5) * 4f;
                break;
            case REMIND:
                offsetX = (float) Math.sin(progress * Math.PI * 12f) * 6f * density * (1f - progress);
                scaleX = scaleY = 1.03f;
                break;
            case CELEBRATE:
                rotation = (float) Math.sin(progress * Math.PI * 8f) * 9f;
                scaleX = scaleY = 1f + (float) Math.sin(progress * Math.PI) * .13f;
                drawConfetti(canvas, width, height, density, progress);
                break;
            default:
                break;
        }

        if (now < messageUntil || messageUntil == Long.MAX_VALUE) {
            drawBubble(canvas, message, width, density);
        }

        float top = 58f * density;
        float bottom = height - 2f * density;
        float left = 7f * density;
        float right = width - 7f * density;
        canvas.save();
        canvas.translate(width / 2f + offsetX, (top + bottom) / 2f + bob);
        canvas.rotate(rotation);
        canvas.scale(scaleX, scaleY);
        canvas.translate(-width / 2f, -(top + bottom) / 2f);
        drawSkin(canvas, left, top, right, bottom);
        canvas.restore();

        postInvalidateDelayed(50L);
    }

    private void drawSkin(Canvas canvas, float left, float top, float right, float bottom) {
        if (skinBitmap == null || skinBitmap.isRecycled()) return;
        float maxWidth = right - left;
        float maxHeight = bottom - top;
        float ratio = Math.min(maxWidth / skinBitmap.getWidth(), maxHeight / skinBitmap.getHeight());
        float drawWidth = skinBitmap.getWidth() * ratio;
        float drawHeight = skinBitmap.getHeight() * ratio;
        float x = (getWidth() - drawWidth) / 2f;
        float y = bottom - drawHeight;
        imageRect.set(x, y, x + drawWidth, y + drawHeight);
        imagePaint.setFilterBitmap(selectedSkin != 2);
        canvas.drawBitmap(skinBitmap, null, imageRect, imagePaint);
    }

    private void drawListeningGlow(Canvas canvas, float cx, float cy, float density, double phase) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f * density);
        int alpha = 95 + (int) (Math.sin(phase * 2) * 45);
        paint.setColor(Color.argb(alpha, 50, 225, 245));
        float radius = (52f + (float) Math.sin(phase * 2) * 8f) * density;
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawConfetti(Canvas canvas, float width, float height, float density, float progress) {
        int[] colors = {Color.CYAN, Color.rgb(255, 195, 65), Color.rgb(155, 110, 255), Color.WHITE};
        for (int i = 0; i < 12; i++) {
            float x = ((i * 47) % 100) / 100f * width;
            float y = (20f + ((i * 31) % 80) * progress) * density;
            paint.setColor(colors[i % colors.length]);
            canvas.drawCircle(x, y, 2.5f * density, paint);
        }
    }

    private void drawBubble(Canvas canvas, String text, float width, float density) {
        float left = 4f * density;
        float top = 2f * density;
        float right = width - 4f * density;
        float bottom = 50f * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(242, 255, 255, 255));
        bubbleRect.set(left, top, right, bottom);
        canvas.drawRoundRect(bubbleRect, 14f * density, 14f * density, paint);
        tailPath.reset();
        tailPath.moveTo(width / 2f - 9f * density, bottom - 1f);
        tailPath.lineTo(width / 2f, bottom + 10f * density);
        tailPath.lineTo(width / 2f + 9f * density, bottom - 1f);
        tailPath.close();
        canvas.drawPath(tailPath, paint);
        paint.setColor(Color.rgb(47, 52, 69));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(12.5f * density);
        String display = text.length() > 18 ? text.substring(0, 17) + "…" : text;
        canvas.drawText(display, width / 2f, top + 29f * density, paint);
    }

    private Bitmap loadSelectedSkin() {
        SharedPreferences prefs = getContext().getSharedPreferences("pet_settings", Context.MODE_PRIVATE);
        selectedSkin = prefs.getInt("selected_skin", 1);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (selectedSkin == -1) {
            String uriValue = prefs.getString("custom_skin_uri", "");
            if (!uriValue.isEmpty()) {
                try (InputStream stream = getContext().getContentResolver().openInputStream(Uri.parse(uriValue))) {
                    Bitmap custom = BitmapFactory.decodeStream(stream, null, options);
                    if (custom != null) return custom;
                } catch (Exception ignored) {}
            }
            selectedSkin = 1;
        }
        int index = SkinCatalog.safeIndex(selectedSkin);
        return BitmapFactory.decodeResource(getResources(), SkinCatalog.BUILT_INS[index].resourceId, options);
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
                    action = Action.DRAG;
                    handler.removeCallbacks(longPressRunnable);
                }
                dragListener.moveBy(Math.round(dx), Math.round(dy));
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (action == Action.DRAG) setAction(Action.TAP, 500L);
                if (!moved && !longPressed && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    String[] phrases = {"今天也要加油呀", "记得喝水", "有事长按告诉我", "完成一件就很棒", "我会帮你守着待办"};
                    showMessage(phrases[random.nextInt(phrases.length)], Action.TAP, 3000L);
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
