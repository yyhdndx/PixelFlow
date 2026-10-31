package com.PixelFlow.app.ButtonFragment;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.PixelFlow.app.*;
import com.PixelFlow.app.API.*;
import com.PixelFlow.app.ui.ActionSheetDialog;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** Server-backed page; never reads the legacy device-wide profile preferences. */
public class ProfileFragment extends Fragment {
    private ApiClient api;
    private CommunityClient.Job userJob, statsJob;
    private SwipeRefreshLayout refresh;
    private TextView name, username, bio, posts, followers, following, status;
    private ImageView avatar;
    private Button edit, retry;
    private View loading, insights, logout;
    private AvatarModel avatarModel;
    private int observedAvatarCompletion;
    private String avatarUrl = "";
    private int requestVersion;
    private final ActivityResultLauncher<Intent> avatarPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                boolean sameAccount = avatarModel != null && avatarModel.consumePicker();
                if (sameAccount && result.getResultCode() == Activity.RESULT_OK && result.getData() != null
                        && result.getData().getData() != null) {
                    onAvatarPicked(result.getData().getData());
                }
            });
    public static ProfileFragment newInstance() { return new ProfileFragment(); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        api = ApiClient.getInstance(requireContext());
        avatarModel = new ViewModelProvider(requireActivity()).get(AvatarModel.class);
        observedAvatarCompletion = avatarModel.completedVersion;
        refresh = view.findViewById(R.id.profile_refresh); refresh.setColorSchemeResources(R.color.profile_accent);
        name = view.findViewById(R.id.tv_user_name); username = view.findViewById(R.id.profile_username);
        bio = view.findViewById(R.id.tv_user_bio); avatar = view.findViewById(R.id.iv_avatar);
        posts = view.findViewById(R.id.tv_posts_count); followers = view.findViewById(R.id.tv_followers_count);
        following = view.findViewById(R.id.tv_following_count); status = view.findViewById(R.id.profile_status);
        edit = view.findViewById(R.id.btn_edit_profile); retry = view.findViewById(R.id.profile_retry);
        loading = view.findViewById(R.id.profile_loading); insights = view.findViewById(R.id.btn_insights);
        refresh.setOnRefreshListener(this::load); retry.setOnClickListener(v -> load());
        edit.setOnClickListener(v -> startActivity(new Intent(requireContext(), api.isLoggedIn() ? EditProfileActivity.class : AccountActivity.class)));
        logout = view.findViewById(R.id.btn_logout);
        logout.setOnClickListener(v -> confirmLogout());
        avatar.setOnClickListener(v -> onAvatarClick());
        insights.setOnClickListener(v -> startActivity(new Intent(requireContext(), InterestProfileActivity.class)));
        view.findViewById(R.id.stats_posts).setOnClickListener(v -> openMyWorks());
        view.findViewById(R.id.btn_favorites).setOnClickListener(v -> openFavorites());
        view.findViewById(R.id.stats_followers).setOnClickListener(v -> openConnections(true));
        view.findViewById(R.id.stats_following).setOnClickListener(v -> openConnections(false));
        avatarModel.changes.observe(getViewLifecycleOwner(), ignored -> {
            avatar.setEnabled(!avatarModel.isBusy());
            if (observedAvatarCompletion != avatarModel.completedVersion) {
                observedAvatarCompletion = avatarModel.completedVersion;
                if (avatarModel.belongsToSession()) {
                    // Re-read even on failure: a lost response does not prove the write failed.
                    load();
                    Toast.makeText(requireContext(), avatarModel.resultMessage, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
    @Override public void onResume() {
        super.onResume();
        // Home uses immersive media browsing; the personal page restores normal system bars.
        Window window = requireActivity().getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
        window.setStatusBarColor(requireContext().getColor(R.color.profile_bg));
        window.setNavigationBarColor(requireContext().getColor(R.color.profile_surface));
        load();
    }
    private void cancel() {
        requestVersion++;
        if (userJob != null) userJob.cancel();
        if (statsJob != null) statsJob.cancel();
        userJob = null; statsJob = null;
    }
    private boolean alive(int version) { return isAdded() && getView() != null && version == requestVersion; }
    private void clear() {
        avatarUrl = "";
        Glide.with(this).clear(avatar); avatar.setImageResource(R.drawable.profile_avatar);
        posts.setText(R.string.profile_dash); followers.setText(R.string.profile_dash); following.setText(R.string.profile_dash);
        status.setVisibility(View.GONE); retry.setVisibility(View.GONE);
    }
    private void guest() {
        clear(); name.setText(R.string.profile_guest); username.setText("PixelFlow"); bio.setText(R.string.profile_guest_bio);
        edit.setText(R.string.profile_sign_in); edit.setEnabled(true);
        insights.setEnabled(false); insights.setAlpha(0.5f); logout.setVisibility(View.GONE); finishLoading();
    }
    private void finishLoading() {
        loading.setVisibility(View.GONE); refresh.setRefreshing(false);
        if (avatarModel.belongsToSession() && avatarModel.error != null) {
            status.setText(avatarModel.error); status.setVisibility(View.VISIBLE);
        }
    }
    private void failure(String message) {
        finishLoading(); status.setText(message); status.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
    }
    private void load() {
        if (getView() == null) return;
        cancel();
        avatar.setEnabled(!avatarModel.isBusy());
        if (!api.isLoggedIn()) { guest(); return; }
        clear(); name.setText("正在读取…"); username.setText("@" + api.username()); bio.setText(R.string.profile_loading);
        edit.setText(R.string.profile_edit); edit.setEnabled(false); insights.setEnabled(true); insights.setAlpha(1f);
        logout.setVisibility(View.VISIBLE); loading.setVisibility(View.VISIBLE);
        final int version = requestVersion;
        final long session = api.sessionVersion();
        userJob = api.getCurrentUser(new CommunityClient.Callback<CurrentUser>() {
            public void onSuccess(CurrentUser value) {
                if (!alive(version)) return;
                name.setText(value.nickname); username.setText("@" + value.username);
                bio.setText(value.bio.isEmpty() ? getString(R.string.profile_bio_empty) : value.bio);
                avatarUrl = value.avatar;
                Glide.with(ProfileFragment.this).load(value.avatar.isEmpty() ? null : value.avatar)
                        .placeholder(R.drawable.profile_avatar).error(R.drawable.profile_avatar).circleCrop().into(avatar);
                edit.setEnabled(true);
                statsJob = api.getUserStats(value.id, new CommunityClient.Callback<UserStats>() {
                    public void onSuccess(UserStats stats) {
                        if (!alive(version)) return;
                        posts.setText(Long.toString(stats.posts)); followers.setText(Long.toString(stats.followers));
                        following.setText(Long.toString(stats.following)); finishLoading();
                    }
                    public void onFailure(String message) { failed(message, true); }
                });
            }
            public void onFailure(String message) { failed(message, false); }
            private void failed(String message, boolean partial) {
                if (!alive(version)) return;
                if (!api.isLoggedIn()) { guest(); status.setText("登录已失效，请重新登录"); status.setVisibility(View.VISIBLE); }
                else if (api.sessionVersion() != session) load();
                else {
                    if (!partial) { name.setText("资料暂未加载"); bio.setText("重新加载后即可编辑个人资料。"); }
                    failure((partial ? "统计暂时无法读取。" : "") + message);
                }
            }
        });
    }
    private void onAvatarClick() {
        if (!api.isLoggedIn()) { startActivity(new Intent(requireContext(), AccountActivity.class)); return; }
        if (avatarModel.isBusy()) return;
        final long session = api.sessionVersion();
        final String owner = api.username();
        List<ActionSheetDialog.Option> options = new ArrayList<>();
        options.add(new ActionSheetDialog.Option(R.drawable.ic_photo,
                getString(R.string.choose_from_gallery), this::openAvatarPickerGuarded));
        if (!avatarUrl.isEmpty()) {
            options.add(new ActionSheetDialog.Option(R.drawable.ic_edit_note,
                    getString(R.string.restore_default_avatar), this::clearAvatarGuarded));
        }
        // 打开时校验会话，选项点击时再复核一次，防止跨账号误操作
        sheetSession = session;
        sheetOwner = owner;
        ActionSheetDialog.show(requireContext(), getString(R.string.change_avatar), null, options);
    }

    private long sheetSession;
    private String sheetOwner;

    private boolean sheetSessionValid() {
        return api.isLoggedIn() && api.sessionVersion() == sheetSession && sheetOwner.equals(api.username());
    }

    private void openAvatarPickerGuarded() {
        if (!sheetSessionValid()) return;
        openAvatarPicker();
    }

    private void clearAvatarGuarded() {
        if (!sheetSessionValid()) return;
        clearAvatar();
    }
    private void openAvatarPicker() {
        if (!avatarModel.preparePicker()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        avatarPicker.launch(intent);
    }
    private void onAvatarPicked(Uri uri) {
        Toast.makeText(requireContext(), R.string.uploading_avatar, Toast.LENGTH_SHORT).show();
        avatarModel.replace(uri);
    }
    private void clearAvatar() {
        avatarModel.clear();
    }
    private void confirmLogout() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.profile_logout)
                .setMessage(R.string.profile_logout_desc)
                .setNegativeButton(R.string.profile_cancel, null)
                .setPositiveButton(R.string.profile_logout, (d, w) -> {
                    api.logout(new CommunityClient.Callback<Void>() {
                        public void onSuccess(Void value) { }
                        public void onFailure(String message) { }
                    });
                    load();
                })
                .show();
    }
    private void openConnections(boolean followers) {
        if (!api.isLoggedIn()) { startActivity(new Intent(requireContext(), AccountActivity.class)); return; }
        Intent intent = new Intent(requireContext(), ConnectionsActivity.class);
        intent.putExtra(ConnectionsActivity.EXTRA_FOLLOWERS, followers);
        startActivity(intent);
    }
    private void openMyWorks() {
        if (!api.isLoggedIn()) { startActivity(new Intent(requireContext(), AccountActivity.class)); return; }
        startActivity(new Intent(requireContext(), MyWorksActivity.class));
    }
    private void openFavorites() {
        if (!api.isLoggedIn()) { startActivity(new Intent(requireContext(), AccountActivity.class)); return; }
        startActivity(new Intent(requireContext(), FavoritesActivity.class));
    }
    @Override public void onStop() { cancel(); super.onStop(); }
    @Override public void onDestroyView() {
        cancel(); if (avatar != null) Glide.with(this).clear(avatar);
        refresh = null; name = username = bio = posts = followers = following = status = null;
        avatar = null; edit = retry = null; loading = insights = logout = null;
        super.onDestroyView();
    }
}
