package com.example.chatbot;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter untuk menampilkan daftar sesi chat di MainActivity.
 */
public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {

    private List<Session> sessionList;
    private OnItemClickListener clickListener;
    private OnMenuClickListener menuListener;

    // Interface untuk menangani klik pada area konten sesi
    public interface OnItemClickListener {
        void onItemClick(Session session);
    }

    // Interface untuk menangani klik tombol titik tiga
    public interface OnMenuClickListener {
        void onMenuClick(Session session);
    }

    public SessionAdapter(List<Session> sessionList) {
        this.sessionList = sessionList;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnMenuClickListener(OnMenuClickListener listener) {
        this.menuListener = listener;
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        Session session = sessionList.get(position);
        holder.tvTitle.setText(session.getTitle());
        holder.tvDate.setText(session.getUpdatedAt());

        // Klik area kiri membuka chat
        holder.contentArea.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onItemClick(session);
        });

        // Tekan lama area kiri juga membuka menu sebagai fallback
        holder.contentArea.setOnLongClickListener(v -> {
            if (menuListener != null) menuListener.onMenuClick(session);
            return true;
        });

        holder.btnMenu.setOnClickListener(v -> {
            if (menuListener != null) menuListener.onMenuClick(session);
        });
    }

    @Override
    public int getItemCount() {
        return sessionList.size();
    }

    // Memperbarui seluruh isi list
    public void setSessions(List<Session> sessions) {
        this.sessionList = sessions;
        notifyDataSetChanged();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvDate, btnMenu;
        View contentArea;

        public SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvSessionTitle);
            tvDate = itemView.findViewById(R.id.tvSessionDate);
            btnMenu = itemView.findViewById(R.id.btnMenu);
            contentArea = itemView.findViewById(R.id.contentArea);
        }
    }
}
