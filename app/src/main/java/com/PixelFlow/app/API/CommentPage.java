package com.PixelFlow.app.API;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** 作品评论分页：一级评论按 ID 倒序，每条带它下面的回复。 */
public final class CommentPage {
    public final List<CommentItem> items;
    public final boolean hasMore;
    public final String nextCursor;

    public static final class CommentItem {
        public final String id;
        public final String postId;
        /** 所属一级评论 id；一级评论自身为 null。 */
        public final String rootId;
        public final String parentNickname;
        public final String content;
        public final String authorId;
        public final String authorNickname;
        public final String authorAvatar;
        public final long createTime;
        /** 仅一级评论有：该评论下的回复（时间正序）。 */
        public final List<CommentItem> replies;
        public final int replyCount;
        /** 是否为 Agent 按作者规则自动发出的回复。 */
        public final boolean auto;

        private CommentItem(JsonObject value) {
            id = string(value, "comment_id");
            postId = string(value, "post_id");
            rootId = value.has("root_comment_id") && !value.get("root_comment_id").isJsonNull()
                    ? string(value, "root_comment_id")
                    : value.has("parent_comment_id") && !value.get("parent_comment_id").isJsonNull()
                            ? string(value, "parent_comment_id") : null;
            parentNickname = value.has("parent_nickname") && !value.get("parent_nickname").isJsonNull()
                    ? string(value, "parent_nickname") : null;
            content = string(value, "content");
            JsonObject author = value.has("author") && value.get("author").isJsonObject()
                    ? value.getAsJsonObject("author") : new JsonObject();
            authorId = string(author, "user_id");
            authorNickname = string(author, "nickname");
            authorAvatar = string(author, "avatar");
            createTime = value.has("create_time") && value.get("create_time").isJsonPrimitive()
                    && value.getAsJsonPrimitive("create_time").isNumber()
                    ? value.getAsJsonPrimitive("create_time").getAsLong() : 0L;
            List<CommentItem> parsed = new ArrayList<>();
            if (value.has("replies") && value.get("replies").isJsonArray()) {
                JsonArray array = value.getAsJsonArray("replies");
                for (int i = 0; i < array.size(); i++) {
                    if (array.get(i).isJsonObject()) parsed.add(new CommentItem(array.get(i).getAsJsonObject()));
                }
            }
            replies = parsed;
            auto = value.has("auto") && value.get("auto").isJsonPrimitive()
                    && value.get("auto").getAsJsonPrimitive().isBoolean() && value.get("auto").getAsBoolean();
            replyCount = value.has("reply_count") && value.get("reply_count").isJsonPrimitive()
                    && value.getAsJsonPrimitive("reply_count").isNumber()
                    ? value.getAsJsonPrimitive("reply_count").getAsInt() : parsed.size();
        }

        public boolean isRoot() { return rootId == null; }

        public static CommentItem parse(JsonObject value) { return new CommentItem(value); }

        private static String string(JsonObject value, String key) {
            return value.has(key) && value.get(key).isJsonPrimitive()
                    && value.getAsJsonPrimitive(key).isString() ? value.get(key).getAsString() : "";
        }
    }

    private CommentPage(JsonObject value) {
        if (value == null || !value.has("comment_list") || !value.get("comment_list").isJsonArray())
            throw new IllegalArgumentException("Invalid comment page");
        JsonArray array = value.getAsJsonArray("comment_list");
        items = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) throw new IllegalArgumentException("Invalid comment item");
            items.add(new CommentItem(array.get(i).getAsJsonObject()));
        }
        hasMore = value.has("has_more") && value.get("has_more").isJsonPrimitive()
                && value.getAsJsonPrimitive("has_more").isNumber()
                && value.getAsJsonPrimitive("has_more").getAsInt() == 1;
        nextCursor = value.has("next_cursor") && !value.get("next_cursor").isJsonNull()
                && value.get("next_cursor").isJsonPrimitive()
                && value.getAsJsonPrimitive("next_cursor").isString()
                ? value.getAsJsonPrimitive("next_cursor").getAsString() : null;
    }

    public static CommentPage parse(JsonObject value) { return new CommentPage(value); }
}
