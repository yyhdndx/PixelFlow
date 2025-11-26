package com.PixelFlow.app;

import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.PixelFlow.app.stream.WaterfallAdapter;
import com.PixelFlow.app.stream.WorkItem;
import com.google.android.material.tabs.TabLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class HomeFragment extends Fragment {

    private TabLayout tabLayout;
    private SwipeRefreshLayout swipeRefreshLayout;
    private androidx.recyclerview.widget.RecyclerView recyclerView;
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

        // 设置加载更多监听
        adapter.setOnLoadMoreListener(() -> {
            if (!adapter.isLoading() && hasMoreData) {
                loadData(false);
            }
        });

        // 设置点赞点击监听
        adapter.setOnLikeClickListener((workItem, position) -> {
            boolean newLikeState = !workItem.isLiked();
            adapter.updateLikeStatus(position, newLikeState);
        });

        // 添加滚动监听，检测是否到达底部
        recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
           public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);

                StaggeredGridLayoutManager layoutManager = (StaggeredGridLayoutManager) recyclerView.getLayoutManager();
                if (layoutManager == null) return;

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
        if (isRefresh) {
            currentPage = 0;
            hasMoreData = true;
            isRefreshing = true;
            swipeRefreshLayout.setRefreshing(true);
        } else {
            adapter.setLoading(true);
        }

        // 模拟网络请求延迟
        handler.postDelayed(() -> {
            List<WorkItem> newData = generateMockData(currentPage);

            if (isRefresh) {
                // 首刷：模拟10%的失败率
                if (new Random().nextInt(10) == 0) {
                    showEmptyState();
                } else {
                    hideEmptyState();
                    adapter.setData(newData);
                }
                isRefreshing = false;
                swipeRefreshLayout.setRefreshing(false);
            } else {
                // 加载更多
                if (newData.isEmpty()) {
                    hasMoreData = false;
                } else {
                    adapter.addData(newData);
                }
                adapter.setLoading(false);
            }

            currentPage++;
        }, 1500);
    }

    private List<WorkItem> generateMockData(int page) {
        List<WorkItem> data = new ArrayList<>();

        // 模拟没有更多数据的情况
        if (page >= 3) {
            return data;
        }

        int itemsPerPage = 10;
        Random random = new Random();

        for (int i = 0; i < itemsPerPage; i++) {
            String id = "work_" + page + "_" + i;
            String title = getRandomTitle(random);
            String content = getRandomContent(random);
            String authorName = getRandomAuthorName(random);

            // 随机图片尺寸，但限制在合理范围内
            int width = 300 + random.nextInt(400);
            int height = 400 + random.nextInt(300);

            WorkItem item = new WorkItem(
                    id,
                    "https://picsum.photos/" + width + "/" + height, // 随机图片
                    title,
                    content,
                    authorName,
                    "https://i.pravatar.cc/150?img=" + (1 + random.nextInt(70)), // 随机头像
                    100 + random.nextInt(1000),
                    width,
                    height
            );

            data.add(item);
        }

        return data;
    }

    private String getRandomTitle(Random random) {
        String[] titles = {
                "美丽的风景摄影",
                "城市夜景",
                "美食分享",
                "旅行日记",
                "创意设计",
                "生活小技巧",
                "艺术作品展示",
                "摄影教程",
                null // 模拟没有标题的情况
        };
        return titles[random.nextInt(titles.length)];
    }

    private String getRandomContent(Random random) {
        String[] contents = {
                "这是一段作品描述，讲述了创作的过程和灵感来源...",
                "分享生活中的美好瞬间，记录每一个值得纪念的时刻",
                "通过这个作品想表达对自然的热爱和对生活的热情",
                "创作过程中的心得体会和经验分享",
                "希望能给大家带来一些启发和思考"
        };
        return contents[random.nextInt(contents.length)];
    }

    private String getRandomAuthorName(Random random) {
        String[] names = {
                "摄影师小明", "旅行达人小红", "美食家小李",
                "设计师小王", "艺术家小张", "创作者小刘"
        };
        return names[random.nextInt(names.length)];
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
