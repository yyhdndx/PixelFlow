package com.PixelFlow.app;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
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

import com.PixelFlow.app.API.AgentSearchResult;
import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.ApiResponse;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.DetailPage.WorkDetailActivity;
import com.PixelFlow.app.Streams.FeedItems;
import com.PixelFlow.app.Streams.WaterfallAdapter;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private LinearLayout resultsContainer;
    private RecyclerView resultsView;
    private LinearLayout stateView;
    private TextView stateTitle;
    private TextView stateMessage;
    private Button retryButton;

    // AI 综合回答卡片（Agent 智能搜索，契约见 docs/issues/21-java-agent-搜索接口.md）
    private static final int AI_NONE = 0;
    private static final int AI_LOADING = 1;
    private static final int AI_ANSWER = 2;
    private static final int AI_DEGRADED = 3;

    private View aiCard;
    private View aiLoadingView;
    private View aiContentView;
    private View aiCloseButton;
    private TextView aiDegradedView;
    private TextView aiAnswerView;
    private TextView aiSourcesTitle;
    private LinearLayout aiSourcesView;
    private int aiState = AI_NONE;
    private boolean aiDismissed;
    private int aiGeneration;
    private AgentSearchResult aiResult;
    private CommunityClient.Job aiCall;
    private final Map<String, String> aiSessions = new HashMap<>(); // query -> session_id，追问复用

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

    @Override
    protected void onDestroy() {
        if (aiCall != null) aiCall.cancel();
        if (activeCall != null) activeCall.cancel();
        super.onDestroy();
    }

    private void initViews() {
        searchInput = findViewById(R.id.et_search);
        clearButton = findViewById(R.id.btn_clear);
        discoveryView = findViewById(R.id.search_discovery);
        historyChips = findViewById(R.id.history_chips);
        hotChips = findViewById(R.id.hot_chips);
        historyEmpty = findViewById(R.id.history_empty);
        progress = findViewById(R.id.search_progress);
        resultsContainer = findViewById(R.id.search_results_container);
        resultsView = findViewById(R.id.search_results);
        stateView = findViewById(R.id.search_state);
        stateTitle = findViewById(R.id.search_state_title);
        stateMessage = findViewById(R.id.search_state_message);
        retryButton = findViewById(R.id.search_retry);

        aiCard = findViewById(R.id.ai_card);
        aiLoadingView = findViewById(R.id.ai_loading);
        aiContentView = findViewById(R.id.ai_content);
        aiCloseButton = findViewById(R.id.ai_close);
        aiDegradedView = findViewById(R.id.ai_degraded);
        aiAnswerView = findViewById(R.id.ai_answer);
        aiSourcesTitle = findViewById(R.id.ai_sources_title);
        aiSourcesView = findViewById(R.id.ai_sources);
        aiCloseButton.setOnClickListener(v -> {
            aiDismissed = true;
            updateAiCard();
        });

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
        if (cursor == null) runAgentSearch(query);
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
        aiState = AI_NONE;
        discoveryView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.GONE);
        stateView.setVisibility(View.GONE);
    }

    private void showLoading(boolean clearList) {
        discoveryView.setVisibility(View.GONE);
        stateView.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        if (clearList) {
            adapter.setData(Collections.emptyList());
            adapter.setEndVisible(false);
        }
        updateAiCard();
    }

    private void showResults() {
        discoveryView.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        stateView.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.VISIBLE);
        updateAiCard();
    }

    private void showState(String title, String message, boolean retryable) {
        discoveryView.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        resultsContainer.setVisibility(View.GONE);
        stateView.setVisibility(View.VISIBLE);
        stateTitle.setText(title);
        stateMessage.setText(message == null ? "" : message);
        stateMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        retryButton.setVisibility(retryable ? View.VISIBLE : View.GONE);
    }

    // ---------- AI 综合回答（Agent 智能搜索，POST /api/agent/search） ----------

    private void runAgentSearch(String query) {
        if (aiCall != null) aiCall.cancel();
        final int generation = ++aiGeneration;
        aiState = AI_LOADING;
        aiDismissed = false;
        aiResult = null;
        updateAiCard();
        aiCall = ApiClient.getInstance(this).agentSearch(query, aiSessions.get(query),
                new CommunityClient.Callback<AgentSearchResult>() {
                    @Override public void onSuccess(AgentSearchResult result) {
                        handler.post(() -> {
                            if (generation != aiGeneration) return;
                            if (result != null && result.valid()) {
                                if (result.session_id != null && !result.session_id.isEmpty())
                                    aiSessions.put(query, result.session_id);
                                aiResult = result;
                                aiState = AI_ANSWER;
                            } else {
                                aiState = AI_DEGRADED;
                            }
                            updateAiCard();
                        });
                    }

                    @Override public void onFailure(String errorMessage) {
                        onFailure(0, errorMessage);
                    }

                    @Override public void onFailure(int status, String errorMessage) {
                        handler.post(() -> {
                            if (generation != aiGeneration) return;
                            // 401（未登录）/404/501（接口未部署）/502（Python 故障）/超时：
                            // 统一降级文案，普通搜索结果不受影响。
                            aiState = AI_DEGRADED;
                            updateAiCard();
                        });
                    }
                });
    }

    private void updateAiCard() {
        boolean visible = !aiDismissed && aiState != AI_NONE
                && resultsContainer.getVisibility() == View.VISIBLE;
        aiCard.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        aiLoadingView.setVisibility(aiState == AI_LOADING ? View.VISIBLE : View.GONE);
        aiDegradedView.setVisibility(aiState == AI_DEGRADED ? View.VISIBLE : View.GONE);
        aiContentView.setVisibility(aiState == AI_ANSWER ? View.VISIBLE : View.GONE);
        if (aiState == AI_ANSWER && aiResult != null) renderAiAnswer(aiResult);
    }

    private void renderAiAnswer(AgentSearchResult result) {
        aiAnswerView.setText(result.answer);
        aiSourcesView.removeAllViews();
        List<AgentSearchResult.Source> sources = result.sources == null
                ? Collections.emptyList() : result.sources;
        if (sources.isEmpty()) {
            aiSourcesTitle.setVisibility(View.GONE);
            return;
        }
        aiSourcesTitle.setVisibility(View.VISIBLE);
        int shown = 0;
        for (AgentSearchResult.Source source : sources) {
            if (source == null || source.post_id == null || source.post_id.isEmpty()) continue;
            shown++;
            aiSourcesView.addView(buildSourceRow(shown, source));
        }
        if (shown == 0) aiSourcesTitle.setVisibility(View.GONE);
    }

    private View buildSourceRow(int index, AgentSearchResult.Source source) {
        int marginStart = dp(10);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        TextView badge = new TextView(this);
        int size = dp(20);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(size, size);
        badge.setLayoutParams(badgeParams);
        badge.setGravity(Gravity.CENTER);
        badge.setText(String.valueOf(index));
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(11);
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(getColor(R.color.profile_accent));
        badge.setBackground(oval);
        row.addView(badge);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(marginStart);
        texts.setLayoutParams(textParams);

        TextView title = new TextView(this);
        title.setText(source.title == null || source.title.isEmpty()
                ? getString(R.string.search_ai_no_sources) : source.title);
        title.setTextColor(getColor(R.color.profile_ink));
        title.setTextSize(14);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);

        String author = source.author_name == null ? "" : source.author_name.trim();
        String excerpt = source.excerpt == null ? "" : source.excerpt.trim();
        String subText = author.isEmpty() ? excerpt
                : (excerpt.isEmpty() ? author : author + " · " + excerpt);
        if (!subText.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subText);
            sub.setTextColor(getColor(R.color.profile_muted));
            sub.setTextSize(12);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(sub);
        }
        row.addView(texts);
        row.setOnClickListener(v -> openSourceDetail(source));
        return row;
    }

    private void openSourceDetail(AgentSearchResult.Source source) {
        WorkItem item = new WorkItem(source.post_id, null, source.title, source.excerpt,
                source.author_name, null, 0, 0, 0);
        Intent intent = new Intent(this, WorkDetailActivity.class);
        intent.putExtra("work_item", item);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
