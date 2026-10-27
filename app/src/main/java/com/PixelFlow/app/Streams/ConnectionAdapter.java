package com.PixelFlow.app.Streams;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.PixelFlow.app.API.ConnectionUser;
import com.PixelFlow.app.R;
import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

/** Followers/following list with follow intents handled by the owning activity. */
public class ConnectionAdapter extends RecyclerView.Adapter<ConnectionAdapter.Holder> {
    private final Context context;
    private final List<ConnectionUser> users = new ArrayList<>();
    private boolean actionsEnabled;
    private OnUserClickListener userClickListener;
    private OnFollowListener followListener;

    public interface OnUserClickListener { void onUserClick(ConnectionUser user); }
    public interface OnFollowListener { void onFollow(ConnectionUser user); }

    public ConnectionAdapter(Context context) { this.context = context; }

    public void setOnUserClickListener(OnUserClickListener listener) { this.userClickListener = listener; }
    public void setOnFollowListener(OnFollowListener listener) { this.followListener = listener; }

    public void setData(List<ConnectionUser> items) {
        users.clear();
        users.addAll(items);
        notifyDataSetChanged();
    }

    public void addData(List<ConnectionUser> items) {
        int start = users.size();
        users.addAll(items);
        notifyItemRangeInserted(start, items.size());
    }

    public List<ConnectionUser> getData() { return users; }

    public void removeUser(String userId) {
        int index = -1;
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).id.equals(userId)) { index = i; break; }
        }
        if (index >= 0) { users.remove(index); notifyItemRemoved(index); }
    }

    public void setActionsEnabled(boolean enabled) {
        if (actionsEnabled == enabled) return;
        actionsEnabled = enabled;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.item_connection, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ConnectionUser user = users.get(position);
        holder.name.setText(user.nickname);
        holder.bio.setVisibility(user.bio.isEmpty() ? View.GONE : View.VISIBLE);
        holder.bio.setText(user.bio);
        Glide.with(context).load(user.avatar.isEmpty() ? null : user.avatar)
                .placeholder(R.drawable.profile_avatar).error(R.drawable.profile_avatar).circleCrop().into(holder.avatar);
        bindFollow(holder, user);
        holder.follow.setOnClickListener(v -> toggleFollow(holder.getBindingAdapterPosition()));
        holder.itemView.setOnClickListener(v -> {
            if (userClickListener != null) userClickListener.onUserClick(user);
        });
    }

    private void bindFollow(Holder holder, ConnectionUser user) {
        holder.follow.setEnabled(actionsEnabled);
        holder.follow.setText(user.following ? R.string.connection_following : R.string.connection_follow);
        holder.follow.setBackgroundTintList(ColorStateList.valueOf(
                context.getColor(user.following ? R.color.profile_soft : R.color.profile_accent)));
        holder.follow.setTextColor(context.getColor(user.following ? R.color.profile_accent : R.color.white));
    }

    private void toggleFollow(int position) {
        if (!actionsEnabled || position < 0 || position >= users.size() || followListener == null) return;
        followListener.onFollow(users.get(position));
    }

    @Override public int getItemCount() { return users.size(); }

    static class Holder extends RecyclerView.ViewHolder {
        ImageView avatar;
        TextView name, bio;
        MaterialButton follow;
        Holder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.connection_avatar);
            name = itemView.findViewById(R.id.connection_name);
            bio = itemView.findViewById(R.id.connection_bio);
            follow = itemView.findViewById(R.id.connection_follow);
        }
    }
}
