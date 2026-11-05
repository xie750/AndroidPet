package com.example.desktoppet.feature.voice;

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
import android.speech.tts.Voice;

import com.example.desktoppet.R;
import com.example.desktoppet.feature.pet.PetOverlayService;
import com.example.desktoppet.feature.pet.PetView;
import com.example.desktoppet.ui.MainActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class VoiceControlService extends Service implements RecognitionListener {
    public static final String ACTION_STOP = "com.example.desktoppet.STOP_VOICE";
    public static final String ACTION_REFRESH_SETTINGS = "com.example.desktoppet.REFRESH_VOICE_SETTINGS";
    public static final String ACTION_PREVIEW_TTS = "com.example.desktoppet.PREVIEW_TTS";
    private static final String CHANNEL_ID = "voice_control";
    private static final int NOTIFICATION_ID = 88;
    private static final long FOLLOW_UP_WINDOW_MS = 12_000L;
    private static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private TextToSpeech tts;
    private VoiceCommandProcessor processor;
    private ModelServiceClient modelClient;
    private long awakenedUntil;
    private boolean speaking;
    private boolean ttsReady;
    private boolean stoppingAfterReply;
    private boolean modelLoading;
    private boolean understanding;
    private String pendingMode = "";
    private final Runnable followUpTimeout = () -> {
        if (!running || speaking || understanding ||
                System.currentTimeMillis() <= awakenedUntil) return;
        awakenedUntil = 0L;
        PetOverlayService.showAssistantMessage("连续对话已结束，需要时再叫我", PetView.Action.IDLE);
        updateNotification("正在等待“你好费雾”");
    };

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
        modelClient = new ModelServiceClient(this);
        setupTts();
        setupOfflineRecognizer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences("pet_settings", MODE_PRIVATE).edit()
                    .putBoolean("voice_enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH_SETTINGS.equals(intent.getAction())) {
            applyTtsSettings();
        }
        if (intent != null && ACTION_PREVIEW_TTS.equals(intent.getAction())) {
            applyTtsSettings();
            Runnable preview = () -> {
                if (running && ttsReady) {
                    speakReply("你好呀，我是费雾。这个声音听起来怎么样？",
                            false, PetView.Action.TAP);
                }
            };
            if (ttsReady) preview.run(); else handler.postDelayed(preview, 1200L);
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
        if (modelClient != null) modelClient.close();
        PetOverlayService.showAssistantMessage("语音监听已关闭", PetView.Action.IDLE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isRunning() {
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
            applyTtsSettings();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    PetOverlayService.setAssistantSpeaking(true);
                }
                @Override public void onError(String utteranceId) { onDone(utteranceId); }
                @Override public void onDone(String utteranceId) {
                    handler.post(() -> {
                        PetOverlayService.setAssistantSpeaking(false);
                        speaking = false;
                        if (stoppingAfterReply) {
                            stopSelf();
                        } else if (speechService != null) {
                            resumeListeningAfterReply();
                        }
                    });
                }
            });
            ttsReady = true;
        });
    }

    private void applyTtsSettings() {
        if (tts == null) return;
        String profile = getSharedPreferences("pet_settings", MODE_PRIVATE)
                .getString("tts_profile", "natural");
        tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        float rate = 0.97f;
        float pitch = 1.00f;
        boolean offlineOnly = false;
        if ("gentle".equals(profile)) {
            rate = 0.86f;
            pitch = 0.92f;
        } else if ("bright".equals(profile)) {
            rate = 1.10f;
            pitch = 1.18f;
        } else if ("offline".equals(profile)) {
            rate = 0.96f;
            pitch = 1.00f;
            offlineOnly = true;
        } else if ("system".equals(profile)) {
            tts.setSpeechRate(1.0f);
            tts.setPitch(1.0f);
            return;
        }
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        selectChineseVoice(profile, offlineOnly);
    }

    private void selectChineseVoice(String profile, boolean offlineOnly) {
        if (tts == null) return;
        Set<Voice> voices = tts.getVoices();
        if (voices == null) return;
        List<Voice> candidates = new ArrayList<>();
        for (Voice voice : voices) {
            if (voice.getLocale() == null ||
                    !"zh".equalsIgnoreCase(voice.getLocale().getLanguage())) continue;
            if (offlineOnly && voice.isNetworkConnectionRequired()) continue;
            candidates.add(voice);
        }
        candidates.sort(Comparator
                .comparingInt(this::scoreChineseVoice).reversed()
                .thenComparing(Voice::getName));
        if (candidates.isEmpty()) return;

        int index = 0;
        if ("gentle".equals(profile) && candidates.size() > 1) {
            index = 1;
        } else if ("bright".equals(profile)) {
            index = candidates.size() > 2 ? 2 : candidates.size() - 1;
        }
        Voice selected = candidates.get(index);
        if (tts.setVoice(selected) != TextToSpeech.SUCCESS && index != 0) {
            tts.setVoice(candidates.get(0));
        }
    }

    private int scoreChineseVoice(Voice voice) {
        String country = voice.getLocale().getCountry();
        int localeScore = "CN".equalsIgnoreCase(country) ? 10_000 :
                (country == null || country.isEmpty() ? 5_000 : 0);
        int networkBonus = voice.isNetworkConnectionRequired() ? 500 : 0;
        return localeScore + voice.getQuality() * 100 - voice.getLatency() + networkBonus;
    }

    private String extractText(String hypothesis, String key) {
        try {
            return new JSONObject(hypothesis).optString(key, "").trim();
        } catch (JSONException ignored) {
            return "";
        }
    }

    private void handleRecognized(String text) {
        if (text == null || text.trim().isEmpty() || speaking || understanding) return;
        String normalized = text.replace("飞舞", "费雾")
                .replace("飞雾", "费雾")
                .replace("废物", "费雾")
                .replace("代办", "待办")
                .replaceAll("[，。！？,.!? ]", "");
        boolean hasWake = normalized.contains("费雾");
        if (hasWake) {
            beginConversationTurn();
            PetOverlayService.showAssistantMessage("我在，正在听", PetView.Action.LISTEN);
            updateNotification("已唤醒，正在听命令");
            String afterWake = normalized.replace("你好费雾", "")
                    .replace("费雾费雾", "")
                    .replace("费雾", "");
            if (!afterWake.isEmpty()) {
                processCommand(text);
            } else {
                speakReply("我在", false, PetView.Action.LISTEN);
            }
            return;
        }
        if (System.currentTimeMillis() <= awakenedUntil) {
            if (isConversationEnd(normalized)) {
                endConversation();
                speakReply("好的，有事再叫我。", false, PetView.Action.IDLE);
                return;
            }
            processCommand(text);
        }
    }

    private void processCommand(String text) {
        beginConversationTurn();
        PetOverlayService.showAssistantMessage("正在理解：" + shorten(text), PetView.Action.THINK);
        String commandText = text;
        if ("TASK".equals(pendingMode)) commandText = "提醒我" + text;
        if ("NOTE".equals(pendingMode)) commandText = "记一下" + text;
        pendingMode = "";
        VoiceCommandProcessor.Result result = processor.process(commandText);
        if (result.reply.contains("待办内容是什么")) pendingMode = "TASK";
        if (result.reply.contains("要记什么内容")) pendingMode = "NOTE";
        if (result.handled) {
            finishLocalResult(result);
            return;
        }
        if (modelClient == null || !modelClient.isEnabled()) {
            speakReply("智能对话服务还没有配置好。配置后，普通问题也会由我继续回答。",
                    false, PetView.Action.THINK);
            return;
        }
        understanding = true;
        if (speechService != null) speechService.setPause(true);
        updateNotification("正在请电脑上的模型理解");
        PetOverlayService.showAssistantMessage("让我想一下…", PetView.Action.THINK);
        modelClient.interpret(text, new ModelServiceClient.Callback() {
            @Override
            public void onSuccess(String command, String reply) {
                handler.post(() -> {
                    understanding = false;
                    if (!running) return;
                    if (!command.isEmpty()) {
                        VoiceCommandProcessor.Result modelResult = processor.process(command);
                        if (modelResult.handled) {
                            finishLocalResult(modelResult);
                            return;
                        }
                    }
                    speakReply(reply.isEmpty() ? "你可以再补充一点具体信息吗？" : reply,
                            false, reply.isEmpty() ? PetView.Action.LISTEN : PetView.Action.TAP);
                });
            }

            @Override
            public void onFailure(String reason) {
                handler.post(() -> {
                    understanding = false;
                    if (running) speakReply("智能对话服务暂时没有响应，请稍后再问我一次。",
                            false, PetView.Action.THINK);
                });
            }
        });
    }

    private void finishLocalResult(VoiceCommandProcessor.Result result) {
        if (result.stopVoice) {
            getSharedPreferences("pet_settings", MODE_PRIVATE).edit()
                    .putBoolean("voice_enabled", false).apply();
        }
        speakReply(result.reply, result.stopVoice, result.action);
    }

    private void speakReply(String reply, boolean stopAfter, PetView.Action action) {
        stoppingAfterReply = stopAfter;
        speaking = true;
        if (speechService != null) speechService.setPause(true);
        PetOverlayService.showAssistantMessage(reply, action);
        updateNotification(reply);
        if (ttsReady && tts != null) {
            PetOverlayService.setAssistantSpeaking(true);
            tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "feiwu_reply");
        } else {
            PetOverlayService.setAssistantSpeaking(false);
            speaking = false;
            if (stopAfter) handler.postDelayed(this::stopSelf, 1200L);
            else if (speechService != null) handler.postDelayed(this::resumeListeningAfterReply, 900L);
        }
    }

    private void beginConversationTurn() {
        handler.removeCallbacks(followUpTimeout);
        awakenedUntil = Long.MAX_VALUE;
    }

    private void resumeListeningAfterReply() {
        if (!running || speechService == null) return;
        speechService.setPause(false);
        if (awakenedUntil == 0L) return;
        awakenedUntil = System.currentTimeMillis() + FOLLOW_UP_WINDOW_MS;
        PetOverlayService.showAssistantMessage("我还在听，可以直接继续说", PetView.Action.LISTEN);
        updateNotification("连续对话中，可以直接继续说");
        handler.removeCallbacks(followUpTimeout);
        handler.postDelayed(followUpTimeout, FOLLOW_UP_WINDOW_MS + 100L);
    }

    private void endConversation() {
        handler.removeCallbacks(followUpTimeout);
        awakenedUntil = 0L;
        pendingMode = "";
    }

    private boolean isConversationEnd(String text) {
        return text.contains("谢谢") || text.contains("不用了") || text.contains("没事了") ||
                text.contains("结束对话") || text.contains("先这样") || text.contains("再见");
    }

    private String shorten(String text) {
        return text.length() <= 14 ? text : text.substring(0, 14) + "…";
    }

    @Override
    public void onPartialResult(String hypothesis) {
        if (speaking || understanding || System.currentTimeMillis() > awakenedUntil) return;
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
