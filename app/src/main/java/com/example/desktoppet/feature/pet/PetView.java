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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.example.desktoppet.R;
import com.example.desktoppet.ui.MainActivity;

import java.io.InputStream;
import java.util.Random;

@SuppressLint("ViewConstructor")
public final class PetView extends View {
    interface DragListener {
        void moveBy(int dx, int dy);
    }

    public enum Action {
        IDLE, OBSERVE, TAP, PET, POKE, DRAG, LAND, LISTEN, THINK, REMIND, CELEBRATE, SLEEP
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
    private Bitmap pettedBitmap;
    private Bitmap pokedBitmap;
    private Bitmap talkingBitmap;
    private Bitmap sleepingBitmap;
    private Bitmap renderedBitmap;
    private Bitmap transitionFromBitmap;
    private long bitmapTransitionStarted;
    private int selectedSkin;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean moved;
    private boolean stroked;
    private boolean longPressed;
    private boolean speaking;
    private float lastDragDx;
    private float lastDragDy;
    private float touchNormalizedX = .5f;
    private float touchNormalizedY = .5f;
    private long downAt;
    private long lastInteractionAt = System.currentTimeMillis();
    private long nextAutonomousAt = lastInteractionAt + 9000L;
    private String message = "点我互动 · 长按记事";
    private long messageUntil = System.currentTimeMillis() + 7000L;
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
        recycleBitmaps();
        skinBitmap = loadSelectedSkin();
        if (selectedSkin == 1) loadMistExpressions();
        renderedBitmap = skinBitmap;
        transitionFromBitmap = null;
        invalidate();
    }

    void setSpeaking(boolean value) {
        speaking = value;
        lastInteractionAt = System.currentTimeMillis();
        if (value && action == Action.SLEEP) setAction(Action.OBSERVE, 900L);
        if (!value && action == Action.IDLE) nextAutonomousAt = lastInteractionAt + 7000L;
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
        actionUntil = nextAction == Action.SLEEP ? Long.MAX_VALUE : actionStarted + duration;
        if (nextAction != Action.IDLE && nextAction != Action.SLEEP) {
            lastInteractionAt = actionStarted;
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        recycleBitmaps();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        if (now > actionUntil && action != Action.DRAG && action != Action.SLEEP) {
            action = Action.IDLE;
        }
        updateAutonomousBehavior(now);
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        double phase = (now % 2600L) / 2600.0 * Math.PI * 2.0;
        float bob = (float) Math.sin(phase) * 1.6f * density;
        float rotation = (float) Math.sin(phase) * .7f;
        float scaleX = 1f;
        float scaleY = 1f;
        float offsetX = 0f;
        float offsetY = 0f;

        float progress = actionUntil > actionStarted
                ? Math.min(1f, Math.max(0f, (now - actionStarted) / (float) (actionUntil - actionStarted)))
                : 0f;
        switch (action) {
            case OBSERVE:
                rotation = (float) Math.sin(progress * Math.PI) * (touchNormalizedX < .5f ? -4f : 4f);
                scaleX = scaleY = 1f + (float) Math.sin(progress * Math.PI) * .025f;
                break;
            case TAP:
                scaleX = 1f + (float) Math.sin(progress * Math.PI * 4f) * .09f;
                scaleY = 1f - (float) Math.sin(progress * Math.PI * 4f) * .10f;
                rotation = (float) Math.sin(progress * Math.PI * 5f) * 5f;
                break;
            case PET:
                rotation = (touchNormalizedX - .5f) * 7f;
                scaleX = 1f + (float) Math.sin(phase * 2) * .018f;
                scaleY = 1f - (float) Math.sin(phase * 2) * .012f;
                offsetY = 2f * density;
                break;
            case POKE:
                float poke = (float) Math.sin(progress * Math.PI);
                scaleX = 1f + poke * .14f;
                scaleY = 1f - poke * .18f;
                offsetX = (touchNormalizedX < .5f ? 1f : -1f) * poke * 7f * density;
                rotation = (touchNormalizedX < .5f ? 1f : -1f) * poke * 7f;
                break;
            case DRAG:
                rotation = Math.max(-14f, Math.min(14f, lastDragDx * .7f));
                scaleX = .94f;
                scaleY = 1.06f;
                offsetX = Math.max(-4f * density, Math.min(4f * density, lastDragDx * .18f));
                offsetY = -3f * density;
                break;
            case LAND:
                float landingWave = (float) (Math.sin(progress * Math.PI * 3f) * (1f - progress));
                float compression = Math.max(0f, landingWave);
                scaleX = 1f + compression * .16f;
                scaleY = 1f - compression * .20f;
                offsetY = Math.abs(landingWave) * 4f * density;
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
            case SLEEP:
                bob = (float) Math.sin(phase) * .6f * density;
                rotation = 0f;
                scaleX = 1f + (float) Math.sin(phase) * .008f;
                scaleY = 1f - (float) Math.sin(phase) * .012f;
                break;
            default:
                break;
        }

        if (speaking) {
            float syllable = (float) Math.sin((now % 360L) / 360f * Math.PI * 2f);
            scaleY *= 1f + syllable * .012f;
            rotation += syllable * .65f;
        }

        if (now < messageUntil || messageUntil == Long.MAX_VALUE) {
            drawBubble(canvas, message, width, density);
        }

        float top = 58f * density;
        float bottom = height - 2f * density;
        float left = 7f * density;
        float right = width - 7f * density;
        canvas.save();
        canvas.translate(width / 2f + offsetX, (top + bottom) / 2f + bob + offsetY);
        canvas.rotate(rotation);
        canvas.scale(scaleX, scaleY);
        canvas.translate(-width / 2f, -(top + bottom) / 2f);
        drawSkin(canvas, left, top, right, bottom);
        canvas.restore();

        postInvalidateDelayed(50L);
    }

    private void drawSkin(Canvas canvas, float left, float top, float right, float bottom) {
        Bitmap target = targetBitmap();
        if (target == null || target.isRecycled()) return;
        long now = System.currentTimeMillis();
        if (renderedBitmap != target) {
            transitionFromBitmap = renderedBitmap;
            renderedBitmap = target;
            bitmapTransitionStarted = now;
        }
        float transition = Math.min(1f, (now - bitmapTransitionStarted) / 170f);
        if (transitionFromBitmap != null && !transitionFromBitmap.isRecycled() && transition < 1f) {
            drawBitmap(canvas, transitionFromBitmap, left, top, right, bottom,
                    Math.round(255f * (1f - transition)), false);
        }
        drawBitmap(canvas, target, left, top, right, bottom, Math.round(255f * transition), true);
        if (transition >= 1f) transitionFromBitmap = null;
    }

    private void drawBitmap(Canvas canvas, Bitmap bitmap, float left, float top, float right,
                            float bottom, int alpha, boolean updateHitRect) {
        float maxWidth = right - left;
        float maxHeight = bottom - top;
        float ratio = Math.min(maxWidth / bitmap.getWidth(), maxHeight / bitmap.getHeight());
        float drawWidth = bitmap.getWidth() * ratio;
        float drawHeight = bitmap.getHeight() * ratio;
        float x = (getWidth() - drawWidth) / 2f;
        float y = bottom - drawHeight;
        RectF targetRect = updateHitRect ? imageRect : new RectF();
        targetRect.set(x, y, x + drawWidth, y + drawHeight);
        imagePaint.setFilterBitmap(selectedSkin != 2);
        imagePaint.setAlpha(alpha);
        canvas.drawBitmap(bitmap, null, targetRect, imagePaint);
        imagePaint.setAlpha(255);
    }

    private Bitmap targetBitmap() {
        if (selectedSkin != 1) return skinBitmap;
        if (speaking && talkingBitmap != null) return talkingBitmap;
        switch (action) {
            case PET: return pettedBitmap != null ? pettedBitmap : skinBitmap;
            case POKE: return pokedBitmap != null ? pokedBitmap : skinBitmap;
            case SLEEP: return sleepingBitmap != null ? sleepingBitmap : skinBitmap;
            default: return skinBitmap;
        }
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

    private void loadMistExpressions() {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        pettedBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.skin_mist_blob_petted, options);
        pokedBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.skin_mist_blob_poked, options);
        talkingBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.skin_mist_blob_talking, options);
        sleepingBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.skin_mist_blob_sleeping, options);
    }

    private void recycleBitmaps() {
        Bitmap[] bitmaps = {skinBitmap, pettedBitmap, pokedBitmap, talkingBitmap, sleepingBitmap};
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        skinBitmap = pettedBitmap = pokedBitmap = talkingBitmap = sleepingBitmap = null;
        renderedBitmap = transitionFromBitmap = null;
    }

    private void updateAutonomousBehavior(long now) {
        if (speaking || action == Action.DRAG || action == Action.SLEEP) return;
        if (action == Action.IDLE && now - lastInteractionAt > 38_000L) {
            messageUntil = 0L;
            setAction(Action.SLEEP, Long.MAX_VALUE);
            return;
        }
        if (action == Action.IDLE && now >= nextAutonomousAt) {
            touchNormalizedX = random.nextBoolean() ? .25f : .75f;
            setAction(Action.OBSERVE, 1800L + random.nextInt(900));
            nextAutonomousAt = now + 9000L + random.nextInt(9000);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = lastRawX = event.getRawX();
                downRawY = lastRawY = event.getRawY();
                downAt = event.getEventTime();
                moved = false;
                stroked = false;
                longPressed = false;
                lastDragDx = lastDragDy = 0f;
                touchNormalizedX = getWidth() == 0 ? .5f : event.getX() / getWidth();
                touchNormalizedY = getHeight() == 0 ? .5f : event.getY() / getHeight();
                lastInteractionAt = System.currentTimeMillis();
                nextAutonomousAt = lastInteractionAt + 9000L + random.nextInt(7000);
                if (action == Action.SLEEP) {
                    showMessage("唔……你来啦", Action.OBSERVE, 1800L);
                } else {
                    setAction(Action.OBSERVE, 700L);
                }
                handler.postDelayed(longPressRunnable, 780L);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - lastRawX;
                float dy = event.getRawY() - lastRawY;
                float totalDx = event.getRawX() - downRawX;
                float totalDy = event.getRawY() - downRawY;
                float distance = (float) Math.hypot(totalDx, totalDy);
                float density = getResources().getDisplayMetrics().density;
                if (distance > 22f * density) {
                    moved = true;
                    stroked = false;
                    action = Action.DRAG;
                    handler.removeCallbacks(longPressRunnable);
                    dragListener.moveBy(Math.round(dx), Math.round(dy));
                } else if (event.getEventTime() - downAt > 130L &&
                        touchNormalizedY < .68f && distance > 5f * density) {
                    stroked = true;
                    action = Action.PET;
                    actionStarted = System.currentTimeMillis();
                    actionUntil = actionStarted + 1600L;
                    handler.removeCallbacks(longPressRunnable);
                }
                lastDragDx = dx;
                lastDragDy = dy;
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                lastInteractionAt = System.currentTimeMillis();
                if (moved) {
                    setAction(Action.LAND, 760L);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (stroked) {
                    String[] phrases = {"嗯……再摸一下", "这里很舒服", "尾巴都要藏不住啦"};
                    showMessage(phrases[random.nextInt(phrases.length)], Action.PET, 2300L);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (!longPressed && event.getActionMasked() == MotionEvent.ACTION_UP) {
                    String[] phrases = touchNormalizedY < .62f
                            ? new String[]{"呀！", "被你戳到了", "我有在看你哦"}
                            : new String[]{"肚子软软的吧", "不许一直戳", "痒……"};
                    showMessage(phrases[random.nextInt(phrases.length)], Action.POKE, 1500L);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
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
        if (moved || stroked) return;
        longPressed = true;
        showMessage("抱一下……再帮你记事", Action.PET, 1500L);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        handler.postDelayed(() -> {
            Intent intent = new Intent(getContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_QUICK_ADD, true);
            getContext().startActivity(intent);
        }, 350L);
    };
}
