package com.PixelFlow.app.API;

import com.google.gson.JsonObject;

/** Account data is transient and never mixed with the legacy device-local profile. */
public final class CurrentUser {
    public final String id, username, nickname, bio, avatar;
    private CurrentUser(JsonObject value) {
        id = text(value, "id"); username = text(value, "username");
        nickname = text(value, "nickname"); bio = text(value, "bio"); avatar = text(value, "avatar");
        if (id.isEmpty() || username.isEmpty() || nickname.trim().isEmpty())
            throw new IllegalArgumentException("Incomplete user");
    }
    static CurrentUser parse(JsonObject value) { return new CurrentUser(value); }
    private static String text(JsonObject value, String field) {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
                || !value.getAsJsonPrimitive(field).isString()) throw new IllegalArgumentException("Invalid user field");
        return value.get(field).getAsString();
    }
}
