package com.PixelFlow.app.Streams;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

/** Favorites list: cover, title, author, and a remove-favorite action. */
public class FavoriteAdapter extends RecyclerView.Adapter<FavoriteAdapter.Holder> {
    public interface OnFavoriteListener {
        void onOpen(PostItem post);
        void onRemove(PostItem post);
    }
    private boolean actionsEnabled;
    private final Context context;
    private final List<PostItem> items = new ArrayList<>();
    private OnFavoriteListener listener;
    public FavoriteAdapter(Context context) { this.context = context; }
    public void setOnFavoriteListener(OnFavoriteListener l) { this.listener = l; }
    public void setActionsEnabled(boolean enabled) {
        if (actionsEnabled == enabled) return;
        actionsEnabled = enabled;
        notifyDataSetChanged();
    }
    public void setData(List<PostItem> data) { items.clear(); items.addAll(data); notifyDataSetChanged(); }
    public void addData(List<PostItem> data) { int s = items.size(); items.addAll(data); notifyItemRangeInserted(s, data.size()); }
    public List<PostItem> getData() { return items; }
    public void removeById(String postId) {
        for (int i = 0; i < items.size(); i++) {
            if (postId != null && postId.equals(items.get(i).getPostId())) {
                items.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_favorite, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        PostItem post = items.get(position);
        String title = post.getTitle();
        holder.title.setText(title == null || title.isEmpty() ? "（无标题）" : title);
        String author = post.getAuthor() == null ? "" : post.getAuthor().getNickname();
        holder.author.setText(author == null || author.isEmpty() ? "未知作者" : author);
        Glide.with(context).load(firstImageUrl(post))
                .placeholder(R.drawable.ic_image_placeholder).error(R.drawable.ic_image_error).centerCrop().into(holder.cover);
        holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onOpen(post); });
        holder.remove.setEnabled(actionsEnabled);
        holder.remove.setOnClickListener(v -> { if (actionsEnabled && listener != null) listener.onRemove(post); });
    }

    private String firstImageUrl(PostItem post) {
        if (post.getClips() != null) {
            for (PostItem.Clip clip : post.getClips()) {
                if (clip.getType() != 1 && clip.getUrl() != null && !clip.getUrl().isEmpty()) return clip.getUrl();
            }
        }
        return null;
    }

    @Override public int getItemCount() { return items.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView cover;
        TextView title, author;
        MaterialButton remove;
        Holder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.favorite_cover);
            title = itemView.findViewById(R.id.favorite_title);
            author = itemView.findViewById(R.id.favorite_author);
            remove = itemView.findViewById(R.id.favorite_remove);
        }
    }
}
