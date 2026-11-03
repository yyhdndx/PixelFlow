package com.PixelFlow.app.API;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** One page of the notification list (@我 / 回复 / 赞). */
public final class NotificationPage {
    public final List<NotificationItem> items;
    public final boolean hasMore;
    public final String nextCursor;

    public static final class NotificationItem {
        public final String id;
        public final String type;          // MENTION | REPLY | LIKE
        public final String actorId;
        public final String actorNickname;
        public final String actorAvatar;
        public final String postId;
        public final String postTitle;
        public final String commentId;
        public final boolean read;
        public final long createTime;

        private NotificationItem(JsonObject value) {
            id = string(value, "notification_id");
            type = string(value, "type");
            JsonObject actor = value.has("actor") && value.get("actor").isJsonObject()
                    ? value.getAsJsonObject("actor") : new JsonObject();
            actorId = string(actor, "user_id");
            actorNickname = string(actor, "nickname");
            actorAvatar = string(actor, "avatar");
            postId = string(value, "post_id");
            postTitle = string(value, "post_title");
            commentId = string(value, "comment_id");
            read = value.has("read") && value.get("read").isJsonPrimitive()
                    && value.getAsJsonPrimitive("read").getAsBoolean();
            createTime = value.has("create_time") && value.get("create_time").isJsonPrimitive()
                    && value.getAsJsonPrimitive("create_time").isNumber()
                    ? value.getAsJsonPrimitive("create_time").getAsLong() : 0L;
        }
        private static String string(JsonObject value, String key) {
            return value.has(key) && value.get(key).isJsonPrimitive()
                    && value.getAsJsonPrimitive(key).isString() ? value.get(key).getAsString() : "";
        }
    }

    private NotificationPage(JsonObject value) {
        if (value == null || !value.has("notification_list") || !value.get("notification_list").isJsonArray())
            throw new IllegalArgumentException("Invalid notification page");
        JsonArray array = value.getAsJsonArray("notification_list");
        items = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) throw new IllegalArgumentException("Invalid notification item");
            items.add(new NotificationItem(array.get(i).getAsJsonObject()));
        }
        hasMore = value.has("has_more") && value.get("has_more").isJsonPrimitive()
                && value.getAsJsonPrimitive("has_more").isNumber()
                && value.getAsJsonPrimitive("has_more").getAsInt() == 1;
        nextCursor = value.has("next_cursor") && value.get("next_cursor").isJsonPrimitive()
                && value.getAsJsonPrimitive("next_cursor").isString()
                ? value.getAsJsonPrimitive("next_cursor").getAsString() : null;
    }
    public static NotificationPage parse(JsonObject value) { return new NotificationPage(value); }
}
