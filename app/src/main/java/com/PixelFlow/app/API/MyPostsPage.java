package com.PixelFlow.app.API;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/** Local pagination state for GET /api/posts, whose wire format is an array. */
public final class MyPostsPage {
    private final List<PostItem> posts;

    MyPostsPage(List<PostItem> posts) {
        this.posts = Collections.unmodifiableList(new ArrayList<>(posts));
    }

    public List<PostItem> getPosts() { return posts; }
    public String getNextCursor() {
        return posts.isEmpty() ? null : posts.get(posts.size() - 1).getPostId();
    }
    // A short page does not prove exhaustion. Only an empty response ends pagination.
    public boolean canLoadMore() { return !posts.isEmpty(); }
}
