package com.example.desktoppet.feature.voice;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.desktoppet.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Talks only to the Feiwu bridge. Third-party API credentials never enter the APK. */
final class ModelServiceClient {
    interface Callback {
        void onSuccess(String command, String reply);
        void onFailure(String reason);
    }

    private static final int MAX_HISTORY_TURNS = 4;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Deque<Turn> recentTurns = new ArrayDeque<>();

    private static final class Turn {
        final String user;
        final String assistant;

        Turn(String user, String assistant) {
            this.user = user;
            this.assistant = assistant;
        }
    }

    ModelServiceClient(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences("pet_settings", Context.MODE_PRIVATE);
    }

    boolean isEnabled() {
        return prefs.getBoolean("model_enabled", true) && !endpoint().isEmpty();
    }

    void interpret(String userText, Callback callback) {
        String endpoint = endpoint();
        String clientToken = prefs.getString(
                "bridge_client_token", BuildConfig.ASSISTANT_CLIENT_TOKEN).trim();
        executor.execute(() -> {
            try {
                JSONObject result = request(endpoint, clientToken, userText);
                String command = shorten(result.optString("command", "").trim(), 240);
                String reply = shorten(result.optString("reply", "").trim(), 400);
                remember(userText, reply.isEmpty() ? command : reply);
                callback.onSuccess(command, reply);
            } catch (Exception exception) {
                callback.onFailure(exception.getMessage() == null ? "连接失败" : exception.getMessage());
            }
        });
    }

    void close() {
        executor.shutdownNow();
    }

    private String endpoint() {
        String custom = prefs.getString("model_endpoint", "").trim();
        return custom.isEmpty() ? BuildConfig.ASSISTANT_ENDPOINT.trim() : custom;
    }

    private JSONObject request(String baseUrl, String clientToken, String userText)
            throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(normalizeEndpoint(baseUrl)).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(4500);
        connection.setReadTimeout(35000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!clientToken.isEmpty()) connection.setRequestProperty("X-Feiwu-Token", clientToken);

        JSONArray history = new JSONArray();
        for (Turn turn : recentTurns) {
            history.put(new JSONObject()
                    .put("user", turn.user)
                    .put("assistant", turn.assistant));
        }
        String now = new SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.CHINA).format(new Date());
        JSONObject body = new JSONObject()
                .put("message", userText)
                .put("client_time", now)
                .put("history", history);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }

        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(input);
        connection.disconnect();
        if (code == 401) throw new IOException("电脑服务访问令牌不正确");
        if (code < 200 || code >= 300) throw new IOException("智能对话服务返回 " + code);
        return new JSONObject(response);
    }

    private String normalizeEndpoint(String value) {
        String endpoint = value.trim();
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("服务地址需要以 http:// 或 https:// 开头");
        }
        while (endpoint.endsWith("/")) endpoint = endpoint.substring(0, endpoint.length() - 1);
        return endpoint.endsWith("/v1/assistant") ? endpoint : endpoint + "/v1/assistant";
    }

    private void remember(String user, String assistant) {
        recentTurns.addLast(new Turn(shorten(user, 500), shorten(assistant, 500)));
        while (recentTurns.size() > MAX_HISTORY_TURNS) recentTurns.removeFirst();
    }

    private String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
