package com.PixelFlow.app.DAO.PersistState;

/**
 * 点赞状态数据模型
 */
public class LikeStatus {
    private String workId;
    private boolean isLiked;
    private int likeCount;
    private long lastUpdated;
    private String userId; // 支持多用户（未来扩展）
    private int likeType;  // 点赞类型（0:普通点赞, 1:超级点赞等）

    public LikeStatus(String workId, boolean isLiked, int likeCount) {
        this.workId = workId;
        this.isLiked = isLiked;
        this.likeCount = likeCount;
        this.lastUpdated = System.currentTimeMillis();
        this.userId = "default"; // 默认用户
        this.likeType = 0;       // 普通点赞
    }

    // 全参数构造函数
    public LikeStatus(String workId, boolean isLiked, int likeCount,
                      long lastUpdated, String userId, int likeType) {
        this.workId = workId;
        this.isLiked = isLiked;
        this.likeCount = likeCount;
        this.lastUpdated = lastUpdated;
        this.userId = userId;
        this.likeType = likeType;
    }

    // Getters and Setters
    public String getWorkId() { return workId; }
    public void setWorkId(String workId) { this.workId = workId; }

    public boolean isLiked() { return isLiked; }
    public void setLiked(boolean liked) {
        isLiked = liked;
        lastUpdated = System.currentTimeMillis();
    }

    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) {
        this.likeCount = likeCount;
        lastUpdated = System.currentTimeMillis();
    }

    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public int getLikeType() { return likeType; }
    public void setLikeType(int likeType) { this.likeType = likeType; }
}
