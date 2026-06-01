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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class AiRequestService extends Service {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_API_TEXT = "api_text";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_USER_MESSAGE_ID = "user_message_id";
    public static final String EXTRA_ERROR_MESSAGE = "error_message";
    public static final String ACTION_AI_STATUS_CHANGED = "com.example.chatbot.AI_STATUS_CHANGED";
    public static final String ACTION_AI_RESPONSE_SAVED = "com.example.chatbot.AI_RESPONSE_SAVED";
    public static final String ACTION_AI_REQUEST_FAILED = "com.example.chatbot.AI_REQUEST_FAILED";

    private static final String CHANNEL_ID = "ai_requests";
    private static final int NOTIFICATION_ID = 1001;
    private static final long ROUTING_TIMEOUT_MS = 45_000;
    private static final long CHAT_TIMEOUT_MS = 90_000;
    private static final long WEB_TIMEOUT_MS = 90_000;
    private static final long IMAGE_TIMEOUT_MS = 300_000;

    private ChatDAO chatDAO;
    private DwipaApiClient apiClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        chatDAO = new ChatDAO(this);
        apiClient = new DwipaApiClient();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        long sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1);
        long userMessageId = intent.getLongExtra(EXTRA_USER_MESSAGE_ID, -1);
        String apiText = intent.getStringExtra(EXTRA_API_TEXT);
        if (sessionId == -1 || apiText == null || apiText.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(sessionId, "Thinking"));
        notifyStatusChanged(sessionId, "Thinking");
        processRequest(sessionId, userMessageId, apiText, startId);
        return START_NOT_STICKY;
    }

    private void processRequest(long sessionId, long userMessageId, String apiText, int startId) {
        RequestLifecycle lifecycle = new RequestLifecycle(sessionId, userMessageId, startId);
        lifecycle.setTimeout(ROUTING_TIMEOUT_MS, "Permintaan terlalu lama dianalisis. Coba lagi.");

        apiClient.detectIntent(apiText, new DwipaApiClient.IntentCallback() {
            @Override
            public void onSuccess(String intent) {
                String currentUrl = extractFirstUrl(apiText);
                String previousUrl = "";
                if (currentUrl.isEmpty() && shouldUsePreviousUrlForFetch(apiText)) {
                    previousUrl = findLastUrlInSession(sessionId, userMessageId);
                }

                String routedIntent = intent;
                if (!currentUrl.isEmpty() && !"image_generations".equals(intent) && !isFreshnessRequest(apiText)) {
                    routedIntent = "web_fetch";
                } else if (!previousUrl.isEmpty() && "chat".equals(intent)) {
                    routedIntent = "web_fetch";
                }

                DwipaApiClient.ApiCallback callback = new DwipaApiClient.ApiCallback() {
                    @Override
                    public void onSuccess(String response) {
                        lifecycle.complete(cleanBotResponse(response));
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        if (isRetryableFailure(errorMessage)) {
                            lifecycle.fail(errorMessage);
                        } else {
                            lifecycle.complete(errorMessage);
                        }
                    }
                };

                if ("image_generations".equals(routedIntent)) {
                    updateNotification(sessionId, "Image generation");
                    lifecycle.setTimeout(IMAGE_TIMEOUT_MS, "Pembuatan gambar terlalu lama. Coba lagi.");
                    apiClient.generateImage(apiText, callback);
                } else if ("web_fetch".equals(routedIntent)) {
                    String url = currentUrl.isEmpty() ? previousUrl : currentUrl;
                    if (url.isEmpty()) {
                        updateNotification(sessionId, "Chat");
                        lifecycle.setTimeout(CHAT_TIMEOUT_MS, "Jawaban AI terlalu lama. Coba lagi.");
                        apiClient.sendMessage(getMessages(sessionId), callback);
                    } else {
                        updateNotification(sessionId, "Web fetch");
                        lifecycle.setTimeout(WEB_TIMEOUT_MS, "Pengambilan halaman web terlalu lama. Coba lagi.");
                        apiClient.webFetch(url, new DwipaApiClient.ApiCallback() {
                            @Override
                            public void onSuccess(String fetchedContent) {
                                updateNotification(sessionId, "Preparing answer");
                                lifecycle.setTimeout(CHAT_TIMEOUT_MS, "Penyusunan jawaban AI terlalu lama. Coba lagi.");
                                apiClient.answerWithFetchedPage(apiText, url, fetchedContent, callback);
                            }

                            @Override
                            public void onFailure(String errorMessage) {
                                callback.onFailure(errorMessage);
                            }
                        });
                    }
                } else if ("web_search".equals(routedIntent)) {
                    updateNotification(sessionId, "Web search");
                    lifecycle.setTimeout(WEB_TIMEOUT_MS, "Pencarian web terlalu lama. Coba lagi.");
                    apiClient.webSearch(apiText, new DwipaApiClient.ApiCallback() {
                        @Override
                        public void onSuccess(String searchResults) {
                            updateNotification(sessionId, "Preparing answer");
                            lifecycle.setTimeout(CHAT_TIMEOUT_MS, "Penyusunan jawaban AI terlalu lama. Coba lagi.");
                            apiClient.answerWithSearchResults(apiText, searchResults, callback);
                        }

                        @Override
                        public void onFailure(String errorMessage) {
                            callback.onFailure(errorMessage);
                        }
                    });
                } else {
                    updateNotification(sessionId, "Chat");
                    lifecycle.setTimeout(CHAT_TIMEOUT_MS, "Jawaban AI terlalu lama. Coba lagi.");
                    apiClient.sendMessage(getMessages(sessionId), callback);
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                lifecycle.fail(errorMessage);
            }
        });
    }

    private class RequestLifecycle {
        private final long sessionId;
        private final long userMessageId;
        private final int startId;
        private Runnable timeoutRunnable;
        private boolean completed = false;

        RequestLifecycle(long sessionId, long userMessageId, int startId) {
            this.sessionId = sessionId;
            this.userMessageId = userMessageId;
            this.startId = startId;
        }

        void setTimeout(long timeoutMs, String timeoutMessage) {
            if (completed) return;
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
            }
            timeoutRunnable = () -> fail(timeoutMessage);
            mainHandler.postDelayed(timeoutRunnable, timeoutMs);
        }

        void complete(String response) {
            if (completed) return;
            completed = true;
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
            finishWithResponse(sessionId, response, startId);
        }

        void fail(String errorMessage) {
            if (completed) return;
            completed = true;
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
            finishWithFailure(sessionId, userMessageId, errorMessage, startId);
        }
    }

    private boolean isRetryableFailure(String errorMessage) {
        if (errorMessage == null) return true;
        String lower = errorMessage.toLowerCase();
        return lower.contains("timeout")
                || lower.contains("terlalu lama")
                || lower.contains("tidak ada koneksi")
                || lower.contains("koneksi gagal");
    }

    private void finishWithResponse(long sessionId, String response, int startId) {
        new Thread(() -> {
            boolean responseSaved = false;
            try {
                saveBotResponse(sessionId, response);
                responseSaved = true;
            } catch (Exception ignored) {
                // Tetap selesaikan request di UI meskipun penyimpanan lokal gagal.
            } finally {
                if (!responseSaved) {
                    notifyResponseSaved(sessionId);
                }
                mainHandler.post(() -> finish(startId));
            }
        }).start();
    }

    private List<Message> getMessages(long sessionId) {
        return chatDAO.getMessagesBySession(sessionId);
    }

    private void saveBotResponse(long sessionId, String response) {
        chatDAO.insertMessage(sessionId, "bot", persistLargeImageIfNeeded(response));
        notifyResponseSaved(sessionId);
    }

    private void finishWithFailure(long sessionId, long userMessageId, String errorMessage, int startId) {
        notifyRequestFailed(sessionId, userMessageId, errorMessage);
        mainHandler.post(() -> finish(startId));
    }

    private String persistLargeImageIfNeeded(String response) {
        if (response == null || !response.startsWith(DwipaApiClient.IMAGE_BASE64_PREFIX)) {
            return response;
        }

        String base64Image = response.substring(DwipaApiClient.IMAGE_BASE64_PREFIX.length()).trim();
        try {
            String data = base64Image;
            int commaIndex = data.indexOf(',');
            if (commaIndex >= 0) data = data.substring(commaIndex + 1);

            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            File imageDir = new File(getFilesDir(), "generated_images");
            if (!imageDir.exists() && !imageDir.mkdirs()) {
                throw new IOException("Image directory unavailable");
            }

            String fileName = "ai_image_" + System.currentTimeMillis() + ".png";
            File imageFile = new File(imageDir, fileName);
            try (FileOutputStream outputStream = new FileOutputStream(imageFile)) {
                outputStream.write(bytes);
            }

            return DwipaApiClient.IMAGE_FILE_PREFIX + "generated_images/" + fileName;
        } catch (Exception e) {
            return "Gambar berhasil dibuat, tetapi gagal disimpan di perangkat.";
        }
    }

    private void notifyResponseSaved(long sessionId) {
        Intent intent = new Intent(ACTION_AI_RESPONSE_SAVED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        sendBroadcast(intent);
    }

    private void notifyRequestFailed(long sessionId, long userMessageId, String errorMessage) {
        Intent intent = new Intent(ACTION_AI_REQUEST_FAILED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_USER_MESSAGE_ID, userMessageId);
        intent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage);
        sendBroadcast(intent);
    }

    private void notifyStatusChanged(long sessionId, String status) {
        Intent intent = new Intent(ACTION_AI_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.putExtra(EXTRA_STATUS, status);
        sendBroadcast(intent);
    }

    private String extractFirstUrl(String text) {
        if (text == null) return "";

        Matcher explicitUrl = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE).matcher(text);
        if (explicitUrl.find()) {
            return cleanUrl(explicitUrl.group());
        }

        Matcher bareDomain = Pattern.compile("\\b((?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,})(/[^\\s]*)?").matcher(text);
        while (bareDomain.find()) {
            if (bareDomain.start() > 0 && text.charAt(bareDomain.start() - 1) == '@') continue;
            return cleanUrl("https://" + bareDomain.group());
        }

        return "";
    }

    private String cleanUrl(String url) {
        if (url == null) return "";
        return url.trim().replaceAll("[),.?!]+$", "");
    }

    private String findLastUrlInSession(long sessionId, long currentUserMessageId) {
        List<Message> messages = getMessages(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getId() == currentUserMessageId) continue;
            if (!"user".equalsIgnoreCase(message.getRole())) continue;

            String url = extractFirstUrl(message.getContent());
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private boolean shouldUsePreviousUrlForFetch(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("readme")
                || lower.contains("profil")
                || lower.contains("profile")
                || lower.contains("repo")
                || lower.contains("repository")
                || lower.contains("github")
                || lower.contains("cek")
                || lower.contains("buka")
                || lower.contains("lihat")
                || lower.contains("akses")
                || lower.contains("ringkas")
                || lower.contains("analisis")
                || lower.contains("tadi");
    }

    private boolean isFreshnessRequest(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("terbaru")
                || lower.contains("terkini")
                || lower.contains("berita")
                || lower.contains("hari ini")
                || lower.contains("sekarang")
                || lower.contains("latest")
                || lower.contains("news")
                || lower.contains("current")
                || lower.contains("harga")
                || lower.contains("cuaca")
                || lower.contains("jadwal");
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

    private void finish(int startId) {
        stopForeground(true);
        stopSelf(startId);
    }

    private void updateNotification(long sessionId, String status) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(sessionId, status));
        }
        notifyStatusChanged(sessionId, status);
    }

    private Notification buildNotification(long sessionId, String status) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(EXTRA_SESSION_ID, sessionId);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, (int) sessionId, intent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Chatbot sedang memproses")
                .setContentText(status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "AI Requests",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Notifikasi saat Chatbot memproses permintaan AI");

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (chatDAO != null) {
            chatDAO.close();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
