package com.PixelFlow.app.API;

import com.google.gson.JsonObject;
import java.math.BigDecimal;

public final class UserStats {
    public final long posts, followers, following;
    private UserStats(JsonObject value, String userId) {
        if (value == null || !value.has("user_id") || !value.get("user_id").isJsonPrimitive()
                || !value.getAsJsonPrimitive("user_id").isString()
                || !userId.equals(value.get("user_id").getAsString())) throw new IllegalArgumentException("Wrong user");
        posts = count(value, "post_count"); followers = count(value, "follower_count"); following = count(value, "following_count");
    }
    static UserStats parse(JsonObject value, String userId) { return new UserStats(value, userId); }
    private static long count(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonPrimitive() || !value.getAsJsonPrimitive(field).isNumber())
            throw new IllegalArgumentException("Invalid count");
        long number = new BigDecimal(value.get(field).getAsString()).longValueExact();
        if (number < 0) throw new IllegalArgumentException("Invalid count");
        return number;
    }
}
