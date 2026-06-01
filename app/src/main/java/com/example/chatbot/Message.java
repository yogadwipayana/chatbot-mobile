package com.example.chatbot;

/**
 * Model POJO untuk merepresentasikan satu pesan dalam percakapan.
 */
public class Message {
    private long id;
    private long sessionId;
    private String role; // 'user' atau 'assistant'
    private String content;
    private String timestamp;

    // Konstruktor kosong
    public Message() {}

    // Konstruktor lengkap
    public Message(long id, long sessionId, String role, String content, String timestamp) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    // Getter dan Setter
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getSessionId() { return sessionId; }
    public void setSessionId(long sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
