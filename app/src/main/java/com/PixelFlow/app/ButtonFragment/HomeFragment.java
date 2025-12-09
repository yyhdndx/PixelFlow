package com.PixelFlow.app.ButtonFragment;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

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
import com.PixelFlow.app.Streams.WaterfallAdapter;
import com.PixelFlow.app.Streams.WorkItem;
import com.google.android.material.tabs.TabLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeFragment extends Fragment {

    // 滚动监听接口
    public interface OnScrollListener {
        void onScrollDown();    // 向下滚动
        void onScrollUp();      // 向上滚动
        void onScrollToTop();   // 滚动到顶部
    }

    private TabLayout tabLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private View emptyStateLayout;

    private WaterfallAdapter adapter;
    private boolean isRefreshing = false;
    private boolean hasMoreData = true;
    private int currentPage = 0;

    private final Handler handler = new Handler();
    private OnScrollListener scrollListener;

    // 滚动状态跟踪
    private int lastScrollY = 0;
    private boolean isScrollingDown = false;
    private final int SCROLL_THRESHOLD = 20; // 滚动阈值，避免频繁触发

    private PreloadManager preloadManager;

    private Set<String> workItemSet;

    public static HomeFragment newInstance() {
        return new HomeFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        workItemSet=new HashSet<>();
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
        Button retryButton = view.findViewById(R.id.retry_button);

        retryButton.setOnClickListener(v -> loadData(true));
    }

    private void setupRecyclerView() {
        // 设置双列瀑布流布局
        StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        // 创建适配器
        adapter = new WaterfallAdapter(requireContext());
        recyclerView.setAdapter(adapter);

        // 设置统一的回调监听器
        adapter.setOnWorkActionListener(new WaterfallAdapter.OnWorkActionListener() {
            @Override
            public void onLoadMore() {
                // 当到达底部时加载更多数据
                if (!adapter.isLoading() && hasMoreData) {
                    loadData(false);
                }
            }

            @Override
            public void onLikeClick(WorkItem workItem, int position) {
                // 处理点赞点击
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
                if (!adapter.isLoading() && hasMoreData && lastVisibleItem >= totalItemCount - 5) {
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
        swipeRefreshLayout.setColorSchemeColors(
                getResources().getColor(android.R.color.holo_red_dark),
                getResources().getColor(android.R.color.holo_green_dark),
                getResources().getColor(android.R.color.holo_blue_dark)
        );

        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (!isRefreshing) {
                loadData(true);
            }
        });
    }

    private void setupTabs() {
        String[] tabNames = {"北京", "团购", "关注", "社区", "推荐", "搜索"};
        for (String tabName : tabNames) {
            tabLayout.addTab(tabLayout.newTab().setText(tabName));
        }

        // 默认显示社区
        TabLayout.Tab communityTab = tabLayout.getTabAt(3);
        if (communityTab != null) {
            communityTab.select();
        }

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                // 切换Tab时重新加载数据
                currentPage = 0;
                hasMoreData = true;
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
        if (isRefresh && isRefreshing) {
            return;
        }
        if (!isRefresh && adapter.isLoading()) {
            return;
        }

        if (isRefresh) {
            currentPage = 0;
            hasMoreData = true;
            isRefreshing = true;
            swipeRefreshLayout.setRefreshing(true);
        } else {
            adapter.setLoading(true);
        }

        /*
        大概算了一下，当前界面中最多也就是6个作品
        两页加起来就是12个
        只要保证大体上不重复就行了
         */
        final int requestCount = 12;

        ApiClient.getInstance(requireContext()).getFeed(requestCount, new ApiClient.ApiCallback() {

            @Override
            public void onSuccess(ApiResponse response) {

                if (!isAdded() || getActivity() == null) {
                    Log.w("HomeFragment", "Fragment已销毁，忽略网络响应");
                    return;
                }

                requireActivity().runOnUiThread(() -> {
                    // 将API中的数据转为WorkItem的列表中的数据
                    List<WorkItem> newData = new ArrayList<>();
                    if (response.getPostList() != null) {
                        for (PostItem itm : response.getPostList()) {
                            newData.add(new WorkItem(itm));
                        }
                    }

                    // 去重
                    newData=dropDuplicate(newData);

                    hasMoreData = response.getHasMore() == 1;

                    if (isRefresh) {
                        // 下拉刷新，已经触底加载了
                        if (newData.isEmpty()) {
                            showEmptyState(); // 刷到底了
                        } else {
                            hideEmptyState();
                            adapter.setData(newData);
                        }
                        isRefreshing = false;
                        swipeRefreshLayout.setRefreshing(false);
                    } else {
                        if (newData.isEmpty()) {
                            hasMoreData = false;
                            Toast.makeText(getContext(), "刷到底了捏亲亲，该休息一下嘞", Toast.LENGTH_SHORT).show();
                        } else {
                            adapter.addData(newData);
                        }
                        adapter.setLoading(false);
                    }
                    currentPage++;
                    adapter.setLoading(false);

                    if (!newData.isEmpty() && preloadManager != null) {
                        preloadManager.preloadWorkItems(newData, PreloadManager.PRIORITY_LOW);
                    }
                });
            }

            @Override
            public void onFailure(String errorMessage) {

                if (!isAdded() || getActivity() == null) {
                    Log.w("HomeFragment", "Fragment已销毁，忽略网络错误");
                    return;
                }

                requireActivity().runOnUiThread(() -> {
                    Log.e("HomePage", "API Fail " + errorMessage);

                    if (isRefresh) {
                        showEmptyState();
                        isRefreshing = false;
                        swipeRefreshLayout.setRefreshing(false);
                    } else {
                        adapter.setLoading(false);
                        Toast.makeText(getContext(), "页面飞走了捏？？？" + errorMessage, Toast.LENGTH_SHORT).show();
                    }

                    // 如果是首次的加载失败，尝试重试
                    if (isRefresh && adapter.getData().isEmpty()) {
                        showEmptyState();
                    }
                });
            }
        });
    }

    /**
     * 预加载可见项
     */
    private void preloadVisibleItems() {
        if (adapter == null || recyclerView.getLayoutManager() == null || preloadManager == null) return;

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
        if (adapter == null || recyclerView.getLayoutManager() == null || preloadManager == null) return;

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
        if (adapter == null || recyclerView.getLayoutManager() == null || preloadManager == null) return;

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

    private List<WorkItem> dropDuplicate(List<WorkItem> newItems){
        workItemSet.clear();
        List<WorkItem> ans=new ArrayList<>();
        for(WorkItem workItem:newItems){
            if(!workItemSet.contains(workItem.getId())) {
                workItemSet.add(workItem.getId());
                ans.add(workItem);
            }
        }
        return ans;
    }


    private void showEmptyState() {
        emptyStateLayout.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    private void hideEmptyState() {
        emptyStateLayout.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
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
        if (requestCode == WaterfallAdapter.REQUEST_CODE_DETAIL) {
            // 处理详情页返回的数据
            adapter.handleDetailResult(resultCode, data);
        }
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