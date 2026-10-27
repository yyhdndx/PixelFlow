package com.PixelFlow.app.API;

import com.google.gson.JsonObject;

/** PUT/DELETE /api/users/me/avatar response: the authenticated user's current avatar URL. */
public final class AvatarResult {
    public final String userId, avatar;
    private AvatarResult(JsonObject value) {
        if (value == null || !value.has("user_id") || !value.get("user_id").isJsonPrimitive()
                || !value.getAsJsonPrimitive("user_id").isString()
                || value.get("user_id").getAsString().isEmpty()) throw new IllegalArgumentException("Incomplete avatar");
        if (!value.has("avatar") || !value.get("avatar").isJsonPrimitive()
                || !value.getAsJsonPrimitive("avatar").isString()) throw new IllegalArgumentException("Incomplete avatar");
        userId = value.get("user_id").getAsString();
        avatar = value.get("avatar").getAsString();
    }
    static AvatarResult parse(JsonObject value) { return new AvatarResult(value); }
}
