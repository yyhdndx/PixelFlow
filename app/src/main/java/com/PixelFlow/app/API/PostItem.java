package com.PixelFlow.app.API;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class PostItem {
    @SerializedName("post_id")
    private String postId;

    private String title;
    private String content;
    @SerializedName("like_count") private int likeCount;
    @SerializedName("is_liked") private boolean liked;
    @SerializedName("is_favorited") private boolean favorited;
    @SerializedName("rec_reason") private String recReason;
    @SerializedName("rec_score") private Double recScore;
    public int getLikeCount() { return Math.max(0, likeCount); }
    public boolean isLiked() { return liked; }
    public boolean isFavorited() { return favorited; }
    public void setFavorited(boolean favorited) { this.favorited = favorited; }
    public String getRecReason() { return recReason; }
    public Double getRecScore() { return recScore; }

    @SerializedName("create_time")
    private long createTime;

    @SerializedName("status")
    private String status;

    @SerializedName("media_ids")
    private List<String> mediaIds;

    private Author author;
    private List<Clip> clips;
    private Music music;

    // 新增hashtag字段
    @SerializedName("hashtag")
    private List<Hashtag> hashtags;

    // Getters and Setters
    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getMediaIds() { return mediaIds; }
    public void setMediaIds(List<String> mediaIds) { this.mediaIds = mediaIds; }

    public Author getAuthor() { return author; }
    public void setAuthor(Author author) { this.author = author; }

    public List<Clip> getClips() { return clips; }
    public void setClips(List<Clip> clips) { this.clips = clips; }

    public Music getMusic() { return music; }
    public void setMusic(Music music) { this.music = music; }

    // 新增hashtags的getter和setter
    public List<Hashtag> getHashtag() { return hashtags; }
    public void setHashtag(List<Hashtag> hashtags) { this.hashtags = hashtags; }

    // Hashtag内部类
    public static class Hashtag {
        private int start;
        private int end;

        public int getStart() { return start; }
        public void setStart(int start) { this.start = start; }

        public int getEnd() { return end; }
        public void setEnd(int end) { this.end = end; }
    }

    // 其他内部类保持不变
    public static class Author {
        @SerializedName("user_id")
        private String userId;

        private String nickname;
        private String avatar;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }

        public String getAvatar() { return avatar; }
        public void setAvatar(String avatar) { this.avatar = avatar; }
    }

    public static class Clip {
        private int type;
        private int width;
        private int height;
        private String url;

        public int getType() { return type; }
        public void setType(int type) { this.type = type; }

        public int getWidth() { return width; }
        public void setWidth(int width) { this.width = width; }

        public int getHeight() { return height; }
        public void setHeight(int height) { this.height = height; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class Music {
        private int volume;

        @SerializedName("seek_time")
        private int seekTime;

        private String url;

        public int getVolume() { return volume; }
        public void setVolume(int volume) { this.volume = volume; }

        public int getSeekTime() { return seekTime; }
        public void setSeekTime(int seekTime) { this.seekTime = seekTime; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }
}
