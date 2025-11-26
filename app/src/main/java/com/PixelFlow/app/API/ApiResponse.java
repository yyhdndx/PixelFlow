package com.PixelFlow.app.API;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class ApiResponse {
    @SerializedName("status_code")
    private int statusCode;

    @SerializedName("has_more")
    private int hasMore;

    @SerializedName("post_list")
    private List<PostItem> postList;

    // Getters and Setters
    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public int getHasMore() { return hasMore; }
    public void setHasMore(int hasMore) { this.hasMore = hasMore; }

    public List<PostItem> getPostList() { return postList; }
    public void setPostList(List<PostItem> postList) { this.postList = postList; }
}