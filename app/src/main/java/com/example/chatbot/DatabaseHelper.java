package com.example.chatbot;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Helper untuk mengelola pembuatan dan versi database SQLite.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "chatbot.db";
    private static final int DATABASE_VERSION = 1;

    // Nama Tabel
    public static final String TABLE_SESSIONS = "sessions";
    public static final String TABLE_MESSAGES = "messages";

    // Kolom Tabel Sessions
    public static final String COL_SESSION_ID = "id";
    public static final String COL_SESSION_TITLE = "title";
    public static final String COL_SESSION_CREATED_AT = "created_at";
    public static final String COL_SESSION_UPDATED_AT = "updated_at";

    // Kolom Tabel Messages
    public static final String COL_MESSAGE_ID = "id";
    public static final String COL_MESSAGE_SESSION_ID = "session_id";
    public static final String COL_MESSAGE_ROLE = "role";
    public static final String COL_MESSAGE_CONTENT = "content";
    public static final String COL_MESSAGE_TIMESTAMP = "timestamp";

    // SQL untuk membuat tabel sessions
    private static final String CREATE_TABLE_SESSIONS = "CREATE TABLE " + TABLE_SESSIONS + " (" +
            COL_SESSION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_SESSION_TITLE + " TEXT NOT NULL DEFAULT 'New Chat', " +
            COL_SESSION_CREATED_AT + " TEXT NOT NULL, " +
            COL_SESSION_UPDATED_AT + " TEXT NOT NULL);";

    // SQL untuk membuat tabel messages dengan Foreign Key dan ON DELETE CASCADE
    private static final String CREATE_TABLE_MESSAGES = "CREATE TABLE " + TABLE_MESSAGES + " (" +
            COL_MESSAGE_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_MESSAGE_SESSION_ID + " INTEGER NOT NULL, " +
            COL_MESSAGE_ROLE + " TEXT NOT NULL, " +
            COL_MESSAGE_CONTENT + " TEXT NOT NULL, " +
            COL_MESSAGE_TIMESTAMP + " TEXT NOT NULL, " +
            "FOREIGN KEY (" + COL_MESSAGE_SESSION_ID + ") REFERENCES " + TABLE_SESSIONS + "(" + COL_SESSION_ID + ") ON DELETE CASCADE);";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // Eksekusi pembuatan tabel saat database pertama kali dibuat
        db.execSQL(CREATE_TABLE_SESSIONS);
        db.execSQL(CREATE_TABLE_MESSAGES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Hapus tabel lama jika ada kenaikan versi dan buat ulang
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SESSIONS);
        onCreate(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Aktifkan dukungan Foreign Key secara eksplisit
        db.execSQL("PRAGMA foreign_keys=ON;");
    }
}
