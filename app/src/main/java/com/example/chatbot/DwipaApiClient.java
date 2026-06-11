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

public class DwipaApiClient {

    private static final String API_URL          = "https://ai.yogathedev.com/v1/chat/completions";
    private static final String IMAGE_API_URL    = "https://ai.yogathedev.com/v1/images/generations";
    private static final String SEARCH_API_URL   = "https://ai.yogathedev.com/v1/search";
    private static final String WEB_FETCH_API_URL = "https://ai.yogathedev.com/v1/web/fetch";

    public static final String IMAGE_URL_PREFIX    = "[[image_url]]";
    public static final String IMAGE_BASE64_PREFIX = "[[image_base64]]";
    public static final String IMAGE_FILE_PREFIX   = "[[image_file]]";

    public static final String TOOL_WEB_SEARCH        = "web_search";
    public static final String TOOL_WEB_FETCH         = "web_fetch";
    public static final String TOOL_IMAGE_GENERATIONS = "image_generations";

    private static final String API_KEY = "sk-d5d89a9e09252c66-erytkd-5b9e8493";

    // Context window limits applied in buildBaseMessages
    static final int CONTEXT_MESSAGE_LIMIT      = 10;
    static final int CONTEXT_MESSAGE_CHAR_LIMIT = 700;

    private final OkHttpClient client;
    private final Gson         gson;
    private final Handler      mainHandler;

    public DwipaApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .build();
        gson        = new Gson();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    // -------------------------------------------------------------------------
    // Callbacks
    // -------------------------------------------------------------------------

    public interface ApiCallback {
        void onSuccess(String content);
        void onFailure(String errorMessage);
    }

    /** Returned per tool_call entry from the model. */
    public static class ToolCall {
        public final String     id;
        public final String     name;
        public final JsonObject args;
        public final JsonObject assistantMessage; // full assistant message object for re-injection

        ToolCall(String id, String name, JsonObject args, JsonObject assistantMessage) {
            this.id               = id;
            this.name             = name;
            this.args             = args;
            this.assistantMessage = assistantMessage;
        }
    }

    public interface ToolCallCallback {
        /** Called when the model wants to invoke one or more tools. */
        void onToolCalls(List<ToolCall> toolCalls);
        void onFinalAnswer(String content);
        void onFailure(String errorMessage);
    }

    // -------------------------------------------------------------------------
    // Tool definitions
    // -------------------------------------------------------------------------

    private JsonArray buildToolDefinitions() {
        JsonArray tools = new JsonArray();
        tools.add(buildTool(
                TOOL_WEB_SEARCH,
                "Cari informasi terbaru di web. Gunakan untuk berita, harga, cuaca, jadwal, data real-time, atau topik yang membutuhkan informasi terkini.",
                buildParams(new String[][]{
                        {"query", "string", "Query pencarian yang jelas dan spesifik"}
                }, new String[]{"query"})
        ));
        tools.add(buildTool(
                TOOL_WEB_FETCH,
                "Ambil dan baca isi halaman web dari URL tertentu. Gunakan ketika user memberikan URL atau domain spesifik.",
                buildParams(new String[][]{
                        {"url", "string", "URL lengkap halaman yang ingin dibaca, termasuk https://"}
                }, new String[]{"url"})
        ));
        tools.add(buildTool(
                TOOL_IMAGE_GENERATIONS,
                "Buat gambar, ilustrasi, poster, logo, infografis, atau konten visual berdasarkan deskripsi user.",
                buildParams(new String[][]{
                        {"prompt", "string", "Deskripsi detail gambar dalam bahasa Inggris untuk hasil terbaik"}
                }, new String[]{"prompt"})
        ));
        return tools;
    }

    private JsonObject buildTool(String name, String description, JsonObject parameters) {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        fn.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", fn);
        return tool;
    }

    private JsonObject buildParams(String[][] props, String[] required) {
        JsonObject properties = new JsonObject();
        for (String[] p : props) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", p[1]);
            prop.addProperty("description", p[2]);
            properties.add(p[0], prop);
        }
        JsonArray req = new JsonArray();
        for (String r : required) req.add(r);
        JsonObject params = new JsonObject();
        params.addProperty("type", "object");
        params.add("properties", properties);
        params.add("required", req);
        return params;
    }

    // -------------------------------------------------------------------------
    // Chat with native tool calling — first turn
    // history: DB messages (trimmed to CONTEXT_MESSAGE_LIMIT)
    // -------------------------------------------------------------------------

    public void chatWithTools(List<Message> history, ToolCallCallback callback) {
        JsonArray messages = buildBaseMessages(history);
        postChatWithTools(messages, callback);
    }

    // -------------------------------------------------------------------------
    // Continue after one or more tool results — subsequent turns
    // inFlightMessages: the accumulated messages array from the previous call,
    //   already containing the assistant tool_call message(s) and tool results.
    // -------------------------------------------------------------------------

    public void continueWithMessages(JsonArray inFlightMessages, ToolCallCallback callback) {
        postChatWithTools(inFlightMessages, callback);
    }

    private void postChatWithTools(JsonArray messages, ToolCallCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("model", "gpt-5.5");
        body.add("messages", messages);
        body.add("tools", buildToolDefinitions());
        body.addProperty("tool_choice", "auto");
        body.addProperty("max_tokens", 4096);
        body.addProperty("temperature", 0.7);

        enqueuePost(API_URL, body, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    int code = response.code();
                    response.close();
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(code)));
                    return;
                }
                try {
                    JsonObject json   = gson.fromJson(response.body().string(), JsonObject.class);
                    JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                    JsonObject msg    = choice.getAsJsonObject("message");
                    String finishReason = choice.get("finish_reason").getAsString();

                    if ("tool_calls".equals(finishReason) && msg.has("tool_calls")) {
                        // Parse ALL tool_calls, not just index 0
                        JsonArray rawCalls = msg.getAsJsonArray("tool_calls");
                        List<ToolCall> toolCalls = new java.util.ArrayList<>();
                        for (JsonElement el : rawCalls) {
                            JsonObject tc   = el.getAsJsonObject();
                            String tcId     = tc.get("id").getAsString();
                            String tcName   = tc.getAsJsonObject("function").get("name").getAsString();
                            String argsStr  = tc.getAsJsonObject("function").get("arguments").getAsString();
                            JsonObject args;
                            try {
                                args = gson.fromJson(argsStr, JsonObject.class);
                            } catch (Exception e) {
                                args = new JsonObject(); // malformed args — use empty object
                            }
                            toolCalls.add(new ToolCall(tcId, tcName, args, msg));
                        }
                        mainHandler.post(() -> callback.onToolCalls(toolCalls));
                    } else {
                        String content = msg.has("content") && !msg.get("content").isJsonNull()
                                ? msg.get("content").getAsString() : "";
                        mainHandler.post(() -> callback.onFinalAnswer(content));
                    }
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onFailure("Gagal memproses jawaban AI. Coba lagi."));
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Build a fresh messages array from DB history (base turn)
    // -------------------------------------------------------------------------

    public JsonArray buildBaseMessages(List<Message> history) {
        JsonArray messages = new JsonArray();
        messages.add(buildSystemMessage());

        if (history == null) return messages;

        // Apply context window limit — take the most recent CONTEXT_MESSAGE_LIMIT messages
        int start = Math.max(0, history.size() - CONTEXT_MESSAGE_LIMIT);
        for (int i = start; i < history.size(); i++) {
            Message msg = history.get(i);
            addHistoryMessage(messages, msg);
        }
        return messages;
    }

    public JsonArray buildMessagesForRequest(List<Message> history, long userMsgId, String apiText) {
        JsonArray messages = new JsonArray();
        messages.add(buildSystemMessage());

        if (history == null || history.isEmpty()) {
            addUserMessage(messages, apiText);
            return messages;
        }

        int targetIndex = findMessageIndex(history, userMsgId);
        if (targetIndex == -1) targetIndex = history.size() - 1;

        String requestText = normalizeRequestText(apiText);
        if (requestText.isEmpty() && targetIndex >= 0 && targetIndex < history.size()) {
            requestText = normalizeRequestText(history.get(targetIndex).getContent());
        }

        int contextEnd = targetIndex;
        while (contextEnd > 0 && isUserMessage(history.get(contextEnd - 1))) {
            contextEnd--;
        }

        int start = Math.max(0, contextEnd - CONTEXT_MESSAGE_LIMIT);
        for (int i = start; i < contextEnd; i++) {
            addHistoryMessage(messages, history.get(i));
        }

        addUserMessage(messages, requestText);
        return messages;
    }

    private int findMessageIndex(List<Message> history, long messageId) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).getId() == messageId) return i;
        }
        return -1;
    }

    private void addHistoryMessage(JsonArray messages, Message msg) {
        if (msg.getContent() == null || msg.getContent().trim().isEmpty()) return;

        String role = "bot".equalsIgnoreCase(msg.getRole()) ? "assistant" : "user";
        String content = isImageContent(msg.getContent())
                ? "Gambar sebelumnya sudah berhasil dibuat dan ditampilkan ke user."
                : shorten(msg.getContent(), CONTEXT_MESSAGE_CHAR_LIMIT);
        addChatMessage(messages, role, content);
    }

    private void addUserMessage(JsonArray messages, String content) {
        String normalized = normalizeRequestText(content);
        if (normalized.isEmpty()) return;

        addChatMessage(messages, "user", shorten(normalized, CONTEXT_MESSAGE_CHAR_LIMIT));
    }

    private void addChatMessage(JsonArray messages, String role, String content) {
        if (content == null || content.trim().isEmpty()) return;

        int lastIndex = messages.size() - 1;
        if (lastIndex >= 0 && messages.get(lastIndex).isJsonObject()) {
            JsonObject last = messages.get(lastIndex).getAsJsonObject();
            if (last.has("role")
                    && role.equals(last.get("role").getAsString())
                    && !"system".equals(role)
                    && last.has("content")
                    && last.get("content").isJsonPrimitive()) {
                String merged = last.get("content").getAsString() + "\n\n" + content.trim();
                last.addProperty("content", shortenKeepingEnd(merged, CONTEXT_MESSAGE_CHAR_LIMIT));
                return;
            }
        }

        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content.trim());
        messages.add(m);
    }

    private boolean isUserMessage(Message message) {
        return message != null && "user".equalsIgnoreCase(message.getRole());
    }

    private String normalizeRequestText(String text) {
        return text == null ? "" : text.trim();
    }

    private JsonObject buildSystemMessage() {
        // Inject current date so model knows "today" for time-sensitive queries
        String today = new java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.forLanguageTag("id"))
                .format(new java.util.Date());

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                "Kamu adalah asisten AI berbahasa Indonesia. Hari ini adalah " + today + ".\n\n" +
                "FORMAT: Jawab dengan paragraf pendek 1-3 kalimat. Gunakan bullet • untuk daftar. " +
                "Mulai dengan jawaban inti. Hindari paragraf panjang dan tabel lebar kecuali diminta.\n\n" +
                "TOOLS: Kamu punya tiga tool yang dipanggil otomatis:\n" +
                "• web_search — gunakan untuk informasi terbaru, berita, harga, cuaca, jadwal, atau data real-time. " +
                "Jangan gunakan untuk pengetahuan umum yang sudah kamu tahu.\n" +
                "• web_fetch — gunakan ketika user memberi URL atau domain spesifik.\n" +
                "• image_generations — gunakan untuk membuat gambar, ilustrasi, poster, logo, atau infografis.\n\n" +
                "TOOL ERRORS: Jika tool gagal atau hasilnya kosong, tetap jawab berdasarkan pengetahuanmu " +
                "dan sebutkan bahwa data real-time tidak tersedia saat ini.\n\n" +
                "BAHASA: Selalu jawab dalam bahasa yang sama dengan bahasa user. " +
                "Jika user menulis Indonesia, jawab Indonesia. Jika Inggris, jawab Inggris.\n\n" +
                "Setelah mendapat hasil tool, tulis jawaban final yang rapi — jangan tampilkan JSON atau metadata mentah."
        );
        return system;
    }

    // -------------------------------------------------------------------------
    // Append tool exchange to an existing messages array (mutates and returns it)
    // assistantMsg: the assistant message object that contained tool_calls
    // toolResults: list of (toolCallId, toolName, result) triples
    // -------------------------------------------------------------------------

    public JsonArray appendToolExchange(
            JsonArray messages,
            JsonObject assistantMsg,
            List<String[]> toolResults  // each entry: [toolCallId, toolName, result]
    ) {
        // Clone to avoid mutating the caller's array
        JsonArray updated = messages.deepCopy();
        updated.add(assistantMsg);
        for (String[] tr : toolResults) {
            JsonObject toolMsg = new JsonObject();
            toolMsg.addProperty("role", "tool");
            toolMsg.addProperty("tool_call_id", tr[0]);
            toolMsg.addProperty("name", tr[1]);
            toolMsg.addProperty("content", tr[2]);
            updated.add(toolMsg);
        }
        return updated;
    }

    // -------------------------------------------------------------------------
    // Tool execution
    // -------------------------------------------------------------------------

    public void webSearch(String query, ApiCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("model", "tavily");
        body.addProperty("query", query);
        body.addProperty("search_type", "web");
        body.addProperty("max_results", 10);

        enqueuePost(SEARCH_API_URL, body, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    int code = response.code();
                    response.close();
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(code)));
                    return;
                }
                try {
                    String result = formatSearchResponse(response.body().string(), query);
                    mainHandler.post(() -> callback.onSuccess(result));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onFailure("Gagal memproses hasil pencarian. Coba lagi."));
                }
            }
        });
    }

    public void webFetch(String url, ApiCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("model", "tavily");
        body.addProperty("url", url);
        body.addProperty("format", "markdown");
        body.addProperty("max_characters", 6000);

        enqueuePost(WEB_FETCH_API_URL, body, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    int code = response.code();
                    response.close();
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(code)));
                    return;
                }
                try {
                    String result = formatFetchResponse(response.body().string());
                    mainHandler.post(() -> callback.onSuccess(result));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onFailure("Gagal memproses halaman web. Coba lagi."));
                }
            }
        });
    }

    public void generateImage(String prompt, ApiCallback callback) {
        JsonObject body = new JsonObject();
        body.addProperty("model", "cx/gpt-5.5-image");
        body.addProperty("prompt", prompt);
        body.addProperty("n", 1);
        body.addProperty("size", "auto");
        body.addProperty("quality", "auto");
        body.addProperty("background", "auto");
        body.addProperty("image_detail", "high");
        body.addProperty("output_format", "png");

        Request request = new Request.Builder()
                .url(IMAGE_API_URL)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .addHeader("Accept", "text/event-stream")
                .post(RequestBody.create(gson.toJson(body),
                        MediaType.get("application/json; charset=utf-8")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onFailure(getConnectionErrorMessage(e)));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                ResponseBody rb = response.body();
                if (!response.isSuccessful() || rb == null) {
                    int code = response.code();
                    response.close();
                    mainHandler.post(() -> callback.onFailure(getApiErrorMessage(code)));
                    return;
                }
                try (ResponseBody body2 = rb) {
                    String imageContent = readImageContent(body2);
                    mainHandler.post(() -> callback.onSuccess(imageContent));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onFailure("Gagal memproses gambar dari AI. Coba lagi."));
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Response formatters
    // -------------------------------------------------------------------------

    private String formatFetchResponse(String responseData) {
        JsonObject root = gson.fromJson(responseData, JsonObject.class);
        StringBuilder b = new StringBuilder();
        String title   = getStringValue(root, "title");
        String url     = getStringValue(root, "url");
        String content = getFetchContent(root);
        if (!title.isEmpty())   b.append(title).append("\n\n");
        if (!url.isEmpty())     b.append("Sumber: ").append(url).append("\n\n");
        b.append(content.isEmpty() ? "Konten halaman tidak tersedia." : shorten(content, 6000));
        return cleanReadableText(b.toString());
    }

    private String getFetchContent(JsonObject root) {
        for (String key : new String[]{"content", "markdown", "text"}) {
            String v = getStringValue(root, key);
            if (!v.isEmpty()) return v;
        }
        JsonObject nested = getObjectValue(root, "content");
        if (nested != null) {
            for (String key : new String[]{"text", "markdown", "content"}) {
                String v = getStringValue(nested, key);
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    private String formatSearchResponse(String responseData, String query) {
        JsonObject root    = gson.fromJson(responseData, JsonObject.class);
        StringBuilder b    = new StringBuilder("Hasil pencarian web:\n\n");
        boolean wantsNews  = isNewsQuery(query);

        if (!wantsNews && root.has("answer") && root.get("answer").isJsonPrimitive()) {
            String answer = cleanReadableText(root.get("answer").getAsString());
            if (!answer.isEmpty()) b.append(answer).append("\n\n");
        }

        if (!root.has("results") || !root.get("results").isJsonArray()) {
            return cleanReadableText(b.append(responseData).toString());
        }

        b.append(wantsNews ? "Artikel berita:\n" : "Sumber:\n");
        JsonArray results = root.getAsJsonArray("results");
        int shown = 0;
        for (int i = 0; i < results.size() && shown < 6; i++) {
            if (!results.get(i).isJsonObject()) continue;
            JsonObject item  = results.get(i).getAsJsonObject();
            String title     = cleanReadableText(getStringValue(item, "title"));
            String url       = cleanReadableText(getStringValue(item, "url"));
            String content   = cleanReadableText(getStringValue(item, "content"));
            if (content.isEmpty()) content = cleanReadableText(getStringValue(item, "snippet"));
            String date      = getStringValue(item, "published_date");
            if (date.isEmpty()) date = getStringValue(item, "publishedDate");

            if (title.isEmpty() && content.isEmpty() && url.isEmpty()) continue;
            if (wantsNews && shouldSkipAsNewsArticle(url, title)) continue;

            b.append("• ").append(title.isEmpty() ? "Sumber web" : title).append("\n");
            if (!url.isEmpty())     b.append("URL: ").append(url).append("\n");
            if (!date.isEmpty())    b.append("Tanggal: ").append(date).append("\n");
            if (!content.isEmpty()) b.append(shorten(content, 280)).append("\n");
            shown++;
        }

        if (shown == 0 && wantsNews) {
            b.append("Tidak ada artikel berita spesifik yang ditemukan.\n");
        }
        return cleanReadableText(b.toString());
    }

    private boolean isNewsQuery(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase();
        return lower.contains("berita") || lower.contains("news") || lower.contains("artikel")
                || lower.contains("terbaru") || lower.contains("terkini") || lower.contains("hari ini");
    }

    private boolean shouldSkipAsNewsArticle(String url, String title) {
        String lu = url == null ? "" : url.toLowerCase();
        String lt = title == null ? "" : title.toLowerCase();
        if (lu.contains("youtube.com/") || lu.contains("youtu.be/") || lu.contains("instagram.com/")
                || lu.contains("facebook.com/") || lu.contains("tiktok.com/")
                || lu.contains("x.com/") || lu.contains("twitter.com/")
                || lu.contains(".ac.id/") || lu.contains(".edu/")) return true;
        String path = lu.replaceFirst("^https?://[^/]+", "");
        if (path.isEmpty() || "/".equals(path)) return true;
        if (path.contains("/tag/") || path.contains("/tags/") || path.contains("/topic/")
                || path.contains("/topics/") || path.contains("/kategori/") || path.contains("/category/")
                || path.contains("/kanal/") || path.contains("/rubrik/") || path.contains("/search")
                || path.contains("/indeks") || path.contains("/latest")) return true;
        return lt.contains("tag ") || lt.contains("topik ") || lt.contains("terkini dan terbaru hari ini");
    }

    // -------------------------------------------------------------------------
    // Image SSE reader
    // -------------------------------------------------------------------------

    private String readImageContent(ResponseBody responseBody) throws IOException {
        BufferedSource source    = responseBody.source();
        StringBuilder collected  = new StringBuilder();
        boolean sawEventStream   = false;
        String currentEvent      = "";
        String lastImage         = null;

        while (true) {
            String line = source.readUtf8Line();
            if (line == null) break;
            collected.append(line).append('\n');
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("event:")) {
                sawEventStream = true;
                currentEvent = trimmed.substring(6).trim();
                continue;
            }
            if (trimmed.startsWith("data:")) {
                sawEventStream = true;
                String data = trimmed.substring(5).trim();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                String image = extractImageFromJson(data);
                if (image != null) {
                    if ("done".equalsIgnoreCase(currentEvent)) return image;
                    lastImage = image;
                }
                continue;
            }
            if (!sawEventStream) {
                String image = extractImageFromJson(trimmed);
                if (image != null) return image;
            }
        }

        String direct = extractImageFromJson(collected.toString());
        if (direct != null) return direct;
        if (lastImage != null) return lastImage;
        throw new IllegalStateException("Image not found in response");
    }

    private String extractImageFromJson(String json) {
        try { return findImageValue(gson.fromJson(json, JsonObject.class)); }
        catch (Exception e) { return null; }
    }

    private String findImageValue(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            for (String key : new String[]{"url", "image_url"}) {
                if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                    String v = obj.get(key).getAsString();
                    if (v.trim().startsWith("http")) return IMAGE_URL_PREFIX + v;
                }
            }
            for (String key : new String[]{"b64_json", "base64", "image", "data"}) {
                if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                    String v = obj.get(key).getAsString();
                    if (looksLikeImageBase64(v)) return IMAGE_BASE64_PREFIX + v;
                }
            }
            for (String key : obj.keySet()) {
                String nested = findImageValue(obj.get(key));
                if (nested != null) return nested;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                String nested = findImageValue(item);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private boolean looksLikeImageBase64(String value) {
        if (value == null) return false;
        String t = value.trim();
        if (t.startsWith("data:image/")) return true;
        if (t.startsWith("http")) return false;
        if (t.length() < 256) return false;
        return t.matches("^[A-Za-z0-9+/=\\r\\n]+$");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void enqueuePost(String url, JsonObject body, Callback callback) {
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + API_KEY)
                .post(RequestBody.create(gson.toJson(body),
                        MediaType.get("application/json; charset=utf-8")))
                .build();
        client.newCall(request).enqueue(callback);
    }

    public boolean isImageContent(String content) {
        if (content == null) return false;
        return content.startsWith(IMAGE_BASE64_PREFIX)
                || content.startsWith(IMAGE_FILE_PREFIX)
                || content.startsWith(IMAGE_URL_PREFIX);
    }

    private String getStringValue(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString().trim();
    }

    private JsonObject getObjectValue(JsonObject object, String key) {
        if (!object.has(key)) return null;
        JsonElement el = object.get(key);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
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

    private String shortenKeepingEnd(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return "..." + text.substring(text.length() - maxLength).trim();
    }

    private String getConnectionErrorMessage(IOException e) {
        if (e instanceof java.net.SocketTimeoutException) return "Koneksi timeout. Coba lagi.";
        if (e instanceof java.net.UnknownHostException)   return "Tidak ada koneksi internet.";
        return "Koneksi gagal: " + e.getMessage();
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
}
