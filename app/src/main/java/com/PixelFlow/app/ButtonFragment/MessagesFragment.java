package com.PixelFlow.app.ButtonFragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.NotificationPage;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.NotificationAdapter;
import com.PixelFlow.app.Streams.WorkItem;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** 消息中心：@我 / 回复 / 赞 三 Tab，支持屏蔽设置、全部已读、未读角标联动。 */
public class MessagesFragment extends Fragment {
    private static final String[] TYPES = {"MENTION", "REPLY", "LIKE"};

    private ApiClient api;
    private NotificationAdapter adapter;
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private TextView empty;
    private TextView loginView;
    private final TextView[] tabs = new TextView[3];

    private int activeTab = 0;
    private String cursor;
    private boolean hasMore;
    private boolean inFlight;
    private int generation;

    /** 已读 / 屏蔽变化后通知宿主刷新底部导航角标。 */
    public interface OnMessagesChangedListener { void onMessagesChanged(); }

    public static MessagesFragment newInstance() { return new MessagesFragment(); }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_messages, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        api = ApiClient.getInstance(requireContext());

        refresh = view.findViewById(R.id.messages_refresh);
        list = view.findViewById(R.id.messages_list);
        empty = view.findViewById(R.id.messages_empty);
        loginView = view.findViewById(R.id.messages_login);
        tabs[0] = view.findViewById(R.id.messages_tab_mention);
        tabs[1] = view.findViewById(R.id.messages_tab_reply);
        tabs[2] = view.findViewById(R.id.messages_tab_like);

        adapter = new NotificationAdapter(requireContext());
        adapter.setOnNotificationClickListener(this::openPost);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) return;
                LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (manager != null && manager.findLastVisibleItemPosition() >= adapter.getItemCount() - 3)
                    loadMore();
            }
        });

        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            tabs[i].setOnClickListener(v -> switchTab(index));
        }

        refresh.setOnRefreshListener(() -> reload());
        refresh.setColorSchemeResources(R.color.profile_accent);

        view.findViewById(R.id.messages_mark_read).setOnClickListener(v -> markAllRead());
        view.findViewById(R.id.messages_settings).setOnClickListener(v -> showSettings());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!api.isLoggedIn()) {
            showLoginRequired();
        } else {
            loginView.setVisibility(View.GONE);
            list.setVisibility(View.VISIBLE);
            reload();
        }
    }

    private void showLoginRequired() {
        adapter.setData(Arrays.asList());
        empty.setVisibility(View.GONE);
        list.setVisibility(View.GONE);
        loginView.setVisibility(View.VISIBLE);
    }

    private void switchTab(int index) {
        if (index == activeTab) return;
        activeTab = index;
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == index;
            tabs[i].setBackgroundResource(selected ? R.drawable.bg_ai_candidate_on : 0);
            tabs[i].setTextColor(requireContext().getColor(selected ? R.color.white : R.color.black));
        }
        reload();
    }

    private void reload() {
        if (!api.isLoggedIn()) { showLoginRequired(); return; }
        final int gen = ++generation;
        inFlight = true;
        api.getNotifications(TYPES[activeTab], null, new ApiClient.Callback<NotificationPage>() {
            @Override public void onSuccess(NotificationPage page) {
                if (!alive(gen)) return;
                inFlight = false;
                refresh.setRefreshing(false);
                cursor = page.nextCursor;
                hasMore = page.hasMore;
                adapter.setData(page.items);
                updateEmpty();
            }
            @Override public void onFailure(String message) {
                if (!alive(gen)) return;
                inFlight = false;
                refresh.setRefreshing(false);
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                updateEmpty();
            }
        });
    }

    private void loadMore() {
        if (inFlight || !hasMore || cursor == null || !api.isLoggedIn()) return;
        final int gen = generation;
        inFlight = true;
        api.getNotifications(TYPES[activeTab], cursor, new ApiClient.Callback<NotificationPage>() {
            @Override public void onSuccess(NotificationPage page) {
                if (!alive(gen)) return;
                inFlight = false;
                cursor = page.nextCursor;
                hasMore = page.hasMore;
                adapter.addData(page.items);
            }
            @Override public void onFailure(String message) {
                if (!alive(gen)) return;
                inFlight = false;
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateEmpty() {
        boolean isEmpty = adapter.getItemCount() == 0;
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        if (isEmpty) {
            int res = activeTab == 1 ? R.string.messages_empty_reply
                    : activeTab == 2 ? R.string.messages_empty_like
                    : R.string.messages_empty_mention;
            empty.setText(res);
        }
    }

    private void markAllRead() {
        if (!api.isLoggedIn() || inFlight) return;
        final int gen = generation;
        inFlight = true;
        api.markNotificationsRead(new ApiClient.Callback<Void>() {
            @Override public void onSuccess(Void value) {
                if (!alive(gen)) return;
                inFlight = false;
                reload();
                notifyChanged();
            }
            @Override public void onFailure(String message) {
                if (!alive(gen)) return;
                inFlight = false;
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showSettings() {
        if (!api.isLoggedIn()) return;
        final int gen = generation;
        api.getNotificationPreferences(new ApiClient.Callback<Set<String>>() {
            @Override public void onSuccess(Set<String> muted) {
                if (!alive(gen)) return;
                presentSettings(new LinkedHashSet<>(muted));
            }
            @Override public void onFailure(String message) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void presentSettings(Set<String> muted) {
        View content = LayoutInflater.from(getContext()).inflate(R.layout.dialog_notification_settings, null);
        CheckBox mention = content.findViewById(R.id.ns_mention);
        CheckBox reply = content.findViewById(R.id.ns_reply);
        CheckBox like = content.findViewById(R.id.ns_like);
        mention.setChecked(muted.contains("MENTION"));
        reply.setChecked(muted.contains("REPLY"));
        like.setChecked(muted.contains("LIKE"));

        new android.app.AlertDialog.Builder(getContext())
                .setTitle(R.string.messages_settings)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Set<String> next = new LinkedHashSet<>();
                    if (mention.isChecked()) next.add("MENTION");
                    if (reply.isChecked()) next.add("REPLY");
                    if (like.isChecked()) next.add("LIKE");
                    saveSettings(next);
                })
                .show();
    }

    private void saveSettings(Set<String> muted) {
        final int gen = generation;
        api.setNotificationPreferences(muted, new ApiClient.Callback<Set<String>>() {
            @Override public void onSuccess(Set<String> value) {
                if (!alive(gen)) return;
                reload();
                notifyChanged();
            }
            @Override public void onFailure(String message) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openPost(NotificationPage.NotificationItem item) {
        if (item.postId == null || item.postId.isEmpty()) return;
        api.getPost(item.postId, new ApiClient.Callback<PostItem>() {
            @Override public void onSuccess(PostItem post) {
                Intent intent = new Intent(getContext(), WorkDetailActivity.class);
                intent.putExtra("work_item", new WorkItem(post));
                startActivity(intent);
            }
            @Override public void onFailure(String message) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void notifyChanged() {
        if (getActivity() instanceof OnMessagesChangedListener)
            ((OnMessagesChangedListener) getActivity()).onMessagesChanged();
    }

    private boolean alive(int gen) { return gen == generation && isAdded(); }

    @Override
    public void onDestroyView() {
        generation++;
        super.onDestroyView();
    }
}
