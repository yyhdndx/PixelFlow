package com.PixelFlow.app.ButtonFragment;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.PixelFlow.app.API.CommunityClient;
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
import com.PixelFlow.app.API.RecommendationFeed;
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

    private static final int RECOMMENDATION_TAB = 1;
    private static final String SAVED_RECOMMENDATION = "home.recommendation";
    private TabLayout tabLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private View emptyStateLayout;
    private TextView recommendationBanner;
    private boolean recommendationMode;
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
        if (savedInstanceState != null) recommendationMode = savedInstanceState.getBoolean(SAVED_RECOMMENDATION);

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
        loadData(true);
        return view;
    }

    private void initView(View view) {
        tabLayout = view.findViewById(R.id.tab_layout);
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh_layout);
        recyclerView = view.findViewById(R.id.recycler_view);
        emptyStateLayout = view.findViewById(R.id.empty_state_layout);
        recommendationBanner = view.findViewById(R.id.rec_banner);
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
        String[] tabNames = {getString(R.string.home_community), getString(R.string.home_recommendations)};
        for (String tabName : tabNames) {
            tabLayout.addTab(tabLayout.newTab().setText(tabName));
        }

        TabLayout.Tab selectedTab = tabLayout.getTabAt(recommendationMode ? RECOMMENDATION_TAB : 0);
        if (selectedTab != null) {
            selectedTab.select();
        }

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                // 切换Tab时重新加载数据
                recommendationMode = tab.getPosition() == RECOMMENDATION_TAB;
                currentPage = 0;
                nextCursor = null;
                hasMoreData = !recommendationMode;
                paginationPaused = false;
                hidePageStatus();
                adapter.setLoading(false);
                adapter.setEndVisible(false);
                adapter.setData(Collections.emptyList());
                hideRecommendationBanner();
                hideEmptyState();
                loadData(true);
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
        if (recommendationMode) { loadRecommendations(isRefresh); return; }
        if (adapter == null || recyclerView == null) return;
        if (!isRefresh && (requestInFlight || !hasMoreData || paginationPaused)) return;
        if (activeFeedCall != null) activeFeedCall.cancel();
        final int generation = ++feedGeneration;
        requestInFlight = true;
        isRefreshing = isRefresh;
        adapter.setLoading(false);
        adapter.setEndVisible(false);
        syncAccount();
        paginationPaused = false;
        hidePageStatus();
        hideEmptyState();
        if (isRefresh) swipeRefreshLayout.setRefreshing(true);
        else adapter.setLoading(true);

        final String requestedCursor = isRefresh ? null : nextCursor;
        activeFeedCall = ApiClient.getInstance(requireContext()).getFeed(50, requestedCursor,
                new CommunityClient.Callback<ApiResponse>() {
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
                    // 刷新后打乱卡片顺序，让每次下拉都呈现不同排布
                    if (isRefresh) Collections.shuffle(unique);
                    finishFeedRequest();
                    nextCursor = response.getNextCursor();
                    hasMoreData = response.getHasMore() == 1 && nextCursor != null
                            && !nextCursor.equals(requestedCursor);
                    if (isRefresh) {
                        adapter.setData(unique);
                        currentPage = 1;
                        recyclerView.scheduleLayoutAnimation();
                    } else {
                        adapter.addData(unique);
                        currentPage++;
                        // Avoid an endless loading loop when a server repeats the same page.
                        if (unique.isEmpty()) hasMoreData = false;
                    }
                    if (adapter.getData().isEmpty()) showEmptyState();
                    else hideEmptyState();
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
                    if (adapter.getData().isEmpty()) showFeedError(errorMessage);
                    else {
                        hideEmptyState();
                        paginationPaused = true;
                        retryRefresh = isRefresh;
                        pageMessage.setText(getString(isRefresh ? R.string.home_refresh_failed : R.string.home_page_failed, errorMessage));
                        pageRetry.setText(R.string.home_retry);
                        pageRetry.setVisibility(View.VISIBLE);
                        pageStatus.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void loadRecommendations(boolean isRefresh) {
        if (adapter == null || recyclerView == null) return;
        if (!isRefresh) return; // Single page; retries are explicit refreshes.
        if (activeFeedCall != null) activeFeedCall.cancel();
        final int generation = ++feedGeneration;
        requestInFlight = true;
        isRefreshing = isRefresh;
        adapter.setLoading(false);
        adapter.setEndVisible(false);
        syncAccount();
        paginationPaused = false;
        hidePageStatus();
        hasMoreData = false;
        hideEmptyState();
        hideRecommendationBanner();
        if (isRefresh) swipeRefreshLayout.setRefreshing(true);
        activeFeedCall = ApiClient.getInstance(requireContext()).getRecommendationFeed(
                new CommunityClient.Callback<RecommendationFeed>() {
                    @Override public void onSuccess(RecommendationFeed feed) {
                        handler.post(() -> {
                            if (!canApplyFeed(generation)) return;
                            List<WorkItem> incoming = new ArrayList<>();
                            for (PostItem post : feed.getPostList()) {
                                if (post == null || post.getPostId() == null) continue;
                                incoming.add(new WorkItem(post));
                            }
                            finishFeedRequest();
                            nextCursor = null;
                            hasMoreData = false; // 推荐单页，无分页
                            adapter.setData(incoming);
                            showRecommendationBanner(feed);
                            if (adapter.getData().isEmpty()) showEmptyState();
                            else hideEmptyState();
                        });
                    }
                    @Override public void onFailure(String errorMessage) {
                        onFailure(0, errorMessage);
                    }
                    @Override public void onFailure(int status, String errorMessage) {
                        handler.post(() -> {
                            if (!canApplyFeed(generation)) return;
                            finishFeedRequest();
                            syncAccount();
                            adapter.setData(Collections.emptyList());
                            nextCursor = null;
                            hasMoreData = false;
                            hideRecommendationBanner();
                            showFeedError(status == 404 ? getString(R.string.home_recommendations_unavailable) : errorMessage);
                        });
                    }
                });
    }

    private void showRecommendationBanner(RecommendationFeed feed) {
        if (recommendationBanner == null) return;
        String label = "popular".equals(feed.getStrategy())
                ? getString(R.string.rec_popular) : getString(R.string.rec_personalized);
        String reason = feed.getReason();
        recommendationBanner.setText(reason == null || reason.isEmpty() ? label : label + " · " + reason);
        recommendationBanner.setVisibility(View.VISIBLE);
    }
    private void hideRecommendationBanner() {
        if (recommendationBanner != null) recommendationBanner.setVisibility(View.GONE);
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

    private void syncAccount() {
        String account = accountKey();
        if (!account.equals(displayedAccount)) {
            adapter.setData(Collections.emptyList());
            adapter.setEndVisible(false);
            hideRecommendationBanner();
            nextCursor = null;
            hasMoreData = !recommendationMode;
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
                recommendationMode ? R.string.home_empty_recommendations : R.string.home_empty_community);
        ((Button) emptyStateLayout.findViewById(R.id.retry_button)).setText(R.string.home_refresh);
        showStatePanel();
    }

    private void showFeedError(String message) {
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_title)).setText(R.string.home_error_title);
        ((TextView) emptyStateLayout.findViewById(R.id.feed_state_message)).setText(message);
        ((Button) emptyStateLayout.findViewById(R.id.retry_button)).setText(R.string.home_retry);
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
            recyclerView.post(() -> {
                if (isResumed() && !recommendationMode && !requestInFlight
                        && hasMoreData && !paginationPaused && !adapter.getData().isEmpty()
                        && !recyclerView.canScrollVertically(1)) {
                    loadData(false);
                }
            });
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SAVED_RECOMMENDATION, recommendationMode);
    }

    @Override public void onStop() {
        ++feedGeneration;
        if (activeFeedCall != null) activeFeedCall.cancel();
        if (adapter != null && swipeRefreshLayout != null) finishFeedRequest();
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
        recommendationBanner = null;
        pageStatus = null;
        pageMessage = null;
        pageRetry = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 仅账户变化时刷新；从详情页返回时保留滚动位置，卡片状态由 handleDetailResult 更新。
        if (adapter != null && !accountKey().equals(displayedAccount)) loadData(true);
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
