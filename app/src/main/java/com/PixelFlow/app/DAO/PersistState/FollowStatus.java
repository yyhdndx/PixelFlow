package com.PixelFlow.app.DAO.PersistState;

public class FollowStatus {
    private String authorId;
    private boolean isFollowing;
    private long followTime;
    private long updateTime;

    public FollowStatus() {}

    public FollowStatus(String authorId, boolean isFollowing, long followTime) {
        this.authorId = authorId;
        this.isFollowing = isFollowing;
        this.followTime = followTime;
        this.updateTime = System.currentTimeMillis();
    }

    // Getter 和 Setter 方法
    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public boolean isFollowing() { return isFollowing; }
    public void setFollowing(boolean following) {
        this.isFollowing = following;
        this.updateTime = System.currentTimeMillis();
    }

    public long getFollowTime() { return followTime; }
    public void setFollowTime(long followTime) { this.followTime = followTime; }

    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }
}
