package com.example.desktoppet.feature.pet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import com.example.desktoppet.R;
import com.example.desktoppet.ui.MainActivity;

import java.util.Random;

@SuppressLint("ViewConstructor")
public final class PetView extends View {
    static final int WALL_X = 1, WALL_Y = 2;
    interface DragListener {
        int moveBy(int dx, int dy);
    }

    interface MusicSceneListener {
        void onVisibilityChanged(boolean visible);
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
    private final MusicSceneListener musicSceneListener;
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
    private final PetRig rig = new PetRig();
    private final android.graphics.Matrix pose = new android.graphics.Matrix();
    private final android.graphics.Matrix inversePose = new android.graphics.Matrix();
    private final float[] hitPoint = new float[2];
    private float carryX, carryY;
    private long nextBlink, blinkStart = -1000;
    private float blink;
    private boolean touching;
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
    private boolean musicSceneVisible;
    private boolean musicPlaying;
    private boolean musicPaused;
    private long musicSceneStarted;
    private long musicExitAt = Long.MAX_VALUE;
    private String musicTitle = "";
    private String musicArtist = "";

    PetView(Context context, DragListener dragListener, MusicSceneListener musicSceneListener) {
        super(context);
        this.dragListener = dragListener;
        this.musicSceneListener = musicSceneListener;
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

    void setMusicPlayback(boolean playing, boolean paused, String title, String artist) {
        long now = System.currentTimeMillis();
        String nextTitle = title == null ? "" : title.trim();
        String nextArtist = artist == null ? "" : artist.trim();
        boolean trackChanged = !nextTitle.isEmpty() && !nextTitle.equals(musicTitle);
        musicTitle = nextTitle;
        musicArtist = nextArtist;

        if (playing) {
            if (!musicSceneVisible) {
                musicSceneVisible = true;
                musicSceneStarted = now;
                musicSceneListener.onVisibilityChanged(true);
            }
            musicPlaying = true;
            musicPaused = false;
            musicExitAt = Long.MAX_VALUE;
            if (trackChanged || now - musicSceneStarted < 250L) {
                String track = musicTitle;
                if (!musicArtist.isEmpty()) track += " · " + musicArtist;
                message = track.isEmpty() ? "黑胶伴听模式启动" : "♪ " + track;
                messageUntil = now + 3800L;
            }
            if (action == Action.SLEEP) action = Action.IDLE;
            lastInteractionAt = now;
        } else if (paused && musicSceneVisible) {
            musicPlaying = false;
            musicPaused = true;
            musicExitAt = now + 18_000L;
            message = "唱针抬起，等你回来";
            messageUntil = now + 3200L;
        } else if (musicSceneVisible) {
            musicPlaying = false;
            musicPaused = false;
            musicExitAt = Math.min(musicExitAt, now + 1800L);
        }
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
        if (musicSceneVisible && now >= musicExitAt) {
            musicSceneVisible = false;
            musicPlaying = false;
            musicPaused = false;
            musicExitAt = Long.MAX_VALUE;
            musicSceneListener.onVisibilityChanged(false);
        }
        if (now > actionUntil && action != Action.DRAG && action != Action.SLEEP) {
            action = Action.IDLE;
        }
        updateAutonomousBehavior(now);
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        double phase = (now % 2600L) / 2600.0 * Math.PI * 2.0;
        long tick = android.os.SystemClock.uptimeMillis();
        if (tick >= nextBlink) {
            blinkStart = tick;
            nextBlink = tick + 2200L + random.nextInt(3600);
        }
        blink = (float)Math.sin(Math.PI * Math.min(1f, (tick - blinkStart) / 170f));
        float attention = action == Action.OBSERVE || action == Action.PET ?
                (touchNormalizedX - .5f) * 2f : 0;
        float dt = rig.advance(tick, action == Action.DRAG, attention,
                action == Action.PET || action == Action.POKE ? 1f : 0);
        advanceCoast(dt, density);
        float bob = 0f;
        float rotation = 0f;
        float scaleX = 1f;
        float scaleY = 1f;
        float offsetX = 0f;
        float offsetY = 0f;

        if (musicSceneVisible) {
            float stageAlpha = Math.min(1f, (now - musicSceneStarted) / 650f);
            if (musicExitAt != Long.MAX_VALUE && musicExitAt - now < 1200L) {
                stageAlpha *= Math.max(0f, (musicExitAt - now) / 1200f);
            }
            drawMusicStage(canvas, width, height, density, now, stageAlpha);
        }

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
            case LAND:
                // The mesh keeps its position and angular momentum across release.
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

        if (musicPlaying && action != Action.DRAG && action != Action.POKE && action != Action.PET) {
            double beat = (now % 720L) / 720.0 * Math.PI * 2.0;
            rotation += (float) Math.sin(beat) * 4.6f;
            bob += (float) Math.abs(Math.sin(beat)) * -2.6f * density;
            scaleY *= 1f - (float) Math.sin(beat) * .016f;
        }

        if (now < messageUntil || messageUntil == Long.MAX_VALUE) {
            drawBubble(canvas, message, width, density);
        }

        float top = musicSceneVisible ? height * .24f : 58f * density;
        float bottom = height - (musicSceneVisible ? 9f : 2f) * density;
        float left = musicSceneVisible ? width * .48f : 7f * density;
        float right = width - 7f * density;
        float pivotX = (left + right) / 2f;
        canvas.save();
        float pivotY = (top + bottom) / 2f;
        pose.setScale(1f + (scaleX - 1f) * .5f, 1f + (scaleY - 1f) * .5f, pivotX, pivotY);
        pose.postRotate(rotation * .55f, pivotX, pivotY);
        pose.postTranslate(offsetX, bob + offsetY);
        canvas.concat(pose);
        pose.invert(inversePose);
        drawSkin(canvas, left, top, right, bottom);
        canvas.restore();

        if (touching || speaking || musicSceneVisible || (action != Action.IDLE && action != Action.SLEEP)
                || tick - blinkStart < 200L) postInvalidateOnAnimation();
        else postInvalidateDelayed(action == Action.SLEEP ? 100L : 40L);
    }

    private void advanceCoast(float dt, float density) {
        if (Math.abs(rig.coastX) + Math.abs(rig.coastY) < 4f) return;
        float decay = (float)Math.exp(-dt * 8f);
        carryX += rig.coastX * density * (1f - decay) / 8f;
        carryY += rig.coastY * density * (1f - decay) / 8f;
        rig.coastX *= decay;
        rig.coastY *= decay;
        int dx = Math.round(carryX), dy = Math.round(carryY);
        carryX -= dx;
        carryY -= dy;
        if (dx == 0 && dy == 0) return;
        int walls = dragListener.moveBy(dx, dy);
        if ((walls & WALL_X) != 0) rig.bumpX();
        if ((walls & WALL_Y) != 0) rig.bumpY();
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
        float x = left + (maxWidth - drawWidth) / 2f;
        float y = bottom - drawHeight;
        RectF targetRect = updateHitRect ? imageRect : new RectF();
        targetRect.set(x, y, x + drawWidth, y + drawHeight);
        imagePaint.setFilterBitmap(selectedSkin != 2);
        imagePaint.setAlpha(alpha);
        rig.draw(canvas, bitmap, targetRect, imagePaint, selectedSkin,
                bitmap == sleepingBitmap, speaking, android.os.SystemClock.uptimeMillis(),
                bitmap == skinBitmap ? blink : 0);
        imagePaint.setAlpha(255);
    }

    private Bitmap targetBitmap() {
        if (selectedSkin != 1) return skinBitmap;
        if (speaking && talkingBitmap != null) return talkingBitmap;
        if (musicSceneVisible && pettedBitmap != null) return pettedBitmap;
        switch (action) {
            case PET: return pettedBitmap != null ? pettedBitmap : skinBitmap;
            case POKE: return pokedBitmap != null ? pokedBitmap : skinBitmap;
            case SLEEP: return sleepingBitmap != null ? sleepingBitmap : skinBitmap;
            default: return skinBitmap;
        }
    }

    private void drawMusicStage(Canvas canvas, float width, float height, float density,
                                long now, float alpha) {
        density = Math.min(density, Math.min(width / 330f, height / 220f));
        int stageAlpha = Math.round(238f * alpha);
        float panelLeft = 4f * density;
        float panelTop = 53f * density;
        float panelRight = width - 4f * density;
        float panelBottom = height - 3f * density;
        RectF panel = new RectF(panelLeft, panelTop, panelRight, panelBottom);
        paint.setShader(new LinearGradient(panelLeft, panelTop, panelRight, panelBottom,
                new int[]{Color.argb(stageAlpha, 8, 10, 27),
                        Color.argb(stageAlpha, 35, 16, 48),
                        Color.argb(stageAlpha, 8, 34, 46)},
                null, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(panel, 22f * density, 22f * density, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f * density);
        paint.setColor(Color.argb(Math.round(150f * alpha), 67, 231, 246));
        canvas.drawRoundRect(panel, 22f * density, 22f * density, paint);
        paint.setStyle(Paint.Style.FILL);

        float barsBottom = panelBottom - 8f * density;
        for (int i = 0; i < 11; i++) {
            double wave = now / 185.0 + i * .82;
            float energy = musicPlaying ? (float) Math.abs(Math.sin(wave)) : .12f;
            float barHeight = (5f + energy * (18f + i % 3 * 5f)) * density;
            float barLeft = panelLeft + (12f + i * 10f) * density;
            paint.setColor(Color.argb(Math.round((75f + energy * 105f) * alpha),
                    i % 2 == 0 ? 54 : 244, i % 2 == 0 ? 220 : 76,
                    i % 2 == 0 ? 244 : 151));
            canvas.drawRoundRect(new RectF(barLeft, barsBottom - barHeight,
                    barLeft + 4f * density, barsBottom), 2f * density, 2f * density, paint);
        }

        float cx = Math.min(width * .27f, 92f * density);
        float cy = height * .63f;
        float radius = Math.min(43f * density, width * .145f);
        RectF deck = new RectF(cx - radius - 15f * density, cy - radius - 13f * density,
                cx + radius + 24f * density, cy + radius + 17f * density);
        paint.setShader(new LinearGradient(deck.left, deck.top, deck.right, deck.bottom,
                Color.argb(Math.round(245f * alpha), 112, 55, 38),
                Color.argb(Math.round(245f * alpha), 36, 18, 28), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(deck, 12f * density, 12f * density, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setColor(Color.argb(Math.round(180f * alpha), 255, 171, 79));
        canvas.drawRoundRect(deck, 12f * density, 12f * density, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(Color.argb(Math.round(110f * alpha), 45, 226, 247));
        canvas.drawCircle(cx, cy + 2f * density, radius + 6f * density, paint);
        canvas.save();
        if (musicPlaying) canvas.rotate((now - musicSceneStarted) * .095f, cx, cy);
        paint.setColor(Color.argb(Math.round(255f * alpha), 8, 8, 13));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.75f * density);
        for (int i = 1; i <= 6; i++) {
            paint.setColor(Color.argb(Math.round((45f + i * 8f) * alpha), 185, 199, 214));
            canvas.drawCircle(cx, cy, radius * i / 7f, paint);
        }
        paint.setStrokeWidth(5f * density);
        paint.setColor(Color.argb(Math.round(90f * alpha), 90, 224, 244));
        canvas.drawArc(new RectF(cx - radius * .76f, cy - radius * .76f,
                cx + radius * .76f, cy + radius * .76f), 205f, 34f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(255f * alpha), 239, 88, 125));
        canvas.drawCircle(cx, cy, radius * .25f, paint);
        paint.setColor(Color.argb(Math.round(255f * alpha), 255, 205, 92));
        canvas.drawCircle(cx, cy, radius * .075f, paint);
        paint.setStrokeWidth(2f * density);
        paint.setColor(Color.argb(Math.round(225f * alpha), 255, 225, 175));
        canvas.drawRect(cx - radius * .05f, cy - radius * .23f,
                cx + radius * .05f, cy + radius * .23f, paint);
        canvas.restore();

        float armX = cx + radius + 13f * density;
        float armY = cy - radius + 5f * density;
        float needleX = musicPlaying ? cx + radius * .48f : cx + radius * .84f;
        float needleY = musicPlaying ? cy - radius * .28f : cy - radius * .70f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(4f * density);
        paint.setColor(Color.argb(Math.round(235f * alpha), 220, 224, 229));
        canvas.drawLine(armX, armY, needleX, needleY, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(Math.round(255f * alpha), 250, 188, 78));
        canvas.drawCircle(armX, armY, 6f * density, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        for (int i = 0; i < 5; i++) {
            double notePhase = (now / 760.0) + i * 1.37;
            float noteX = cx - radius + (i * 23f % 86f) * density;
            float noteY = panelTop + (24f + (float) Math.sin(notePhase) * 11f) * density;
            paint.setTextSize((16f + i % 2 * 5f) * density);
            paint.setTextAlign(Paint.Align.CENTER);
            int noteAlpha = Math.round((musicPlaying ? 185f : 65f) * alpha);
            paint.setColor(Color.argb(noteAlpha, i % 2 == 0 ? 69 : 255,
                    i % 2 == 0 ? 229 : 107, i % 2 == 0 ? 244 : 170));
            canvas.drawText(i % 2 == 0 ? "♪" : "♫", noteX, noteY, paint);
        }

        String label = musicPaused ? "PAUSED" : "VINYL MODE";
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(8.5f * density);
        paint.setColor(Color.argb(Math.round(210f * alpha), 194, 242, 248));
        canvas.drawText(label, cx, panelBottom - 10f * density, paint);
        if (!musicTitle.isEmpty()) {
            String title = musicTitle.length() > 11 ? musicTitle.substring(0, 10) + "…" : musicTitle;
            paint.setTextSize(9.5f * density);
            paint.setColor(Color.argb(Math.round(235f * alpha), 255, 219, 149));
            canvas.drawText(title, cx, panelTop + 16f * density, paint);
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
        selectedSkin = SkinCatalog.safeIndex(prefs.getInt("selected_skin", 1));
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(
                getResources(), SkinCatalog.BUILT_INS[selectedSkin].resourceId, options);
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
        if (musicSceneVisible) return;
        if (speaking || action == Action.DRAG || action == Action.SLEEP) return;
        if (action == Action.IDLE && now - lastInteractionAt > 38_000L) {
            messageUntil = 0L;
            setAction(Action.SLEEP, Long.MAX_VALUE);
            return;
        }
        if (action == Action.IDLE && now >= nextAutonomousAt) {
            touchNormalizedX = random.nextBoolean() ? .25f : .75f;
            long lastUserInteraction = lastInteractionAt;
            setAction(Action.OBSERVE, 1800L + random.nextInt(900));
            lastInteractionAt = lastUserInteraction;
            nextAutonomousAt = now + 9000L + random.nextInt(9000);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                hitPoint[0] = event.getX();
                hitPoint[1] = event.getY();
                inversePose.mapPoints(hitPoint);
                if (renderedBitmap == null || !rig.texturePoint(hitPoint[0], hitPoint[1], hitPoint)) return false;
                touchNormalizedX = hitPoint[0];
                touchNormalizedY = hitPoint[1];
                int px = Math.min(renderedBitmap.getWidth()-1, (int)(touchNormalizedX * renderedBitmap.getWidth()));
                int py = Math.min(renderedBitmap.getHeight()-1, (int)(touchNormalizedY * renderedBitmap.getHeight()));
                if (Color.alpha(renderedBitmap.getPixel(px, py)) < 20) return false;
                touching = true;
                carryX = carryY = 0;
                rig.grab(touchNormalizedX, touchNormalizedY, event.getEventTime());
                downRawX = lastRawX = event.getRawX();
                downRawY = lastRawY = event.getRawY();
                downAt = event.getEventTime();
                moved = false;
                stroked = false;
                longPressed = false;
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
                rig.sample(dx / density, dy / density, event.getEventTime());
                if (moved || distance > (stroked ? 22f : 13f) * density) {
                    if (!moved) { dx = totalDx; dy = totalDy; }
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
                postInvalidateOnAnimation();
                lastRawX = event.getRawX();
                lastRawY = event.getRawY();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                touching = false;
                boolean canceled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                rig.release(event.getEventTime(), moved && !canceled);
                if (canceled) {
                    setAction(Action.IDLE, 0);
                    return true;
                }
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
