package com.example.desktoppet.feature.pet;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** A weighted texture mesh. All offsets are fractions of the visible artwork. */
final class PetRig {
    private static final int COLS = 28, ROWS = 28;
    private final float[] vertices = new float[(COLS + 1) * (ROWS + 1) * 2];
    private final Spring sway = new Spring(), lift = new Spring(), tip = new Spring();
    private final Spring stretch = new Spring(), gaze = new Spring(), press = new Spring();
    private float speedX, speedY, gripX = .45f, gripY = .45f;
    private long sampleTime, frameTime;
    private boolean held;
    float coastX, coastY;

    private static final class Spring {
        float x, v;
        void step(float target, float frequency, float damping, float dt) {
            v += ((target - x) * frequency * frequency - 2f * damping * frequency * v) * dt;
            x += v * dt;
        }
    }

    void grab(float x, float y, long time) {
        gripX = x;
        gripY = y;
        sampleTime = time;
        speedX = speedY = coastX = coastY = 0;
        held = true;
    }

    void sample(float dxDp, float dyDp, long time) {
        float dt = Math.max(.004f, (time - sampleTime) * .001f);
        float mix = 1f - (float)Math.exp(-dt * 28f);
        speedX += (limit(dxDp / dt, 2200f) - speedX) * mix;
        speedY += (limit(dyDp / dt, 2200f) - speedY) * mix;
        sampleTime = time;
    }

    void release(long time, boolean dragged) {
        float recent = freshness(time);
        coastX = dragged ? limit(speedX * recent * .36f, 540f) : 0;
        coastY = dragged ? limit(speedY * recent * .36f, 540f) : 0;
        if (dragged) stretch.v -= .45f + Math.abs(coastY) * .0025f;
        held = false;
    }

    void bumpX() { sway.v += limit(coastX * .002f, .85f); coastX = 0; }
    void bumpY() { stretch.v -= Math.abs(coastY) * .002f; coastY = 0; }
    private float freshness(long t) {
        return (float)Math.exp(-Math.max(0, t - sampleTime - 18) / 55f);
    }

    float advance(long t, boolean dragging, float look, float touch) {
        float dt = Math.min(.05f, Math.max(0f, (t - frameTime) * .001f));
        frameTime = t;
        float recent = held && dragging ? freshness(t) : 0;
        float left = dt;
        while (left > .00001f) {
            float h = Math.min(left, 1f / 120f);
            sway.step(limit(-speedX * recent / 6500f, .19f), 16, .56f, h);
            lift.step(limit(-speedY * recent / 8500f, .13f), 17, .65f, h);
            tip.step(sway.x * 1.2f, 10, .44f, h);
            stretch.step(dragging ? .045f : 0f, 18, .49f, h);
            gaze.step(look, 13, .8f, h);
            press.step(touch, 19, .7f, h);
            left -= h;
        }
        return dt;
    }

    void draw(Canvas canvas, Bitmap bitmap, RectF rect, Paint paint, int skin,
              boolean sleeping, boolean speaking, long t, float blink) {
        float breath = (float)Math.sin(t * .0021) * (sleeping ? .009f : .006f);
        float earIdle = sleeping ? 0 : (float)Math.sin(t * .0029) * .008f;
        float tailIdle = (float)Math.sin(t * .0023 + .7) * (sleeping ? .006f : .016f);
        float mouth = speaking ? (.5f + .5f * (float)Math.sin(t * .024)) * .019f : 0;
        int n = 0;
        for (int row = 0; row <= ROWS; row++) {
            float y = row / (float)ROWS;
            for (int col = 0; col <= COLS; col++) {
                float x = col / (float)COLS;
                float lever = y - gripY;
                float dx = sway.x * lever + gaze.x * .025f * (1f-y);
                float dy = lift.x * lever + stretch.x * lever + breath * (y - 1f);
                if (skin == 1) {
                    float ears = ramp((.43f - y) / .4f);
                    float tail = ramp((x - .59f) / .36f) * ramp((y - .20f) / .24f);
                    dx += ears * (tip.x * .38f + earIdle) + tail * (tip.x + tailIdle);
                    dy += tail * (tip.x * .55f + tailIdle) + ears * Math.abs(tip.x) * .13f;
                    if (!sleeping) {
                        float eye = spot(x, y, .29f, .49f, .12f, .095f)
                                + spot(x, y, .49f, .46f, .12f, .095f);
                        dy -= eye * (y - (.49f - (x - .29f) * .15f)) * blink * .88f;
                        dy += spot(x,y,.40f,.56f,.085f,.07f) * mouth;
                    }
                } else if (skin == 4) {
                    // The raised forearm turns about the elbow. Its loose sleeve settles afterwards.
                    float arm = ramp((x-.58f)/.26f) * ramp((.41f-y)/.13f);
                    float sleeve = ramp((x-.63f)/.23f) * ramp((y-.24f)/.23f);
                    float hem = ramp((y-.42f)/.34f) * ramp((Math.abs(x-.49f)-.09f)/.20f);
                    float lamp = spot(x,y,.57f,.51f,.095f,.18f);
                    float hair = ramp((.33f-y)/.14f) * ramp((Math.abs(x-.49f)-.055f)/.16f);
                    float wrist = .028f * gaze.x + sway.x*.36f + lift.x*.46f + earIdle;
                    dy += arm * wrist + sleeve * (tip.x*.22f + tailIdle*.36f);
                    dx += hair * (tip.x*.32f + earIdle*.55f)
                            + hem * (tip.x*.64f + tailIdle*.46f)
                            + lamp * (tip.x*.47f + tailIdle*.4f);
                    dy += spot(x,y,.50f,.13f,.10f,.10f) * press.x * .014f;
                } else if (skin == 2 || skin == 3) {
                    // Both animals face right, with their tails curving up the left silhouette.
                    float tail = ramp((.5f-x)/.36f);
                    float ears = ramp((x-.46f)/.2f) * ramp((.38f-y)/.25f);
                    dx += tail * (tip.x + tailIdle) + ears * (tip.x*.42f + earIdle);
                    dy -= tail * (tip.x*.35f + tailIdle*.6f);
                } else {
                    float cloak = ramp((y-.34f)/.39f) * ramp((Math.abs(x-.5f)-.07f)/.23f);
                    float hand = spot(x,y,.24f,.47f,.21f,.18f);
                    dx += cloak * (tip.x*.8f + tailIdle);
                    dy += hand * (lift.x*.65f + tip.x*.32f + breath);
                }
                float contact = spot(x,y,gripX,gripY,.24f,.22f);
                dx -= press.x * contact * (x - gripX) * .38f;
                dy += press.x * contact * .028f;
                float pin = held ? 1f - contact : 1f;
                vertices[n++] = rect.left + rect.width() * (x + dx * pin);
                vertices[n++] = rect.top + rect.height() * (y + dy * pin);
            }
        }
        canvas.drawBitmapMesh(bitmap, COLS, ROWS, vertices, 0, null, 0, paint);
    }

    // Recover the touched texel from the most recently drawn mesh, including bent limbs.
    boolean texturePoint(float x, float y, float[] uv) {
        for (int row=0; row<ROWS; row++) {
            for (int col=0; col<COLS; col++) {
                int a = (row*(COLS+1)+col)*2, b = a+2, c = a+2*(COLS+1), d = c+2;
                if (triangle(x,y,a,b,c,uv)) {
                    uv[0] = (col+uv[0])/COLS;
                    uv[1] = (row+uv[1])/ROWS;
                    return true;
                }
                if (triangle(x,y,d,c,b,uv)) {
                    uv[0] = (col+1-uv[0])/COLS;
                    uv[1] = (row+1-uv[1])/ROWS;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean triangle(float x,float y,int a,int b,int c,float[] uv) {
        float ax=vertices[a], ay=vertices[a+1];
        float bx=vertices[b]-ax, by=vertices[b+1]-ay;
        float cx=vertices[c]-ax, cy=vertices[c+1]-ay;
        float det=bx*cy-by*cx;
        if (Math.abs(det)<.00001f) return false;
        float u=((x-ax)*cy-(y-ay)*cx)/det, v=(bx*(y-ay)-by*(x-ax))/det;
        if (u<0 || v<0 || u+v>1f) return false;
        uv[0]=u;
        uv[1]=v;
        return true;
    }

    private static float spot(float x,float y,float cx,float cy,float rx,float ry) {
        float u = (x-cx)/rx, v = (y-cy)/ry;
        return ramp(1f-u*u-v*v);
    }
    private static float ramp(float x) {
        x = Math.max(0,Math.min(1,x));
        return x*x*(3-2*x);
    }
    private static float limit(float x,float max) { return Math.max(-max, Math.min(max,x)); }
}
