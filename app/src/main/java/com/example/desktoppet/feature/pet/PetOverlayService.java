package com.example.desktoppet.feature.pet;

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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import com.example.desktoppet.R;
import com.example.desktoppet.feature.music.MusicNotificationListener;
import com.example.desktoppet.ui.MainActivity;

public class PetOverlayService extends Service {
    public static final String ACTION_STOP = "com.example.desktoppet.STOP_PET";
    public static final String ACTION_REFRESH_SKIN = "com.example.desktoppet.REFRESH_SKIN";
    private static final String ACTION_MUSIC_AUTOSTART =
            "com.example.desktoppet.MUSIC_AUTOSTART";
    private static final String EXTRA_MUSIC_PLAYING = "music_playing";
    private static final String EXTRA_MUSIC_PAUSED = "music_paused";
    private static final String EXTRA_MUSIC_TITLE = "music_title";
    private static final String EXTRA_MUSIC_ARTIST = "music_artist";
    private static final String CHANNEL_ID = "pet_running";
    private static final int FOREGROUND_ID = 42;
    private static final String TAG = "FeiwuMusic";

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private PetView petView;
    private QuickActionMenuView quickActionMenu;
    private boolean musicLayout;
    private boolean musicAutoStarted;
    private boolean musicActive;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static volatile PetOverlayService runningInstance;
    private final Runnable musicAutoStop = () -> {
        if (musicAutoStarted && !musicActive) {
            Log.i(TAG, "auto music overlay stopped");
            stopSelf();
        }
    };

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
        if (intent != null && ACTION_MUSIC_AUTOSTART.equals(intent.getAction())) {
            musicAutoStarted = true;
            applyMusicPlayback(
                    intent.getBooleanExtra(EXTRA_MUSIC_PLAYING, false),
                    intent.getBooleanExtra(EXTRA_MUSIC_PAUSED, false),
                    intent.getStringExtra(EXTRA_MUSIC_TITLE),
                    intent.getStringExtra(EXTRA_MUSIC_ARTIST));
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH_SKIN.equals(intent.getAction())) refreshSkinAndSize();
        if (intent == null || intent.getAction() == null) musicAutoStarted = false;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        dismissQuickActions();
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
        int size = scaledDp(190);
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
            if (petView == null) return 0;
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int targetX = params.x + dx, targetY = params.y + dy;
            params.x = Math.max(-params.width / 3,
                    Math.min(screenW - params.width * 2 / 3, targetX));
            params.y = Math.max(0, Math.min(screenH - params.height / 2, targetY));
            try { windowManager.updateViewLayout(petView, params); } catch (RuntimeException ignored) {}
            return (params.x != targetX ? PetView.WALL_X : 0) | (params.y != targetY ? PetView.WALL_Y : 0);
        }, this::setMusicLayout, this::showQuickActions);
        try {
            windowManager.addView(petView, params);
            MusicNotificationListener.syncCurrentState(this);
        } catch (RuntimeException exception) {
            stopSelf();
        }
    }

    private void refreshSkinAndSize() {
        if (petView == null || params == null || windowManager == null) return;
        resizeWindow(musicLayout);
        petView.reloadSkin();
        try { windowManager.updateViewLayout(petView, params); } catch (RuntimeException ignored) {}
    }

    private void setMusicLayout(boolean enabled) {
        if (musicLayout == enabled || petView == null || params == null || windowManager == null) return;
        musicLayout = enabled;
        resizeWindow(enabled);
        try { windowManager.updateViewLayout(petView, params); } catch (RuntimeException ignored) {}
    }

    private void showQuickActions() {
        if (quickActionMenu != null || petView == null || params == null || windowManager == null) {
            return;
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int menuWidth = Math.min(screenW, dp(380));
        int menuHeight = Math.min(screenH, dp(410));
        int petCenterX = params.x + params.width / 2;
        int petCenterY = params.y + params.height / 2;
        int menuX = clamp(petCenterX - menuWidth / 2, 0, Math.max(0, screenW - menuWidth));
        int menuY = clamp(petCenterY - menuHeight / 2, 0, Math.max(0, screenH - menuHeight));
        float anchorX = petCenterX - menuX;
        float anchorY = petCenterY - menuY;
        float petRadius = Math.min(params.width, params.height) * .43f;

        quickActionMenu = new QuickActionMenuView(this, anchorX, anchorY, petRadius,
                new QuickActionMenuView.Listener() {
                    @Override
                    public void onActionSelected(QuickActionMenuView.Action action) {
                        dismissQuickActions();
                        openQuickAction(action);
                    }

                    @Override
                    public void onDismissRequested() {
                        dismissQuickActions();
                    }
                });
        WindowManager.LayoutParams menuParams = new WindowManager.LayoutParams(
                menuWidth,
                menuHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.x = menuX;
        menuParams.y = menuY;
        try {
            windowManager.addView(quickActionMenu, menuParams);
        } catch (RuntimeException ignored) {
            quickActionMenu = null;
        }
    }

    private void dismissQuickActions() {
        QuickActionMenuView menu = quickActionMenu;
        quickActionMenu = null;
        if (menu == null || windowManager == null) return;
        try { windowManager.removeView(menu); } catch (RuntimeException ignored) {}
    }

    private void openQuickAction(QuickActionMenuView.Action action) {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        switch (action) {
            case ADD_TASK:
                intent.putExtra(MainActivity.EXTRA_QUICK_ADD, true);
                break;
            case ADD_NOTE:
                intent.putExtra(MainActivity.EXTRA_QUICK_NOTE, true);
                break;
            case TODAY:
                intent.putExtra(MainActivity.EXTRA_OPEN_PAGE, 0);
                break;
            case SETTINGS:
                intent.putExtra(MainActivity.EXTRA_OPEN_PAGE, 3);
                break;
        }
        startActivity(intent);
    }

    private void resizeWindow(boolean expanded) {
        int oldWidth = params.width;
        int oldHeight = params.height;
        int right = params.x + oldWidth;
        int bottom = params.y + oldHeight;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        params.width = expanded ? Math.min(screenW, scaledDp(360)) : scaledDp(190);
        params.height = expanded ? Math.min(screenH, scaledDp(250)) : scaledDp(190);
        params.x = right - params.width;
        params.y = bottom - params.height;
        params.x = Math.max(-params.width / 3,
                Math.min(screenW - params.width * 2 / 3, params.x));
        params.y = Math.max(0, Math.min(screenH - params.height / 2, params.y));
    }

    public static void showReminderIfRunning(String message) {
        PetOverlayService service = runningInstance;
        if (service != null && service.petView != null) {
            service.petView.post(() -> service.petView.showMessage("该做：" + message));
        }
    }

    public static void showAssistantMessage(String message, PetView.Action action) {
        PetOverlayService service = runningInstance;
        if (service != null && service.petView != null) {
            service.petView.post(() -> service.petView.showMessage(message, action, 5000L));
        }
    }

    public static void setAssistantSpeaking(boolean speaking) {
        PetOverlayService service = runningInstance;
        if (service != null && service.petView != null) {
            service.petView.post(() -> service.petView.setSpeaking(speaking));
        }
    }

    public static void setMusicPlayback(boolean playing, boolean paused,
                                        String title, String artist) {
        PetOverlayService service = runningInstance;
        if (service != null && service.petView != null) {
            service.applyMusicPlayback(playing, paused, title, artist);
        }
    }

    public static void handleMusicPlayback(Context context, boolean playing, boolean paused,
                                           String title, String artist) {
        PetOverlayService service = runningInstance;
        if (service != null) {
            service.applyMusicPlayback(playing, paused, title, artist);
            return;
        }
        if (!playing || context == null || !Settings.canDrawOverlays(context)) return;
        Intent intent = new Intent(context, PetOverlayService.class)
                .setAction(ACTION_MUSIC_AUTOSTART)
                .putExtra(EXTRA_MUSIC_PLAYING, true)
                .putExtra(EXTRA_MUSIC_PAUSED, false)
                .putExtra(EXTRA_MUSIC_TITLE, title == null ? "" : title)
                .putExtra(EXTRA_MUSIC_ARTIST, artist == null ? "" : artist);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "auto music overlay requested");
        } catch (RuntimeException exception) {
            Log.w(TAG, "auto music overlay start rejected", exception);
        }
    }

    private void applyMusicPlayback(boolean playing, boolean paused,
                                    String title, String artist) {
        mainHandler.removeCallbacks(musicAutoStop);
        musicActive = playing;
        if (petView != null) {
            boolean keepPausedScene = paused && !musicAutoStarted;
            petView.post(() -> petView.setMusicPlayback(
                    playing, keepPausedScene, title, artist));
        }
        if (!playing && musicAutoStarted) {
            mainHandler.postDelayed(musicAutoStop, 2200L);
        }
    }

    public static boolean isRunning() {
        return runningInstance != null;
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
                .setContentText("点宠物互动，长按打开快捷气泡")
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

    private int scaledDp(int value) {
        int percent = getSharedPreferences("pet_settings", MODE_PRIVATE)
                .getInt("pet_size_percent", 100);
        return dp(value) * percent / 100;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
