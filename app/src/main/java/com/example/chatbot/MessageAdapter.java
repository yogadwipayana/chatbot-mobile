package com.example.chatbot;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.util.Linkify;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.io.File;
import java.io.OutputStream;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_BOT = 2;
    private static final int VIEW_TYPE_THINKING = 3;
    private static final OkHttpClient IMAGE_CLIENT = new OkHttpClient();

    private List<Message> messageList;
    private boolean isThinking = false;
    private String thinkingStatus = "Thinking";
    private long retryMessageId = -1;
    private long replyMessageId = -1;
    private OnImageIterateListener imageIterateListener;
    private OnRetryMessageListener retryMessageListener;
    private OnReplyMessageListener replyMessageListener;

    public interface OnImageIterateListener {
        void onImageIterate(String originalPrompt);
    }

    public interface OnRetryMessageListener {
        void onRetryMessage(Message message);
    }

    public interface OnReplyMessageListener {
        void onReplyMessage(Message message);
    }

    public MessageAdapter(List<Message> messageList) {
        this.messageList = messageList;
    }

    public void setOnImageIterateListener(OnImageIterateListener listener) {
        this.imageIterateListener = listener;
    }

    public void setOnRetryMessageListener(OnRetryMessageListener listener) {
        this.retryMessageListener = listener;
    }

    public void setOnReplyMessageListener(OnReplyMessageListener listener) {
        this.replyMessageListener = listener;
    }

    public void setRetryMessageId(long messageId) {
        long previousRetryMessageId = retryMessageId;
        retryMessageId = messageId;

        notifyMessageChanged(previousRetryMessageId);
        notifyMessageChanged(retryMessageId);
    }

    public void clearRetryMessage() {
        setRetryMessageId(-1);
    }

    public void setReplyMessageId(long messageId) {
        long previousReplyMessageId = replyMessageId;
        replyMessageId = messageId;

        notifyMessageChanged(previousReplyMessageId);
        notifyMessageChanged(replyMessageId);
    }

    public void clearReplyMessage() {
        setReplyMessageId(-1);
    }

    public void setThinkingStatus(String status) {
        thinkingStatus = status;
        if (isThinking) notifyItemChanged(getItemCount() - 1);
    }

    public void setThinking(boolean thinking) {
        if (this.isThinking == thinking) return;
        int position = getItemCount();
        this.isThinking = thinking;
        if (thinking) {
            notifyItemInserted(position);
        } else {
            notifyItemRemoved(position - 1);
        }
    }

    public boolean isThinking() {
        return isThinking;
    }

    @Override
    public int getItemCount() {
        return messageList.size() + (isThinking ? 1 : 0);
    }

    @Override
    public int getItemViewType(int position) {
        if (isThinking && position == getItemCount() - 1) {
            return VIEW_TYPE_THINKING;
        }
        Message message = messageList.get(position);
        if (message.getRole().equalsIgnoreCase("user")) {
            return VIEW_TYPE_USER;
        } else {
            return VIEW_TYPE_BOT;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_USER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_user, parent, false);
            return new UserMessageViewHolder(view);
        } else if (viewType == VIEW_TYPE_THINKING) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_thinking, parent, false);
            return new ThinkingViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message_bot, parent, false);
            return new BotMessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof ThinkingViewHolder) {
            ((ThinkingViewHolder) holder).tvStatus.setText(thinkingStatus);
            return;
        }
        Message message = messageList.get(position);
        if (holder instanceof UserMessageViewHolder) {
            UserMessageViewHolder userHolder = (UserMessageViewHolder) holder;
            userHolder.tvMessage.setText(message.getContent());
            userHolder.tvMessage.setTextIsSelectable(true);
            userHolder.tvTime.setVisibility(View.GONE);
            boolean showRetry = message.getId() == retryMessageId;
            boolean showReply = !showRetry && message.getId() == replyMessageId;
            userHolder.btnRetry.setVisibility(showRetry ? View.VISIBLE : View.GONE);
            userHolder.btnReply.setVisibility(showReply ? View.VISIBLE : View.GONE);
            userHolder.btnRetry.setOnClickListener(v -> {
                if (retryMessageListener != null) retryMessageListener.onRetryMessage(message);
            });
            userHolder.btnReply.setOnClickListener(v -> {
                if (replyMessageListener != null) replyMessageListener.onReplyMessage(message);
            });
        } else if (holder instanceof BotMessageViewHolder) {
            BotMessageViewHolder botHolder = (BotMessageViewHolder) holder;
            botHolder.tvTime.setVisibility(View.GONE);
            renderBotContent(botHolder.layoutContent, message.getContent(), findPreviousUserPrompt(position));
        }
    }

    public void addMessage(Message message) {
        messageList.add(message);
        notifyItemInserted(messageList.size() - 1);
    }

    public void setSessions(List<Message> messages) {
        this.messageList = messages;
        notifyDataSetChanged();
    }

    private void notifyMessageChanged(long messageId) {
        if (messageId == -1) return;

        int position = findMessagePositionById(messageId);
        if (position >= 0) notifyItemChanged(position);
    }

    private int findMessagePositionById(long messageId) {
        for (int i = 0; i < messageList.size(); i++) {
            if (messageList.get(i).getId() == messageId) return i;
        }
        return -1;
    }

    private String findPreviousUserPrompt(int position) {
        for (int i = position - 1; i >= 0; i--) {
            Message message = messageList.get(i);
            if (message.getRole().equalsIgnoreCase("user")) {
                return message.getContent();
            }
        }
        return "";
    }

    private void renderBotContent(LinearLayout container, String content, String originalPrompt) {
        container.removeAllViews();
        if (content.startsWith(DwipaApiClient.IMAGE_URL_PREFIX)) {
            addImageFromUrl(container, content.substring(DwipaApiClient.IMAGE_URL_PREFIX.length()), originalPrompt);
            return;
        } else if (content.startsWith(DwipaApiClient.IMAGE_BASE64_PREFIX)) {
            addImageFromBase64(container, content.substring(DwipaApiClient.IMAGE_BASE64_PREFIX.length()), originalPrompt);
            return;
        } else if (content.startsWith(DwipaApiClient.IMAGE_FILE_PREFIX)) {
            addImageFromFile(container, content.substring(DwipaApiClient.IMAGE_FILE_PREFIX.length()), originalPrompt);
            return;
        }

        Pattern codeBlockPattern = Pattern.compile("(```|''')(?:\\w+)?\\s*\\n([\\s\\S]*?)\\1", Pattern.MULTILINE);
        Matcher matcher = codeBlockPattern.matcher(content);
        int lastEnd = 0;

        while (matcher.find()) {
            addNormalText(container, content.substring(lastEnd, matcher.start()));
            addCodeBlock(container, matcher.group(2).trim());
            lastEnd = matcher.end();
        }

        addNormalText(container, content.substring(lastEnd));
    }

    private void addNormalText(LinearLayout container, String text) {
        if (text == null) return;

        String normalized = text
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim();
        if (normalized.isEmpty()) return;

        String[] blocks = normalized.split("\\n\\s*\\n");
        for (String block : blocks) {
            String cleaned = formatMarkdownText(block);
            if (cleaned.isEmpty()) continue;
            addTextBlock(container, cleaned);
        }
    }

    private void addTextBlock(LinearLayout container, String text) {
        TextView textView = new TextView(container.getContext());
        textView.setText(text);
        textView.setTextColor(Color.parseColor("#1A1C2E"));
        textView.setTextSize(isHeading(text) ? 16 : 15);
        textView.setTypeface(Typeface.DEFAULT, isHeading(text) ? Typeface.BOLD : Typeface.NORMAL);
        textView.setLineSpacing(dp(container, 3), 1.0f);
        textView.setPadding(0, 0, 0, dp(container, isListItem(text) ? 8 : 10));
        textView.setTextIsSelectable(true);
        textView.setAutoLinkMask(Linkify.WEB_URLS);
        textView.setLinksClickable(true);
        textView.setLinkTextColor(Color.parseColor("#5C6BC0"));
        Linkify.addLinks(textView, Linkify.WEB_URLS);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        container.addView(textView);
    }

    private void addImageFromUrl(LinearLayout container, String imageUrl, String originalPrompt) {
        ImageView imageView = createImageView(container);
        container.addView(imageView);
        addImageActions(container, imageView, originalPrompt);

        new Thread(() -> {
            Request request = new Request.Builder().url(imageUrl.trim()).build();
            try (Response response = IMAGE_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IOException("Image load failed");
                Bitmap bitmap = BitmapFactory.decodeStream(response.body().byteStream());
                if (bitmap == null) throw new IOException("Invalid image");
                imageView.post(() -> {
                    imageView.setImageBitmap(bitmap);
                    imageView.setTag(bitmap);
                });
            } catch (Exception e) {
                imageView.post(() -> showImageError(container, imageView));
            }
        }).start();
    }

    private void addImageFromBase64(LinearLayout container, String base64Image, String originalPrompt) {
        ImageView imageView = createImageView(container);
        container.addView(imageView);
        addImageActions(container, imageView, originalPrompt);

        try {
            String data = base64Image.trim();
            int commaIndex = data.indexOf(',');
            if (commaIndex >= 0) data = data.substring(commaIndex + 1);
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new IllegalArgumentException("Invalid image");
            imageView.setImageBitmap(bitmap);
            imageView.setTag(bitmap);
        } catch (Exception e) {
            showImageError(container, imageView);
        }
    }

    private void addImageFromFile(LinearLayout container, String relativePath, String originalPrompt) {
        ImageView imageView = createImageView(container);
        container.addView(imageView);
        addImageActions(container, imageView, originalPrompt);

        File imageFile = new File(container.getContext().getFilesDir(), relativePath.trim());
        Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
        if (bitmap == null) {
            showImageError(container, imageView);
            return;
        }

        imageView.setImageBitmap(bitmap);
        imageView.setTag(bitmap);
    }

    private void addImageActions(LinearLayout container, ImageView imageView, String originalPrompt) {
        LinearLayout actions = new LinearLayout(container.getContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(container, 8), 0, 0);

        TextView fullscreen = createActionButton(container, "Fullscreen");
        TextView download = createActionButton(container, "Download");
        TextView iterate = createActionButton(container, "Iterasi");

        fullscreen.setOnClickListener(v -> {
            Bitmap bitmap = getBitmapFromImageView(imageView);
            if (bitmap == null) {
                Toast.makeText(container.getContext(), "Gambar masih dimuat", Toast.LENGTH_SHORT).show();
                return;
            }
            showFullscreenImage(container.getContext(), bitmap);
        });

        download.setOnClickListener(v -> {
            Bitmap bitmap = getBitmapFromImageView(imageView);
            if (bitmap == null) {
                Toast.makeText(container.getContext(), "Gambar masih dimuat", Toast.LENGTH_SHORT).show();
                return;
            }
            saveImageToGallery(container.getContext(), bitmap);
        });

        iterate.setOnClickListener(v -> {
            if (imageIterateListener != null) imageIterateListener.onImageIterate(originalPrompt);
        });

        actions.addView(fullscreen);
        actions.addView(download);
        actions.addView(iterate);
        container.addView(actions);
    }

    private TextView createActionButton(LinearLayout container, String text) {
        TextView button = new TextView(container.getContext());
        button.setText(text);
        button.setTextColor(Color.parseColor("#5C6BC0"));
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(container, 8), dp(container, 6), dp(container, 8), dp(container, 6));
        return button;
    }

    private Bitmap getBitmapFromImageView(ImageView imageView) {
        Object tag = imageView.getTag();
        return tag instanceof Bitmap ? (Bitmap) tag : null;
    }

    private void showFullscreenImage(Context context, Bitmap bitmap) {
        Dialog dialog = new Dialog(context);
        ImageView imageView = new ImageView(context);
        imageView.setImageBitmap(bitmap);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setBackgroundColor(Color.BLACK);
        dialog.setContentView(imageView);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        imageView.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void saveImageToGallery(Context context, Bitmap bitmap) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "chatbot_image_" + System.currentTimeMillis() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Chatbot");

        try {
            android.net.Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Save failed");
            try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new IOException("Save failed");
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            Toast.makeText(context, "Gambar tersimpan di galeri", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "Gagal menyimpan gambar", Toast.LENGTH_SHORT).show();
        }
    }

    private ImageView createImageView(LinearLayout container) {
        ImageView imageView = new ImageView(container.getContext());
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setBackgroundColor(Color.parseColor("#F0F1F8"));
        imageView.setMinimumHeight(dp(container, 180));
        imageView.setImageResource(android.R.drawable.ic_menu_gallery);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(container, 300),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        imageView.setLayoutParams(params);
        return imageView;
    }

    private void showImageError(LinearLayout container, ImageView imageView) {
        container.removeView(imageView);
        addNormalText(container, "Gambar gagal dimuat.");
    }

    private String formatMarkdownText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ")
                .replaceAll("[ \\t]+\\n", "\n")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim();
    }

    private boolean isHeading(String text) {
        return text.length() <= 48 && !text.contains("\n") && text.matches(".*[:：]$");
    }

    private boolean isListItem(String text) {
        return text.startsWith("• ") || text.matches("^\\d+\\. .*");
    }

    private void addCodeBlock(LinearLayout container, String code) {
        if (code.isEmpty()) return;

        LinearLayout codeContainer = new LinearLayout(container.getContext());
        codeContainer.setOrientation(LinearLayout.VERTICAL);
        codeContainer.setBackgroundColor(Color.parseColor("#F0F1F8"));
        codeContainer.setPadding(dp(container, 8), dp(container, 6), dp(container, 8), dp(container, 8));

        LinearLayout header = new LinearLayout(container.getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(container, 6));

        TextView label = new TextView(container.getContext());
        label.setText("Kode");
        label.setTextColor(Color.parseColor("#6B7280"));
        label.setTextSize(12);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView copyButton = new TextView(container.getContext());
        copyButton.setText("Copy");
        copyButton.setTextColor(Color.parseColor("#5C6BC0"));
        copyButton.setTextSize(12);
        copyButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        copyButton.setPadding(dp(container, 8), dp(container, 4), dp(container, 8), dp(container, 4));
        copyButton.setOnClickListener(v -> copyCodeToClipboard(copyButton, code));

        header.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(copyButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        codeContainer.addView(header);

        HorizontalScrollView scrollView = new HorizontalScrollView(container.getContext());
        scrollView.setHorizontalScrollBarEnabled(true);
        scrollView.setFillViewport(false);

        TextView codeView = new TextView(container.getContext());
        codeView.setText(highlightCode(code));
        codeView.setTextSize(13);
        codeView.setTextColor(Color.parseColor("#1F2937"));
        codeView.setTypeface(Typeface.MONOSPACE);
        codeView.setSingleLine(false);
        codeView.setHorizontallyScrolling(true);
        codeView.setIncludeFontPadding(false);
        codeView.setLineSpacing(0, 1.0f);

        scrollView.addView(codeView, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        codeContainer.addView(scrollView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(container, 300),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(container, 4), 0, dp(container, 8));
        container.addView(codeContainer, params);
    }

    private void copyCodeToClipboard(TextView copyButton, String code) {
        Context context = copyButton.getContext();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("code", code));
            copyButton.setText("Copied!");
            copyButton.setTextColor(Color.parseColor("#059669"));
            copyButton.animate()
                    .scaleX(1.12f)
                    .scaleY(1.12f)
                    .setDuration(120)
                    .withEndAction(() -> copyButton.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(120)
                            .start())
                    .start();
            Toast.makeText(context, "Kode berhasil disalin", Toast.LENGTH_SHORT).show();
            copyButton.postDelayed(() -> {
                copyButton.setText("Copy");
                copyButton.setTextColor(Color.parseColor("#5C6BC0"));
            }, 1500);
        }
    }

    private CharSequence highlightCode(String code) {
        SpannableStringBuilder builder = new SpannableStringBuilder(code);
        builder.setSpan(new TypefaceSpan("monospace"), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        highlightPattern(builder, code, "</?[^>]+>", "#5C6BC0", true);
        highlightPattern(builder, code, "\\b(html|head|body|title|meta|header|nav|main|section|div|h1|h2|h3|p|a|img|style|script|footer|class|public|private|static|void|int|new|extends|implements|import|return|if|else|for|while)\\b", "#7C4DFF", false);
        highlightPattern(builder, code, "\\b(function|const|let|var|document|display|margin|padding|color|background|font|width|height)\\b", "#D97706", false);
        highlightPattern(builder, code, "#[0-9A-Fa-f]{3,6}|\\b\\d+(px|rem|em|%)\\b", "#059669", false);
        highlightPattern(builder, code, "\"[^\"]*\"|'[^']*'", "#DC2626", false);
        return builder;
    }

    private void highlightPattern(SpannableStringBuilder builder, String code, String regex, String color, boolean bold) {
        Matcher matcher = Pattern.compile(regex).matcher(code);
        while (matcher.find()) {
            builder.setSpan(new ForegroundColorSpan(Color.parseColor(color)), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) {
                builder.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private int dp(LinearLayout container, int value) {
        return (int) (value * container.getResources().getDisplayMetrics().density + 0.5f);
    }

    static class UserMessageViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage, tvTime;
        MaterialButton btnRetry, btnReply;

        UserMessageViewHolder(View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessageUser);
            tvTime = itemView.findViewById(R.id.tvTimeUser);
            btnRetry = itemView.findViewById(R.id.btnRetryUser);
            btnReply = itemView.findViewById(R.id.btnReplyUser);
        }
    }

    static class BotMessageViewHolder extends RecyclerView.ViewHolder {
        LinearLayout layoutContent;
        TextView tvTime;

        BotMessageViewHolder(View itemView) {
            super(itemView);
            layoutContent = itemView.findViewById(R.id.layoutBotContent);
            tvTime = itemView.findViewById(R.id.tvTimeBot);
        }
    }

    static class ThinkingViewHolder extends RecyclerView.ViewHolder {
        TextView tvStatus;

        ThinkingViewHolder(View itemView) {
            super(itemView);
            tvStatus = itemView.findViewById(R.id.tvThinkingStatus);
        }
    }
}
