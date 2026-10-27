package com.PixelFlow.app.Streams;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.AccountMedia;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.R;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class UserPostsAdapter extends RecyclerView.Adapter<UserPostsAdapter.Holder> {
    public interface Listener { void open(PostItem post); }
    private final List<PostItem> items = new ArrayList<>();
    private final Set<Holder> bound = new HashSet<>();
    private final Listener listener;
    private boolean enabled;

    public UserPostsAdapter(Listener listener) { this.listener = listener; }
    public void setActionsEnabled(boolean value) { enabled = value; }
    public void clear() {
        for (Holder holder : bound) AccountMedia.clear(holder.cover);
        bound.clear();
        int count = items.size();
        items.clear();
        if (count > 0) notifyItemRangeRemoved(0, count);
    }
    public void append(List<PostItem> posts) {
        int start = items.size(); items.addAll(posts); notifyItemRangeInserted(start, posts.size());
    }
    @Override public int getItemCount() { return items.size(); }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_user_post, parent, false));
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        bound.add(holder);
        PostItem post = items.get(position);
        String title = post.getTitle();
        holder.title.setText(title == null || title.isEmpty() ? holder.itemView.getContext().getString(R.string.author_work_untitled) : title);
        boolean video = false;
        String image = null;
        for (PostItem.Clip clip : post.getClips()) {
            if (clip.getType() == 1) video = true;
            else if (image == null) image = clip.getUrl();
        }
        holder.kind.setText(video ? R.string.author_work_video : R.string.author_work_images);
        holder.likes.setText(holder.itemView.getContext().getString(post.isLiked()
                ? R.string.author_work_liked : R.string.author_work_likes, post.getLikeCount()));
        holder.likes.setTextColor(holder.itemView.getContext().getColor(post.isLiked() ? R.color.profile_accent : R.color.profile_ink));
        AccountMedia.load(holder.cover, image);
        holder.itemView.setContentDescription(holder.title.getText() + "，" + holder.kind.getText() + "，" + holder.likes.getText());
        holder.itemView.setOnClickListener(v -> { if (enabled) listener.open(post); });
    }
    @Override public void onViewRecycled(@NonNull Holder holder) {
        AccountMedia.clear(holder.cover); bound.remove(holder); holder.itemView.setOnClickListener(null);
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title, kind, likes;
        Holder(View view) {
            super(view);
            cover = view.findViewById(R.id.author_work_cover);
            title = view.findViewById(R.id.author_work_title);
            kind = view.findViewById(R.id.author_work_kind);
            likes = view.findViewById(R.id.author_work_likes);
        }
    }
}
