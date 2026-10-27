package com.PixelFlow.app.API;

import com.google.gson.JsonObject;

/** Public profile of another user; the following state updates in place on follow/unfollow. */
public final class UserProfile {
    public final String id, nickname, bio, avatar;
    public final long posts, followers, following;
    public boolean isFollowing;
    private UserProfile(JsonObject value) {
        id = text(value, "user_id"); nickname = text(value, "nickname");
        bio = text(value, "bio"); avatar = text(value, "avatar");
        posts = number(value, "post_count"); followers = number(value, "follower_count"); following = number(value, "following_count");
        isFollowing = value != null && value.has("is_following") && value.get("is_following").isJsonPrimitive()
                && value.getAsJsonPrimitive("is_following").isBoolean()
                && value.getAsJsonPrimitive("is_following").getAsBoolean();
        if (id.isEmpty() || nickname.isEmpty()) throw new IllegalArgumentException("Incomplete profile");
    }
    static UserProfile parse(JsonObject value) { return new UserProfile(value); }
    private static String text(JsonObject value, String field) {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
                || !value.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("Invalid profile field");
        return value.get(field).getAsString();
    }
    private static long number(JsonObject value, String field) {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
                || !value.getAsJsonPrimitive(field).isNumber()) throw new IllegalArgumentException("Invalid profile count");
        return value.getAsJsonPrimitive(field).getAsLong();
    }
}
