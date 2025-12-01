package com.PixelFlow.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.PixelFlow.app.API.ApiClient;
import com.PixelFlow.app.API.ApiResponse;
import com.PixelFlow.app.API.PostItem;
import com.PixelFlow.app.stream.WaterfallAdapter;
import com.PixelFlow.app.stream.WorkItem;
import com.google.android.material.tabs.TabLayout;
import java.util.ArrayList;
import java.util.List;

public class HomeFragment extends Fragment {

    private TabLayout tabLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private View emptyStateLayout;
    private Button retryButton;

    private WaterfallAdapter adapter;
    private boolean isRefreshing = false;
    private boolean hasMoreData = true;
    private int currentPage = 0;

    private Handler handler = new Handler();

    public static HomeFragment newInstance() {
        return new HomeFragment();
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
        retryButton = view.findViewById(R.id.retry_button);

        retryButton.setOnClickListener(v -> loadData(true));
    }

    private void setupRecyclerView() {
        // 设置双列瀑布流布局
        StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        recyclerView.setLayoutManager(layoutManager);

        // 创建适配器
        adapter = new WaterfallAdapter(requireContext());
        recyclerView.setAdapter(adapter);

        // 设置统一的回调监听器 - 替换原来的多个监听器
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

        // 设置Activity启动监听器 - 用于启动详情页
        adapter.setOnStartActivityListener(new WaterfallAdapter.OnStartActivityListener() {
            @Override
            public void onStartActivityForResult(Intent intent, int requestCode) {
                // 启动详情页（需要处理返回结果）
                startActivityForResult(intent, requestCode);
            }
        });

        // 添加滚动监听，检测是否到达底部
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                StaggeredGridLayoutManager layoutManager = (StaggeredGridLayoutManager) recyclerView.getLayoutManager();
                if (layoutManager == null) return;

                // 获取最后的可见项的位置
                int[] lastVisiblePositions = layoutManager.findLastVisibleItemPositions(null);
                int lastVisibleItem = getMaxValue(lastVisiblePositions);
                int totalItemCount = layoutManager.getItemCount();

                // 当滚动到最后几个item时触发加载更多
                if (!adapter.isLoading() && hasMoreData && lastVisibleItem >= totalItemCount - 5) {
                    loadData(false);
                }
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

        final int requestCount = 10;
        final boolean shouldAcceptVideoClip = false;

        ApiClient.getInstance(requireContext()).getFeed(requestCount, shouldAcceptVideoClip, new ApiClient.ApiCallback() {

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
    }
}