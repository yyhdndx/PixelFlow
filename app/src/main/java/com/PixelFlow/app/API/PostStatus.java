package com.PixelFlow.app.API;

/** Status transition result returned by publish/withdraw (camelCase `postId`). */
public class PostStatus {
    private String postId;
    private String status;
    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
