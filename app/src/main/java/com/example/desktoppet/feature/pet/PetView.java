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
    private float musicDiscAngle;
    private float musicRunPhase = .58f;
    private long musicLastFrameAt;
    private final RectF musicOval = new RectF();
    private final Path musicPath = new Path();

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
                musicDiscAngle = 0f;
                musicRunPhase = .58f;
                musicLastFrameAt = now;
                musicSceneListener.onVisibilityChanged(true);
            }
            musicPlaying = true;
            musicPaused = false;
            musicExitAt = Long.MAX_VALUE;
            if (trackChanged || now - musicSceneStarted < 250L) {
                String track = musicTitle;
                if (!musicArtist.isEmpty()) track += " · " + musicArtist;
                message = track.isEmpty() ? "四季开始流转" : track;
                messageUntil = now + 3800L;
            }
            if (action == Action.SLEEP) action = Action.IDLE;
            lastInteractionAt = now;
        } else if (paused && musicSceneVisible) {
            musicPlaying = false;
            musicPaused = true;
            musicExitAt = now + 18_000L;
            message = "四季停在这里，等你回来";
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

        float musicStageAlpha = 0f;
        if (musicSceneVisible) {
            advanceMusicScene(now);
            musicStageAlpha = Math.min(1f, (now - musicSceneStarted) / 650f);
            if (musicExitAt != Long.MAX_VALUE && musicExitAt - now < 1200L) {
                musicStageAlpha *= Math.max(0f, (musicExitAt - now) / 1200f);
            }
            drawMusicStage(canvas, width, height, density, now, musicStageAlpha);
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

        if (musicPlaying && !musicSceneVisible && action != Action.DRAG
                && action != Action.POKE && action != Action.PET) {
            double beat = (now % 720L) / 720.0 * Math.PI * 2.0;
            rotation += (float) Math.sin(beat) * 4.6f;
            bob += (float) Math.abs(Math.sin(beat)) * -2.6f * density;
            scaleY *= 1f - (float) Math.sin(beat) * .016f;
        }

        if (now < messageUntil || messageUntil == Long.MAX_VALUE) {
            if (musicSceneVisible) drawMusicCaption(canvas, message, width, height, density, musicStageAlpha);
            else drawBubble(canvas, message, width, density);
        } else if (musicSceneVisible && !musicTitle.isEmpty()) {
            String track = musicArtist.isEmpty() ? musicTitle : musicTitle + " · " + musicArtist;
            drawMusicCaption(canvas, track, width, height, density, musicStageAlpha);
        }

        if (musicSceneVisible) {
            drawMusicPet(canvas, width, height, density, now, musicStageAlpha);
        } else {
            float top = 58f * density;
            float bottom = height - 2f * density;
            float left = 7f * density;
            float right = width - 7f * density;
            float pivotX = (left + right) / 2f;
            canvas.save();
            float pivotY = (top + bottom) / 2f;
            pose.setScale(1f + (scaleX - 1f) * .5f,
                    1f + (scaleY - 1f) * .5f, pivotX, pivotY);
            pose.postRotate(rotation * .55f, pivotX, pivotY);
            pose.postTranslate(offsetX, bob + offsetY);
            canvas.concat(pose);
            pose.invert(inversePose);
            drawSkin(canvas, left, top, right, bottom);
            canvas.restore();
        }

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
        density = musicDensity(width, height, density);
        float cx = width * .5f;
        float cy = height * .50f;
        float radius = Math.min(width * .465f, 168f * density);
        float flatten = .42f;
        float ry = radius * flatten;
        float terrainRadius = radius * .82f;
        float edgeDepth = 10f * density;
        float entrance = easeOut(Math.min(1f, (now - musicSceneStarted) / 720f));

        canvas.save();
        canvas.scale(.86f + entrance * .14f, .86f + entrance * .14f, cx, cy);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(Color.argb(Math.round(92f * alpha), 3, 5, 7));
        musicOval.set(cx - radius * .92f, cy + ry * .66f,
                cx + radius * .92f, cy + ry + edgeDepth * 1.8f);
        canvas.drawOval(musicOval, paint);

        musicOval.set(cx - radius, cy - ry + edgeDepth,
                cx + radius, cy + ry + edgeDepth);
        paint.setShader(new LinearGradient(cx, cy - ry, cx, cy + ry + edgeDepth,
                Color.argb(Math.round(255f * alpha), 43, 48, 53),
                Color.argb(Math.round(255f * alpha), 3, 5, 8), Shader.TileMode.CLAMP));
        canvas.drawOval(musicOval, paint);
        paint.setShader(null);

        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(1f, flatten);
        canvas.rotate(musicDiscAngle);
        musicPath.reset();
        musicPath.addCircle(0f, 0f, radius, Path.Direction.CW);
        canvas.clipPath(musicPath);

        paint.setShader(new LinearGradient(-radius, -radius, radius, radius,
                Color.argb(Math.round(255f * alpha), 35, 40, 45),
                Color.argb(Math.round(255f * alpha), 2, 4, 7), Shader.TileMode.CLAMP));
        canvas.drawCircle(0f, 0f, radius, paint);

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.65f * density);
        for (int i = 0; i < 6; i++) {
            float groove = radius * (.86f + i * .022f);
            paint.setColor(Color.argb(Math.round((65f + i * 7f) * alpha), 126, 139, 146));
            canvas.drawCircle(0f, 0f, groove, paint);
        }
        paint.setStrokeWidth(2.1f * density);
        paint.setColor(Color.argb(Math.round(130f * alpha), 167, 188, 196));
        canvas.drawArc(-radius * .96f, -radius * .96f, radius * .96f, radius * .96f,
                198f, 58f, false, paint);
        paint.setStyle(Paint.Style.FILL);

        canvas.save();
        musicPath.reset();
        musicPath.addCircle(0f, 0f, terrainRadius, Path.Direction.CW);
        canvas.clipPath(musicPath);

        paint.setShader(new LinearGradient(-terrainRadius, -terrainRadius,
                terrainRadius, terrainRadius,
                Color.argb(Math.round(255f * alpha), 205, 238, 255),
                Color.argb(Math.round(255f * alpha), 137, 190, 219), Shader.TileMode.CLAMP));
        canvas.drawCircle(0f, 0f, terrainRadius, paint);

        paint.setShader(new LinearGradient(-terrainRadius, -terrainRadius,
                terrainRadius * .2f, terrainRadius * .2f,
                Color.argb(Math.round(255f * alpha), 130, 224, 82),
                Color.argb(Math.round(255f * alpha), 45, 142, 70), Shader.TileMode.CLAMP));
        canvas.drawCircle(-terrainRadius * .38f, -terrainRadius * .53f,
                terrainRadius * .83f, paint);

        paint.setShader(new LinearGradient(-terrainRadius * .1f, -terrainRadius,
                terrainRadius * .8f, terrainRadius * .15f,
                Color.argb(Math.round(255f * alpha), 75, 180, 85),
                Color.argb(Math.round(255f * alpha), 17, 104, 57), Shader.TileMode.CLAMP));
        canvas.drawCircle(terrainRadius * .12f, -terrainRadius * .83f,
                terrainRadius * .76f, paint);

        paint.setShader(new LinearGradient(terrainRadius * .1f, -terrainRadius,
                terrainRadius, terrainRadius * .2f,
                Color.argb(Math.round(255f * alpha), 246, 184, 70),
                Color.argb(Math.round(255f * alpha), 183, 80, 31), Shader.TileMode.CLAMP));
        canvas.drawCircle(terrainRadius * .70f, -terrainRadius * .36f,
                terrainRadius * .73f, paint);
        paint.setShader(null);

        drawSeasonTexture(canvas, terrainRadius, density, alpha);
        canvas.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f * density);
        paint.setColor(Color.argb(Math.round(210f * alpha), 221, 231, 220));
        canvas.drawCircle(0f, 0f, terrainRadius, paint);
        paint.setStrokeWidth(1.1f * density);
        paint.setColor(Color.argb(Math.round(165f * alpha), 188, 207, 214));
        canvas.drawCircle(0f, 0f, radius - 1.2f * density, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();

        drawRimTicks(canvas, cx, cy, radius, ry, edgeDepth, density, alpha);
        drawMusicHub(canvas, cx, cy, radius, ry, density, alpha);
        drawSeasonWeather(canvas, cx, cy, radius, ry, density, alpha);
        canvas.restore();
    }

    private void advanceMusicScene(long now) {
        if (musicLastFrameAt == 0L) musicLastFrameAt = now;
        float dt = Math.min(.05f, Math.max(0f, (now - musicLastFrameAt) / 1000f));
        musicLastFrameAt = now;
        if (!musicPlaying) return;
        musicDiscAngle = (musicDiscAngle + dt * 15f) % 360f;
        musicRunPhase = (float) ((musicRunPhase + dt * .68f) % (Math.PI * 2.0));
    }

    private float musicDensity(float width, float height, float density) {
        return Math.min(density, Math.min(width / 360f, height / 250f));
    }

    private float easeOut(float value) {
        float inverse = 1f - value;
        return 1f - inverse * inverse * inverse;
    }

    private void drawSeasonTexture(Canvas canvas, float radius, float density, float alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 210; i++) {
            double angle = i * 2.3999632297 + .37;
            float radial = radius * .96f * (float) Math.sqrt((i + .5f) / 210f);
            float x = (float) Math.cos(angle) * radial;
            float y = (float) Math.sin(angle) * radial;
            float blade = (3.6f + i % 5 * .72f) * density;
            if (y > radius * .06f) {
                paint.setColor(Color.argb(Math.round((105f + i % 4 * 18f) * alpha),
                        226, 245, 250));
                paint.setStrokeWidth((.65f + i % 3 * .14f) * density);
                canvas.drawLine(x, y + blade * .35f, x + (i % 3 - 1) * .65f * density,
                        y - blade * .45f, paint);
            } else if (x > radius * .28f) {
                paint.setColor(Color.argb(Math.round((120f + i % 5 * 15f) * alpha),
                        113, 57, 21));
                paint.setStrokeWidth((.7f + i % 2 * .18f) * density);
                canvas.drawLine(x, y + blade * .42f, x + (i % 3 - 1) * .9f * density,
                        y - blade * .58f, paint);
            } else {
                paint.setColor(Color.argb(Math.round((125f + i % 5 * 16f) * alpha),
                        20, 101 + i % 3 * 13, 48));
                paint.setStrokeWidth((.72f + i % 2 * .18f) * density);
                canvas.drawLine(x, y + blade * .42f, x + (i % 3 - 1) * .8f * density,
                        y - blade * .58f, paint);
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawRimTicks(Canvas canvas, float cx, float cy, float rx, float ry,
                              float edgeDepth, float density, float alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.15f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Color.argb(Math.round(205f * alpha), 225, 218, 197));
        double rotation = Math.toRadians(musicDiscAngle);
        for (int i = 0; i < 38; i++) {
            double angle = i * Math.PI * 2.0 / 38.0 + rotation;
            if (Math.sin(angle) < -.18) continue;
            float x = cx + (float) Math.cos(angle) * rx;
            float y = cy + (float) Math.sin(angle) * ry + edgeDepth * .35f;
            canvas.drawLine(x, y, x, y + edgeDepth * .58f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMusicHub(Canvas canvas, float cx, float cy, float radius, float ry,
                              float density, float alpha) {
        float pointerAngle = (float) Math.toRadians(-72f + musicDiscAngle * 1.65f);
        float endX = cx + (float) Math.cos(pointerAngle) * radius * .77f;
        float endY = cy + (float) Math.sin(pointerAngle) * ry * .96f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.7f * density);
        paint.setColor(Color.argb(Math.round(250f * alpha), 238, 184, 50));
        canvas.drawLine(cx, cy - 1f * density, endX, endY, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        paint.setStyle(Paint.Style.FILL);
        musicPath.reset();
        float dx = (float) Math.cos(pointerAngle);
        float dy = (float) Math.sin(pointerAngle);
        float px = -dy * 4f * density;
        float py = dx * 4f * density;
        musicPath.moveTo(endX + dx * 8f * density, endY + dy * 4f * density);
        musicPath.lineTo(endX + px, endY + py);
        musicPath.lineTo(endX - px, endY - py);
        musicPath.close();
        paint.setColor(Color.argb(Math.round(255f * alpha), 255, 205, 77));
        canvas.drawPath(musicPath, paint);

        musicOval.set(cx - radius * .095f, cy - ry * .13f + 2f * density,
                cx + radius * .095f, cy + ry * .13f + 3f * density);
        paint.setColor(Color.argb(Math.round(115f * alpha), 0, 0, 0));
        canvas.drawOval(musicOval, paint);
        musicOval.offset(0f, -2f * density);
        paint.setShader(new LinearGradient(musicOval.left, musicOval.top,
                musicOval.right, musicOval.bottom,
                Color.argb(Math.round(255f * alpha), 49, 50, 51),
                Color.argb(Math.round(255f * alpha), 6, 7, 8), Shader.TileMode.CLAMP));
        canvas.drawOval(musicOval, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.8f * density);
        paint.setColor(Color.argb(Math.round(145f * alpha), 202, 205, 207));
        canvas.drawOval(musicOval, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSeasonWeather(Canvas canvas, float cx, float cy, float rx, float ry,
                                   float density, float alpha) {
        int season = ((int) (musicDiscAngle / 90f)) & 3;
        float seasonProgress = (musicDiscAngle % 90f) / 90f;
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 30; i++) {
            float seed = (float) (Math.sin(i * 17.23 + 4.71) * 43758.5453);
            seed -= (float) Math.floor(seed);
            float fall = (seasonProgress * (1.25f + i % 3 * .18f) + i * .173f) % 1f;
            float x = cx + (seed - .5f) * rx * 1.78f;
            float y = cy - ry * 1.75f + fall * ry * 3.0f;
            if (season == 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(.65f * density);
                paint.setColor(Color.argb(Math.round(80f * alpha), 177, 198, 203));
                canvas.drawLine(x, y, x - 1.7f * density, y + 5.5f * density, paint);
            } else if (season == 1) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(Math.round(75f * alpha), 239, 228, 174));
                canvas.drawCircle(x, y, (.45f + seed * .65f) * density, paint);
            } else if (season == 2) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.7f * density);
                paint.setColor(Color.argb(Math.round(115f * alpha), 169, 104, 51));
                canvas.drawLine(x, y, x + (seed - .5f) * 5f * density,
                        y + 2.8f * density, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(Math.round((105f + seed * 95f) * alpha),
                        255, 255, 255));
                canvas.drawCircle(x, y, (.7f + seed * 1.05f) * density, paint);
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMusicPet(Canvas canvas, float width, float height, float density,
                              long now, float alpha) {
        Bitmap bitmap = targetBitmap();
        if (bitmap == null || bitmap.isRecycled()) return;
        density = musicDensity(width, height, density);
        float cx = width * .5f;
        float cy = height * .50f;
        float radius = Math.min(width * .465f, 168f * density);
        float ry = radius * .42f;
        float pathX = radius * .47f;
        float pathY = ry * .49f;
        float sin = (float) Math.sin(musicRunPhase);
        float cos = (float) Math.cos(musicRunPhase);
        float depth = .79f + (sin + 1f) * .105f;
        float footX = cx + cos * pathX;
        float footY = cy + sin * pathY + 2f * density;
        float run = musicPlaying ? (float) Math.sin(musicRunPhase * 10.5f) : 0f;
        float jump = musicPlaying
                ? Math.max(0f, (float) Math.sin(musicRunPhase * 5.25f)) : 0f;
        float bounce = musicPlaying ? jump * 10.5f * density
                : (float) Math.sin(now / 520.0) * .7f * density;
        float entrance = easeOut(Math.min(1f, (now - musicSceneStarted) / 720f));
        float petWidth = 52f * density * depth * (1.38f - entrance * .38f);
        float petHeight = petWidth * 1.04f;
        float groundY = footY;
        footY = groundY - (1f - entrance) * 18f * density - bounce;

        if (musicPlaying) {
            paint.setColor(Color.argb(Math.round(54f * alpha), 236, 237, 225));
            for (int i = 0; i < 3; i++) {
                float puffX = footX + (cos >= 0f ? -1f : 1f) * (5f + i * 3f) * density;
                float puffY = groundY - i * 1.2f * density;
                canvas.drawCircle(puffX, puffY, (2.2f - i * .4f) * density, paint);
            }
        }

        paint.setColor(Color.argb(Math.round((95f - jump * 42f) * alpha), 12, 18, 16));
        float shadowScale = 1f - jump * .28f;
        musicOval.set(footX - petWidth * .31f * shadowScale, groundY - 2.2f * density,
                footX + petWidth * .31f * shadowScale, groundY + 3.3f * density);
        canvas.drawOval(musicOval, paint);

        float left = footX - petWidth * .5f;
        float right = footX + petWidth * .5f;
        float top = footY - petHeight;
        float bottom = footY;
        float facing = -sin >= 0f ? 1f : -1f;
        float tilt = musicPlaying ? -run * 2.8f : 0f;
        canvas.save();
        pose.reset();
        float squash = 1f - (1f - jump) * Math.abs(run) * .055f;
        pose.postScale(facing, squash, footX, footY);
        pose.postRotate(tilt - jump * facing * 2.2f, footX, footY - petHeight * .45f);
        canvas.concat(pose);
        pose.invert(inversePose);
        drawBitmap(canvas, bitmap, left, top, right, bottom,
                Math.round(255f * alpha), true);
        canvas.restore();
        drawPetGrass(canvas, footX, groundY, petWidth, density, alpha);

        if (sin < -.15f) {
            drawMusicHub(canvas, cx, cy, radius, ry, density, alpha);
        }
    }

    private void drawPetGrass(Canvas canvas, float footX, float footY, float petWidth,
                              float density, float alpha) {
        int season = ((int) (musicDiscAngle / 90f)) & 3;
        int color;
        if (season == 2) color = Color.rgb(151, 80, 29);
        else if (season == 3) color = Color.rgb(193, 222, 218);
        else color = Color.rgb(24, 126, 58);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(1.05f * density);
        paint.setColor(Color.argb(Math.round(205f * alpha),
                Color.red(color), Color.green(color), Color.blue(color)));
        for (int i = 0; i < 11; i++) {
            float offset = (i - 5f) / 10f * petWidth * .72f;
            float sway = (i % 3 - 1) * 1.4f * density;
            float blade = (3.2f + i % 4 * 1.15f) * density;
            canvas.drawLine(footX + offset, footY + 1.5f * density,
                    footX + offset + sway, footY - blade, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMusicCaption(Canvas canvas, String text, float width, float height,
                                  float density, float alpha) {
        if (text == null || text.isEmpty()) return;
        density = musicDensity(width, height, density);
        String display = text.length() > 30 ? text.substring(0, 29) + "…" : text;
        if (musicPaused && !display.startsWith("四季停在这里")) display = "暂停 · " + display;
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(10.2f * density);
        float textWidth = paint.measureText(display);
        float pillWidth = Math.min(width - 22f * density,
                Math.max(166f * density, textWidth + 54f * density));
        float centerY = height - 16f * density;
        musicOval.set(width / 2f - pillWidth / 2f, centerY - 13f * density,
                width / 2f + pillWidth / 2f, centerY + 10f * density);
        paint.setColor(Color.argb(Math.round(218f * alpha), 18, 24, 25));
        canvas.drawRoundRect(musicOval, 12f * density, 12f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(.75f * density);
        paint.setColor(Color.argb(Math.round(110f * alpha), 185, 214, 196));
        canvas.drawRoundRect(musicOval, 12f * density, 12f * density, paint);

        float barsLeft = musicOval.left + 15f * density;
        float barsBottom = centerY + 4f * density;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.2f * density);
        paint.setColor(Color.argb(Math.round(245f * alpha), 100, 220, 112));
        for (int i = 0; i < 3; i++) {
            float energy = musicPlaying
                    ? .45f + .55f * Math.abs((float) Math.sin(
                    Math.toRadians(musicDiscAngle * 3.2f + i * 74f))) : .25f;
            canvas.drawLine(barsLeft + i * 5f * density, barsBottom,
                    barsLeft + i * 5f * density,
                    barsBottom - (4f + energy * 8f) * density, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.argb(Math.round(248f * alpha), 250, 251, 247));
        canvas.drawText(display, musicOval.left + 38f * density,
                centerY + 3.7f * density, paint);
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
