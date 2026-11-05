package com.example.desktoppet.feature.music;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Observes the system media sessions after the user grants notification access.
 * Only playback state and media metadata are consumed; notification bodies are ignored.
 */
public final class MusicNotificationListener extends NotificationListenerService {
    private static final String TAG = "FeiwuMusic";
    private static final int STATE_STOPPED = 0;
    private static final int STATE_PAUSED = 1;
    private static final int STATE_PLAYING = 2;

    private static volatile Snapshot latest = new Snapshot(STATE_STOPPED, "", "");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private final Map<String, Integer> notificationPlaybackHints = new HashMap<>();
    private MediaSessionManager sessionManager;
    private ComponentName listenerComponent;

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
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (RuntimeException ignored) {}
        }
        publish(new Snapshot(STATE_STOPPED, "", ""));
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
        if (sbn != null) notificationPlaybackHints.remove(sbn.getPackageName());
        publishBestSession();
    }

    private void refreshNotificationHints() {
        notificationPlaybackHints.clear();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            for (StatusBarNotification sbn : active) updateNotificationHint(sbn);
        } catch (RuntimeException ignored) {}
    }

    private void updateNotificationHint(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        int state = transportStateFromNotification(sbn.getNotification());
        if (state == STATE_STOPPED) return;
        notificationPlaybackHints.put(sbn.getPackageName(), state);
        Log.d(TAG, "transport hint package=" + sbn.getPackageName() + " state=" + state);
    }

    /**
     * Some players (notably certain Luna Music builds) keep their MediaSession in PAUSED while
     * audio is actually playing. Their transport notification remains authoritative: a visible
     * Pause action means playback is active, while a Play action means it is paused.
     */
    private int transportStateFromNotification(Notification notification) {
        boolean transport = Notification.CATEGORY_TRANSPORT.equals(notification.category)
                || (notification.extras != null
                && notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) != null);
        if (!transport || notification.actions == null) return STATE_STOPPED;
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
        MediaController pausedCandidate = null;
        for (MediaController controller : controllers) {
            PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState == null) continue;
            int state = playbackState.getState();
            if (state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_BUFFERING ||
                    state == PlaybackState.STATE_CONNECTING ||
                    state == PlaybackState.STATE_FAST_FORWARDING ||
                    state == PlaybackState.STATE_REWINDING) {
                publish(snapshotOf(controller, STATE_PLAYING));
                return;
            }
            if (notificationPlaybackHints.getOrDefault(controller.getPackageName(), STATE_STOPPED)
                    == STATE_PLAYING) {
                publish(snapshotOf(controller, STATE_PLAYING));
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
        Snapshot previous = latest;
        latest = snapshot;
        if (previous.equals(snapshot)) return;
        Log.d(TAG, "publish state=" + snapshot.state);
        boolean enabled = getSharedPreferences("pet_settings", MODE_PRIVATE)
                .getBoolean("music_companion_enabled", true);
        if (!enabled) {
            PetOverlayService.handleMusicPlayback(this, false, false, "", "");
            return;
        }
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
