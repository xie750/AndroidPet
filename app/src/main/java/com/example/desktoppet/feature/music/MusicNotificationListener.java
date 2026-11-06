package com.example.desktoppet.feature.music;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.example.desktoppet.feature.pet.PetOverlayService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Observes the system media sessions after the user grants notification access.
 * Only playback state and media metadata are consumed; notification bodies are ignored.
 */
public final class MusicNotificationListener extends NotificationListenerService {
    private static final String TAG = "FeiwuMusic";
    private static final int STATE_STOPPED = 0;
    private static final int STATE_PAUSED = 1;
    private static final int STATE_PLAYING = 2;
    private static final Set<String> KNOWN_MUSIC_PACKAGES = new HashSet<>(Arrays.asList(
            "com.luna.music",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.kugou.android",
            "cn.kuwo.player",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.apple.android.music",
            "com.miui.player",
            "com.android.mediacenter"
    ));
    private static final String[] NON_MUSIC_PACKAGE_MARKERS = {
            "video", "qqlive", "youku", "iqiyi", "bilibili", "danmaku",
            "douyin", "aweme", "kuaishou", "gifmaker", "browser", "chrome",
            "game", "player.tv", "tvplayer"
    };

    private static volatile Snapshot latest = new Snapshot(STATE_STOPPED, "", "");
    private static volatile MusicNotificationListener connectedInstance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private final Map<String, Integer> notificationPlaybackHints = new HashMap<>();
    private final Set<String> activeMusicNotifications = new HashSet<>();
    private MediaSessionManager sessionManager;
    private ComponentName listenerComponent;
    private final Runnable playbackWatchdog = this::publishBestSession;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publishBestSession();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publishBestSession();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            this::replaceControllers;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connectedInstance = this;
        Log.i(TAG, "notification listener connected");
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent = new ComponentName(this, MusicNotificationListener.class);
        if (sessionManager == null) return;
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener, listenerComponent, mainHandler);
            refreshNotificationHints();
            refreshSessions();
        } catch (SecurityException ignored) {
            publish(new Snapshot(STATE_STOPPED, "", ""));
        }
    }

    @Override
    public void onListenerDisconnected() {
        clearControllers();
        notificationPlaybackHints.clear();
        activeMusicNotifications.clear();
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (RuntimeException ignored) {}
        }
        publish(new Snapshot(STATE_STOPPED, "", ""));
        if (connectedInstance == this) connectedInstance = null;
        super.onListenerDisconnected();
        requestSystemRebind(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        updateNotificationHint(sbn);
        publishBestSession();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) {
            notificationPlaybackHints.remove(sbn.getPackageName());
            activeMusicNotifications.remove(sbn.getPackageName());
        }
        publishBestSession();
    }

    private void refreshNotificationHints() {
        notificationPlaybackHints.clear();
        activeMusicNotifications.clear();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) updateNotificationHint(sbn);
        } catch (RuntimeException ignored) {}
    }

    private void updateNotificationHint(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        String packageName = sbn.getPackageName();
        if (!isMusicPackage(packageName, null) || !isTransportNotification(sbn.getNotification())) {
            notificationPlaybackHints.remove(packageName);
            activeMusicNotifications.remove(packageName);
            return;
        }
        activeMusicNotifications.add(packageName);
        int state = transportStateFromNotification(sbn.getNotification());
        if (state == STATE_STOPPED) {
            notificationPlaybackHints.remove(packageName);
            return;
        }
        notificationPlaybackHints.put(packageName, state);
        Log.d(TAG, "music transport package=" + packageName + " state=" + state);
    }

    private boolean isTransportNotification(Notification notification) {
        return Notification.CATEGORY_TRANSPORT.equals(notification.category)
                || (notification.extras != null
                && notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null);
    }

    /**
     * Some players (notably certain Luna Music builds) keep their MediaSession in PAUSED while
     * audio is actually playing. Their transport notification remains authoritative: a visible
     * Pause action means playback is active, while a Play action means it is paused.
     */
    private int transportStateFromNotification(Notification notification) {
        if (!isTransportNotification(notification) || notification.actions == null) {
            return STATE_STOPPED;
        }
        for (Notification.Action action : notification.actions) {
            if (action == null) continue;
            String title = action.title == null ? ""
                    : action.title.toString().trim().toLowerCase(Locale.ROOT);
            if (title.equals("pause") || title.contains("暂停")) return STATE_PLAYING;
            if (title.equals("play") || title.contains("播放") || title.contains("继续")) {
                return STATE_PAUSED;
            }
        }
        return STATE_STOPPED;
    }

    private void refreshSessions() {
        if (sessionManager == null || listenerComponent == null) return;
        try {
            replaceControllers(sessionManager.getActiveSessions(listenerComponent));
        } catch (SecurityException ignored) {
            replaceControllers(Collections.emptyList());
        }
    }

    private void replaceControllers(List<MediaController> activeControllers) {
        clearControllers();
        if (activeControllers != null) {
            for (MediaController controller : activeControllers) {
                if (controller == null) continue;
                controllers.add(controller);
                controller.registerCallback(controllerCallback, mainHandler);
            }
        }
        publishBestSession();
    }

    private void clearControllers() {
        for (MediaController controller : controllers) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (RuntimeException ignored) {}
        }
        controllers.clear();
    }

    private void publishBestSession() {
        mainHandler.removeCallbacks(playbackWatchdog);
        MediaController pausedCandidate = null;
        for (MediaController controller : controllers) {
            if (!isMusicController(controller)) continue;
            PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState == null) continue;
            int notificationState = notificationPlaybackHints.getOrDefault(
                    controller.getPackageName(), STATE_STOPPED);
            // The transport button is more reliable than several vendor players' stale session
            // state. A Play button means the music app is paused even if its session says PLAYING.
            if (notificationState == STATE_PLAYING) {
                publishAudibleOrStopped(controller);
                return;
            }
            if (notificationState == STATE_PAUSED) {
                if (pausedCandidate == null) pausedCandidate = controller;
                continue;
            }
            int state = playbackState.getState();
            if (state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_CONNECTING ||
                    state == PlaybackState.STATE_FAST_FORWARDING ||
                    state == PlaybackState.STATE_REWINDING) {
                publishAudibleOrStopped(controller);
                return;
            }
            if (state == PlaybackState.STATE_PAUSED && pausedCandidate == null) {
                pausedCandidate = controller;
            }
        }
        publish(pausedCandidate == null
                ? new Snapshot(STATE_STOPPED, "", "")
                : snapshotOf(pausedCandidate, STATE_PAUSED));
    }

    private void publishAudibleOrStopped(MediaController controller) {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean audible = audioManager != null && audioManager.isMusicActive();
        publish(audible
                ? snapshotOf(controller, STATE_PLAYING)
                : new Snapshot(STATE_STOPPED, "", ""));
        mainHandler.postDelayed(playbackWatchdog, audible ? 1500L : 400L);
    }

    private boolean isMusicController(MediaController controller) {
        if (controller == null) return false;
        String packageName = controller.getPackageName();
        if (!activeMusicNotifications.contains(packageName)) return false;
        return isMusicPackage(packageName, controller.getMetadata());
    }

    private boolean isMusicPackage(String packageName, MediaMetadata metadata) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (KNOWN_MUSIC_PACKAGES.contains(packageName)) return true;
        String normalized = packageName.toLowerCase(Locale.ROOT);
        for (String marker : NON_MUSIC_PACKAGE_MARKERS) {
            if (normalized.contains(marker)) return false;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            if (info.category == ApplicationInfo.CATEGORY_AUDIO) return true;
            if (info.category == ApplicationInfo.CATEGORY_VIDEO
                    || info.category == ApplicationInfo.CATEGORY_GAME) return false;
        } catch (PackageManager.NameNotFoundException ignored) {}
        String artist = metadataText(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) {
            artist = metadataText(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        String album = metadataText(metadata, MediaMetadata.METADATA_KEY_ALBUM);
        return !artist.isEmpty() && !album.isEmpty();
    }

    private Snapshot snapshotOf(MediaController controller, int state) {
        MediaMetadata metadata = controller.getMetadata();
        String title = metadataText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title.isEmpty()) title = metadataText(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadataText(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) artist = metadataText(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        return new Snapshot(state, title, artist);
    }

    private String metadataText(MediaMetadata metadata, String key) {
        if (metadata == null) return "";
        CharSequence value = metadata.getText(key);
        return value == null ? "" : value.toString().trim();
    }

    private void publish(Snapshot snapshot) {
        boolean enabled = getSharedPreferences("pet_settings", MODE_PRIVATE)
                .getBoolean("music_companion_enabled", true);
        if (!enabled) {
            latest = new Snapshot(STATE_STOPPED, "", "");
            return;
        }
        Snapshot previous = latest;
        latest = snapshot;
        if (previous.equals(snapshot)) return;
        Log.d(TAG, "publish state=" + snapshot.state);
        PetOverlayService.handleMusicPlayback(
                this,
                snapshot.state == STATE_PLAYING,
                snapshot.state == STATE_PAUSED,
                snapshot.title,
                snapshot.artist);
    }

    /** Replays the current state when the overlay is created after music has already started. */
    public static void syncCurrentState(Context context) {
        boolean enabled = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
                .getBoolean("music_companion_enabled", true);
        if (enabled) {
            try {
                requestRebind(new ComponentName(context, MusicNotificationListener.class));
            } catch (RuntimeException ignored) {}
        }
        Snapshot snapshot = latest;
        if (!enabled || snapshot.state != STATE_PLAYING) return;
        PetOverlayService.handleMusicPlayback(
                context, true, false, snapshot.title, snapshot.artist);
    }

    public static void setCompanionEnabled(Context context, boolean enabled) {
        if (context == null) return;
        context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("music_companion_enabled", enabled).apply();
        if (!enabled) {
            latest = new Snapshot(STATE_STOPPED, "", "");
            PetOverlayService.forceStop(context);
            return;
        }
        requestSystemRebind(context);
        MusicNotificationListener listener = connectedInstance;
        if (listener != null) {
            listener.mainHandler.post(() -> {
                listener.refreshNotificationHints();
                listener.refreshSessions();
            });
        }
    }

    /**
     * Asks Android to restore the lightweight system-owned listener after boot, an app update,
     * or an OEM background-process reclaim. This does not start the visible pet or a foreground
     * service; it only restores the callback that can notice the next media session.
     */
    public static void requestSystemRebind(Context context) {
        if (context == null) return;
        boolean enabled = context.getSharedPreferences("pet_settings", Context.MODE_PRIVATE)
                .getBoolean("music_companion_enabled", true);
        if (!enabled) return;
        try {
            requestRebind(new ComponentName(context, MusicNotificationListener.class));
            Log.i(TAG, "music listener rebind requested");
        } catch (RuntimeException exception) {
            Log.w(TAG, "music listener rebind rejected", exception);
        }
    }

    private static final class Snapshot {
        final int state;
        final String title;
        final String artist;

        Snapshot(int state, String title, String artist) {
            this.state = state;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof Snapshot)) return false;
            Snapshot snapshot = (Snapshot) object;
            return state == snapshot.state && title.equals(snapshot.title) && artist.equals(snapshot.artist);
        }

        @Override
        public int hashCode() {
            return Objects.hash(state, title, artist);
        }
    }
}
