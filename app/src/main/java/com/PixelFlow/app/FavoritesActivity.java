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
import com.PixelFlow.app.API.ApiResponse;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.Streams.FavoriteAdapter;
import com.PixelFlow.app.Streams.WorkItem;

/** Server-backed favorites list (paged), with per-item remove-favorite. */
public class FavoritesActivity extends AccountDataActivity {
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private View loading, empty, retry;
    private TextView status;
    private FavoriteAdapter adapter;
    private String nextCursor;
    private boolean hasMore = true;
    private boolean inFlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_favorites);
        findViewById(R.id.favorites_back).setOnClickListener(v -> finish());
        refresh = findViewById(R.id.favorites_refresh);
        list = findViewById(R.id.favorites_list);
        loading = findViewById(R.id.favorites_loading);
        empty = findViewById(R.id.favorites_empty);
        status = findViewById(R.id.favorites_status);
        retry = findViewById(R.id.favorites_retry);
        refresh.setColorSchemeResources(R.color.profile_accent);
        refresh.setOnRefreshListener(() -> load(true));
        retry.setOnClickListener(v -> load(true));
        adapter = new FavoriteAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        adapter.setOnFavoriteListener(new FavoriteAdapter.OnFavoriteListener() {
            public void onOpen(PostItem post) { view(post); }
            public void onRemove(PostItem post) { remove(post); }
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
        job = api.getFavorites(20, cursor, new CommunityClient.Callback<ApiResponse>() {
            public void onSuccess(ApiResponse page) {
                if (!alive(generation)) return;
                inFlight = false; refresh.setRefreshing(false); loading.setVisibility(View.GONE);
                if (refreshMode) adapter.setData(page.getPostList());
                else adapter.addData(page.getPostList());
                nextCursor = page.getNextCursor();
                hasMore = page.getHasMore() == 1 && nextCursor != null && !nextCursor.equals(cursor);
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
                    Toast.makeText(FavoritesActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void remove(PostItem post) {
        if (!beginWrite()) return;
        api.setFavorite(post.getPostId(), false, writeResult("已取消收藏"));
    }

    private void view(PostItem post) {
        Intent intent = new Intent(this, WorkDetailActivity.class);
        intent.putExtra("work_item", new WorkItem(post));
        startActivity(intent);
    }

    @Override protected void setActionsEnabled(boolean enabled) {
        if (adapter != null) adapter.setActionsEnabled(enabled);
    }
}
