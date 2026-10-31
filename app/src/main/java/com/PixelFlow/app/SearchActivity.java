package com.PixelFlow.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.ApiResponse;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.Streams.FeedItems;
import com.PixelFlow.app.Streams.WaterfallAdapter;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 搜索页：关键词搜索公开作品。
 * 后端接口为 GET /api/search/posts（见 contracts/http/java-api.yaml），
 * 返回结构与社区 Feed 一致，因此结果直接复用瀑布流卡片。
 * 后端未上线时以 404 空状态提示「搜索服务即将上线」。
 */
public class SearchActivity extends AppCompatActivity implements WaterfallAdapter.OnWorkActionListener {

    private static final int PAGE_SIZE = 30;
    private static final int MAX_HISTORY = 10;
    private static final String PREFS = "search_prefs";
    private static final String KEY_HISTORY = "history";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText searchInput;
    private ImageButton clearButton;
    private ScrollView discoveryView;
    private ChipGroup historyChips;
    private ChipGroup hotChips;
    private TextView historyEmpty;
    private ProgressBar progress;
    private RecyclerView resultsView;
    private LinearLayout stateView;
    private TextView stateTitle;
    private TextView stateMessage;
    private Button retryButton;

    private WaterfallAdapter adapter;
    private CommunityClient.Job activeCall;
    private String activeQuery;
    private String nextCursor;
    private boolean hasMore;
    private boolean loading;
    private int requestGeneration;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        initViews();
        showDiscovery();
    }

    private void initViews() {
        searchInput = findViewById(R.id.et_search);
        clearButton = findViewById(R.id.btn_clear);
        discoveryView = findViewById(R.id.search_discovery);
        historyChips = findViewById(R.id.history_chips);
        hotChips = findViewById(R.id.hot_chips);
        historyEmpty = findViewById(R.id.history_empty);
        progress = findViewById(R.id.search_progress);
        resultsView = findViewById(R.id.search_results);
        stateView = findViewById(R.id.search_state);
        stateTitle = findViewById(R.id.search_state_title);
        stateMessage = findViewById(R.id.search_state_message);
        retryButton = findViewById(R.id.search_retry);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_submit).setOnClickListener(v -> submitFromInput());
        clearButton.setOnClickListener(v -> {
            searchInput.setText("");
            searchInput.requestFocus();
            showKeyboard();
        });
        findViewById(R.id.btn_clear_history).setOnClickListener(v -> {
            saveHistory(new LinkedHashSet<>());
            renderHistoryChips();
            Toast.makeText(this, R.string.search_history_cleared, Toast.LENGTH_SHORT).show();
        });
        retryButton.setOnClickListener(v -> {
            if (activeQuery != null) runSearch(activeQuery, null);
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitFromInput();
                return true;
            }
            return false;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (s.length() == 0 && activeQuery == null) showDiscovery();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        resultsView.setLayoutManager(new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL));
        adapter = new WaterfallAdapter(this);
        adapter.setOnWorkActionListener(this);
        resultsView.setAdapter(adapter);

        renderHistoryChips();
        renderHotChips();
        searchInput.requestFocus();
        showKeyboard();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
    }

    private void submitFromInput() {
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        if (query.isEmpty()) return;
        hideKeyboard();
        addHistory(query);
        renderHistoryChips();
        runSearch(query, null);
    }

    // ---------- 搜索请求 ----------

    private void runSearch(String query, String cursor) {
        if (activeCall != null) activeCall.cancel();
        final int generation = ++requestGeneration;
        loading = true;
        activeQuery = query;
        showLoading(cursor == null);
        activeCall = ApiClient.getInstance(this).searchPosts(query, PAGE_SIZE, cursor,
                new CommunityClient.Callback<ApiResponse>() {
                    @Override public void onSuccess(ApiResponse response) {
                        handler.post(() -> {
                            if (generation != requestGeneration) return;
                            loading = false;
                            List<WorkItem> incoming = new ArrayList<>();
                            if (response.getPostList() != null) {
                                for (PostItem post : response.getPostList()) {
                                    if (post == null || post.getPostId() == null) continue;
                                    incoming.add(new WorkItem(post));
                                }
                            }
                            List<WorkItem> unique = FeedItems.unique(incoming,
                                    cursor == null ? Collections.emptyList() : adapter.getData());
                            nextCursor = response.getNextCursor();
                            hasMore = response.getHasMore() == 1 && nextCursor != null
                                    && !nextCursor.equals(cursor);
                            if (cursor == null) {
                                adapter.setData(unique);
                            } else {
                                adapter.addData(unique);
                                if (unique.isEmpty()) hasMore = false;
                            }
                            adapter.setLoading(false);
                            adapter.setEndVisible(!hasMore && !adapter.getData().isEmpty());
                            if (adapter.getData().isEmpty()) showState(getString(R.string.search_empty_title),
                                    getString(R.string.search_empty_message), false);
                            else showResults();
                        });
                    }

                    @Override public void onFailure(String errorMessage) {
                        onFailure(0, errorMessage);
                    }

                    @Override public void onFailure(int status, String errorMessage) {
                        handler.post(() -> {
                            if (generation != requestGeneration) return;
                            loading = false;
                            adapter.setLoading(false);
                            if (adapter.getData().isEmpty()) {
                                boolean unavailable = status == 404 || status == 501;
                                showState(getString(unavailable ? R.string.search_unavailable : R.string.search_error_title),
                                        unavailable ? null : errorMessage, !unavailable);
                            } else {
                                Toast.makeText(SearchActivity.this, R.string.search_load_more_failed, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
    }

    // ---------- 界面状态 ----------

    private void showDiscovery() {
        activeQuery = null;
        discoveryView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        resultsView.setVisibility(View.GONE);
        stateView.setVisibility(View.GONE);
    }

    private void showLoading(boolean clearList) {
        discoveryView.setVisibility(View.GONE);
        stateView.setVisibility(View.GONE);
        resultsView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        if (clearList) {
            adapter.setData(Collections.emptyList());
            adapter.setEndVisible(false);
        }
    }

    private void showResults() {
        discoveryView.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        stateView.setVisibility(View.GONE);
        resultsView.setVisibility(View.VISIBLE);
    }

    private void showState(String title, String message, boolean retryable) {
        discoveryView.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        resultsView.setVisibility(View.GONE);
        stateView.setVisibility(View.VISIBLE);
        stateTitle.setText(title);
        stateMessage.setText(message == null ? "" : message);
        stateMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        retryButton.setVisibility(retryable ? View.VISIBLE : View.GONE);
    }

    // ---------- 历史与热门 ----------

    private Set<String> loadHistory() {
        Set<String> ordered = new LinkedHashSet<>();
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) ordered.add(array.getString(i));
        } catch (org.json.JSONException ignored) { }
        return ordered;
    }

    private void saveHistory(Set<String> history) {
        JSONArray array = new JSONArray();
        for (String entry : history) array.put(entry);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HISTORY, array.toString()).apply();
    }

    private void addHistory(String query) {
        Set<String> history = loadHistory();
        history.remove(query);
        history.add(query);
        while (history.size() > MAX_HISTORY) {
            String first = history.iterator().next();
            history.remove(first);
        }
        saveHistory(history);
    }

    private void renderHistoryChips() {
        historyChips.removeAllViews();
        Set<String> history = loadHistory();
        historyEmpty.setVisibility(history.isEmpty() ? View.VISIBLE : View.GONE);
        List<String> recent = new ArrayList<>(history);
        Collections.reverse(recent); // 最新在前
        for (String entry : recent) {
            Chip chip = new Chip(this);
            chip.setText(entry);
            chip.setCheckable(false);
            chip.setClickable(true);
            chip.setChipBackgroundColorResource(R.color.profile_soft);
            chip.setTextColor(getColor(R.color.profile_ink));
            chip.setTextSize(13);
            chip.setCloseIconResource(R.drawable.ic_close);
            chip.setCloseIconVisible(true);
            chip.setCloseIconTintResource(R.color.profile_muted);
            chip.setOnClickListener(v -> {
                searchInput.setText(entry);
                searchInput.setSelection(entry.length());
                submitFromInput();
            });
            chip.setOnCloseIconClickListener(v -> {
                Set<String> updated = loadHistory();
                updated.remove(entry);
                saveHistory(updated);
                renderHistoryChips();
            });
            historyChips.addView(chip);
        }
    }

    private void renderHotChips() {
        hotChips.removeAllViews();
        String[] hot = getResources().getStringArray(R.array.search_hot_default);
        for (String entry : hot) {
            Chip chip = new Chip(this);
            chip.setText(entry);
            chip.setCheckable(false);
            chip.setClickable(true);
            chip.setChipBackgroundColorResource(R.color.profile_soft);
            chip.setTextColor(getColor(R.color.profile_ink));
            chip.setTextSize(13);
            chip.setOnClickListener(v -> {
                searchInput.setText(entry);
                searchInput.setSelection(entry.length());
                submitFromInput();
            });
            hotChips.addView(chip);
        }
    }

    // ---------- 瀑布流回调 ----------

    @Override public void onLoadMore() {
        if (loading || !hasMore || activeQuery == null) return;
        adapter.setLoading(true);
        runSearch(activeQuery, nextCursor);
    }

    @Override public void onLikeClick(WorkItem workItem, int position) {
        // 复用信息流卡片：未登录时跳转登录页，与首页行为一致由适配器内部处理。
    }

    @Override public void onItemClick(WorkItem workItem, int position) {
        // 详情页跳转由 WaterfallAdapter 内部完成。
    }
}
