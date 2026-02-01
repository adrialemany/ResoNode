package com.example.spotifly;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {

    public static final int MODE_PRIVATE = 0;
    public static final int MODE_PUBLIC = 1;
    public static final int MODE_VAULT = 2;
    public static final int MODE_SEARCH = MODE_VAULT;

    private final Context context;
    private final List<MusicItem> items;
    private int mode;
    private String currentUsername;
    private String currentPath = "";

    private final OnItemClickListener listener;
    private final OnItemMenuClickListener menuListener;

    private OfflineDB offlineDB;

    private final List<MusicItem> selectedItems = new ArrayList<>();
    private OnSelectionChangedListener selectionListener;
    private boolean isSelectionActive = false;

    public interface OnItemClickListener { void onItemClick(MusicItem item); }
    public interface OnItemMenuClickListener { void onMenuClick(MusicItem item, String action); }
    public interface OnSelectionChangedListener { void onSelectionChanged(int count); }

    public PlaylistAdapter(Context context, List<MusicItem> items, int initialMode,
                           OnItemClickListener listener, OnItemMenuClickListener menuListener) {
        this.context = context;
        this.items = items;
        this.mode = initialMode;
        this.listener = listener;
        this.menuListener = menuListener;
        this.isSelectionActive = (initialMode == MODE_PUBLIC);

        this.offlineDB = new OfflineDB(context);
        SessionManager session = new SessionManager(context);
        this.currentUsername = session.getUsername();
    }

    public void setMode(int newMode) {
        this.mode = newMode;
        this.selectedItems.clear();
        this.isSelectionActive = (newMode == MODE_PUBLIC);
        notifyDataSetChanged();
    }

    public void setCurrentPath(String path) {
        this.currentPath = path;
    }

    public void setSelectionMode(boolean enabled) {
        this.isSelectionActive = enabled;
        if (!enabled) {
            selectedItems.clear();
            if (selectionListener != null) selectionListener.onSelectionChanged(0);
        }
        notifyDataSetChanged();
    }

    public List<MusicItem> getSelectedItems() { return selectedItems; }
    public void setOnSelectionChangedListener(OnSelectionChangedListener l) { this.selectionListener = l; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final MusicItem item = items.get(position);

        
        String rawName = item.getName();
        if (rawName.equals("General")) rawName = "Playlists Públicas";
        String displayName = item.isFolder() ? rawName : rawName.replace(".mp3", "").replace(".MP3", "").replaceAll("^(\\d+[\\s_\\-]*)+", "");
        holder.tvName.setText(displayName);

        String infoText = "";
        if (item.isFolder()) infoText = (mode == MODE_VAULT) ? (currentPath.isEmpty() ? "Artista" : "Album") : "Playlist";
        else infoText = (item.getArtist() != null && !item.getArtist().isEmpty() && !item.getArtist().equals("SpotiFly")) ? item.getArtist() : "Canción";
        holder.tvArtist.setText(infoText);

        

        
        String onlineUrlToTry = null;
        if (item.getPath().startsWith("/")) {
            
            String originalServerPath = offlineDB.getServerPathForLocalFile(item.getPath());
            if (originalServerPath != null) {
                try {
                    onlineUrlToTry = Config.SERVER_URL + "/cover?username=" + currentUsername + "&path=" + URLEncoder.encode(originalServerPath, "UTF-8");
                } catch (Exception e) {}
            }
        } else {
            
            try {
                onlineUrlToTry = Config.SERVER_URL + "/cover?username=" + currentUsername + "&path=" + URLEncoder.encode(item.getPath(), "UTF-8");
            } catch (Exception e) {}
        }

        
        File backupCover = null;
        File offlineDir = context.getDir("offline_music", Context.MODE_PRIVATE);

        
        String coverNameTarget = item.isFolder() ? item.getName() : currentPath;
        if (!coverNameTarget.isEmpty()) {
            String safeName = "cover_" + coverNameTarget.replaceAll("[^a-zA-Z0-9.-]", "_") + ".jpg";
            File f = new File(offlineDir, safeName);
            if (f.exists()) backupCover = f;
        }

        
        

        holder.ivIcon.setVisibility(View.VISIBLE);
        holder.tvIcon.setVisibility(View.GONE);

        if (onlineUrlToTry != null) {
            Glide.with(context)
                    .load(onlineUrlToTry)
                    .diskCacheStrategy(DiskCacheStrategy.ALL) 
                    .error(Glide.with(context).load(backupCover)) 
                    .placeholder(R.mipmap.ic_launcher)
                    .circleCrop()
                    .into(holder.ivIcon);
        } else if (backupCover != null) {
            
            Glide.with(context).load(backupCover).circleCrop().into(holder.ivIcon);
        } else {
            
            holder.ivIcon.setVisibility(View.GONE);
            holder.tvIcon.setVisibility(View.VISIBLE);
            String emoji = item.isFolder() ? (mode == MODE_VAULT && !item.getPath().contains("/") ? "👤" : "💿") : "🎵";
            if(item.isFolder() && mode != MODE_VAULT) emoji = "📁";
            holder.tvIcon.setText(emoji);
        }

        
        
        if (mode == MODE_PUBLIC) {
            holder.btnMore.setVisibility(View.GONE);
            if (isSelectionActive) {
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setOnCheckedChangeListener(null);
                holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
                holder.checkBox.setChecked(selectedItems.contains(item));
                holder.itemView.setBackgroundColor(selectedItems.contains(item) ? 0xFF333333 : 0x00000000);
                holder.checkBox.setOnClickListener(v -> {
                    if (selectedItems.contains(item)) selectedItems.remove(item);
                    else selectedItems.add(item);
                    notifyItemChanged(position);
                    if(selectionListener != null) selectionListener.onSelectionChanged(selectedItems.size());
                });
            } else {
                holder.checkBox.setVisibility(View.GONE);
                holder.itemView.setBackgroundColor(0x00000000);
                holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
            }
        } else {
            holder.checkBox.setVisibility(View.GONE);
            holder.btnMore.setVisibility(View.VISIBLE);
            holder.itemView.setBackgroundColor(0x00000000);
            holder.btnMore.setOnClickListener(v -> showPopupMenu(v, item));
            holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
        }
    }

    private void showPopupMenu(View view, MusicItem item) {
        PopupMenu popup = new PopupMenu(context, view);

        if (mode == MODE_VAULT || mode == MODE_PRIVATE || mode == MODE_SEARCH) {
            popup.getMenu().add("Añadir a Playlist");
        }

        if (item.isFolder()) {
            boolean isDownloaded = false;
            try {
                if (offlineDB != null) isDownloaded = offlineDB.isPlaylistDownloaded(item.getName());
            } catch (Exception e) {}

            if (isDownloaded) popup.getMenu().add("Borrar Offline");
            else popup.getMenu().add("Descargar Offline");
        }

        if (mode == MODE_PRIVATE) {
            popup.getMenu().add("Cambiar Portada");
            popup.getMenu().add("Renombrar");
            popup.getMenu().add("Eliminar");
        }

        popup.setOnMenuItemClickListener(menuItem -> {
            menuListener.onMenuClick(item, menuItem.getTitle().toString());
            return true;
        });
        popup.show();
    }

    @Override
    public int getItemCount() { return items.size(); }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvIcon;
        TextView tvName, tvArtist;
        ImageView btnMore;
        CheckBox checkBox;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_icon);
            tvIcon = itemView.findViewById(R.id.tv_icon);
            tvName = itemView.findViewById(R.id.tv_name);
            tvArtist = itemView.findViewById(R.id.tv_artist);
            btnMore = itemView.findViewById(R.id.btn_more);
            checkBox = itemView.findViewById(R.id.checkbox_select);
        }
    }
}