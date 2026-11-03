package com.example.desktoppet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.Locale;

public class VoiceControlService extends Service implements RecognitionListener {
    static final String ACTION_STOP = "com.example.desktoppet.STOP_VOICE";
    private static final String CHANNEL_ID = "voice_control";
    private static final int NOTIFICATION_ID = 88;
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private TextToSpeech tts;
    private VoiceCommandProcessor processor;
    private long awakenedUntil;
    private boolean speaking;
    private boolean ttsReady;
    private boolean stoppingAfterReply;
    private boolean modelLoading;
    private String pendingMode = "";

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createChannel();
        Notification notification = createNotification("正在加载离线中文语音模型");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        processor = new VoiceCommandProcessor(this);
        setupTts();
        setupOfflineRecognizer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (processor != null) processor.close();
        PetOverlayService.showAssistantMessage("语音监听已关闭", PetView.Action.IDLE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static boolean isRunning() {
        return running;
    }

    private void setupOfflineRecognizer() {
        if (modelLoading || model != null) return;
        modelLoading = true;
        StorageService.unpack(this, "model-cn", "model-cn",
                unpackedModel -> handler.post(() -> startOfflineRecognizer(unpackedModel)),
                exception -> handler.post(() -> onModelError(exception)));
    }

    private void startOfflineRecognizer(Model unpackedModel) {
        modelLoading = false;
        if (!running) {
            unpackedModel.close();
            return;
        }
        model = unpackedModel;
        try {
            recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this);
            updateNotification("正在等待“你好费雾”");
            PetOverlayService.showAssistantMessage("离线语音已就绪，请说：你好费雾", PetView.Action.LISTEN);
        } catch (IOException | RuntimeException exception) {
            onModelError(exception);
        }
    }

    private void onModelError(Exception exception) {
        modelLoading = false;
        updateNotification("离线语音模型加载失败");
        PetOverlayService.showAssistantMessage("语音模型加载失败，请重新开启", PetView.Action.THINK);
        handler.postDelayed(this::stopSelf, 1600L);
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) return;
            tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            tts.setSpeechRate(1.05f);
            tts.setPitch(1.08f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {}
                @Override public void onError(String utteranceId) { onDone(utteranceId); }
                @Override public void onDone(String utteranceId) {
                    handler.post(() -> {
                        speaking = false;
                        if (stoppingAfterReply) {
                            stopSelf();
                        } else if (speechService != null) {
                            speechService.setPause(false);
                        }
                    });
                }
            });
            ttsReady = true;
        });
    }

    private String extractText(String hypothesis, String key) {
        try {
            return new JSONObject(hypothesis).optString(key, "").trim();
        } catch (JSONException ignored) {
            return "";
        }
    }

    private void handleRecognized(String text) {
        if (text == null || text.trim().isEmpty() || speaking) return;
        String normalized = text.replace("飞舞", "费雾")
                .replace("飞雾", "费雾")
                .replace("废物", "费雾")
                .replace("代办", "待办")
                .replaceAll("[，。！？,.!? ]", "");
        boolean hasWake = normalized.contains("费雾");
        if (hasWake) {
            awakenedUntil = System.currentTimeMillis() + 12_000L;
            PetOverlayService.showAssistantMessage("我在，正在听", PetView.Action.LISTEN);
            updateNotification("已唤醒，正在听命令");
            String afterWake = normalized.replace("你好费雾", "")
                    .replace("费雾费雾", "")
                    .replace("费雾", "");
            if (looksLikeCommand(afterWake)) {
                processCommand(text);
            } else {
                speakReply("我在", false, PetView.Action.LISTEN);
            }
            return;
        }
        if (System.currentTimeMillis() <= awakenedUntil) {
            processCommand(text);
        }
    }

    private boolean looksLikeCommand(String value) {
        return value.contains("提醒") || value.contains("待办") || value.contains("笔记") ||
                value.contains("记一下") || value.contains("记住") || value.contains("今天") ||
                value.contains("关闭语音") || value.contains("停止语音") || value.contains("睡觉") ||
                value.contains("隐藏");
    }

    private void processCommand(String text) {
        PetOverlayService.showAssistantMessage("正在理解：" + shorten(text), PetView.Action.THINK);
        String commandText = text;
        if ("TASK".equals(pendingMode)) commandText = "提醒我" + text;
        if ("NOTE".equals(pendingMode)) commandText = "记一下" + text;
        pendingMode = "";
        VoiceCommandProcessor.Result result = processor.process(commandText);
        if (result.reply.contains("待办内容是什么")) pendingMode = "TASK";
        if (result.reply.contains("要记什么内容")) pendingMode = "NOTE";
        awakenedUntil = result.action == PetView.Action.LISTEN
                ? System.currentTimeMillis() + 12_000L : 0L;
        speakReply(result.reply, result.stopVoice, result.action);
    }

    private void speakReply(String reply, boolean stopAfter, PetView.Action action) {
        stoppingAfterReply = stopAfter;
        speaking = true;
        if (speechService != null) speechService.setPause(true);
        PetOverlayService.showAssistantMessage(reply, action);
        updateNotification(reply);
        if (ttsReady && tts != null) {
            tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "feiwu_reply");
        } else {
            speaking = false;
            if (stopAfter) handler.postDelayed(this::stopSelf, 1200L);
            else if (speechService != null) handler.postDelayed(() -> speechService.setPause(false), 900L);
        }
    }

    private String shorten(String text) {
        return text.length() <= 14 ? text : text.substring(0, 14) + "…";
    }

    @Override
    public void onPartialResult(String hypothesis) {
        if (speaking || System.currentTimeMillis() > awakenedUntil) return;
        String partial = extractText(hypothesis, "partial");
        if (!partial.isEmpty()) {
            PetOverlayService.showAssistantMessage("正在听…", PetView.Action.LISTEN);
        }
    }

    @Override
    public void onResult(String hypothesis) {
        handleRecognized(extractText(hypothesis, "text"));
    }

    @Override
    public void onFinalResult(String hypothesis) {
        if (running && !stoppingAfterReply) handleRecognized(extractText(hypothesis, "text"));
    }

    @Override
    public void onError(Exception exception) {
        if (!running) return;
        updateNotification("麦克风监听失败");
        PetOverlayService.showAssistantMessage("麦克风监听失败，请重新开启", PetView.Action.THINK);
        handler.postDelayed(this::stopSelf, 1600L);
    }

    @Override
    public void onTimeout() {
        if (running && speechService != null && !speaking) {
            speechService.startListening(this);
        }
    }

    private Notification createNotification(String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 801, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, VoiceControlService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 802, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pet_notification)
                .setContentTitle("费雾语音控制已开启")
                .setContentText(status)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "关闭语音", stopPi).build())
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String status) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, createNotification(status));
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "费雾语音控制", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("使用离线中文模型持续监听唤醒词“你好费雾”");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }
}
