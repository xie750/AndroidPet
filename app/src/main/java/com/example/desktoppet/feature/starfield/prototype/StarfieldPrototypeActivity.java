package com.example.desktoppet.feature.starfield.prototype;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.desktoppet.R;
import com.example.desktoppet.feature.pet.PetOverlayService;

/** Immersive holographic core opened from the desktop-pet quick action menu. */
public final class StarfieldPrototypeActivity extends Activity implements SensorEventListener {
    private HolographicCoreView starfieldView;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private boolean hasSensorOrigin;
    private float originPitch;
    private float originRoll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        starfieldView = new HolographicCoreView(this);
        root.addView(starfieldView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout labelGroup = new LinearLayout(this);
        labelGroup.setOrientation(LinearLayout.VERTICAL);
        labelGroup.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable labelSurface = new GradientDrawable();
        labelSurface.setColor(Color.argb(76, 2, 15, 23));
        labelSurface.setCornerRadius(dp(10));
        labelSurface.setStroke(dp(1), Color.argb(78, 86, 224, 244));
        labelGroup.setBackground(labelSurface);

        TextView title = new TextView(this);
        title.setText("HOLOGRAPHIC CORE");
        title.setTextColor(Color.rgb(180, 246, 255));
        title.setTextSize(11f);
        title.setLetterSpacing(.12f);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        labelGroup.addView(title);

        TextView hint = new TextView(this);
        hint.setText("拖动旋转  ·  双指缩放  ·  拖动轨道节点");
        hint.setTextColor(Color.rgb(142, 190, 202));
        hint.setTextSize(11f);
        hint.setLetterSpacing(.04f);
        hint.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(3);
        labelGroup.addView(hint, hintParams);
        labelGroup.setContentDescription("全息星核原型。拖动旋转，双指缩放，拖动轨道节点");

        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        labelParams.leftMargin = dp(16);
        labelParams.topMargin = dp(20);
        root.addView(labelGroup, labelParams);

        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_starfield_close);
        close.setColorFilter(Color.rgb(190, 241, 249));
        close.setScaleType(ImageButton.ScaleType.CENTER);
        close.setContentDescription("关闭三维星空原型");
        GradientDrawable closeSurface = new GradientDrawable();
        closeSurface.setColor(Color.argb(76, 2, 15, 23));
        closeSurface.setCornerRadius(dp(10));
        closeSurface.setStroke(dp(1), Color.argb(88, 86, 224, 244));
        GradientDrawable closeMask = new GradientDrawable();
        closeMask.setColor(Color.WHITE);
        closeMask.setCornerRadius(dp(10));
        close.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(58, 133, 239, 255)),
                closeSurface, closeMask));
        close.setOnClickListener(view -> finishPrototype());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.END);
        closeParams.rightMargin = dp(16);
        closeParams.topMargin = dp(20);
        root.addView(close, closeParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int safeTop = insets.getStableInsetTop();
            int safeLeft = insets.getStableInsetLeft();
            int safeRight = insets.getStableInsetRight();
            labelParams.topMargin = Math.max(dp(20), safeTop + dp(10));
            labelParams.leftMargin = Math.max(dp(16), safeLeft + dp(12));
            closeParams.topMargin = Math.max(dp(20), safeTop + dp(10));
            closeParams.rightMargin = Math.max(dp(16), safeRight + dp(12));
            labelGroup.setLayoutParams(labelParams);
            close.setLayoutParams(closeParams);
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        hideSystemBars();
        PetOverlayService.setOverlaySuppressed(true);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = .18f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        window.setAttributes(params);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    }

    @Override
    protected void onResume() {
        super.onResume();
        starfieldView.onResume();
        hasSensorOrigin = false;
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor,
                    SensorManager.SENSOR_DELAY_GAME);
        }
        hideSystemBars();
        PetOverlayService.setOverlaySuppressed(true);
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (starfieldView != null) starfieldView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        PetOverlayService.setOverlaySuppressed(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        PetOverlayService.setOverlaySuppressed(false);
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR || starfieldView == null) return;
        float[] rotation = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotation, event.values);
        SensorManager.getOrientation(rotation, orientation);
        float pitch = orientation[1];
        float roll = orientation[2];
        if (!hasSensorOrigin) {
            originPitch = pitch;
            originRoll = roll;
            hasSensorOrigin = true;
        }
        starfieldView.setSensorParallax((roll - originRoll) * .22f,
                (pitch - originPitch) * .18f);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No calibration UI is needed for this short-lived visual prototype.
    }

    @Override
    public void onBackPressed() {
        finishPrototype();
    }

    private void finishPrototype() {
        finish();
        overridePendingTransition(0, 0);
    }

    private void hideSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
