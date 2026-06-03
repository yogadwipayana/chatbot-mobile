package com.example.chatbot;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity untuk antarmuka percakapan chat dengan AI.
 */
public class ChatActivity extends AppCompatActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 100;

    private long sessionId;
    private ChatDAO chatDAO;
    
    private RecyclerView rvMessages;
    private MessageAdapter adapter;
    private EditText etMessage;
    private ProgressBar progressBar;
    private View viewConnectionDot;
    private TextView tvConnectionStatus;
    private List<Message> messageList = new ArrayList<>();
    private String pendingImageIterationPrompt = "";
    private boolean aiResponseReceiverRegistered = false;
    private final BroadcastReceiver aiResponseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long responseSessionId = intent.getLongExtra(AiRequestService.EXTRA_SESSION_ID, -1);
            if (responseSessionId != sessionId) return;

            String action = intent.getAction();
            if (AiRequestService.ACTION_AI_STATUS_CHANGED.equals(action)) {
                showProcessingStatus(intent.getStringExtra(AiRequestService.EXTRA_STATUS));
            } else if (AiRequestService.ACTION_AI_RESPONSE_SAVED.equals(action)) {
                adapter.setThinking(false);
                progressBar.setVisibility(View.GONE);
                adapter.clearRetryMessage();
                adapter.clearReplyMessage();
                loadChatHistory();
            } else if (AiRequestService.ACTION_AI_REQUEST_FAILED.equals(action)) {
                adapter.setThinking(false);
                progressBar.setVisibility(View.GONE);
                adapter.clearReplyMessage();
                updateConnectionStatus(hasInternetConnection());
                String errorMessage = intent.getStringExtra(AiRequestService.EXTRA_ERROR_MESSAGE);
                if (errorMessage != null && !errorMessage.trim().isEmpty()) {
                    Toast.makeText(ChatActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                }
                long failedMessageId = intent.getLongExtra(AiRequestService.EXTRA_USER_MESSAGE_ID, -1);
                if (failedMessageId != -1) {
                    adapter.setRetryMessageId(failedMessageId);
                    scrollToBottom();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Ambil session_id dari Intent
        sessionId = getIntent().getLongExtra("session_id", -1);
        if (sessionId == -1) {
            finish();
            return;
        }

        chatDAO = new ChatDAO(this);
        requestNotificationPermissionIfNeeded();

        // Inisialisasi UI
        findViewById(R.id.btnBack).setOnClickListener(v -> onBackPressed());

        rvMessages = findViewById(R.id.rvMessages);
        etMessage = findViewById(R.id.etMessage);
        Button btnSend = findViewById(R.id.btnSend);
        progressBar = findViewById(R.id.progressBar);
        viewConnectionDot = findViewById(R.id.viewConnectionDot);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        updateConnectionStatus(hasInternetConnection());

        // Setup RecyclerView
        adapter = new MessageAdapter(messageList);
        adapter.setOnImageIterateListener(originalPrompt -> {
            pendingImageIterationPrompt = originalPrompt;
            etMessage.setText("Ubah gambar sebelumnya: ");
            etMessage.setSelection(etMessage.getText().length());
            etMessage.requestFocus();
        });
        adapter.setOnRetryMessageListener(this::retryMessage);
        adapter.setOnReplyMessageListener(this::replyToUnansweredMessages);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Mulai dari bawah
        rvMessages.setLayoutManager(layoutManager);
        rvMessages.setAdapter(adapter);

        // Load data awal
        loadChatHistory();

        // Tombol Kirim
        btnSend.setOnClickListener(v -> sendMessage());
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS
        );
    }

    /**
     * Memuat riwayat pesan dari database dan menampilkan di UI.
     */
    private void loadChatHistory() {
        new Thread(() -> {
            List<Message> messages = chatDAO.getMessagesBySession(sessionId);
            // Ambil judul sesi langsung by ID, tidak perlu load semua sesi
            Session session = chatDAO.getSessionById(sessionId);
            String title = (session != null) ? session.getTitle() : "Chat";

            runOnUiThread(() -> {
                ((android.widget.TextView) findViewById(R.id.tvChatTitle)).setText(title);
                messageList.clear();
                messageList.addAll(messages);
                adapter.notifyDataSetChanged();
                updateReplyActionForMessages();
                scrollToBottom();
            });
        }).start();
    }

    /**
     * Alur pengiriman pesan: simpan ke DB -> tampilkan -> panggil API -> simpan respon -> tampilkan.
     */
    private void sendMessage() {
        if (!hasInternetConnection()) {
            updateConnectionStatus(false);
            Toast.makeText(this, "Tidak ada koneksi internet. Aktifkan Wi-Fi atau data seluler.", Toast.LENGTH_LONG).show();
            return;
        }

        if (adapter.isThinking()) {
            Toast.makeText(this, "Tunggu jawaban AI selesai diproses.", Toast.LENGTH_SHORT).show();
            return;
        }

        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        String apiText = buildApiText(text);

        etMessage.setText("");

        new Thread(() -> {
            boolean isFirstMessage = chatDAO.getMessagesBySession(sessionId).isEmpty();
            chatDAO.insertMessage(sessionId, "user", text);
            String generatedTitle = isFirstMessage ? buildSessionTitle(text) : "";
            if (isFirstMessage) {
                chatDAO.updateSessionTitle(sessionId, generatedTitle);
            }

            List<Message> updatedMessages = chatDAO.getMessagesBySession(sessionId);
            if (updatedMessages.isEmpty()) return;
            Message lastUserMsg = updatedMessages.get(updatedMessages.size() - 1);

            runOnUiThread(() -> {
                if (isFirstMessage) {
                    ((android.widget.TextView) findViewById(R.id.tvChatTitle)).setText(generatedTitle);
                }
                adapter.addMessage(lastUserMsg);
                scrollToBottom();
                adapter.clearRetryMessage();
                adapter.clearReplyMessage();
                showProcessingStatus("Thinking");
                startAiRequestService(apiText, lastUserMsg.getId());
            });
        }).start();
    }

    private String buildSessionTitle(String text) {
        String title = text.trim().replaceAll("\\s+", " ");
        if (title.length() > 30) {
            return title.substring(0, 30);
        }
        return title;
    }

    private void retryMessage(Message message) {
        if (adapter.isThinking()) return;
        restartAiRequest(message.getContent(), message.getId());
    }

    private void replyToUnansweredMessages(Message message) {
        if (adapter.isThinking()) return;
        restartAiRequest(buildUnansweredUserPrompt(message), message.getId());
    }

    private void restartAiRequest(String apiText, long userMessageId) {
        adapter.clearRetryMessage();
        adapter.clearReplyMessage();
        showProcessingStatus("Mencoba ulang");
        startAiRequestService(apiText, userMessageId);
    }

    private void startAiRequestService(String apiText, long userMessageId) {
        updateConnectionStatus(hasInternetConnection());
        Intent intent = new Intent(this, AiRequestService.class);
        intent.putExtra(AiRequestService.EXTRA_SESSION_ID, sessionId);
        intent.putExtra(AiRequestService.EXTRA_API_TEXT, apiText);
        intent.putExtra(AiRequestService.EXTRA_USER_MESSAGE_ID, userMessageId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private String buildApiText(String text) {
        if (!text.toLowerCase().startsWith("ubah gambar sebelumnya") || pendingImageIterationPrompt.isEmpty()) {
            return text;
        }

        String changeRequest = text.replaceFirst("(?i)^ubah gambar sebelumnya\\s*:?\\s*", "").trim();
        String prompt = "Buat ulang gambar berdasarkan prompt awal berikut, pertahankan subjek dan komposisi utamanya. Prompt awal: "
                + pendingImageIterationPrompt
                + ". Perubahan yang diminta user: "
                + changeRequest;
        pendingImageIterationPrompt = "";
        return prompt;
    }

    private void scrollToBottom() {
        if (adapter.getItemCount() > 0) {
            rvMessages.smoothScrollToPosition(adapter.getItemCount() - 1);
        }
    }

    private void showProcessingStatus(String status) {
        progressBar.setVisibility(View.VISIBLE);
        adapter.clearReplyMessage();
        adapter.setThinkingStatus(formatProcessingStatus(status));
        if (!adapter.isThinking()) {
            adapter.setThinking(true);
        }
        scrollToBottom();
    }

    private String formatProcessingStatus(String status) {
        if (status == null || status.trim().isEmpty()) return "Memproses";
        return status.trim();
    }

    private void updateReplyActionForMessages() {
        if (adapter.isThinking()) return;

        long retryableErrorUserMessageId = findRetryableErrorUserMessageId();
        if (retryableErrorUserMessageId != -1) {
            adapter.clearReplyMessage();
            adapter.setRetryMessageId(retryableErrorUserMessageId);
            return;
        }

        long lastUnansweredUserMessageId = findLastUnansweredUserMessageId();
        if (lastUnansweredUserMessageId == -1) {
            adapter.clearReplyMessage();
        } else {
            adapter.setReplyMessageId(lastUnansweredUserMessageId);
        }
    }

    private long findLastUnansweredUserMessageId() {
        if (messageList.isEmpty()) return -1;

        Message lastMessage = messageList.get(messageList.size() - 1);
        if (!lastMessage.getRole().equalsIgnoreCase("user")) return -1;
        return lastMessage.getId();
    }

    private long findRetryableErrorUserMessageId() {
        if (messageList.size() < 2) return -1;

        Message lastMessage = messageList.get(messageList.size() - 1);
        if (!lastMessage.getRole().equalsIgnoreCase("bot")
                || !isRetryableBotError(lastMessage.getContent())) {
            return -1;
        }

        for (int i = messageList.size() - 2; i >= 0; i--) {
            Message message = messageList.get(i);
            if (message.getRole().equalsIgnoreCase("user")) return message.getId();
        }
        return -1;
    }

    private boolean isRetryableBotError(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return lower.contains("timeout")
                || lower.contains("terlalu lama")
                || lower.contains("tidak ada koneksi")
                || lower.contains("koneksi gagal")
                || lower.contains("gagal memproses")
                || lower.contains("coba lagi")
                || lower.contains("error api");
    }

    private String buildUnansweredUserPrompt(Message fallbackMessage) {
        StringBuilder promptBuilder = new StringBuilder();
        for (int i = messageList.size() - 1; i >= 0; i--) {
            Message message = messageList.get(i);
            if (!message.getRole().equalsIgnoreCase("user")) break;

            if (promptBuilder.length() == 0) {
                promptBuilder.insert(0, message.getContent());
            } else {
                promptBuilder.insert(0, message.getContent() + "\n");
            }
        }

        String prompt = promptBuilder.toString().trim();
        return prompt.isEmpty() ? fallbackMessage.getContent() : prompt;
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerAiResponseReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateConnectionStatus(hasInternetConnection());
        boolean aiServiceRunning = isAiServiceRunning();
        if (aiServiceRunning) {
            showProcessingStatus("Memproses");
        } else {
            adapter.setThinking(false);
            progressBar.setVisibility(View.GONE);
        }
        loadChatHistory();
    }

    private boolean hasInternetConnection() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            Network network = cm.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (SecurityException e) {
            return false;
        }
    }

    private void updateConnectionStatus(boolean online) {
        if (tvConnectionStatus != null) {
            tvConnectionStatus.setText(online ? "Online" : "Offline");
        }
        if (viewConnectionDot != null) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(ContextCompat.getColor(this, online ? R.color.online_green : R.color.offline_red));
            viewConnectionDot.setBackground(dot);
        }
    }

    private boolean isAiServiceRunning() {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info :
                am.getRunningServices(Integer.MAX_VALUE)) {
            if (AiRequestService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onStop() {
        unregisterAiResponseReceiver();
        super.onStop();
    }

    private void registerAiResponseReceiver() {
        if (aiResponseReceiverRegistered) return;

        IntentFilter filter = new IntentFilter();
        filter.addAction(AiRequestService.ACTION_AI_STATUS_CHANGED);
        filter.addAction(AiRequestService.ACTION_AI_RESPONSE_SAVED);
        filter.addAction(AiRequestService.ACTION_AI_REQUEST_FAILED);
        ContextCompat.registerReceiver(this, aiResponseReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        aiResponseReceiverRegistered = true;
    }

    private void unregisterAiResponseReceiver() {
        if (!aiResponseReceiverRegistered) return;

        unregisterReceiver(aiResponseReceiver);
        aiResponseReceiverRegistered = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        chatDAO.close();
    }
}
