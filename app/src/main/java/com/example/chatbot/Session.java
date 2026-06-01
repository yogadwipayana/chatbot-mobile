package com.example.chatbot;

/**
 * Model POJO untuk merepresentasikan satu sesi percakapan.
 */
public class Session {
    private long id;
    private String title;
    private String createdAt;
    private String updatedAt;

    // Konstruktor kosong
    public Session() {}

    // Konstruktor lengkap untuk memudahkan inisialisasi
    public Session(long id, String title, String createdAt, String updatedAt) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // Getter dan Setter untuk mengakses dan mengubah data field
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
