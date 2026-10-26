package com.example.resonode;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Lista de la cola (panel inferior del reproductor). */
public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.ViewHolder> {

    public interface DragStarter { void startDrag(RecyclerView.ViewHolder holder); }

    private final List<MusicItem> items = new ArrayList<>();

    public List<MusicItem> getItems() { return new ArrayList<>(items); }
    private DragStarter dragStarter;

    public QueueAdapter(List<MusicItem> initial) {
        if (initial != null) items.addAll(initial);
    }

    public void setDragStarter(DragStarter starter) { this.dragStarter = starter; }

    public void setItems(List<MusicItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void moveItem(int from, int to) {
        items.add(to, items.remove(from));
        notifyItemMoved(from, to);
    }

    public void removeItem(int position) {
        items.remove(position);
        notifyItemRemoved(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_queue, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MusicItem item = items.get(position);

        String title = item.getName()
                .replace(".mp3", "").replace(".MP3", "")
                .replaceAll("^(\\d+[\\s_\\-]*)+", "");
        holder.tvTitle.setText(title);

        String artist = item.getArtist();
        holder.tvArtist.setText(artist == null || artist.isEmpty()
                ? holder.itemView.getContext().getString(R.string.label_type_song)
                : artist);

        // Arrastrar solo desde el asa ≡
        holder.ivHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && dragStarter != null) {
                dragStarter.startDrag(holder);
            }
            return false;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle, tvArtist;
        final ImageView ivHandle;

        ViewHolder(@NonNull View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tv_queue_title);
            tvArtist = v.findViewById(R.id.tv_queue_artist);
            ivHandle = v.findViewById(R.id.iv_drag_handle);
        }
    }
}