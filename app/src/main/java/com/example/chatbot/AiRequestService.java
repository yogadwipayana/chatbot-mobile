package com.example.chatbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class AiRequestService extends Service {

    public static final String EXTRA_SESSION_ID      = "session_id";
    public static final String EXTRA_API_TEXT        = "api_text";
    public static final String EXTRA_STATUS          = "status";
    public static final String EXTRA_USER_MESSAGE_ID = "user_message_id";
    public static final String EXTRA_ERROR_MESSAGE   = "error_message";

    public static final String ACTION_AI_STATUS_CHANGED = "com.example.chatbot.AI_STATUS_CHANGED";
    public static final String ACTION_AI_RESPONSE_SAVED = "com.example.chatbot.AI_RESPONSE_SAVED";
    public static final String ACTION_AI_REQUEST_FAILED = "com.example.chatbot.AI_REQUEST_FAILED";

    private static final String CHANNEL_ID      = "ai_requests";
    private static final int    NOTIFICATION_ID = 1001;

    private static final long TIMEOUT_CHAT_MS  = 90_000;
    private static final long TIMEOUT_WEB_MS   = 90_000;
    private static final long TIMEOUT_IMAGE_MS = 300_000;

    // Max tool-call iterations per request (prevents infinite loops)
    private static final int MAX_TOOL_ITERATIONS = 5;

    private ChatDAO        chatDAO;
    private DwipaApiClient apiClient;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());
    private final Object activeRequestLock = new Object();
    private final Map<Long, RequestLifecycle> activeRequests = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        chatDAO   = new ChatDAO(this);
        apiClient = new DwipaApiClient();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }

        long sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1);
        long userMsgId = intent.getLongExtra(EXTRA_USER_MESSAGE_ID, -1);
        String apiText = intent.getStringExtra(EXTRA_API_TEXT);

        if (sessionId == -1) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(sessionId, "Menganalisis permintaan"));
        notifyStatus(sessionId, "Menganalisis permintaan");
        processRequest(sessionId, userMsgId, apiText, startId);
        return START_NOT_STICKY;
    }

    // -------------------------------------------------------------------------
    // Core: multi-turn tool loop
    // -------------------------------------------------------------------------

    private void processRequest(long sessionId, long userMsgId, String apiText, int startId) {
        List<Message> history = chatDAO.getMessagesBySession(sessionId);
        RequestLifecycle lifecycle = new RequestLifecycle(sessionId, userMsgId, startId);
        replaceActiveRequest(lifecycle);
        lifecycle.setTimeout(TIMEOUT_CHAT_MS, "Jawaban AI terlalu lama. Coba lagi.");

        JsonArray messages = apiClient.buildMessagesForRequest(history, userMsgId, apiText);
        runToolLoop(sessionId, messages, lifecycle, 0);
    }

    /**
     * Core loop. messages is the full accumulated conversation array including
     * any in-flight tool exchanges from previous iterations.
     */
    private void runToolLoop(
            long sessionId,
            JsonArray messages,
            RequestLifecycle lifecycle,
            int iteration
    ) {
        if (lifecycle.isCompleted()) return;
        if (iteration >= MAX_TOOL_ITERATIONS) {
            lifecycle.complete("Maaf, permintaan ini membutuhkan terlalu banyak langkah. Coba sederhanakan pertanyaanmu.");
            return;
        }

        apiClient.continueWithMessages(messages, new DwipaApiClient.ToolCallCallback() {
            @Override
            public void onToolCalls(List<DwipaApiClient.ToolCall> toolCalls) {
                dispatchToolCalls(sessionId, messages, lifecycle, iteration, toolCalls);
            }

            @Override
            public void onFinalAnswer(String content) {
                lifecycle.complete(cleanBotResponse(content));
            }

            @Override
            public void onFailure(String errorMessage) {
                if (isRetryable(errorMessage)) {
                    lifecycle.fail(errorMessage);
                } else {
                    lifecycle.complete(errorMessage);
                }
            }
        });
    }

    /**
     * Dispatch all tool calls returned in one model response.
     * Executes them, collects all results, then continues the loop with the
     * fully updated messages array (assistant tool_call msg + all tool results).
     */
    private void dispatchToolCalls(
            long sessionId,
            JsonArray messages,
            RequestLifecycle lifecycle,
            int iteration,
            List<DwipaApiClient.ToolCall> toolCalls
    ) {
        if (lifecycle.isCompleted()) return;
        int total = toolCalls.size();
        // AtomicReferenceArray ensures thread-safe writes from concurrent callbacks
        AtomicReferenceArray<String> results = new AtomicReferenceArray<>(total);
        AtomicInteger done = new AtomicInteger(0);

        // All tool_calls in one response share the same assistant message object
        JsonObject assistantMsg = toolCalls.get(0).assistantMessage;

        for (int i = 0; i < total; i++) {
            final int idx = i;
            DwipaApiClient.ToolCall tc = toolCalls.get(i);
            executeSingleTool(sessionId, lifecycle, tc, new DwipaApiClient.ApiCallback() {
                @Override
                public void onSuccess(String result) {
                    results.set(idx, result);
                    if (done.incrementAndGet() == total) {
                        onAllToolsDone(sessionId, messages, lifecycle, iteration,
                                assistantMsg, toolCalls, results);
                    }
                }

                @Override
                public void onFailure(String errorMessage) {
                    // Inject error as tool result so model can recover gracefully
                    results.set(idx, "Tool gagal: " + errorMessage +
                            ". Jawab berdasarkan pengetahuanmu dan sebutkan data real-time tidak tersedia.");
                    if (done.incrementAndGet() == total) {
                        onAllToolsDone(sessionId, messages, lifecycle, iteration,
                                assistantMsg, toolCalls, results);
                    }
                }
            });
        }
    }

    /** Called once all parallel tool executions have completed. */
    private void onAllToolsDone(
            long sessionId,
            JsonArray messages,
            RequestLifecycle lifecycle,
            int iteration,
            JsonObject assistantMsg,
            List<DwipaApiClient.ToolCall> toolCalls,
            AtomicReferenceArray<String> results
    ) {
        if (lifecycle.isCompleted()) return;
        // Build tool result triples: [toolCallId, toolName, result]
        List<String[]> toolResults = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            DwipaApiClient.ToolCall tc = toolCalls.get(i);
            toolResults.add(new String[]{tc.id, tc.name, results.get(i)});
        }

        // Append assistant tool_call message + all tool results to the messages array
        JsonArray updated = apiClient.appendToolExchange(messages, assistantMsg, toolResults);

        notifyStatus(sessionId, "Menyusun jawaban");
        updateNotification(sessionId, "Menyusun jawaban");
        lifecycle.setTimeout(TIMEOUT_CHAT_MS, "Penyusunan jawaban terlalu lama. Coba lagi.");

        // Continue the loop with the fully updated messages array
        runToolLoop(sessionId, updated, lifecycle, iteration + 1);
    }

    /** Execute a single tool and return its result via callback (always calls onSuccess). */
    private void executeSingleTool(
            long sessionId,
            RequestLifecycle lifecycle,
            DwipaApiClient.ToolCall tc,
            DwipaApiClient.ApiCallback callback
    ) {
        switch (tc.name) {
            case DwipaApiClient.TOOL_WEB_SEARCH: {
                String query = tc.args.has("query") ? tc.args.get("query").getAsString() : "";
                notifyStatus(sessionId, "Mencari: " + truncateStatus(query));
                updateNotification(sessionId, "Mencari di web");
                lifecycle.setTimeout(TIMEOUT_WEB_MS, "Pencarian web terlalu lama. Coba lagi.");
                apiClient.webSearch(query, callback);
                break;
            }
            case DwipaApiClient.TOOL_WEB_FETCH: {
                String url = tc.args.has("url") ? tc.args.get("url").getAsString() : "";
                notifyStatus(sessionId, "Membaca: " + truncateStatus(url));
                updateNotification(sessionId, "Membaca halaman web");
                lifecycle.setTimeout(TIMEOUT_WEB_MS, "Pengambilan halaman web terlalu lama. Coba lagi.");
                apiClient.webFetch(url, callback);
                break;
            }
            case DwipaApiClient.TOOL_IMAGE_GENERATIONS: {
                String prompt = tc.args.has("prompt") ? tc.args.get("prompt").getAsString() : "";
                notifyStatus(sessionId, "Membuat gambar");
                updateNotification(sessionId, "Membuat gambar");
                lifecycle.setTimeout(TIMEOUT_IMAGE_MS, "Pembuatan gambar terlalu lama. Coba lagi.");
                apiClient.generateImage(prompt, new DwipaApiClient.ApiCallback() {
                    @Override
                    public void onSuccess(String result) {
                        notifyStatus(sessionId, "Menyimpan gambar");
                        lifecycle.complete(result);
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        lifecycle.fail(errorMessage);
                    }
                });
                break;
            }
            default:
                // Unknown tool — return empty result so model can continue
                callback.onSuccess("Tool '" + tc.name + "' tidak dikenal.");
                break;
        }
    }

    // -------------------------------------------------------------------------
    // RequestLifecycle — timeout + single-completion guard
    // -------------------------------------------------------------------------

    private class RequestLifecycle {
        private final long     sessionId;
        private final long     userMsgId;
        private final int      startId;
        private Runnable       timeoutRunnable;
        private final AtomicBoolean completed = new AtomicBoolean(false);

        RequestLifecycle(long sessionId, long userMsgId, int startId) {
            this.sessionId = sessionId;
            this.userMsgId = userMsgId;
            this.startId   = startId;
        }

        void setTimeout(long ms, String message) {
            if (completed.get()) return;
            if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = () -> fail(message);
            mainHandler.postDelayed(timeoutRunnable, ms);
        }

        boolean isCompleted() {
            return completed.get();
        }

        void complete(String response) {
            if (!completed.compareAndSet(false, true)) return;
            clearActiveRequest(this);
            cancelTimeout();
            new Thread(() -> {
                try {
                    chatDAO.insertMessage(sessionId, "bot", persistLargeImageIfNeeded(response));
                } catch (Exception ignored) {}
                notifyResponseSaved(sessionId);
                mainHandler.post(() -> finish(startId));
            }).start();
        }

        void fail(String errorMessage) {
            if (!completed.compareAndSet(false, true)) return;
            clearActiveRequest(this);
            cancelTimeout();
            notifyRequestFailed(sessionId, userMsgId, errorMessage);
            mainHandler.post(() -> finish(startId));
        }

        void cancelSuperseded() {
            if (!completed.compareAndSet(false, true)) return;
            clearActiveRequest(this);
            cancelTimeout();
            mainHandler.post(() -> stopSelf(startId));
        }

        private void cancelTimeout() {
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
        }
    }

    private void replaceActiveRequest(RequestLifecycle lifecycle) {
        synchronized (activeRequestLock) {
            RequestLifecycle previous = activeRequests.get(lifecycle.sessionId);
            if (previous != null && previous != lifecycle) {
                previous.cancelSuperseded();
            }
            activeRequests.put(lifecycle.sessionId, lifecycle);
        }
    }

    private void clearActiveRequest(RequestLifecycle lifecycle) {
        synchronized (activeRequestLock) {
            if (activeRequests.get(lifecycle.sessionId) == lifecycle) {
                activeRequests.remove(lifecycle.sessionId);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Image persistence
    // -------------------------------------------------------------------------

    private String persistLargeImageIfNeeded(String response) {
        if (response == null || !response.startsWith(DwipaApiClient.IMAGE_BASE64_PREFIX)) {
            return response;
        }
        String base64 = response.substring(DwipaApiClient.IMAGE_BASE64_PREFIX.length()).trim();
        try {
            String data = base64;
            int comma = data.indexOf(',');
            if (comma >= 0) data = data.substring(comma + 1);

            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            File dir = new File(getFilesDir(), "generated_images");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create image dir");

            String fileName = "ai_image_" + System.currentTimeMillis() + ".png";
            try (FileOutputStream out = new FileOutputStream(new File(dir, fileName))) {
                out.write(bytes);
            }
            return DwipaApiClient.IMAGE_FILE_PREFIX + "generated_images/" + fileName;
        } catch (Exception e) {
            return "Gambar berhasil dibuat, tetapi gagal disimpan di perangkat.";
        }
    }

    // -------------------------------------------------------------------------
    // Broadcasts
    // -------------------------------------------------------------------------

    private void notifyResponseSaved(long sessionId) {
        Intent i = new Intent(ACTION_AI_RESPONSE_SAVED);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_SESSION_ID, sessionId);
        sendBroadcast(i);
    }

    private void notifyRequestFailed(long sessionId, long userMsgId, String error) {
        Intent i = new Intent(ACTION_AI_REQUEST_FAILED);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_SESSION_ID, sessionId);
        i.putExtra(EXTRA_USER_MESSAGE_ID, userMsgId);
        i.putExtra(EXTRA_ERROR_MESSAGE, error);
        sendBroadcast(i);
    }

    private void notifyStatus(long sessionId, String status) {
        Intent i = new Intent(ACTION_AI_STATUS_CHANGED);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_SESSION_ID, sessionId);
        i.putExtra(EXTRA_STATUS, status);
        sendBroadcast(i);
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------

    private void updateNotification(long sessionId, String status) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(sessionId, status));
    }

    private Notification buildNotification(long sessionId, String status) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pi = PendingIntent.getActivity(this, (int) sessionId, intent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Chatbot sedang memproses")
                .setContentText(status)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "AI Requests", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Notifikasi saat Chatbot memproses permintaan AI");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isRetryable(String msg) {
        if (msg == null) return true;
        String lower = msg.toLowerCase();
        return lower.contains("timeout") || lower.contains("terlalu lama")
                || lower.contains("tidak ada koneksi") || lower.contains("koneksi gagal")
                || lower.contains("gagal memproses") || lower.contains("coba lagi")
                || lower.contains("error api");
    }

    private String cleanBotResponse(String response) {
        if (response == null) return "";
        return response
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("(?m)^\\s*(\\d+)\\.\\s+", "$1. ")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    /** Truncate a string for display in status/notification (max 40 chars). */
    private String truncateStatus(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.length() > 40 ? text.substring(0, 40) + "…" : text;
    }

    private void finish(int startId) {
        stopForeground(true);
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (chatDAO != null) chatDAO.close();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
