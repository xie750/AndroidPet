package com.example.desktoppet.feature.pet;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.lang.reflect.Field;

/** Exercises the installed overlay inside its own process, without system input injection. */
public final class PetMotionCheck extends Instrumentation {
    private PetView view;
    private long down;
    private float rawX, rawY;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            startActivitySync(launch);
            context.startForegroundService(new Intent(context, PetOverlayService.class));
            Field instance = PetOverlayService.class.getDeclaredField("runningInstance");
            Field pet = PetOverlayService.class.getDeclaredField("petView");
            instance.setAccessible(true);
            pet.setAccessible(true);
            for (int i=0; i<40 && view==null; i++) {
                waitForIdleSync();
                Object service = instance.get(null);
                if (service != null) view = (PetView)pet.get(service);
                SystemClock.sleep(50);
            }
            if (view == null) throw new AssertionError("Overlay did not attach");
            SystemClock.sleep(450);
            int[] before = location();
            runOnMainSync(() -> {
                Bitmap frame = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(frame));
                int cx = frame.getWidth()/2, cy = Math.round(frame.getHeight()*.59f);
                if ((frame.getPixel(cx,cy)>>>24)<40) throw new AssertionError("Empty body in rendered frame");
                frame.recycle();
                rawX = before[0]+cx;
                rawY = before[1]+cy;
                down = SystemClock.uptimeMillis();
            });
            touch(MotionEvent.ACTION_DOWN);
            for (int i=0; i<10; i++) {
                SystemClock.sleep(18);
                rawX -= 15;
                rawY -= 6;
                touch(MotionEvent.ACTION_MOVE);
            }
            int[] dragged = location();
            touch(MotionEvent.ACTION_UP);
            SystemClock.sleep(800);
            int[] settled = location();
            SystemClock.sleep(300);
            int[] resting = location();
            if (dragged[0] >= before[0]-65 || settled[0] >= dragged[0]-4
                    || Math.abs(resting[0]-settled[0])>2) throw new AssertionError("Drag/coast/settle failed");
            result.putString("positions",before[0]+" -> "+dragged[0]+" -> "+settled[0]+" -> "+resting[0]);
            finish(Activity.RESULT_OK,result);
        } catch (Throwable failure) {
            result.putString("failure",failure.toString());
            finish(Activity.RESULT_CANCELED,result);
        }
    }

    private int[] location() {
        int[] point = new int[2];
        runOnMainSync(() -> view.getLocationOnScreen(point));
        return point;
    }

    private void touch(int action) {
        int[] origin = location();
        boolean[] accepted = {false};
        runOnMainSync(() -> {
            MotionEvent event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rawX,rawY,0);
            event.offsetLocation(-origin[0],-origin[1]);
            accepted[0] = view.dispatchTouchEvent(event);
            event.recycle();
        });
        if (!accepted[0]) throw new AssertionError("Body rejected touch " + action);
    }
}
