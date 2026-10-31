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
import com.PixelFlow.app.API.MyPostsPage;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.Streams.MyWorksAdapter;
import com.PixelFlow.app.Streams.WorkItem;
import java.util.ArrayList;

/** My-posts list (drafts, pending, published, rejected, withdrawn) with publish/withdraw actions. */
public class MyWorksActivity extends AccountDataActivity {
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private View loading, empty, retry;
    private TextView status;
    private MyWorksAdapter adapter;
    private String nextCursor;
    private boolean hasMore = true;
    private boolean inFlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_my_works);
        findViewById(R.id.my_works_back).setOnClickListener(v -> finish());
        refresh = findViewById(R.id.my_works_refresh);
        list = findViewById(R.id.my_works_list);
        loading = findViewById(R.id.my_works_loading);
        empty = findViewById(R.id.my_works_empty);
        status = findViewById(R.id.my_works_status);
        retry = findViewById(R.id.my_works_retry);
        refresh.setColorSchemeResources(R.color.profile_accent);
        refresh.setOnRefreshListener(() -> load(true));
        retry.setOnClickListener(v -> load(true));
        adapter = new MyWorksAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        adapter.setOnActionListener(new MyWorksAdapter.OnActionListener() {
            public void onPublish(PostItem post) { publish(post.getPostId()); }
            public void onWithdraw(PostItem post) { withdraw(post.getPostId()); }
            public void onEdit(PostItem post) { edit(post); }
            public void onView(PostItem post) { view(post); }
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
        job = api.getMyPosts(20, cursor, new CommunityClient.Callback<MyPostsPage>() {
            public void onSuccess(MyPostsPage page) {
                if (!alive(generation)) return;
                inFlight = false; refresh.setRefreshing(false); loading.setVisibility(View.GONE);
                if (refreshMode) adapter.setData(page.getPosts());
                else adapter.addData(page.getPosts());
                nextCursor = page.getNextCursor();
                hasMore = page.canLoadMore() && nextCursor != null && !nextCursor.equals(cursor);
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
                    Toast.makeText(MyWorksActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void publish(String id) {
        if (!beginWrite()) return;
        api.publishPost(id, writeResult("已提交审核"));
    }

    private void withdraw(String id) {
        if (!beginWrite()) return;
        api.withdrawPost(id, writeResult("已撤回"));
    }

    private void edit(PostItem post) {
        Intent intent = new Intent(this, CreateWorkActivity.class);
        intent.putExtra(CreateWorkActivity.EXTRA_POST_ID, post.getPostId());
        intent.putExtra(CreateWorkActivity.EXTRA_TITLE, post.getTitle() == null ? "" : post.getTitle());
        intent.putExtra(CreateWorkActivity.EXTRA_CONTENT, post.getContent() == null ? "" : post.getContent());
        intent.putStringArrayListExtra(CreateWorkActivity.EXTRA_MEDIA_IDS, mediaIds(post));
        intent.putStringArrayListExtra(CreateWorkActivity.EXTRA_MEDIA_URLS, clipUrls(post));
        startActivity(intent);
    }

    private ArrayList<String> mediaIds(PostItem post) {
        ArrayList<String> ids = new ArrayList<>();
        if (post.getMediaIds() != null) ids.addAll(post.getMediaIds());
        return ids;
    }

    private void view(PostItem post) {
        Intent intent = new Intent(this, WorkDetailActivity.class);
        intent.putExtra("work_item", new WorkItem(post));
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private ArrayList<String> clipUrls(PostItem post) {
        ArrayList<String> urls = new ArrayList<>();
        if (post.getClips() != null) {
            for (PostItem.Clip clip : post.getClips()) {
                if (clip.getType() != 1 && clip.getUrl() != null && !clip.getUrl().isEmpty()) urls.add(clip.getUrl());
            }
        }
        return urls;
    }

    @Override protected void setActionsEnabled(boolean enabled) {
        if (adapter != null) adapter.setActionsEnabled(enabled);
    }
}
