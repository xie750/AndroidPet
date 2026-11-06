package com.example.desktoppet.feature.starfield;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.desktoppet.R;
import com.example.desktoppet.feature.pet.PetOverlayService;

/** Ephemeral immersive space opened from the desktop-pet quick action menu. */
public final class StarfieldActivity extends Activity {
    public static final String EXTRA_ORIGIN_X = "starfield_origin_x";
    public static final String EXTRA_ORIGIN_Y = "starfield_origin_y";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private StarfieldView starfieldView;
    private ImageButton closeButton;
    private TextView hintView;
    private boolean closing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowManager.LayoutParams windowParams = window.getAttributes();
        windowParams.dimAmount = .34f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            windowParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        window.setAttributes(windowParams);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        float defaultX = getResources().getDisplayMetrics().widthPixels * .5f;
        float defaultY = getResources().getDisplayMetrics().heightPixels * .62f;
        float originX = getIntent().getFloatExtra(EXTRA_ORIGIN_X, defaultX);
        float originY = getIntent().getFloatExtra(EXTRA_ORIGIN_Y, defaultY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        starfieldView = new StarfieldView(this, originX, originY);
        root.addView(starfieldView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        hintView = new TextView(this);
        hintView.setText("拖动光点  ·  连接星轨");
        hintView.setTextColor(Color.rgb(183, 237, 248));
        hintView.setTextSize(12f);
        hintView.setLetterSpacing(.08f);
        hintView.setTypeface(android.graphics.Typeface.MONOSPACE);
        hintView.setGravity(Gravity.CENTER);
        hintView.setAlpha(0f);
        hintView.setPadding(dp(16), dp(9), dp(16), dp(9));
        GradientDrawable hintBackground = new GradientDrawable();
        hintBackground.setColor(Color.argb(78, 3, 18, 27));
        hintBackground.setCornerRadius(dp(10));
        hintBackground.setStroke(dp(1), Color.argb(78, 94, 221, 241));
        hintView.setBackground(hintBackground);
        hintView.setContentDescription("提示：拖动光点，连接星轨");
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin = dp(28);
        root.addView(hintView, hintParams);

        closeButton = new ImageButton(this);
        closeButton.setImageResource(R.drawable.ic_starfield_close);
        closeButton.setColorFilter(Color.rgb(230, 242, 255));
        closeButton.setScaleType(ImageButton.ScaleType.CENTER);
        closeButton.setContentDescription("关闭星空");
        closeButton.setAlpha(0f);
        GradientDrawable closeSurface = new GradientDrawable();
        closeSurface.setShape(GradientDrawable.RECTANGLE);
        closeSurface.setCornerRadius(dp(12));
        closeSurface.setColor(Color.argb(82, 3, 18, 27));
        closeSurface.setStroke(dp(1), Color.argb(88, 94, 221, 241));
        GradientDrawable closeMask = new GradientDrawable();
        closeMask.setShape(GradientDrawable.RECTANGLE);
        closeMask.setCornerRadius(dp(12));
        closeMask.setColor(Color.WHITE);
        closeButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(56, 205, 232, 255)),
                closeSurface, closeMask));
        closeButton.setOnClickListener(view -> closeStarfield());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.END);
        closeParams.topMargin = dp(20);
        closeParams.rightMargin = dp(16);
        root.addView(closeButton, closeParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int safeTop = insets.getStableInsetTop();
            int safeRight = insets.getStableInsetRight();
            hintParams.topMargin = Math.max(dp(28), safeTop + dp(8));
            closeParams.topMargin = Math.max(dp(20), safeTop + dp(8));
            closeParams.rightMargin = Math.max(dp(16), safeRight + dp(12));
            hintView.setLayoutParams(hintParams);
            closeButton.setLayoutParams(closeParams);
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        // MIUI's PhoneWindow can throw while resolving its insets controller before the
        // decor view is attached. Enter immersive mode only after content is installed.
        hideSystemBars();
        // Keep the pet visible until the starfield window has initialized successfully.
        PetOverlayService.setOverlaySuppressed(true);
        root.postDelayed(() -> {
            if (closing) return;
            hintView.animate().alpha(.92f).setDuration(220L).start();
            closeButton.animate().alpha(1f).setDuration(220L).start();
        }, 180L);
        handler.postDelayed(() -> {
            if (!closing && hintView != null) {
                hintView.animate().alpha(0f).setDuration(360L).start();
            }
        }, 3600L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        PetOverlayService.setOverlaySuppressed(true);
        if (starfieldView != null) starfieldView.setRunning(true);
    }

    @Override
    protected void onPause() {
        if (starfieldView != null) starfieldView.setRunning(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        PetOverlayService.setOverlaySuppressed(false);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onBackPressed() {
        closeStarfield();
    }

    private void closeStarfield() {
        if (closing) return;
        closing = true;
        handler.removeCallbacksAndMessages(null);
        if (hintView != null) hintView.animate().alpha(0f).setDuration(120L).start();
        if (closeButton != null) closeButton.animate().alpha(0f).setDuration(120L).start();
        if (starfieldView == null) finishImmediately();
        else starfieldView.beginExit(this::finishImmediately);
    }

    private void finishImmediately() {
        finish();
        overridePendingTransition(0, 0);
    }

    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        PetOverlayService.setOverlaySuppressed(false);
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
