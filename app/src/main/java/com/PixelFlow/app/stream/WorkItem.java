package com.PixelFlow.app.stream;

public class WorkItem {
    private String id;
    private String coverUrl;
    private String title;
    private String content;
    private String authorName;
    private String authorAvatarUrl;
    private int likeCount;
    private boolean isLiked;
    private int imageWidth;
    private int imageHeight;

    // 构造函数
    public WorkItem(String id, String coverUrl, String title, String content,
                    String authorName, String authorAvatarUrl, int likeCount,
                    int imageWidth, int imageHeight) {
        this.id = id;
        this.coverUrl = coverUrl;
        this.title = title;
        this.content = content;
        this.authorName = authorName;
        this.authorAvatarUrl = authorAvatarUrl;
        this.likeCount = likeCount;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.isLiked = false; // 默认未点赞
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }

    public String getTitle() {
        if (title != null && !title.isEmpty()) {
            return title;
        } else {
            return content != null ? content : "";
        }
    }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public String getAuthorAvatarUrl() { return authorAvatarUrl; }
    public void setAuthorAvatarUrl(String authorAvatarUrl) { this.authorAvatarUrl = authorAvatarUrl; }

    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }

    public boolean isLiked() { return isLiked; }
    public void setLiked(boolean liked) { isLiked = liked; }

    public int getImageWidth() { return imageWidth; }
    public void setImageWidth(int imageWidth) { this.imageWidth = imageWidth; }

    public int getImageHeight() { return imageHeight; }
    public void setImageHeight(int imageHeight) { this.imageHeight = imageHeight; }

    // 计算图片显示高度（保持宽高比在 3:4 ~ 4:3 之间）
    public int getDisplayHeight(int targetWidth) {
        float aspectRatio = (float) imageWidth / imageHeight;

        // 限制宽高比在 3:4 ~ 4:3 之间
        float minAspectRatio = 0.75f; // 3:4
        float maxAspectRatio = 1.33f; // 4:3

        float clampedAspectRatio = Math.max(minAspectRatio, Math.min(maxAspectRatio, aspectRatio));

        return (int) (targetWidth / clampedAspectRatio);
    }
}
