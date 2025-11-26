package com.PixelFlow.app.stream;

import com.PixelFlow.app.API.PostItem;

public class WorkItem {
    private String id;
    private String coverUrl;
    private String title;
    private String content;
    private String authorName;
    private String authorAvatarUrl;
    private int likeCount;  // 注意：API没有点赞数，我们使用模拟数据
    private boolean isLiked;
    private int imageWidth;
    private int imageHeight;

    // 从API的PostItem创建WorkItem的构造方法
    public WorkItem(PostItem postItem) {
        this.id = postItem.getPostId();
        this.title = postItem.getTitle();
        this.content = postItem.getContent();
        this.authorName = postItem.getAuthor().getNickname();
        this.authorAvatarUrl = postItem.getAuthor().getAvatar();

        // 模拟点赞数（因为API没有提供）
        this.likeCount = (int) (Math.random() * 1000);
        this.isLiked = false;

        // 使用第一个clip作为封面
        if (postItem.getClips() != null && !postItem.getClips().isEmpty()) {
            PostItem.Clip firstClip = postItem.getClips().get(0);
            this.coverUrl = firstClip.getUrl();
            this.imageWidth = firstClip.getWidth();
            this.imageHeight = firstClip.getHeight();
        } else {
            // 如果没有clip，使用默认图片
            this.coverUrl = "";
            this.imageWidth = 400;
            this.imageHeight = 600;
        }
    }

    // 原有的构造函数保持不变
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
        this.isLiked = false;
    }

    // Getters and Setters (保持不变)
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
