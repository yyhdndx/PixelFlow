package com.PixelFlow.app;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.ProfileHistoryPage;
import com.PixelFlow.app.Streams.HistoryAdapter;

/** Paged history-profile snapshots (before_version cursor). Empty is a normal state, not an error. */
public class HistoryProfileActivity extends AccountDataActivity {
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private View loading, empty, retry;
    private TextView status;
    private HistoryAdapter adapter;
    private String nextCursor;
    private boolean hasMore = true;
    private boolean inFlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_history_profile);
        findViewById(R.id.history_back).setOnClickListener(v -> finish());
        refresh = findViewById(R.id.history_refresh);
        list = findViewById(R.id.history_list);
        loading = findViewById(R.id.history_loading);
        empty = findViewById(R.id.history_empty);
        status = findViewById(R.id.history_status);
        retry = findViewById(R.id.history_retry);
        refresh.setColorSchemeResources(R.color.profile_accent);
        refresh.setOnRefreshListener(() -> load(true));
        retry.setOnClickListener(v -> load(true));
        adapter = new HistoryAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!hasMore || inFlight) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && adapter.size() > 0 && lm.findLastVisibleItemPosition() >= adapter.size() - 3)
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
        if (adapter.size() == 0) loading.setVisibility(View.VISIBLE);
        final String cursor = refreshMode ? null : nextCursor;
        job = api.getProfileHistory(20, cursor, new CommunityClient.Callback<ProfileHistoryPage>() {
            public void onSuccess(ProfileHistoryPage page) {
                if (!alive(generation)) return;
                inFlight = false; refresh.setRefreshing(false); loading.setVisibility(View.GONE);
                if (refreshMode) adapter.setData(page.getItems());
                else adapter.addData(page.getItems());
                nextCursor = page.getNextCursor();
                hasMore = page.isHasMore() && nextCursor != null && !nextCursor.equals(cursor);
                if (adapter.size() == 0) { empty.setVisibility(View.VISIBLE); list.setVisibility(View.GONE); }
                else { empty.setVisibility(View.GONE); list.setVisibility(View.VISIBLE); }
                readSucceeded();
            }
            public void onFailure(String message) {
                if (!alive(generation)) return;
                inFlight = false; refresh.setRefreshing(false); loading.setVisibility(View.GONE);
                if (adapter.size() == 0) {
                    status.setText(message); status.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
                } else {
                    retry.setVisibility(View.VISIBLE);
                    Toast.makeText(HistoryProfileActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

}
