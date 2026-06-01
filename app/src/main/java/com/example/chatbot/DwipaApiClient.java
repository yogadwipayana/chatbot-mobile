package com.example.chatbot;

import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Client untuk berinteraksi dengan API AI dwipa.my.id (OpenAI-compatible).
 */
public class DwipaApiClient {

    private static final String API_URL = "https://ai.dwipa.my.id/v1/chat/completions";
    private static final String IMAGE_API_URL = "https://ai.dwipa.my.id/v1/images/generations";
    private static final String SEARCH_API_URL = "https://ai.dwipa.my.id/v1/search";
    private static final String WEB_FETCH_API_URL = "https://ai.dwipa.my.id/v1/web/fetch";
    public static final String IMAGE_URL_PREFIX = "[[image_url]]";
    public static final String IMAGE_BASE64_PREFIX = "[[image_base64]]";
    public static final String IMAGE_FILE_PREFIX = "[[image_file]]";
    // TODO: Ganti dengan API Key kamu dari ai.dwipa.my.id
    private static final String API_KEY = "sk-6bf71243b4b07d49-x4280s-212090a5";

    private final OkHttpClient client;
    private final Gson gson;
    private final Handler mainHandler;

    public DwipaApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .build();
        gson = new Gson();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public interface ApiCallback {
        void onSuccess(String content);
        void onFailure(String errorMessage);
    }

    public interface IntentCallback {
        void onSuccess(String intent);
        void onFailure(String errorMessage);
    }

    public void detectIntent(String userMessage, IntentCallback callback) {
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "gpt-5.5");

        JsonArray messagesArray = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Kamu sedang melakukan thinking untuk memilih tool terbaik dari pesan user. Jawab hanya dengan satu nama tool: image_generations jika user ingin membuat/menghasilkan gambar, ilustrasi, poster, logo, infografis, desain visual, atau image; web_fetch jika user memberi URL atau domain spesifik, termasuk domain tanpa https:// seperti github.com/user, dan meminta membuka, cek, membaca, mengambil isi, merangkum, atau menganalisis halaman tersebut; web_search jika user meminta informasi terbaru, berita terbaru, cari di web/internet, browsing, sumber/link, data terkini, harga/cuaca/jadwal/status real-time tanpa URL spesifik; chat untuk percakapan biasa, penjelasan umum, atau jika user bertanya apa kemampuanmu/tool apa yang tersedia. Jangan beri penjelasan, jangan markdown, jangan panggil lebih dari satu tool.");
        messagesArray.add(systemMessage);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messagesArray.add(userMsg);

        jsonBody.add("messages", messagesArray);
        jsonBody.addProperty("max_tokens", 5);
        jsonBody.addProperty("temperature", 0);

        RequestBody body = RequestBody.create(
                gson.toJson(jsonBody),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JsonObject jsonResponse = gson.fromJson(responseData, JsonObject.class);

                        String answer = jsonResponse.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message")
                                .get("content").getAsString()
                                .trim()
                                .toUpperCase();

                        String tool;
                        if (answer.startsWith("IMAGE_GENERATIONS")) {
                            tool = "image_generations";
                        } else if (answer.startsWith("WEB_FETCH")) {
                            tool = "web_fetch";
                        } else if (answer.startsWith("WEB_SEARCH")) {
                            tool = "web_search";
                        } else {
                            tool = "chat";
                        }
                        mainHandler.post(() -> callback.onSuccess(tool));
                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onFailure("Gagal memahami permintaan. Coba lagi."));
                    }
                } else {
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(response.code())));
                }
            }
        });
    }

    public void generateImage(String prompt, ApiCallback callback) {
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "cx/gpt-5.4-image");
        jsonBody.addProperty("prompt", prompt);
        jsonBody.addProperty("n", 1);
        jsonBody.addProperty("size", "auto");
        jsonBody.addProperty("quality", "auto");
        jsonBody.addProperty("background", "auto");
        jsonBody.addProperty("image_detail", "high");
        jsonBody.addProperty("output_format", "png");

        RequestBody body = RequestBody.create(
                gson.toJson(jsonBody),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(IMAGE_API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    int code = response.code();
                    response.close();
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(code)));
                    return;
                }

                try (ResponseBody body = responseBody) {
                    String imageContent = readImageContent(body);
                    mainHandler.post(() -> callback.onSuccess(imageContent));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onFailure("Gagal memproses gambar dari AI. Coba lagi."));
                }
            }
        });
    }

    /**
     * Mengirim riwayat pesan ke API untuk mendapatkan respon AI yang memahami konteks.
     */
    public void sendMessage(List<Message> messages, ApiCallback callback) {
        // Membangun body JSON request
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "gpt-5.5");

        JsonArray messagesArray = new JsonArray();

        // 1. Tambahkan System Message di awal (instruksi perilaku bot)
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Kamu adalah asisten AI berbahasa Indonesia. Jawab dengan gaya yang rapi, natural, dan nyaman dibaca di layar HP. Gunakan paragraf pendek 1-3 kalimat. Jika menjelaskan beberapa poin, gunakan bullet list sederhana dengan tanda •. Mulai dengan jawaban inti, lalu detail seperlunya. Hindari paragraf panjang, tabel lebar, dan URL mentah yang panjang kecuali user memintanya. Jika memakai sumber, tulis nama sumber secara singkat. Untuk topik teknis, beri langkah praktis yang jelas. Untuk topik umum, jawab ringkas, sopan, dan mudah dipahami. Kamu punya tool yang bisa dipakai otomatis setelah proses thinking: image_generations untuk membuat gambar/ilustrasi/poster/logo/infografis, web_search untuk mencari informasi terbaru di web, dan web_fetch untuk membaca atau merangkum isi URL tertentu. Jika user bertanya 'apa yang kamu bisa', 'fitur kamu apa', 'tool apa saja', atau pertanyaan serupa, jawab dengan daftar kemampuan termasuk chat, image_generations, web_search, dan web_fetch.");
        messagesArray.add(systemMessage);

        // 2. Loop riwayat pesan dan masukkan ke array
        for (Message msg : messages) {
            JsonObject msgObj = new JsonObject();
            // Konversi role 'bot' menjadi 'assistant' sesuai standar OpenAI API
            String role = msg.getRole().equalsIgnoreCase("bot") ? "assistant" : "user";
            msgObj.addProperty("role", role);
            msgObj.addProperty("content", msg.getContent());
            messagesArray.add(msgObj);
        }

        jsonBody.add("messages", messagesArray);
        jsonBody.addProperty("max_tokens", 4096);
        jsonBody.addProperty("temperature", 0.7);

        RequestBody body = RequestBody.create(
                gson.toJson(jsonBody),
                MediaType.get("application/json; charset=utf-8")
        );

        // Membangun request HTTP
        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        // Menjalankan request di background thread secara asinkron
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JsonObject jsonResponse = gson.fromJson(responseData, JsonObject.class);

                        String botReply = jsonResponse.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message")
                                .get("content").getAsString();

                        mainHandler.post(() -> callback.onSuccess(botReply));
                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onFailure("Gagal memproses jawaban AI. Coba lagi."));
                    }
                } else {
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(response.code())));
                }
            }
        });
    }

    public void webSearch(String query, ApiCallback callback) {
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "tavily");
        jsonBody.addProperty("query", query);
        jsonBody.addProperty("search_type", "web");
        jsonBody.addProperty("max_results", 5);

        RequestBody body = RequestBody.create(
                gson.toJson(jsonBody),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(SEARCH_API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        String formatted = formatSearchResponse(responseData);
                        mainHandler.post(() -> callback.onSuccess(formatted));
                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onFailure("Gagal memproses hasil pencarian. Coba lagi."));
                    }
                } else {
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(response.code())));
                }
            }
        });
    }

    public void answerWithSearchResults(String userRequest, String searchResults, ApiCallback callback) {
        String safeUserRequest = userRequest == null ? "" : userRequest.trim();
        String safeSearchResults = searchResults == null ? "" : searchResults.trim();

        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "gpt-5.5");

        JsonArray messagesArray = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Kamu adalah asisten AI berbahasa Indonesia. Kamu menerima hasil tool web_search sebagai konteks internal. Buat jawaban final yang rapi, natural, dan nyaman dibaca di layar HP. Jangan tampilkan JSON, metadata mentah, atau teks internal tool. Mulai dengan jawaban inti, lalu detail seperlunya. Gunakan paragraf pendek dan bullet sederhana jika membantu. Jika hasil pencarian punya sumber, sebutkan nama sumber secara singkat. Jika data terlihat tidak cukup, jelaskan keterbatasannya singkat.");
        messagesArray.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content",
                "Permintaan user:\n" + safeUserRequest
                        + "\n\nHasil web_search:\n" + shorten(safeSearchResults, 6000)
                        + "\n\nTulis jawaban final untuk user. Jangan menyalin output tool mentah.");
        messagesArray.add(userMessage);

        jsonBody.add("messages", messagesArray);
        jsonBody.addProperty("max_tokens", 2048);
        jsonBody.addProperty("temperature", 0.4);

        RequestBody body = RequestBody.create(
                gson.toJson(jsonBody),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JsonObject jsonResponse = gson.fromJson(responseData, JsonObject.class);

                        String botReply = jsonResponse.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message")
                                .get("content").getAsString();

                        mainHandler.post(() -> callback.onSuccess(botReply));
                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onFailure("Gagal merapikan hasil pencarian. Coba lagi."));
                    }
                } else {
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(response.code())));
                }
            }
        });
    }

    public void webFetch(String url, ApiCallback callback) {
        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "tavily");
        jsonBody.addProperty("url", url);
        jsonBody.addProperty("format", "markdown");
        jsonBody.addProperty("max_characters", 6000);

        RequestBody body = RequestBody.create(
                gson.toJson(jsonBody),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(WEB_FETCH_API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        String formatted = formatFetchResponse(responseData);
                        mainHandler.post(() -> callback.onSuccess(formatted));
                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onFailure("Gagal memproses halaman web. Coba lagi."));
                    }
                } else {
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(response.code())));
                }
            }
        });
    }

    public void answerWithFetchedPage(String userRequest, String url, String fetchedContent, ApiCallback callback) {
        String safeUserRequest = userRequest == null ? "" : userRequest.trim();
        String safeUrl = url == null ? "" : url.trim();
        String safeFetchedContent = fetchedContent == null ? "" : fetchedContent.trim();

        JsonObject jsonBody = new JsonObject();
        jsonBody.addProperty("model", "gpt-5.5");

        JsonArray messagesArray = new JsonArray();

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", "Kamu adalah asisten AI berbahasa Indonesia. Kamu menerima hasil tool web_fetch sebagai konteks internal. Buat jawaban final yang rapi, natural, dan nyaman dibaca di layar HP. Jangan tampilkan JSON, metadata mentah, atau teks seperti provider/content/format. Gunakan paragraf pendek dan bullet sederhana jika membantu. Jawab sesuai permintaan user berdasarkan konten halaman. Jika konten tidak cukup, jelaskan keterbatasannya singkat. Jika memakai sumber, sebutkan nama situs atau URL singkat.");
        messagesArray.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content",
                "Permintaan user:\n" + safeUserRequest
                        + "\n\nURL sumber:\n" + safeUrl
                        + "\n\nHasil web_fetch:\n" + shorten(safeFetchedContent, 6000)
                        + "\n\nTulis jawaban final untuk user. Jangan menyalin output tool mentah.");
        messagesArray.add(userMessage);

        jsonBody.add("messages", messagesArray);
        jsonBody.addProperty("max_tokens", 2048);
        jsonBody.addProperty("temperature", 0.4);

        RequestBody body = RequestBody.create(
                gson.toJson(jsonBody),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JsonObject jsonResponse = gson.fromJson(responseData, JsonObject.class);

                        String botReply = jsonResponse.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message")
                                .get("content").getAsString();

                        mainHandler.post(() -> callback.onSuccess(botReply));
                    } catch (Exception e) {
                        mainHandler.post(() -> callback.onFailure("Gagal merapikan hasil halaman web. Coba lagi."));
                    }
                } else {
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(response.code())));
                }
            }
        });
    }

    private String formatFetchResponse(String responseData) {
        JsonObject root = gson.fromJson(responseData, JsonObject.class);
        StringBuilder builder = new StringBuilder("Isi halaman web:\n\n");

        String title = getStringValue(root, "title");
        String url = getStringValue(root, "url");
        String content = getFetchContent(root);

        if (!title.isEmpty()) builder.append(cleanReadableText(title)).append("\n\n");
        if (!url.isEmpty()) builder.append("Sumber: ").append(url).append("\n\n");
        if (!content.isEmpty()) return cleanReadableText(builder.append(shorten(content, 6000)).toString());
        return cleanReadableText(builder.append("Konten halaman tidak tersedia dari tool web_fetch.").toString());
    }

    private String getFetchContent(JsonObject root) {
        String content = getStringValue(root, "content");
        if (!content.isEmpty()) return content;

        content = getStringValue(root, "markdown");
        if (!content.isEmpty()) return content;

        content = getStringValue(root, "text");
        if (!content.isEmpty()) return content;

        JsonObject contentObject = getObjectValue(root, "content");
        if (contentObject != null) {
            content = getStringValue(contentObject, "text");
            if (!content.isEmpty()) return content;

            content = getStringValue(contentObject, "markdown");
            if (!content.isEmpty()) return content;

            content = getStringValue(contentObject, "content");
            if (!content.isEmpty()) return content;
        }

        return "";
    }

    private String formatSearchResponse(String responseData) {
        JsonObject root = gson.fromJson(responseData, JsonObject.class);
        StringBuilder builder = new StringBuilder("Hasil pencarian web:\n\n");

        if (root.has("answer") && root.get("answer").isJsonPrimitive()) {
            String answer = cleanReadableText(root.get("answer").getAsString());
            if (!answer.isEmpty()) {
                builder.append(answer).append("\n\n");
            }
        }

        if (!root.has("results") || !root.get("results").isJsonArray()) {
            return cleanReadableText(builder.append(responseData).toString());
        }

        builder.append("Sumber ringkas:\n");
        JsonArray results = root.getAsJsonArray("results");
        int shown = 0;
        for (int i = 0; i < results.size() && shown < 3; i++) {
            if (!results.get(i).isJsonObject()) continue;
            JsonObject item = results.get(i).getAsJsonObject();
            String title = cleanReadableText(getStringValue(item, "title"));
            String url = cleanReadableText(getStringValue(item, "url"));
            String content = cleanReadableText(getStringValue(item, "content"));
            if (content.isEmpty()) content = cleanReadableText(getStringValue(item, "snippet"));
            if (title.isEmpty() && content.isEmpty() && url.isEmpty()) continue;

            builder.append("• ").append(title.isEmpty() ? "Sumber web" : title).append("\n");
            if (!url.isEmpty()) builder.append("URL: ").append(url).append("\n");
            if (!content.isEmpty()) builder.append(shorten(content, 140)).append("\n");
            shown++;
        }

        return cleanReadableText(builder.toString());
    }

    private String getStringValue(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString().trim();
    }

    private JsonObject getObjectValue(JsonObject object, String key) {
        if (!object.has(key)) return null;
        JsonElement element = object.get(key);
        if (!element.isJsonObject()) return null;
        return element.getAsJsonObject();
    }

    private String cleanReadableText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String shorten(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength).trim() + "...";
    }

    private String getConnectionErrorMessage(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return "Koneksi timeout. Coba lagi.";
        } else if (e instanceof java.net.UnknownHostException) {
            return "Tidak ada koneksi internet.";
        } else {
            return "Koneksi gagal: " + e.getMessage();
        }
    }

    private String getApiErrorMessage(int code) {
        switch (code) {
            case 401: return "API Key tidak valid.";
            case 429: return "Terlalu banyak request. Tunggu sebentar.";
            case 500: return "Server AI sedang bermasalah.";
            case 503: return "Server AI tidak tersedia.";
            default:  return "Error API: " + code;
        }
    }

    private String extractImageContent(String responseData) {
        String direct = extractImageFromJson(responseData);
        if (direct != null) return direct;

        String[] lines = responseData.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("data:")) continue;

            String data = trimmed.substring(5).trim();
            if (data.isEmpty() || data.equals("[DONE]")) continue;

            String image = extractImageFromJson(data);
            if (image != null) return image;
        }

        throw new IllegalStateException("Image URL not found");
    }

    private String readImageContent(ResponseBody responseBody) throws IOException {
        BufferedSource source = responseBody.source();
        StringBuilder collected = new StringBuilder();
        boolean sawEventStreamLine = false;

        while (true) {
            String line = source.readUtf8Line();
            if (line == null) break;

            collected.append(line).append('\n');
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("data:")) {
                sawEventStreamLine = true;
                String data = trimmed.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;

                String image = extractImageFromJson(data);
                if (image != null) return image;
                continue;
            }

            if (!sawEventStreamLine) {
                String image = extractImageFromJson(trimmed);
                if (image != null) return image;
            }
        }

        return extractImageContent(collected.toString());
    }

    private String extractImageFromJson(String json) {
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            return findImageValue(root);
        } catch (Exception e) {
            return null;
        }
    }

    private String findImageValue(com.google.gson.JsonElement element) {
        if (element == null || element.isJsonNull()) return null;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String[] urlKeys = {"url", "image_url"};
            for (String key : urlKeys) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    String value = object.get(key).getAsString();
                    if (value.trim().startsWith("http")) return IMAGE_URL_PREFIX + value;
                }
            }

            String[] base64Keys = {"b64_json", "base64", "image", "data"};
            for (String key : base64Keys) {
                if (object.has(key) && object.get(key).isJsonPrimitive()) {
                    String value = object.get(key).getAsString();
                    if (looksLikeImageBase64(value)) {
                        return IMAGE_BASE64_PREFIX + value;
                    }
                }
            }

            for (String key : object.keySet()) {
                String nested = findImageValue(object.get(key));
                if (nested != null) return nested;
            }
        } else if (element.isJsonArray()) {
            for (com.google.gson.JsonElement item : element.getAsJsonArray()) {
                String nested = findImageValue(item);
                if (nested != null) return nested;
            }
        }

        return null;
    }

    private boolean looksLikeImageBase64(String value) {
        if (value == null) return false;

        String trimmed = value.trim();
        if (trimmed.startsWith("data:image/")) return true;
        if (trimmed.startsWith("http")) return false;
        if (trimmed.length() < 256) return false;

        return trimmed.matches("^[A-Za-z0-9+/=\\r\\n]+$");
    }
}
