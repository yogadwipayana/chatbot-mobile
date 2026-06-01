package com.example.chatbot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Data Access Object (DAO) untuk menangani operasi CRUD ke database.
 */
public class ChatDAO {
    private static final int MAX_CURSOR_CONTENT_LENGTH = 500_000;

    private SQLiteDatabase db;
    private final DatabaseHelper dbHelper;

    public ChatDAO(Context context) {
        dbHelper = new DatabaseHelper(context.getApplicationContext());
        db = dbHelper.getWritableDatabase();
    }

    private SQLiteDatabase getDb() {
        if (db == null || !db.isOpen()) {
            db = dbHelper.getWritableDatabase();
        }
        return db;
    }

    // Helper untuk mendapatkan timestamp saat ini
    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    // --- OPERASI SESSION ---

    // Insert sesi baru dan kembalikan ID-nya
    public synchronized long insertSession(String title) {
        String now = getCurrentTimestamp();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_SESSION_TITLE, title);
        values.put(DatabaseHelper.COL_SESSION_CREATED_AT, now);
        values.put(DatabaseHelper.COL_SESSION_UPDATED_AT, now);
        return getDb().insert(DatabaseHelper.TABLE_SESSIONS, null, values);
    }

    // Ambil satu sesi berdasarkan ID-nya
    public synchronized Session getSessionById(long id) {
        String selection = DatabaseHelper.COL_SESSION_ID + " = ?";
        String[] selectionArgs = {String.valueOf(id)};
        try (Cursor cursor = getDb().query(DatabaseHelper.TABLE_SESSIONS, null, selection, selectionArgs, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return new Session(
                        cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_ID)),
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_TITLE)),
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_CREATED_AT)),
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_UPDATED_AT))
                );
            }
        }
        return null;
    }

    // Ambil semua sesi, urutkan berdasarkan waktu update terbaru
    public synchronized List<Session> getAllSessions() {
        List<Session> sessions = new ArrayList<>();
        String orderBy = DatabaseHelper.COL_SESSION_UPDATED_AT + " DESC";
        try (Cursor cursor = getDb().query(DatabaseHelper.TABLE_SESSIONS, null, null, null, null, null, orderBy)) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    Session session = new Session(
                            cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_ID)),
                            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_TITLE)),
                            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_CREATED_AT)),
                            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_SESSION_UPDATED_AT))
                    );
                    sessions.add(session);
                } while (cursor.moveToNext());
            }
        }
        return sessions;
    }

    // Update judul sesi
    public synchronized void updateSessionTitle(long id, String newTitle) {
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_SESSION_TITLE, newTitle);
        getDb().update(DatabaseHelper.TABLE_SESSIONS, values, DatabaseHelper.COL_SESSION_ID + " = ?", new String[]{String.valueOf(id)});
    }

    // Update timestamp updated_at setiap ada pesan baru
    public synchronized void updateSessionTimestamp(long id) {
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_SESSION_UPDATED_AT, getCurrentTimestamp());
        getDb().update(DatabaseHelper.TABLE_SESSIONS, values, DatabaseHelper.COL_SESSION_ID + " = ?", new String[]{String.valueOf(id)});
    }

    // Hapus sesi (akan menghapus pesan terkait karena CASCADE)
    public synchronized void deleteSession(long id) {
        getDb().delete(DatabaseHelper.TABLE_SESSIONS, DatabaseHelper.COL_SESSION_ID + " = ?", new String[]{String.valueOf(id)});
    }

    // --- OPERASI MESSAGE ---

    // Simpan pesan baru ke database
    public synchronized void insertMessage(long sessionId, String role, String content) {
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_MESSAGE_SESSION_ID, sessionId);
        values.put(DatabaseHelper.COL_MESSAGE_ROLE, role);
        values.put(DatabaseHelper.COL_MESSAGE_CONTENT, content);
        values.put(DatabaseHelper.COL_MESSAGE_TIMESTAMP, getCurrentTimestamp());
        getDb().insert(DatabaseHelper.TABLE_MESSAGES, null, values);
        
        // Update waktu terakhir sesi diperbarui
        updateSessionTimestamp(sessionId);
    }

    // Ambil semua pesan dalam satu sesi tertentu
    public synchronized List<Message> getMessagesBySession(long sessionId) {
        List<Message> messages = new ArrayList<>();
        String selection = DatabaseHelper.COL_MESSAGE_SESSION_ID + " = ?";
        String[] selectionArgs = {String.valueOf(sessionId)};
        String safeContentColumn = "CASE WHEN length(" + DatabaseHelper.COL_MESSAGE_CONTENT + ") > "
                + MAX_CURSOR_CONTENT_LENGTH
                + " THEN '[Pesan terlalu besar untuk ditampilkan. Jika ini gambar lama, buat ulang gambar agar tersimpan sebagai file.]' "
                + "ELSE " + DatabaseHelper.COL_MESSAGE_CONTENT + " END AS " + DatabaseHelper.COL_MESSAGE_CONTENT;
        String[] columns = {
                DatabaseHelper.COL_MESSAGE_ID,
                DatabaseHelper.COL_MESSAGE_SESSION_ID,
                DatabaseHelper.COL_MESSAGE_ROLE,
                safeContentColumn,
                DatabaseHelper.COL_MESSAGE_TIMESTAMP
        };
        try (Cursor cursor = getDb().query(DatabaseHelper.TABLE_MESSAGES, columns, selection, selectionArgs, null, null, DatabaseHelper.COL_MESSAGE_ID + " ASC")) {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    Message message = new Message(
                            cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_MESSAGE_ID)),
                            cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_MESSAGE_SESSION_ID)),
                            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_MESSAGE_ROLE)),
                            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_MESSAGE_CONTENT)),
                            cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_MESSAGE_TIMESTAMP))
                    );
                    messages.add(message);
                } while (cursor.moveToNext());
            }
        }
        return messages;
    }
    
    // Menutup koneksi database
    public synchronized void close() {
        if (db != null && db.isOpen()) {
            db.close();
        }
        db = null;
        dbHelper.close();
    }
}
