package com.PixelFlow.app.ButtonFragment;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.PixelFlow.app.API.CommunityClient;
import com.PixelFlow.app.Streams.FeedCache;
import com.PixelFlow.app.Streams.FeedItems;
import java.util.Collections;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.PixelFlow.app.R;
import com.PixelFlow.app.Streams.PreloadManager;

import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.ApiResponse;
import com.PixelFlow.app.API.PostItem;
import android.os.Parcelable;
import com.PixelFlow.app.Streams.WaterfallAdapter;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.material.tabs.TabLayout;
import java.util.ArrayList;

import java.util.List;


public class HomeFragment extends Fragment {

    // 滚动监听接口
    public interface OnScrollListener {
        void onScrollDown();    // 向下滚动
        void onScrollUp();      // 向上滚动
        void onScrollToTop();   // 滚动到顶部
    }

    private static final int FOLLOWING_TAB = 0;
    private static final String SAVED_TAB = "home.selectedTab";
    private int selectedTab = FOLLOWING_TAB;
    private static final class FeedState {
        List<WorkItem> items = new ArrayList<>();
        String cursor;
        boolean hasMore = true, initialized, paused, retryRefresh;
        String error;
        Parcelable scroll;
    }
    private final FeedState[] feeds = {new FeedState(), new FeedState()};
    private String feedError;
    private TabLayout tabLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private View emptyStateLayout;
    private View pageStatus;
    private TextView pageMessage;
    private Button pageRetry;
    private boolean paginationPaused;
    private boolean retryRefresh;

    private WaterfallAdapter adapter;
    private boolean isRefreshing = false;
    private boolean hasMoreData = true;
    private int currentPage = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CommunityClient.Job activeFeedCall;
    private String nextCursor;
    private String displayedAccount;
    private int feedGeneration;
    private boolean requestInFlight;
    private OnScrollListener scrollListener;

    // 滚动状态跟踪
    private int lastScrollY = 0;
    private boolean isScrollingDown = false;
    private final int SCROLL_THRESHOLD = 20; // 滚动阈值，避免频繁触发

    private PreloadManager preloadManager;



    public static HomeFragment newInstance() {
        return new HomeFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) selectedTab = savedInstanceState.getInt(SAVED_TAB, FOLLOWING_TAB) == 1 ? 1 : FOLLOWING_TAB;

        Window window = ((Activity)getContext()).getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);

        // 让内容延伸到状态栏下面
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // 获取 Activity 的滚动监听接口
        if (getActivity() instanceof OnScrollListener) {
            scrollListener = (OnScrollListener) getActivity();
        }

        preloadManager=new PreloadManager(requireContext());


    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        initView(view);
        setupRecyclerView();
        setupSwipeRefresh();
        setupTabs();
        syncAccount();
        restoreFeedState();
        return view;
    }

    private void initView(View view) {
        tabLayout = view.findViewById(R.id.tab_layout);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        recyclerView = view.findViewById(R.id.recycler_view);
        emptyStateLayout = view.findViewById(R.id.empty_state_layout);
        pageStatus = view.findViewById(R.id.feed_page_status);
        pageMessage = view.findViewById(R.id.feed_page_message);
        pageRetry = view.findViewById(R.id.feed_page_retry);
        pageRetry.setOnClickListener(v -> {
            paginationPaused = false;
            loadData(retryRefresh);
        });
        Button retryButton = view.findViewById(R.id.retry_button);

        retryButton.setOnClickListener(v -> loadData(true));

        View searchEntry = view.findViewById(R.id.search_entry);
        if (searchEntry != null) {
            searchEntry.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), com.PixelFlow.app.SearchActivity.class)));
        }
    }

    private void setupRecyclerView() {
        // 设置双列瀑布流布局
        StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        // 创建适配器
        adapter = new WaterfallAdapter(requireContext());
        recyclerView.setAdapter(adapter);
        // 刷新后的卡片入场动画（下滑淡入）
        LayoutAnimationController layoutAnimation = AnimationUtils.loadLayoutAnimation(
                requireContext(), R.anim.layout_animation_slide_up);
        recyclerView.setLayoutAnimation(layoutAnimation);

        // 设置统一的回调监听器
        adapter.setOnWorkActionListener(new WaterfallAdapter.OnWorkActionListener() {
            @Override
            public void onLoadMore() {
                // 当到达底部时加载更多数据
                if (!adapter.isLoading() && hasMoreData && !paginationPaused) {
                    loadData(false);
                }
            }

            @Override
            public void onLikeClick(WorkItem workItem, int position) {
                // 未登录时点赞直接跳转登录页，不弹提示
                if (!ApiClient.getInstance(requireContext()).isLoggedIn()) {
                    startActivity(new Intent(requireContext(), com.PixelFlow.app.AccountActivity.class));
                    return;
                }
                boolean newLikeState = !workItem.isLiked();
                adapter.updateLikeStatus(position, newLikeState);
            }

            @Override
            public void onItemClick(WorkItem workItem, int position) {
                // item被点击，可以在这里做一些额外的处理
                Log.d("HomeFragment", "Item clicked: " + workItem.getTitle());
            }
        });

        // 设置Activity启动监听器
        adapter.setOnStartActivityListener(new WaterfallAdapter.OnStartActivityListener() {
            @Override
            public void onStartActivityForResult(Intent intent, int requestCode, Bundle options) {
                // 启动详情页（需要处理返回结果）
                if (options != null) {
                    startActivityForResult(intent, requestCode, options);
                } else {
                    startActivityForResult(intent, requestCode);
                }
            }
        });

        // 添加滚动监听，检测滚动方向和是否到达底部
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private int scrolledDistance = 0;
            private boolean controlsVisible = true;
            private static final int MINIMUM_SCROLL_DISTANCE = 50; // 最小滚动距离，避免小幅度滚动触发

            // 用于防抖，避免频繁触发预加载
            private long lastPreloadTime = 0;
            private static final long PRELOAD_DEBOUNCE_TIME = 500; // 500ms防抖

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                // 更新滚动距离
                scrolledDistance += dy;

                // 判断滚动方向
                boolean currentScrollingDown = dy > 0;

                // 只有当滚动方向改变并且滚动距离超过阈值时才触发
                if (currentScrollingDown != isScrollingDown && Math.abs(dy) > SCROLL_THRESHOLD) {
                    isScrollingDown = currentScrollingDown;

                    if (scrollListener != null) {
                        if (isScrollingDown) {
                            scrollListener.onScrollDown();  // 向下滚动，隐藏FAB
                        } else {
                            scrollListener.onScrollUp();    // 向上滚动，显示FAB
                        }
                    }
                }

                // 检查是否到达底部
                StaggeredGridLayoutManager layoutManager = (StaggeredGridLayoutManager) recyclerView.getLayoutManager();
                if (layoutManager == null) return;

                int[] lastVisiblePositions = layoutManager.findLastVisibleItemPositions(null);
                int lastVisibleItem = getMaxValue(lastVisiblePositions);
                int totalItemCount = layoutManager.getItemCount();

                // 当滚动到最后几个item时触发加载更多
                if (dy > 0 && !adapter.isLoading() && hasMoreData && !paginationPaused
                        && lastVisibleItem >= totalItemCount - 5) {
                    loadData(false);
                }

                // 检查是否滚动到顶部
                int[] firstVisiblePositions = layoutManager.findFirstVisibleItemPositions(null);
                int firstVisibleItem = getMinValue(firstVisiblePositions);

                if (firstVisibleItem == 0 && scrollListener != null) {
                    scrollListener.onScrollToTop();  // 滚动到顶部，显示FAB
                }

                if(preloadManager!=null){
                    long currentTime=System.currentTimeMillis();
                    if (currentTime - lastPreloadTime > PRELOAD_DEBOUNCE_TIME) {
                        if (dy > 10) { // 向下滚动明显时
                            preloadBottomItems();
                            lastPreloadTime = currentTime;
                        } else if (dy < -10) { // 向上滚动明显时
                            preloadTopItems();
                            lastPreloadTime = currentTime;
                        }
                    }
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);

                // 当停止滚动时，如果接近顶部，显示FAB
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    StaggeredGridLayoutManager layoutManager = (StaggeredGridLayoutManager) recyclerView.getLayoutManager();
                    if (layoutManager != null) {
                        int[] firstVisiblePositions = layoutManager.findFirstVisibleItemPositions(null);
                        int firstVisibleItem = getMinValue(firstVisiblePositions);

                        // 如果第一个可见项在顶部附近，显示FAB
                        if (firstVisibleItem <= 3 && scrollListener != null) {
                            scrollListener.onScrollToTop();
                        }

                        // 停止滚动时可视区预加载
                        preloadVisibleItems();
                    }
                }
            }

            private int getMinValue(int[] values) {
                int min = values[0];
                for (int value : values) {
                    if (value < min) {
                        min = value;
                    }
                }
                return min;
            }
        });
    }

    private void setupSwipeRefresh() {
        swipeRefreshLayout.setColorSchemeResources(R.color.profile_accent);

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (!isRefreshing) {
                loadData(true);
            }
        });
    }

    private void setupTabs() {
        String[] tabNames = {getString(R.string.home_following), getString(R.string.home_community)};
        for (String tabName : tabNames) {
            tabLayout.addTab(tabLayout.newTab().setText(tabName));
        }

        TabLayout.Tab restoredTab = tabLayout.getTabAt(selectedTab);
        if (restoredTab != null) restoredTab.select();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                saveFeedState();
                cancelFeedRequest();
                selectedTab = tab.getPosition();
                syncAccount();
                restoreFeedState();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                // 点击当前选中的Tab，滚动到顶部
                recyclerView.smoothScrollToPosition(0);
            }
        });
    }

    private void loadData(boolean isRefresh) {
        if (adapter == null || recyclerView == null) return;
        syncAccount();
        if (selectedTab == FOLLOWING_TAB && !ApiClient.getInstance(requireContext()).isLoggedIn()) {
            cancelFeedRequest();
            showLoginState();
            return;
        }
        if (!isRefresh && (requestInFlight || !hasMoreData || paginationPaused)) return;
        if (activeFeedCall != null) activeFeedCall.cancel();
        final int generation = ++feedGeneration;
        requestInFlight = true;
        isRefreshing = isRefresh;
        adapter.setLoading(false);
        adapter.setEndVisible(false);
        paginationPaused = false;
        hidePageStatus();
        hideEmptyState();
        if (isRefresh) swipeRefreshLayout.setRefreshing(true);
        else adapter.setLoading(true);

        final String requestedCursor = isRefresh ? null : nextCursor;
        final int source = selectedTab;
        CommunityClient.Callback<ApiResponse> callback = new CommunityClient.Callback<ApiResponse>() {
            @Override
            public void onSuccess(ApiResponse response) {
                handler.post(() -> {
                    if (!canApplyFeed(generation)) return;
                    List<WorkItem> incoming = new ArrayList<>();
                    for (PostItem post : response.getPostList()) {
                        if (post == null || post.getPostId() == null) continue;
                        incoming.add(new WorkItem(post));
                    }
                    List<WorkItem> unique = FeedItems.unique(incoming,
                            isRefresh ? Collections.emptyList() : adapter.getData());
                    if (isRefresh && source != FOLLOWING_TAB) Collections.shuffle(unique);
                    feeds[source].initialized = true;
                    feedError = null;
                    finishFeedRequest();
                    nextCursor = response.getNextCursor();
                    hasMoreData = response.getHasMore() == 1 && nextCursor != null
                            && !nextCursor.equals(requestedCursor);
                    if (isRefresh) {
                        adapter.setData(unique);
                        currentPage = 1;
                        recyclerView.scheduleLayoutAnimation();
                        // 仅社区 Tab（公开流）写磁盘缓存；关注流是个性化的，只用内存 FeedState
                        if (source != FOLLOWING_TAB) {
                            FeedCache.forAccount(requireContext(), feedCacheKey()).save(unique);
                        }
                    } else {
                        adapter.addData(unique);
                        currentPage++;
                        // Avoid an endless loading loop when a server repeats the same page.
                        if (unique.isEmpty()) hasMoreData = false;
                    }
                    if (adapter.getData().isEmpty()) showEmptyState();
                    else hideEmptyState();
                    saveFeedState();
                    showPageAvailability();
                    if (!unique.isEmpty() && preloadManager != null) {
                        preloadManager.preloadWorkItems(unique, PreloadManager.PRIORITY_LOW);
                    }
                });
            }

            @Override
            public void onFailure(String errorMessage) {
                handler.post(() -> {
                    if (!canApplyFeed(generation)) return;
                    finishFeedRequest();
                    syncAccount();
                    feeds[source].initialized = true;
                    feedError = errorMessage;
                    paginationPaused = true;
                    retryRefresh = isRefresh;
                    saveFeedState();
                    renderFeedError();
                });
            }
        };
        ApiClient api = ApiClient.getInstance(requireContext());
        activeFeedCall = source == FOLLOWING_TAB ? api.getFollowingFeed(50, requestedCursor, callback)
                : api.getFeed(50, requestedCursor, callback);
    }

    private void saveFeedState() {
        if (adapter == null) return;
        FeedState state = feeds[selectedTab];
        state.items = new ArrayList<>(adapter.getData());
        state.cursor = nextCursor;
        state.hasMore = hasMoreData;
        state.paused = paginationPaused;
        state.retryRefresh = retryRefresh;
        state.error = feedError;
        if (recyclerView.getLayoutManager() != null)
            state.scroll = recyclerView.getLayoutManager().onSaveInstanceState();
    }

    private void restoreFeedState() {
        FeedState state = feeds[selectedTab];
        nextCursor = state.cursor;
        hasMoreData = state.hasMore;
        paginationPaused = state.paused;
        retryRefresh = state.retryRefresh;
        feedError = state.error;
        adapter.setData(state.items);
        adapter.setEndVisible(false);
        hidePageStatus();
        hideEmptyState();
        if (selectedTab == FOLLOWING_TAB && !ApiClient.getInstance(requireContext()).isLoggedIn()) {
            showLoginState();
        } else if (!state.initialized) {
            // 社区 Tab 冷启动秒开：先展示磁盘缓存首屏，随后网络刷新覆盖；关注流是个性化的，不走磁盘缓存
            if (selectedTab != FOLLOWING_TAB && state.items.isEmpty()) {
                List<WorkItem> cached = FeedCache.forAccount(requireContext(), feedCacheKey()).load();
                if (cached != null && !cached.isEmpty()) {
                    adapter.setData(cached);
                    hideEmptyState();
                }
            }
            loadData(true);
        } else if (feedError != null) renderFeedError();
        else if (state.items.isEmpty()) showEmptyState();
        else {
            if (state.scroll != null) recyclerView.getLayoutManager().onRestoreInstanceState(state.scroll);
            showPageAvailability();
        }
    }

    private void cancelFeedRequest() {
        ++feedGeneration;
        if (activeFeedCall != null) activeFeedCall.cancel();
        if (adapter != null && swipeRefreshLayout != null) finishFeedRequest();
    }

    private void renderFeedError() {
        if (adapter.getData().isEmpty()) showFeedError(feedError);
        else {
            hideEmptyState();
            pageMessage.setText(getString(retryRefresh ? R.string.home_refresh_failed : R.string.home_page_failed, feedError));
            pageRetry.setText(R.string.home_retry);
            pageRetry.setVisibility(View.VISIBLE);
            pageStatus.setVisibility(View.VISIBLE);
        }
    }

    private void showLoginState() {
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_title)).setText(R.string.home_login_title);
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_message)).setText(R.string.home_login_message);
        Button button = emptyStateLayout.findViewById(R.id.retry_button);
        button.setText(R.string.home_login);
        button.setOnClickListener(v -> startActivity(new Intent(requireContext(), com.PixelFlow.app.AccountActivity.class)));
        showStatePanel();
    }

    private boolean canApplyFeed(int generation) {
        if (generation != feedGeneration || !isAdded() || recyclerView == null || adapter == null) return false;
        if (!accountKey().equals(displayedAccount)) {
            // A login/logout may complete while the old request is in flight.
            loadData(true);
            return false;
        }
        return true;
    }

    private String accountKey() {
        ApiClient api = ApiClient.getInstance(requireContext());
        return api.username() + "@" + api.sessionVersion();
    }

    /** 磁盘缓存的稳定键：账号 + 服务环境，跨进程重启稳定；会话编号不参与，避免重启后读不到缓存。 */
    private String feedCacheKey() {
        ApiClient api = ApiClient.getInstance(requireContext());
        return api.username() + "@" + api.baseUrl();
    }

    private void syncAccount() {
        String account = accountKey();
        if (!account.equals(displayedAccount)) {
            adapter.setData(Collections.emptyList());
            adapter.setEndVisible(false);
            feeds[0] = new FeedState();
            feeds[1] = new FeedState();
            nextCursor = null;
            hasMoreData = true;
            feedError = null;
            paginationPaused = false;
            hidePageStatus();
            displayedAccount = account;
        }
    }

    private void finishFeedRequest() {
        activeFeedCall = null;
        requestInFlight = false;
        isRefreshing = false;
        swipeRefreshLayout.setRefreshing(false);
        adapter.setLoading(false);
    }

    /**
     * 预加载可见项
     */
    private void preloadVisibleItems() {
        if (adapter == null || recyclerView == null || recyclerView.getLayoutManager() == null || preloadManager == null) return;

        try {
            StaggeredGridLayoutManager layoutManager = (StaggeredGridLayoutManager) recyclerView.getLayoutManager();
            List<WorkItem> visibleItems = getVisibleWorkItems(layoutManager);

            if (!visibleItems.isEmpty()) {
                preloadManager.preloadWorkItems(visibleItems, PreloadManager.PRIORITY_MEDIUM);
            }
        } catch (Exception e) {
            Log.e("HomeFragment", "预加载可见项失败: " + e.getMessage());
        }
    }

    /**
     * 预加载底部项（向下滚动时）
     */
    private void preloadBottomItems() {
        if (adapter == null || recyclerView == null || recyclerView.getLayoutManager() == null || preloadManager == null) return;

        try {
            StaggeredGridLayoutManager layoutManager = (StaggeredGridLayoutManager) recyclerView.getLayoutManager();
            int[] lastVisiblePositions = layoutManager.findLastVisibleItemPositions(null);
            int lastVisible = getMaxValue(lastVisiblePositions);

            if (lastVisible >= 0) {
                // 预加载最后可见项之后的3项
                int startIndex = lastVisible + 1;
                int endIndex = Math.min(startIndex + 2, adapter.getItemCount() - 1);

                List<WorkItem> itemsToPreload = new ArrayList<>();
                for (int i = startIndex; i <= endIndex; i++) {
                    WorkItem item = adapter.getItemAtPosition(i);
                    if (item != null) {
                        itemsToPreload.add(item);
                    }
                }

                preloadManager.preloadWorkItems(itemsToPreload, PreloadManager.PRIORITY_LOW);
            }
        } catch (Exception e) {
            Log.e("HomeFragment", "预加载底部项失败: " + e.getMessage());
        }
    }

    /**
     * 预加载顶部项（向上滚动时）
     */
    private void preloadTopItems() {
        if (adapter == null || recyclerView == null || recyclerView.getLayoutManager() == null || preloadManager == null) return;

        try {
            StaggeredGridLayoutManager layoutManager = (StaggeredGridLayoutManager) recyclerView.getLayoutManager();
            int[] firstVisiblePositions = layoutManager.findFirstVisibleItemPositions(null);
            int firstVisible = getMinValue(firstVisiblePositions);

            if (firstVisible >= 0) {
                // 预加载最先可见项之前的3项
                int endIndex = firstVisible - 1;
                int startIndex = Math.max(0, endIndex - 2);

                List<WorkItem> itemsToPreload = new ArrayList<>();
                for (int i = startIndex; i <= endIndex; i++) {
                    WorkItem item = adapter.getItemAtPosition(i);
                    if (item != null) {
                        itemsToPreload.add(item);
                    }
                }

                preloadManager.preloadWorkItems(itemsToPreload, PreloadManager.PRIORITY_LOW);
            }
        } catch (Exception e) {
            Log.e("HomeFragment", "预加载顶部项失败: " + e.getMessage());
        }
    }

    /**
     * 获取可见的WorkItem列表
     */
    private List<WorkItem> getVisibleWorkItems(StaggeredGridLayoutManager layoutManager) {
        List<WorkItem> visibleItems = new ArrayList<>();

        int[] firstVisiblePositions = layoutManager.findFirstVisibleItemPositions(null);
        int[] lastVisiblePositions = layoutManager.findLastVisibleItemPositions(null);

        int firstVisible = getMinValue(firstVisiblePositions);
        int lastVisible = getMaxValue(lastVisiblePositions);

        for (int i = firstVisible; i <= lastVisible; i++) {
            if (i >= 0 && i < adapter.getItemCount()) {
                WorkItem item = adapter.getItemAtPosition(i);
                if (item != null) {
                    visibleItems.add(item);
                }
            }
        }

        return visibleItems;
    }

    private void showEmptyState() {
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_title)).setText(R.string.home_empty_title);
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_message)).setText(
                selectedTab == FOLLOWING_TAB ? R.string.home_empty_following : R.string.home_empty_community);
        Button button = emptyStateLayout.findViewById(R.id.retry_button);
        button.setText(selectedTab == FOLLOWING_TAB ? R.string.home_go_community : R.string.home_refresh);
        button.setOnClickListener(v -> {
            if (selectedTab == FOLLOWING_TAB) tabLayout.getTabAt(1).select();
            else loadData(true);
        });
        showStatePanel();
    }

    private void showFeedError(String message) {
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_title)).setText(R.string.home_error_title);
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_message)).setText(message);
        Button button = emptyStateLayout.findViewById(R.id.retry_button);
        button.setText(R.string.home_retry);
        button.setOnClickListener(v -> loadData(true));
        showStatePanel();
    }

    private void showStatePanel() {
        hidePageStatus();
        emptyStateLayout.setVisibility(View.VISIBLE);
        swipeRefreshLayout.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
    }

    private void hideEmptyState() {
        emptyStateLayout.setVisibility(View.GONE);
        swipeRefreshLayout.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.VISIBLE);
    }

    private void hidePageStatus() {
        if (pageStatus != null) pageStatus.setVisibility(View.GONE);
    }

    private void showPageAvailability() {
        if (adapter.getData().isEmpty()) return;
        retryRefresh = false;
        pageRetry.setVisibility(View.GONE);
        hidePageStatus();
        // 到底后只在列表末尾显示一行“没有更多了”，不再常驻底部栏。
        adapter.setEndVisible(!hasMoreData);
        if (hasMoreData) {
            // A short first page may not scroll; fill the viewport without a manual button.
            final int generation = feedGeneration;
            recyclerView.post(() -> {
                if (generation == feedGeneration && recyclerView != null && adapter != null && isResumed() && !requestInFlight
                        && hasMoreData && !paginationPaused && !adapter.getData().isEmpty()
                        && !recyclerView.canScrollVertically(1)) {
                    loadData(false);
                }
            });
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVED_TAB, selectedTab);
    }

    @Override public void onStop() {
        saveFeedState();
        cancelFeedRequest();
        super.onStop();
    }

    private int getMaxValue(int[] values) {
        int max = values[0];
        for (int value : values) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private int getMinValue(int[] values) {
        int min = values[0];
        for (int value : values) {
            if (value < min) {
                min = value;
            }
        }
        return min;
    }

    // 处理从详情页返回的结果
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 检查是否是从WorkDetailActivity返回
        if (requestCode == WaterfallAdapter.REQUEST_CODE_DETAIL && adapter != null) {
            // 处理详情页返回的数据
            adapter.handleDetailResult(resultCode, data);
        }
    }

    @Override
    public void onDestroyView() {
        ++feedGeneration;
        if (activeFeedCall != null) activeFeedCall.cancel();
        activeFeedCall = null;
        requestInFlight = false;
        isRefreshing = false;
        handler.removeCallbacksAndMessages(null);
        if (adapter != null) adapter.release();
        if (recyclerView != null) {
            recyclerView.clearOnScrollListeners();
            recyclerView.setAdapter(null);
        }
        adapter = null;
        recyclerView = null;
        swipeRefreshLayout = null;
        tabLayout = null;
        emptyStateLayout = null;
        pageStatus = null;
        pageMessage = null;
        pageRetry = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null && (!accountKey().equals(displayedAccount)
                || (!requestInFlight && !feeds[selectedTab].initialized))) loadData(true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);

        if(preloadManager!=null){
            preloadManager.release();
            preloadManager=null;
        }
    }
}
