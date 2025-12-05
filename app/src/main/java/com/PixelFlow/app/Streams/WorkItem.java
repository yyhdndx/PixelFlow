package com.PixelFlow.app.Streams;

import com.PixelFlow.app.API.PostItem;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class WorkItem implements Serializable {
    private static final long serialVersionUID = 1L;

    // 原有字段（保持不变）
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

    // 新增字段
    private long createTime; // 创建时间戳（秒）
    private Author author;   // 完整的作者信息
    private List<Clip> clips; // 图片/视频片段列表
    private List<Hashtag> hashtags; // 话题标签

    // 作者信息内部类 - 需要实现Serializable
    public static class Author implements Serializable {
        private static final long serialVersionUID = 2L;
        private String userId;
        private String nickname;
        private String avatar;

        public Author() {}

        public Author(String userId, String nickname, String avatar) {
            this.userId = userId;
            this.nickname = nickname;
            this.avatar = avatar;
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }

        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
    }

    // 片段内部类 - 需要实现Serializable
    public static class Clip implements Serializable {
        private static final long serialVersionUID = 3L;
        private int type; // 0：图片，1：视频
        private int width;
        private int height;
        private String url;

        public Clip() {}

        public Clip(int type, int width, int height, String url) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.url = url;
        }

        public int getType() { return type; }
        public void setType(int type) { this.type = type; }

        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }

        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    // 话题标签内部类 - 需要实现Serializable
    public static class Hashtag implements Serializable {
        private static final long serialVersionUID = 4L;
        private int start;
        private int end;

        public Hashtag() {}

        public Hashtag(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public int getStart() { return start; }
        public void setStart(int start) { this.start = start; }

        public int getEnd() { return end; }
        public void setEnd(int end) { this.end = end; }
    }

    // 从API的PostItem创建WorkItem的构造方法（更新版）
    public WorkItem(PostItem postItem) {
        this.id = postItem.getPostId();
        this.title = postItem.getTitle();
        this.content = postItem.getContent();

        // 设置创建时间（从API获取）
        this.createTime = postItem.getCreateTime();

        // 设置作者信息
        if (postItem.getAuthor() != null) {
            this.author = new Author(
                    postItem.getAuthor().getUserId(),
                    postItem.getAuthor().getNickname(),
                    postItem.getAuthor().getAvatar()
            );
            // 同时设置原有的作者字段，保持兼容
            this.authorName = postItem.getAuthor().getNickname();
            this.authorAvatarUrl = postItem.getAuthor().getAvatar();
        }

        // 模拟点赞数（因为API没有提供）
        this.likeCount = (int) (Math.random() * 1000);
        this.isLiked = false;

        // 设置clips信息
        if (postItem.getClips() != null && !postItem.getClips().isEmpty()) {
            this.clips = new ArrayList<>();
            for (PostItem.Clip clip : postItem.getClips()) {
                Clip newClip = new Clip(
                        clip.getType(),
                        clip.getWidth(),
                        clip.getHeight(),
                        clip.getUrl()
                );
                this.clips.add(newClip);
            }

            // 使用第一个clip作为封面
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

        // 设置话题标签
        if (postItem.getHashtag() != null && !postItem.getHashtag().isEmpty()) {
            this.hashtags = new ArrayList<>();
            for (PostItem.Hashtag hashtag : postItem.getHashtag()) {
                Hashtag newHashtag = new Hashtag(
                        hashtag.getStart(),
                        hashtag.getEnd()
                );
                this.hashtags.add(newHashtag);
            }
        }
    }

    // 原有的构造函数保持不变（为了兼容性）
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
        this.createTime = System.currentTimeMillis() / 1000; // 设置为当前时间

        // 同时创建Author对象
        this.author = new Author("", authorName, authorAvatarUrl);
    }

    // 添加一个默认构造函数，用于序列化
    public WorkItem() {
        // 默认构造函数，用于序列化/反序列化
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

    public String getAuthorName() {
        if (author != null && author.getNickname() != null) {
            return author.getNickname();
        }
        return authorName != null ? authorName : "";
    }
    public void setAuthorName(String authorName) {
        this.authorName = authorName;
        if (author != null) {
            author.setNickname(authorName);
        }
    }

    public String getAuthorAvatarUrl() {
        if (author != null && author.getAvatar() != null) {
            return author.getAvatar();
        }
        return authorAvatarUrl != null ? authorAvatarUrl : "";
    }
    public void setAuthorAvatarUrl(String authorAvatarUrl) {
        this.authorAvatarUrl = authorAvatarUrl;
        if (author != null) {
            author.setAvatar(authorAvatarUrl);
        }
    }

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

    // ============ 新增接口 ============

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public Author getAuthor() { return author; }
    public void setAuthor(Author author) {
        this.author = author;
        if (author != null) {
            this.authorName = author.getNickname();
            this.authorAvatarUrl = author.getAvatar();
        }
    }

    public List<Clip> getClips() { return clips; }
    public void setClips(List<Clip> clips) { this.clips = clips; }

    public List<Hashtag> getHashtags() { return hashtags; }
    public void setHashtags(List<Hashtag> hashtags) { this.hashtags = hashtags; }

    /**
     * 获取所有图片URL
     */
    public List<String> getImageUrls() {
        List<String> urls = new ArrayList<>();
        if (clips != null) {
            for (Clip clip : clips) {
                if (clip.getType()!=1) {
                    urls.add(clip.getUrl());
                }
            }
        }
        // 如果没有clips但有封面，使用封面
        if (urls.isEmpty() && coverUrl != null && !coverUrl.isEmpty()) {
            urls.add(coverUrl);
        }
        return urls;
    }

    /**
    获取所有视频的url
     */
    public List<String> getAllVideoUrls() {
        List<String> videoUrls = new ArrayList<>();
        if (clips != null) {
            for (Clip clip : clips) {
                if (clip.getType() == 1) { // 1表示视频
                    videoUrls.add(clip.getUrl());
                }
            }
        }
        return videoUrls;
    }

    /**
     * 获取原始宽度（第一个clip的宽度）
     */
    public int getOriginalWidth() {
        if (clips != null && !clips.isEmpty() && clips.get(0) != null) {
            return clips.get(0).getWidth();
        }
        return imageWidth;
    }

    /**
     * 获取原始高度（第一个clip的高度）
     */
    public int getOriginalHeight() {
        if (clips != null && !clips.isEmpty() && clips.get(0) != null) {
            return clips.get(0).getHeight();
        }
        return imageHeight;
    }

    /**
     * 从content中提取话题标签文本
     */
    public List<String> getTagList() {
        List<String> tags = new ArrayList<>();
        if (hashtags != null && content != null) {
            for (Hashtag hashtag : hashtags) {
                if (hashtag.getStart() >= 0 && hashtag.getEnd() <= content.length() &&
                        hashtag.getStart() < hashtag.getEnd()) {
                    String tag = content.substring(hashtag.getStart(), hashtag.getEnd());
                    tags.add(tag);
                }
            }
        }
        return tags;
    }
}