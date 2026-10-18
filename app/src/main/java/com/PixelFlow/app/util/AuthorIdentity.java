package com.PixelFlow.app.util;

import com.PixelFlow.app.Streams.WorkItem;

public final class AuthorIdentity {
    private AuthorIdentity() {}

    public static String followKey(WorkItem.Author author) {
        if (author == null || author.getUserId() == null
                || author.getUserId().trim().isEmpty()) return null;
        // Namespace distinguishes stable identifiers from historic nickname keys.
        return "user:" + author.getUserId();
    }
}
