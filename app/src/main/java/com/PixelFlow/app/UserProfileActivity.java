package com.PixelFlow.app;

import android.content.res.ColorStateList;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.UserProfile;
import com.PixelFlow.app.API.UserPostsPage;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.Streams.UserPostsAdapter;
import com.PixelFlow.app.Streams.WorkItem;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.button.MaterialButton;

/** Public profile and author posts; each resume rechecks visibility and viewer state. */
public class UserProfileActivity extends AccountDataActivity {
    public static final String EXTRA_USER_ID = "extra_user_id";
    private ImageView avatar;
    private TextView nickname, bio, posts, followers, following, status;
    private View loading, retry;
    private MaterialButton follow;
    private UserProfile profile;
    private String userId;
    private RecyclerView works;
    private UserPostsAdapter adapter;
    private View worksLoading, worksRetry, more, refresh, profileCard;
    private TextView worksStatus;
    private String nextCursor;
    private boolean hasMore, inFlight, actionsEnabled;
    private int pageVersion;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_user_profile);
        userId = getIntent().getStringExtra(EXTRA_USER_ID);
        avatar = findViewById(R.id.user_avatar);
        nickname = findViewById(R.id.user_nickname);
        bio = findViewById(R.id.user_bio);
        posts = findViewById(R.id.user_posts);
        followers = findViewById(R.id.user_followers);
        following = findViewById(R.id.user_following);
        status = findViewById(R.id.user_status);
        loading = findViewById(R.id.user_loading);
        retry = findViewById(R.id.user_retry);
        follow = findViewById(R.id.user_follow);
        profileCard = findViewById(R.id.user_profile_card);
        works = findViewById(R.id.user_works);
        worksLoading = findViewById(R.id.user_works_loading);
        worksStatus = findViewById(R.id.user_works_status);
        worksRetry = findViewById(R.id.user_works_retry);
        more = findViewById(R.id.user_works_more);
        refresh = findViewById(R.id.user_refresh);
        adapter = new UserPostsAdapter(this::openWork);
        works.setLayoutManager(new GridLayoutManager(this, 2));
        works.setNestedScrollingEnabled(false);
        works.setAdapter(adapter);
        more.setOnClickListener(v -> loadWorks());
        worksRetry.setOnClickListener(v -> loadWorks());
        refresh.setOnClickListener(v -> load());
        findViewById(R.id.user_back).setOnClickListener(v -> finish());
        retry.setOnClickListener(v -> load());
        follow.setOnClickListener(v -> toggleFollow());
    }

    @Override protected void reloadPage() { load(); }

    @Override protected void clearPage() {
        profile = null;
        inFlight = false; hasMore = false; nextCursor = null;
        adapter.clear();
        works.setVisibility(View.GONE);
        profileCard.setVisibility(View.GONE);
        worksLoading.setVisibility(View.GONE);
        worksStatus.setVisibility(View.GONE);
        worksRetry.setVisibility(View.GONE);
        more.setVisibility(View.GONE);
        nickname.setText(""); bio.setText("");
        posts.setText("—"); followers.setText("—"); following.setText("—");
        Glide.with(getApplicationContext()).clear(avatar);
        avatar.setImageResource(R.drawable.profile_avatar);
        follow.setEnabled(false);
        follow.setVisibility(View.INVISIBLE);
        loading.setVisibility(View.GONE);
        status.setVisibility(View.GONE); retry.setVisibility(View.GONE);
    }

    @Override protected void setActionsEnabled(boolean enabled) {
        actionsEnabled = enabled;
        if (follow != null) follow.setEnabled(enabled && profile != null);
        if (adapter != null) adapter.setActionsEnabled(enabled);
        if (more != null) more.setEnabled(enabled);
        if (worksRetry != null) worksRetry.setEnabled(enabled);
        if (refresh != null) refresh.setEnabled(enabled);
    }

    private void load() {
        if (!canLoad()) return;
        clearPage();
        if (userId == null || userId.isEmpty()) { showError(getString(R.string.author_missing)); return; }
        final int generation = pageVersion = beginRead();
        loading.setVisibility(View.VISIBLE); status.setVisibility(View.GONE); retry.setVisibility(View.GONE);
        job = api.getUserProfile(userId, new CommunityClient.Callback<UserProfile>() {
            public void onSuccess(UserProfile value) {
                if (!alive(generation)) return;
                loading.setVisibility(View.GONE);
                profile = value;
                render();
                hasMore = true;
                loadWorks();
            }
            public void onFailure(String message) {
                if (!alive(generation)) return;
                loading.setVisibility(View.GONE);
                showError(message);
            }
            public void onFailure(int code, String message) {
                onFailure(code == 404 ? getString(R.string.author_unavailable) : message);
            }
        });
    }

    private void render() {
        profileCard.setVisibility(View.VISIBLE);
        nickname.setText(profile.nickname);
        bio.setVisibility(profile.bio.isEmpty() ? View.GONE : View.VISIBLE);
        bio.setText(profile.bio);
        posts.setText(Long.toString(profile.posts));
        followers.setText(Long.toString(profile.followers));
        following.setText(Long.toString(profile.following));
        Glide.with(this).load(profile.avatar.isEmpty() ? null : profile.avatar)
                .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(true)
                .placeholder(R.drawable.profile_avatar).error(R.drawable.profile_avatar).circleCrop().into(avatar);
        updateFollow();
    }

    private void loadWorks() {
        if (!canLoad() || inFlight || profile == null || !hasMore) return;
        inFlight = true;
        final String cursor = nextCursor;
        final int generation = pageVersion = beginRead();
        worksLoading.setVisibility(View.VISIBLE);
        worksStatus.setVisibility(View.GONE); worksRetry.setVisibility(View.GONE); more.setVisibility(View.GONE);
        job = api.getUserPosts(userId, 20, cursor, new CommunityClient.Callback<UserPostsPage>() {
            public void onSuccess(UserPostsPage page) {
                if (!alive(generation)) return;
                inFlight = false; worksLoading.setVisibility(View.GONE);
                adapter.append(page.posts);
                nextCursor = page.nextCursor; hasMore = page.hasMore;
                works.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
                more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                if (!hasMore) {
                    worksStatus.setText(adapter.getItemCount() == 0 ? R.string.author_works_empty : R.string.author_works_end);
                    worksStatus.setVisibility(View.VISIBLE);
                }
                readSucceeded();
            }
            public void onFailure(String message) { onFailure(0, message); }
            public void onFailure(int code, String message) {
                if (!alive(generation)) return;
                inFlight = false; worksLoading.setVisibility(View.GONE);
                if (code == 404 || code == 401 || code == 403) {
                    // An author disabled between pages must not leave old cards or actions behind.
                    clearPage();
                    setActionsEnabled(false);
                    showError(code == 404 ? getString(R.string.author_unavailable) : message);
                    return;
                }
                // Keep a successful previous page and its cursor; retry only the failed page.
                worksStatus.setText(message); worksStatus.setVisibility(View.VISIBLE);
                worksRetry.setVisibility(View.VISIBLE);
                readSucceeded();
            }
        });
    }

    private void openWork(PostItem post) {
        if (!actionsEnabled || !canLoad() || !alive(pageVersion)) return;
        Intent intent = new Intent(this, WorkDetailActivity.class);
        intent.putExtra("work_item", new WorkItem(post));
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void updateFollow() {
        follow.setVisibility(View.VISIBLE);
        follow.setText(profile.isFollowing ? R.string.connection_following : R.string.connection_follow);
        follow.setBackgroundTintList(ColorStateList.valueOf(
                getColor(profile.isFollowing ? R.color.profile_soft : R.color.profile_accent)));
        follow.setTextColor(getColor(profile.isFollowing ? R.color.profile_accent : R.color.white));
    }

    private void toggleFollow() {
        if (profile == null || !beginWrite()) return;
        api.setFollow(profile.id, !profile.isFollowing, writeResult(null));
    }

    private void showError(String message) {
        loading.setVisibility(View.GONE);
        status.setText(message); status.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
        refresh.setEnabled(true);
    }

}
