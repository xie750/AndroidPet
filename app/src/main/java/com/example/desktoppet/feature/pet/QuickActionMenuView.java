package com.example.desktoppet.feature.pet;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** A short-lived, touchable overlay that fans quick actions around the pet. */
@SuppressLint("ViewConstructor")
final class QuickActionMenuView extends ViewGroup {
    enum Action {
        ADD_TASK("新建待办", "添加一条待办", 0xFFE6FFF9, 0xFF147D72),
        ADD_NOTE("写笔记", "记录一条笔记", 0xFFF1EAFF, 0xFF7454A8),
        TODAY("今日", "查看今日概览", 0xFFFFF2DF, 0xFFA76422),
        STARFIELD("星空", "进入互动星空", 0xFFE8EBFF, 0xFF4654B2),
        SETTINGS("设置", "打开桌宠设置", 0xFFE8F4FF, 0xFF356A9A);

        final String label;
        final String description;
        final int surfaceColor;
        final int contentColor;

        Action(String label, String description, int surfaceColor, int contentColor) {
            this.label = label;
            this.description = description;
            this.surfaceColor = surfaceColor;
            this.contentColor = contentColor;
        }
    }

    interface Listener {
        void onActionSelected(Action action, float localX, float localY);
        void onDismissRequested();
    }

    private static final TimeInterpolator ENTER_EASING = new DecelerateInterpolator(1.65f);
    private final List<ActionBubbleView> bubbles = new ArrayList<>();
    private final float density;
    private Listener listener;
    private float anchorX;
    private float anchorY;
    private float petRadius;
    private boolean laidOutOnce;
    private boolean closing;

    QuickActionMenuView(Context context, float anchorX, float anchorY, float petRadius,
                        Listener listener) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.petRadius = petRadius;
        this.listener = listener;
        setWillNotDraw(true);
        setClipChildren(false);
        setClipToPadding(false);
        setFocusable(true);
        setContentDescription("费雾快捷操作菜单");

        for (Action action : Action.values()) {
            ActionBubbleView bubble = new ActionBubbleView(context, action);
            bubble.setOnClickListener(view -> selectAction(action, bubble));
            bubbles.add(bubble);
            addView(bubble);
        }

    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int childSize = Math.round(Math.min(dp(68), Math.min(width, height) * .21f));
        int childSpec = MeasureSpec.makeMeasureSpec(childSize, MeasureSpec.EXACTLY);
        for (View child : bubbles) child.measure(childSpec, childSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0 || bubbles.isEmpty()) return;

        int childSize = bubbles.get(0).getMeasuredWidth();
        float orbit = Math.max(petRadius + childSize * .48f, dp(114));
        float edge = childSize / 2f + dp(6);
        List<Position> candidates = createCandidates(width, height, orbit, edge);
        List<Position> positions = chooseSpreadPositions(candidates, bubbles.size());

        if (positions.size() < bubbles.size()) {
            positions = fallbackPositions(width, height, orbit, edge, bubbles.size());
        }
        Collections.sort(positions, Comparator.comparingDouble(position -> position.angle));

        for (int i = 0; i < bubbles.size(); i++) {
            ActionBubbleView child = bubbles.get(i);
            Position position = positions.get(i % positions.size());
            int childLeft = Math.round(position.x - childSize / 2f);
            int childTop = Math.round(position.y - childSize / 2f);
            child.layout(childLeft, childTop, childLeft + childSize, childTop + childSize);
            child.setTailVector(anchorX - position.x, anchorY - position.y);
            if (!laidOutOnce) prepareEntrance(child, i);
        }
        if (!laidOutOnce) {
            laidOutOnce = true;
            post(this::playEntrance);
        }
    }

    private List<Position> createCandidates(int width, int height, float orbit, float edge) {
        List<Position> result = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double angle = -Math.PI / 2d + i * Math.PI / 8d;
            float x = anchorX + (float) Math.cos(angle) * orbit;
            float y = anchorY + (float) Math.sin(angle) * orbit;
            if (x >= edge && x <= width - edge && y >= edge && y <= height - edge) {
                result.add(new Position(x, y, angle));
            }
        }
        return result;
    }

    private List<Position> chooseSpreadPositions(List<Position> candidates, int count) {
        List<Position> selected = new ArrayList<>();
        if (candidates.isEmpty()) return selected;

        Position first = Collections.min(candidates, Comparator.comparingDouble(position -> position.y));
        selected.add(first);
        while (selected.size() < count && selected.size() < candidates.size()) {
            Position best = null;
            float bestDistance = -1f;
            for (Position candidate : candidates) {
                if (selected.contains(candidate)) continue;
                float nearest = Float.MAX_VALUE;
                for (Position picked : selected) {
                    nearest = Math.min(nearest,
                            (float) Math.hypot(candidate.x - picked.x, candidate.y - picked.y));
                }
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    best = candidate;
                }
            }
            if (best == null) break;
            selected.add(best);
        }
        return selected;
    }

    private List<Position> fallbackPositions(int width, int height, float orbit, float edge,
                                             int count) {
        List<Position> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = -Math.PI / 2d + i * Math.PI * 2d / count;
            float x = clamp(anchorX + (float) Math.cos(angle) * orbit, edge, width - edge);
            float y = clamp(anchorY + (float) Math.sin(angle) * orbit, edge, height - edge);
            result.add(new Position(x, y, angle));
        }
        return result;
    }

    private void prepareEntrance(View child, int index) {
        float childCenterX = child.getLeft() + child.getMeasuredWidth() / 2f;
        float childCenterY = child.getTop() + child.getMeasuredHeight() / 2f;
        child.setTranslationX(anchorX - childCenterX);
        child.setTranslationY(anchorY - childCenterY);
        child.setScaleX(.58f);
        child.setScaleY(.58f);
        child.setAlpha(0f);
        child.setTag(index);
    }

    private void playEntrance() {
        for (int i = 0; i < bubbles.size(); i++) {
            View child = bubbles.get(i);
            child.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setStartDelay(i * 28L)
                    .setDuration(230L)
                    .setInterpolator(ENTER_EASING)
                    .start();
        }
        invalidate();
    }

    private void selectAction(Action action, ActionBubbleView selected) {
        if (closing) return;
        closing = true;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        for (ActionBubbleView bubble : bubbles) {
            if (bubble == selected) {
                bubble.animate().scaleX(1.08f).scaleY(1.08f).alpha(1f).setDuration(120L).start();
            } else {
                bubble.animate().scaleX(.82f).scaleY(.82f).alpha(0f).setDuration(120L).start();
            }
        }
        postDelayed(() -> {
            if (listener != null) {
                float centerX = selected.getLeft() + selected.getWidth() / 2f +
                        selected.getTranslationX();
                float centerY = selected.getTop() + selected.getHeight() / 2f +
                        selected.getTranslationY();
                listener.onActionSelected(action, centerX, centerY);
            }
        }, 140L);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (closing) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            requestDismiss();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            requestDismiss();
            performClick();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void requestDismiss() {
        if (closing) return;
        closing = true;
        for (ActionBubbleView bubble : bubbles) {
            float centerX = bubble.getLeft() + bubble.getWidth() / 2f;
            float centerY = bubble.getTop() + bubble.getHeight() / 2f;
            bubble.animate()
                    .translationX(anchorX - centerX)
                    .translationY(anchorY - centerY)
                    .scaleX(.62f)
                    .scaleY(.62f)
                    .alpha(0f)
                    .setDuration(150L)
                    .setInterpolator(ENTER_EASING)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (listener != null) listener.onDismissRequested();
                        }
                    })
                    .start();
        }
    }

    private float dp(float value) {
        return value * density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Position {
        final float x;
        final float y;
        final double angle;

        Position(float x, float y, double angle) {
            this.x = x;
            this.y = y;
            this.angle = angle;
        }
    }

    private static final class ActionBubbleView extends View {
        private final Action action;
        private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private final float density;
        private Shader bubbleShader;
        private float tailX;
        private float tailY = 1f;

        ActionBubbleView(Context context, Action action) {
            super(context);
            this.action = action;
            density = getResources().getDisplayMetrics().density;
            setClickable(true);
            setFocusable(true);
            setContentDescription(action.description);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            bubblePaint.setStyle(Paint.Style.FILL);
            bubblePaint.setShadowLayer(dp(5), 0f, dp(2.5f), Color.argb(64, 4, 18, 34));
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth(dp(1));
            outlinePaint.setColor(Color.argb(190, 255, 255, 255));
            shinePaint.setStyle(Paint.Style.STROKE);
            shinePaint.setStrokeCap(Paint.Cap.ROUND);
            shinePaint.setStrokeWidth(dp(2.2f));
            shinePaint.setColor(Color.argb(132, 255, 255, 255));

            iconPaint.setColor(action.contentColor);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(dp(2));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            labelPaint.setColor(0xFF142B3A);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 10.5f);
            labelPaint.setFakeBoldText(true);
        }

        void setTailVector(float x, float y) {
            float length = (float) Math.hypot(x, y);
            if (length < .001f) return;
            tailX = x / length;
            tailY = y / length;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            rect.set(dp(6), dp(8), width - dp(6), height - dp(8));
            int topColor = blend(action.surfaceColor, Color.WHITE, .42f);
            bubbleShader = new LinearGradient(0f, rect.top, 0f, rect.bottom,
                    topColor, action.surfaceColor, Shader.TileMode.CLAMP);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            path.reset();
            path.addRoundRect(rect, dp(18), dp(18), Path.Direction.CW);
            float firstX = cx + tailX * dp(29);
            float firstY = cy + tailY * dp(27);
            float secondX = cx + tailX * dp(33);
            float secondY = cy + tailY * dp(31);
            path.addCircle(firstX, firstY, dp(3.6f), Path.Direction.CW);
            path.addCircle(secondX, secondY, dp(2.1f), Path.Direction.CW);

            bubblePaint.setShader(bubbleShader);
            canvas.drawPath(path, bubblePaint);
            bubblePaint.setShader(null);
            canvas.drawPath(path, outlinePaint);
            canvas.drawLine(rect.left + dp(12), rect.top + dp(7),
                    rect.left + dp(23), rect.top + dp(5.5f), shinePaint);

            float iconCy = getHeight() * .38f;
            float size = Math.min(getWidth(), getHeight()) * .245f;
            drawIcon(canvas, cx, iconCy, size);
            Paint.FontMetrics fm = labelPaint.getFontMetrics();
            float labelY = getHeight() * .69f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(action.label, cx, labelY, labelPaint);
        }

        private void drawIcon(Canvas canvas, float cx, float cy, float size) {
            rect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            path.reset();
            switch (action) {
                case ADD_TASK:
                    canvas.drawRoundRect(rect, dp(3), dp(3), iconPaint);
                    path.moveTo(cx - size * .28f, cy);
                    path.lineTo(cx - size * .08f, cy + size * .19f);
                    path.lineTo(cx + size * .29f, cy - size * .22f);
                    canvas.drawPath(path, iconPaint);
                    break;
                case ADD_NOTE:
                    canvas.drawRoundRect(rect, dp(2.5f), dp(2.5f), iconPaint);
                    canvas.drawLine(cx - size * .27f, cy - size * .16f,
                            cx + size * .27f, cy - size * .16f, iconPaint);
                    canvas.drawLine(cx - size * .27f, cy + size * .08f,
                            cx + size * .16f, cy + size * .08f, iconPaint);
                    canvas.drawLine(cx - size * .27f, cy + size * .30f,
                            cx + size * .05f, cy + size * .30f, iconPaint);
                    break;
                case TODAY:
                    canvas.drawRoundRect(rect, dp(3), dp(3), iconPaint);
                    canvas.drawLine(rect.left, cy - size * .13f, rect.right, cy - size * .13f, iconPaint);
                    canvas.drawLine(cx - size * .24f, rect.top - dp(2),
                            cx - size * .24f, rect.top + dp(4), iconPaint);
                    canvas.drawLine(cx + size * .24f, rect.top - dp(2),
                            cx + size * .24f, rect.top + dp(4), iconPaint);
                    canvas.drawCircle(cx, cy + size * .17f, dp(1.4f), iconPaint);
                    break;
                case STARFIELD:
                    path.moveTo(cx, cy - size * .61f);
                    path.lineTo(cx + size * .14f, cy - size * .14f);
                    path.lineTo(cx + size * .61f, cy);
                    path.lineTo(cx + size * .14f, cy + size * .14f);
                    path.lineTo(cx, cy + size * .61f);
                    path.lineTo(cx - size * .14f, cy + size * .14f);
                    path.lineTo(cx - size * .61f, cy);
                    path.lineTo(cx - size * .14f, cy - size * .14f);
                    path.close();
                    canvas.drawPath(path, iconPaint);
                    canvas.drawCircle(cx + size * .47f, cy - size * .42f,
                            dp(1.25f), iconPaint);
                    break;
                case SETTINGS:
                    canvas.drawCircle(cx, cy, size * .21f, iconPaint);
                    canvas.drawCircle(cx, cy, size * .46f, iconPaint);
                    for (int i = 0; i < 4; i++) {
                        double angle = i * Math.PI / 2d;
                        canvas.drawLine(
                                cx + (float) Math.cos(angle) * size * .46f,
                                cy + (float) Math.sin(angle) * size * .46f,
                                cx + (float) Math.cos(angle) * size * .60f,
                                cy + (float) Math.sin(angle) * size * .60f,
                                iconPaint);
                    }
                    break;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int actionMasked = event.getActionMasked();
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                setPressed(true);
                animate().scaleX(.94f).scaleY(.94f).setDuration(80L).start();
                return true;
            }
            if (actionMasked == MotionEvent.ACTION_MOVE) {
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth() &&
                        event.getY() >= 0 && event.getY() <= getHeight();
                setPressed(inside);
                return true;
            }
            if (actionMasked == MotionEvent.ACTION_UP) {
                boolean activate = isPressed();
                setPressed(false);
                animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                if (activate) performClick();
                return true;
            }
            if (actionMasked == MotionEvent.ACTION_CANCEL) {
                setPressed(false);
                animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                return true;
            }
            return false;
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private float dp(float value) {
            return value * density;
        }

        private static int blend(int from, int to, float amount) {
            float inverse = 1f - amount;
            return Color.rgb(
                    Math.round(Color.red(from) * inverse + Color.red(to) * amount),
                    Math.round(Color.green(from) * inverse + Color.green(to) * amount),
                    Math.round(Color.blue(from) * inverse + Color.blue(to) * amount));
        }
    }
}
