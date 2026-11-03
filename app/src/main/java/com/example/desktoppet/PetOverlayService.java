package com.example.desktoppet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

public class PetOverlayService extends Service {
    static final String ACTION_STOP = "com.example.desktoppet.STOP_PET";
    private static final String CHANNEL_ID = "pet_running";
    private static final int FOREGROUND_ID = 42;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private PetView petView;
    private static volatile PetOverlayService runningInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        runningInstance = this;
        createChannel();
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FOREGROUND_ID, notification);
        }
        if (Settings.canDrawOverlays(this)) showPet();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (petView == null && Settings.canDrawOverlays(this)) showPet();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (petView != null && windowManager != null) {
            try { windowManager.removeView(petView); } catch (RuntimeException ignored) {}
        }
        petView = null;
        if (runningInstance == this) runningInstance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showPet() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;
        int size = dp(190);
        params = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = getResources().getDisplayMetrics().widthPixels - size;
        params.y = Math.round(getResources().getDisplayMetrics().heightPixels * 0.58f);
        petView = new PetView(this, (dx, dy) -> {
            if (petView == null) return;
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            params.x = Math.max(-size / 3, Math.min(screenW - size * 2 / 3, params.x + dx));
            params.y = Math.max(0, Math.min(screenH - size / 2, params.y + dy));
            try { windowManager.updateViewLayout(petView, params); } catch (RuntimeException ignored) {}
        });
        windowManager.addView(petView, params);
    }

    static void showReminderIfRunning(String message) {
        PetOverlayService service = runningInstance;
        if (service != null && service.petView != null) {
            service.petView.post(() -> service.petView.showMessage("该做：" + message));
        }
    }

    private Notification createNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, PetOverlayService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pet_notification)
                .setContentTitle("费雾正在陪你")
                .setContentText("点宠物互动，长按快速添加待办")
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "停止桌宠", stopPi).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "桌宠运行状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持桌宠运行，并提供停止入口");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
