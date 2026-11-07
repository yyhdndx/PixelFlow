package com.PixelFlow.app.share;

import com.PixelFlow.app.API.PostItem;

public final class ShareData {
    private ShareData() { }

    public static boolean isPublic(PostItem post, String expectedId) {
        return post != null && expectedId != null && expectedId.equals(post.getPostId())
                && "PUBLISHED".equals(post.getStatus());
    }

    public static long timestamp(PostItem post) {
        return post.getPublishTime() == null ? post.getCreateTime() : post.getPublishTime();
    }

    public static int comments(PostItem post) {
        return post.getCommentCount() == null ? 0 : Math.max(0, post.getCommentCount());
    }

    public static String text(PostItem post, String link) {
        return (post.getTitle() == null ? "" : post.getTitle()) + "\n" + link;
    }
}
