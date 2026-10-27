package com.PixelFlow.app.API;

import com.google.gson.JsonObject;

/** One user in the followers/following list; the following state updates in place on follow/unfollow. */
public final class ConnectionUser {
    public final String id, nickname, bio, avatar;
    public boolean following;
    private ConnectionUser(JsonObject value) {
        id = text(value, "user_id"); nickname = text(value, "nickname");
        bio = text(value, "bio"); avatar = text(value, "avatar");
        following = value != null && value.has("is_following")
                && value.get("is_following").isJsonPrimitive()
                && value.getAsJsonPrimitive("is_following").isBoolean()
                && value.getAsJsonPrimitive("is_following").getAsBoolean();
        if (id.isEmpty() || nickname.isEmpty()) throw new IllegalArgumentException("Incomplete connection");
    }
    static ConnectionUser parse(JsonObject value) { return new ConnectionUser(value); }
    private static String text(JsonObject value, String field) {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
                || !value.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("Invalid connection field");
        return value.get(field).getAsString();
    }
}
