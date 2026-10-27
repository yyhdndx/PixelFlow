package com.PixelFlow.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.ConnectionPage;
import com.PixelFlow.app.Streams.ConnectionAdapter;

/** Followers / following list, shared by an intent flag; paginates by the server's user-id cursor. */
public class ConnectionsActivity extends AccountDataActivity {
    public static final String EXTRA_FOLLOWERS = "extra_followers";
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private View loading, empty, retry;
    private TextView title, status;
    private ConnectionAdapter adapter;
    private boolean followers;
    private String nextCursor;
    private boolean hasMore = true;
    private boolean inFlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_connections);
        followers = getIntent().getBooleanExtra(EXTRA_FOLLOWERS, true);
        title = findViewById(R.id.connections_title);
        title.setText(followers ? R.string.profile_followers : R.string.profile_following);
        findViewById(R.id.connections_back).setOnClickListener(v -> finish());
        refresh = findViewById(R.id.connections_refresh);
        list = findViewById(R.id.connections_list);
        loading = findViewById(R.id.connections_loading);
        empty = findViewById(R.id.connections_empty);
        status = findViewById(R.id.connections_status);
        retry = findViewById(R.id.connections_retry);
        refresh.setColorSchemeResources(R.color.profile_accent);
        refresh.setOnRefreshListener(() -> load(true));
        retry.setOnClickListener(v -> load(true));
        adapter = new ConnectionAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        adapter.setOnUserClickListener(user -> startActivity(
                new Intent(this, UserProfileActivity.class).putExtra(UserProfileActivity.EXTRA_USER_ID, user.id)));
        adapter.setOnFollowListener(user -> {
            if (!beginWrite()) return;
            api.setFollow(user.id, !user.following, writeResult(null));
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!hasMore || inFlight) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && adapter.getItemCount() > 0 && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3)
                    list.post(() -> { if (hasMore && !inFlight && canLoad()) load(false); });
            }
        });
    }

    @Override protected void reloadPage() { load(true); }

    @Override protected void clearPage() {
        inFlight = false;
        nextCursor = null;
        hasMore = true;
        refresh.setRefreshing(false);
        loading.setVisibility(View.GONE);
        empty.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        adapter.setData(java.util.Collections.emptyList());
    }

    private void load(boolean refreshMode) {
        if (!canLoad()) { refresh.setRefreshing(false); return; }
        inFlight = true;
        final int generation = beginRead();
        if (refreshMode) {
            nextCursor = null; hasMore = true;
            adapter.setData(java.util.Collections.emptyList());
            empty.setVisibility(View.GONE); list.setVisibility(View.GONE);
            refresh.setRefreshing(true);
            status.setVisibility(View.GONE); retry.setVisibility(View.GONE);
        }
        if (adapter.getData().isEmpty()) loading.setVisibility(View.VISIBLE);
        final String cursor = refreshMode ? null : nextCursor;
        job = api.getConnections(followers, 20, cursor, new CommunityClient.Callback<ConnectionPage>() {
            public void onSuccess(ConnectionPage page) {
                if (!alive(generation)) return;
                inFlight = false; refresh.setRefreshing(false); loading.setVisibility(View.GONE);
                if (refreshMode) adapter.setData(page.users);
                else adapter.addData(page.users);
                nextCursor = page.nextCursor;
                hasMore = page.hasMore && nextCursor != null && !nextCursor.equals(cursor);
                if (adapter.getData().isEmpty()) { empty.setVisibility(View.VISIBLE); list.setVisibility(View.GONE); }
                else { empty.setVisibility(View.GONE); list.setVisibility(View.VISIBLE); }
                readSucceeded();
            }
            public void onFailure(String message) {
                if (!alive(generation)) return;
                inFlight = false; refresh.setRefreshing(false); loading.setVisibility(View.GONE);
                if (adapter.getData().isEmpty()) {
                    status.setText(message); status.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
                } else {
                    retry.setVisibility(View.VISIBLE);
                    Toast.makeText(ConnectionsActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override protected void setActionsEnabled(boolean enabled) {
        if (adapter != null) adapter.setActionsEnabled(enabled);
    }
}
