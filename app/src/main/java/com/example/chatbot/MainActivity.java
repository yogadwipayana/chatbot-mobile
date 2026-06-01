package com.example.chatbot;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.content.Context;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity utama untuk menampilkan daftar sesi percakapan.
 */
public class MainActivity extends AppCompatActivity {

    private RecyclerView rvSessions;
    private SessionAdapter adapter;
    private ChatDAO chatDAO;
    private View layoutEmpty;
    private List<Session> sessionList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rvSessions = findViewById(R.id.rvSessions);
        layoutEmpty = findViewById(R.id.layoutEmpty);
        ExtendedFloatingActionButton fabAdd = findViewById(R.id.fabAdd);

        chatDAO = new ChatDAO(this);

        adapter = new SessionAdapter(sessionList);
        rvSessions.setLayoutManager(new LinearLayoutManager(this));
        rvSessions.setAdapter(adapter);

        adapter.setOnItemClickListener(session -> {
            Intent intent = new Intent(MainActivity.this, ChatActivity.class);
            intent.putExtra("session_id", session.getId());
            startActivity(intent);
        });

        adapter.setOnMenuClickListener(this::showSessionMenu);
        fabAdd.setOnClickListener(v -> createNewSession());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessions();
    }

    private void loadSessions() {
        new Thread(() -> {
            List<Session> sessions = chatDAO.getAllSessions();
            runOnUiThread(() -> updateSessionList(sessions));
        }).start();
    }

    private void createNewSession() {
        new Thread(() -> {
            long sessionId = chatDAO.insertSession("New Chat");
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, ChatActivity.class);
                intent.putExtra("session_id", sessionId);
                startActivity(intent);
            });
        }).start();
    }

    private void showSessionMenu(Session session) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_session_actions, null);

        TextView tvActionTitle = view.findViewById(R.id.tvActionTitle);
        View btnRenameSession = view.findViewById(R.id.btnRenameSession);
        View btnDeleteSession = view.findViewById(R.id.btnDeleteSession);

        tvActionTitle.setText(session.getTitle());
        btnRenameSession.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(session);
        });
        btnDeleteSession.setOnClickListener(v -> {
            dialog.dismiss();
            showDeleteConfirmDialog(session);
        });

        dialog.setContentView(view);
        dialog.show();
    }

    private void showRenameDialog(Session session) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_rename_session, null);
        TextInputLayout tilSessionTitle = view.findViewById(R.id.tilSessionTitle);
        TextInputEditText etSessionTitle = view.findViewById(R.id.etSessionTitle);
        MaterialButton btnCancelRename = view.findViewById(R.id.btnCancelRename);
        MaterialButton btnSaveRename = view.findViewById(R.id.btnSaveRename);
        etSessionTitle.setText(session.getTitle());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(view)
                .show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        btnCancelRename.setOnClickListener(v -> dialog.dismiss());
        btnSaveRename.setOnClickListener(v -> {
            String newTitle = etSessionTitle.getText() == null ? "" : etSessionTitle.getText().toString().trim();
            if (newTitle.isEmpty()) {
                tilSessionTitle.setError("Nama percakapan tidak boleh kosong");
                return;
            }
            tilSessionTitle.setError(null);
            renameSession(session.getId(), newTitle);
            dialog.dismiss();
        });

        etSessionTitle.requestFocus();
        etSessionTitle.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etSessionTitle, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 200);
    }

    private void showDeleteConfirmDialog(Session session) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Hapus percakapan?")
                .setMessage("Percakapan \"" + session.getTitle() + "\" akan dihapus permanen.")
                .setPositiveButton("Hapus", (dialog, which) -> deleteSession(session.getId()))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void renameSession(long sessionId, String newTitle) {
        new Thread(() -> {
            chatDAO.updateSessionTitle(sessionId, newTitle);
            List<Session> sessions = chatDAO.getAllSessions();
            runOnUiThread(() -> updateSessionList(sessions));
        }).start();
    }

    private void deleteSession(long sessionId) {
        new Thread(() -> {
            chatDAO.deleteSession(sessionId);
            List<Session> sessions = chatDAO.getAllSessions();
            runOnUiThread(() -> updateSessionList(sessions));
        }).start();
    }

    private void updateSessionList(List<Session> sessions) {
        sessionList = sessions;
        adapter.setSessions(sessions);
        layoutEmpty.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        chatDAO.close();
    }
}


