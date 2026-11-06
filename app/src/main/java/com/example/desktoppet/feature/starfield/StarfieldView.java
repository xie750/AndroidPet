package com.example.desktoppet.feature.starfield;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Random;

/** Full-screen, hardware-accelerated star field with draggable foreground stars. */
@SuppressLint("ViewConstructor")
public final class StarfieldView extends View {
    private static final int FAR_STAR_COUNT = 78;
    private static final int MID_STAR_COUNT = 24;
    private static final int INTERACTIVE_STAR_COUNT = 9;
    private static final long ENTER_DURATION_MS = 420L;
    private static final long EXIT_DURATION_MS = 240L;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nebulaPaintA = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nebulaPaintB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint interfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path revealPath = new Path();
    private final RectF orbitBounds = new RectF();
    private final Particle[] farStars = new Particle[FAR_STAR_COUNT];
    private final Particle[] midStars = new Particle[MID_STAR_COUNT];
    private final InteractiveStar[] interactiveStars =
            new InteractiveStar[INTERACTIVE_STAR_COUNT];
    private final float density;
    private final int touchSlop;
    private final boolean reduceMotion;
    private final float requestedOriginX;
    private final float requestedOriginY;
    private float originX;
    private float originY;
    private float revealRadius;
    private long openedAt;
    private long lastFrameAt;
    private long exitStartedAt;
    private boolean running = true;
    private boolean closing;
    private boolean exitDelivered;
    private Runnable exitCallback;
    private int activeStar = -1;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float dragOffsetX;
    private float dragOffsetY;
    private float downX;
    private float downY;
    private boolean dragged;
    private VelocityTracker velocityTracker;

    public StarfieldView(Context context, float originX, float originY) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        reduceMotion = Settings.Global.getFloat(
                context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        requestedOriginX = originX;
        requestedOriginY = originY;
        openedAt = SystemClock.uptimeMillis();
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setFocusable(true);
        setClickable(true);
        setContentDescription("费雾互动星空。拖动发光的星星进行排列");
        linePaint.setStyle(Paint.Style.STROKE);
        interfacePaint.setStyle(Paint.Style.STROKE);
        initializeStars();
    }

    private void initializeStars() {
        Random random = new Random(0xF31A57L);
        for (int i = 0; i < farStars.length; i++) {
            farStars[i] = new Particle(
                    random.nextFloat(), random.nextFloat(),
                    .45f + random.nextFloat() * .75f,
                    82 + random.nextInt(112),
                    random.nextFloat() * 6.28318f,
                    .35f + random.nextFloat() * .55f);
        }
        for (int i = 0; i < midStars.length; i++) {
            midStars[i] = new Particle(
                    random.nextFloat(), random.nextFloat(),
                    1.05f + random.nextFloat() * 1.05f,
                    118 + random.nextInt(106),
                    random.nextFloat() * 6.28318f,
                    .52f + random.nextFloat() * .74f);
        }
        int[] colors = {
                0xFFBDF7FF, 0xFF83E8F5, 0xFFD8F8FF,
                0xFF72CFE8, 0xFFAEC7FF, 0xFF91F4E2
        };
        for (int i = 0; i < interactiveStars.length; i++) {
            float nx = .13f + random.nextFloat() * .74f;
            float ny = .16f + random.nextFloat() * .70f;
            interactiveStars[i] = new InteractiveStar(
                    nx, ny, 4.2f + random.nextFloat() * 2f,
                    colors[i % colors.length], random.nextFloat() * 6.28318f);
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        originX = clamp(requestedOriginX, 0f, width);
        originY = clamp(requestedOriginY, 0f, height);
        revealRadius = maxCornerDistance(originX, originY, width, height) + dp(24);
        backgroundPaint.setShader(new LinearGradient(
                0f, 0f, width, height,
                0x40000610, 0x52000D1A, Shader.TileMode.CLAMP));
        nebulaPaintA.setShader(new RadialGradient(
                width * .18f, height * .26f, Math.max(width, height) * .56f,
                new int[]{0x1F147087, 0x0C0E5369, Color.TRANSPARENT},
                new float[]{0f, .48f, 1f}, Shader.TileMode.CLAMP));
        nebulaPaintB.setShader(new RadialGradient(
                width * .82f, height * .72f, Math.max(width, height) * .48f,
                new int[]{0x1A354B91, 0x0B253E70, Color.TRANSPARENT},
                new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));

        for (InteractiveStar star : interactiveStars) {
            if (oldWidth > 0 && oldHeight > 0 && star.positioned) {
                star.x = star.x / oldWidth * width;
                star.y = star.y / oldHeight * height;
            } else {
                star.x = star.normalizedX * width;
                star.y = star.normalizedY * height;
                star.positioned = true;
            }
            keepInBounds(star);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.uptimeMillis();
        float dt = lastFrameAt == 0L ? 0f : Math.min(.034f, (now - lastFrameAt) / 1000f);
        lastFrameAt = now;
        updatePhysics(dt);

        float visibleRadius = revealRadius;
        if (!reduceMotion) {
            if (closing) {
                float exit = clamp((now - exitStartedAt) / (float) EXIT_DURATION_MS, 0f, 1f);
                visibleRadius = revealRadius * (1f - easeIn(exit));
                if (exit >= 1f) deliverExit();
            } else {
                float enter = clamp((now - openedAt) / (float) ENTER_DURATION_MS, 0f, 1f);
                visibleRadius = revealRadius * easeOut(enter);
            }
        }

        canvas.save();
        revealPath.rewind();
        revealPath.addCircle(originX, originY, visibleRadius, Path.Direction.CW);
        canvas.clipPath(revealPath);
        drawSpace(canvas, now);
        canvas.restore();

        if (running || closing) postInvalidateOnAnimation();
    }

    private void drawSpace(Canvas canvas, long now) {
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), backgroundPaint);
        float nebulaRadius = Math.max(getWidth(), getHeight()) * .62f;
        canvas.drawCircle(getWidth() * .18f, getHeight() * .26f,
                nebulaRadius, nebulaPaintA);
        canvas.drawCircle(getWidth() * .82f, getHeight() * .72f,
                nebulaRadius, nebulaPaintB);

        float seconds = now / 1000f;
        for (Particle star : farStars) drawParticle(canvas, star, seconds, false);
        for (Particle star : midStars) drawParticle(canvas, star, seconds, true);
        drawInterfaceField(canvas, seconds);
        drawConstellationLines(canvas);
        for (int i = 0; i < interactiveStars.length; i++) {
            drawInteractiveStar(canvas, interactiveStars[i], seconds, i == activeStar);
        }
    }

    private void drawParticle(Canvas canvas, Particle star, float seconds, boolean colored) {
        float pulse = .78f + .22f * (float) Math.sin(star.phase + seconds * star.speed);
        int alpha = Math.round(star.alpha * pulse);
        float drift = (colored ? .00115f : .00034f) * star.speed;
        float x = wrap01(star.normalizedX + seconds * drift) * getWidth();
        float y = wrap01(star.normalizedY + seconds * drift * .18f
                + (float) Math.sin(star.phase + seconds * .09f) * .0018f) * getHeight();
        if (colored) {
            starPaint.setColor(Color.argb(alpha, 134, 226, 242));
        } else {
            starPaint.setColor(Color.argb(alpha, 210, 239, 245));
        }
        canvas.drawCircle(x, y, dp(star.radiusDp), starPaint);
        if (colored && star.radiusDp > 1.45f) {
            linePaint.setColor(Color.argb(Math.min(64, alpha / 3), 105, 232, 247));
            linePaint.setStrokeWidth(dp(.55f));
            canvas.drawLine(x - dp(4), y, x + dp(4), y, linePaint);
        }
    }

    private void drawInterfaceField(Canvas canvas, float seconds) {
        interfacePaint.setStrokeWidth(dp(.55f));
        interfacePaint.setColor(Color.argb(22, 93, 226, 242));
        float scanY = wrap01(seconds * .018f) * getHeight();
        canvas.drawLine(0f, scanY, getWidth(), scanY, interfacePaint);

        float unit = Math.min(getWidth(), getHeight());
        float radius = unit * .34f;
        orbitBounds.set(getWidth() * .5f - radius, getHeight() * .5f - radius,
                getWidth() * .5f + radius, getHeight() * .5f + radius);
        interfacePaint.setColor(Color.argb(15, 111, 220, 239));
        canvas.drawArc(orbitBounds, -32f, 48f, false, interfacePaint);
        canvas.drawArc(orbitBounds, 148f, 31f, false, interfacePaint);

        float tick = dp(10);
        float inset = dp(20);
        interfacePaint.setColor(Color.argb(46, 128, 235, 248));
        canvas.drawLine(inset, inset, inset + tick, inset, interfacePaint);
        canvas.drawLine(inset, inset, inset, inset + tick, interfacePaint);
        canvas.drawLine(getWidth() - inset, getHeight() - inset,
                getWidth() - inset - tick, getHeight() - inset, interfacePaint);
        canvas.drawLine(getWidth() - inset, getHeight() - inset,
                getWidth() - inset, getHeight() - inset - tick, interfacePaint);
    }

    private void drawConstellationLines(Canvas canvas) {
        float connectDistance = dp(118);
        for (int i = 0; i < interactiveStars.length; i++) {
            InteractiveStar first = interactiveStars[i];
            for (int j = i + 1; j < interactiveStars.length; j++) {
                InteractiveStar second = interactiveStars[j];
                float distance = (float) Math.hypot(first.x - second.x, first.y - second.y);
                if (distance >= connectDistance) continue;
                int alpha = Math.round(72f * (1f - distance / connectDistance));
                linePaint.setColor(Color.argb(Math.min(52, alpha), 107, 226, 242));
                linePaint.setStrokeWidth(dp(.6f));
                canvas.drawLine(first.x, first.y, second.x, second.y, linePaint);
            }
        }
    }

    private void drawInteractiveStar(Canvas canvas, InteractiveStar star,
                                     float seconds, boolean selected) {
        float pulse = 1f + (float) Math.sin(star.phase + seconds * .85f) * .055f;
        float radius = dp(star.radiusDp) * pulse * (selected ? 1.12f : 1f);
        int red = Color.red(star.color);
        int green = Color.green(star.color);
        int blue = Color.blue(star.color);

        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(Color.argb(selected ? 34 : 16, red, green, blue));
        canvas.drawCircle(star.x, star.y, radius * 3.1f, glowPaint);
        glowPaint.setColor(Color.argb(selected ? 70 : 38, red, green, blue));
        canvas.drawCircle(star.x, star.y, radius * 1.45f, glowPaint);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(selected ? 1f : .7f));
        linePaint.setColor(Color.argb(selected ? 190 : 104, red, green, blue));
        float orbitRadius = radius * (selected ? 2.45f : 2.05f);
        orbitBounds.set(star.x - orbitRadius, star.y - orbitRadius,
                star.x + orbitRadius, star.y + orbitRadius);
        float phaseDegrees = (star.phase + seconds * .24f) * 57.29578f;
        canvas.drawArc(orbitBounds, phaseDegrees, selected ? 104f : 62f,
                false, linePaint);
        canvas.drawArc(orbitBounds, phaseDegrees + 180f, selected ? 76f : 42f,
                false, linePaint);

        float cross = radius * 1.9f;
        linePaint.setColor(Color.argb(selected ? 210 : 132, red, green, blue));
        canvas.drawLine(star.x - cross, star.y, star.x - radius * .72f, star.y, linePaint);
        canvas.drawLine(star.x + radius * .72f, star.y, star.x + cross, star.y, linePaint);
        canvas.drawLine(star.x, star.y - cross, star.x, star.y - radius * .72f, linePaint);
        canvas.drawLine(star.x, star.y + radius * .72f, star.x, star.y + cross, linePaint);

        starPaint.setStyle(Paint.Style.FILL);
        starPaint.setColor(star.color);
        canvas.drawCircle(star.x, star.y, radius * .52f, starPaint);
        starPaint.setColor(Color.WHITE);
        canvas.drawCircle(star.x, star.y, Math.max(dp(.8f), radius * .16f), starPaint);
    }

    private void updatePhysics(float dt) {
        if (dt <= 0f) return;
        float friction = (float) Math.exp(-3.8f * dt);
        float margin = dp(28);
        for (int i = 0; i < interactiveStars.length; i++) {
            if (i == activeStar) continue;
            InteractiveStar star = interactiveStars[i];
            star.x += star.velocityX * dt;
            star.y += star.velocityY * dt;
            star.velocityX *= friction;
            star.velocityY *= friction;
            if (Math.abs(star.velocityX) < 3f) star.velocityX = 0f;
            if (Math.abs(star.velocityY) < 3f) star.velocityY = 0f;
            if (star.x < margin) {
                star.x = margin;
                star.velocityX = Math.abs(star.velocityX) * .42f;
            } else if (star.x > getWidth() - margin) {
                star.x = getWidth() - margin;
                star.velocityX = -Math.abs(star.velocityX) * .42f;
            }
            if (star.y < margin) {
                star.y = margin;
                star.velocityY = Math.abs(star.velocityY) * .42f;
            } else if (star.y > getHeight() - margin) {
                star.y = getHeight() - margin;
                star.velocityY = -Math.abs(star.velocityY) * .42f;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            int index = findNearestStar(event.getX(), event.getY());
            if (index < 0) return true;
            activeStar = index;
            activePointerId = event.getPointerId(0);
            InteractiveStar star = interactiveStars[index];
            dragOffsetX = star.x - event.getX();
            dragOffsetY = star.y - event.getY();
            downX = event.getX();
            downY = event.getY();
            dragged = false;
            star.velocityX = star.velocityY = 0f;
            velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(event);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
            return true;
        }
        if (activeStar < 0) return true;
        if (velocityTracker != null) velocityTracker.addMovement(event);

        if (action == MotionEvent.ACTION_MOVE) {
            int pointerIndex = event.findPointerIndex(activePointerId);
            if (pointerIndex < 0) return true;
            float x = event.getX(pointerIndex);
            float y = event.getY(pointerIndex);
            if (!dragged && Math.hypot(x - downX, y - downY) > touchSlop) dragged = true;
            InteractiveStar star = interactiveStars[activeStar];
            star.x = clamp(x + dragOffsetX, dp(28), getWidth() - dp(28));
            star.y = clamp(y + dragOffsetY, dp(28), getHeight() - dp(28));
            gentlyRepelNeighbors(star);
            postInvalidateOnAnimation();
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            InteractiveStar star = interactiveStars[activeStar];
            if (action == MotionEvent.ACTION_UP && velocityTracker != null) {
                velocityTracker.computeCurrentVelocity(1000, dp(1650));
                star.velocityX = velocityTracker.getXVelocity(activePointerId);
                star.velocityY = velocityTracker.getYVelocity(activePointerId);
            } else {
                star.velocityX = star.velocityY = 0f;
            }
            if (!dragged && action == MotionEvent.ACTION_UP) {
                performClick();
                announceForAccessibility("星星被点亮");
            }
            recycleVelocityTracker();
            activeStar = -1;
            activePointerId = MotionEvent.INVALID_POINTER_ID;
            postInvalidateOnAnimation();
            return true;
        }
        return true;
    }

    private int findNearestStar(float x, float y) {
        float hitRadius = dp(28);
        float bestDistance = hitRadius;
        int best = -1;
        for (int i = interactiveStars.length - 1; i >= 0; i--) {
            InteractiveStar star = interactiveStars[i];
            float distance = (float) Math.hypot(x - star.x, y - star.y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private void gentlyRepelNeighbors(InteractiveStar draggedStar) {
        float range = dp(54);
        for (InteractiveStar other : interactiveStars) {
            if (other == draggedStar) continue;
            float dx = other.x - draggedStar.x;
            float dy = other.y - draggedStar.y;
            float distance = (float) Math.hypot(dx, dy);
            if (distance < 1f || distance >= range) continue;
            float push = (range - distance) * .075f;
            other.x += dx / distance * push;
            other.y += dy / distance * push;
            keepInBounds(other);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public void setRunning(boolean running) {
        this.running = running;
        lastFrameAt = 0L;
        if (running) postInvalidateOnAnimation();
    }

    public void beginExit(Runnable callback) {
        if (closing) return;
        closing = true;
        running = true;
        exitCallback = callback;
        exitStartedAt = SystemClock.uptimeMillis();
        if (reduceMotion) deliverExit();
        else postInvalidateOnAnimation();
    }

    private void deliverExit() {
        if (exitDelivered) return;
        exitDelivered = true;
        if (exitCallback != null) post(exitCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        recycleVelocityTracker();
        super.onDetachedFromWindow();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private void keepInBounds(InteractiveStar star) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float margin = dp(28);
        star.x = clamp(star.x, margin, getWidth() - margin);
        star.y = clamp(star.y, margin, getHeight() - margin);
    }

    private float maxCornerDistance(float x, float y, float width, float height) {
        float topLeft = (float) Math.hypot(x, y);
        float topRight = (float) Math.hypot(width - x, y);
        float bottomLeft = (float) Math.hypot(x, height - y);
        float bottomRight = (float) Math.hypot(width - x, height - y);
        return Math.max(Math.max(topLeft, topRight), Math.max(bottomLeft, bottomRight));
    }

    private float dp(float value) {
        return value * density;
    }

    private static float easeOut(float value) {
        float inverse = 1f - value;
        return 1f - inverse * inverse * inverse;
    }

    private static float easeIn(float value) {
        return value * value * value;
    }

    private static float wrap01(float value) {
        return value - (float) Math.floor(value);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Particle {
        final float normalizedX;
        final float normalizedY;
        final float radiusDp;
        final int alpha;
        final float phase;
        final float speed;

        Particle(float normalizedX, float normalizedY, float radiusDp,
                 int alpha, float phase, float speed) {
            this.normalizedX = normalizedX;
            this.normalizedY = normalizedY;
            this.radiusDp = radiusDp;
            this.alpha = alpha;
            this.phase = phase;
            this.speed = speed;
        }
    }

    private static final class InteractiveStar {
        final float normalizedX;
        final float normalizedY;
        final float radiusDp;
        final int color;
        final float phase;
        float x;
        float y;
        float velocityX;
        float velocityY;
        boolean positioned;

        InteractiveStar(float normalizedX, float normalizedY, float radiusDp,
                        int color, float phase) {
            this.normalizedX = normalizedX;
            this.normalizedY = normalizedY;
            this.radiusDp = radiusDp;
            this.color = color;
            this.phase = phase;
        }
    }
}
