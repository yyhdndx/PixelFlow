package com.PixelFlow.app.API;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Public author posts use an envelope, unlike the current user's draft array. */
public final class UserPostsPage {
    public final List<PostItem> posts;
    public final boolean hasMore;
    public final String nextCursor;

    private UserPostsPage(List<PostItem> posts, boolean hasMore, String nextCursor) {
        this.posts = Collections.unmodifiableList(posts);
        this.hasMore = hasMore;
        this.nextCursor = nextCursor;
    }

    static long positiveId(String text) {
        if (text == null || !text.matches("[0-9]+")) throw new IllegalArgumentException("Invalid ID");
        long value = Long.parseLong(text);
        if (value <= 0) throw new IllegalArgumentException("Invalid ID");
        return value;
    }

    private static boolean numberIs(JsonObject object, String key, String expected) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                && expected.equals(value.getAsString());
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Missing string: " + key);
        return value.getAsString();
    }

    static UserPostsPage parse(JsonObject value, String authorId, int count, String cursor) {
        if (value == null || !numberIs(value, "status_code", "0")
                || !(numberIs(value, "has_more", "0") || numberIs(value, "has_more", "1"))
                || !value.has("next_cursor") || !value.has("post_list") || !value.get("post_list").isJsonArray())
            throw new IllegalArgumentException("Invalid author page");
        JsonArray rows = value.getAsJsonArray("post_list");
        if (rows.size() > count) throw new IllegalArgumentException("Too many rows");
        Long boundary = cursor == null ? null : positiveId(cursor);
        List<PostItem> posts = new ArrayList<>();
        Gson gson = new Gson();
        for (JsonElement entry : rows) {
            JsonObject row = entry.getAsJsonObject();
            long id = positiveId(string(row, "post_id"));
            if (boundary != null && id >= boundary) throw new IllegalArgumentException("Invalid order");
            boundary = id;
            if (!"PUBLISHED".equals(string(row, "status"))
                    || !authorId.equals(string(row.getAsJsonObject("author"), "user_id"))
                    || !row.has("clips") || !row.get("clips").isJsonArray()
                    || !row.has("is_liked") || !row.get("is_liked").isJsonPrimitive()
                    || !row.getAsJsonPrimitive("is_liked").isBoolean())
                throw new IllegalArgumentException("Invalid public post");
            for (JsonElement clip : row.getAsJsonArray("clips")) {
                JsonObject media = clip.getAsJsonObject();
                if (!(numberIs(media, "type", "0") || numberIs(media, "type", "1"))
                        || string(media, "url").isEmpty()) throw new IllegalArgumentException("Invalid media");
            }
            posts.add(gson.fromJson(row, PostItem.class));
        }
        boolean more = numberIs(value, "has_more", "1");
        String next = value.get("next_cursor").isJsonNull() ? null : string(value, "next_cursor");
        if (more ? (posts.isEmpty() || next == null || positiveId(next) != boundary)
                : next != null) throw new IllegalArgumentException("Invalid next cursor");
        return new UserPostsPage(posts, more, next);
    }
}
