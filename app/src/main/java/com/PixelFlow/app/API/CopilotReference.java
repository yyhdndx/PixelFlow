package com.PixelFlow.app.API;

import com.google.gson.JsonObject;

/** 通用对话检索到的一篇站内帖子，用于气泡内可点击的结果列表。 */
public final class CopilotReference {
    public final String postId;
    public final String title;
    public final String excerpt;

    private CopilotReference(JsonObject value) {
        postId = str(value, "id");
        title = str(value, "title");
        excerpt = str(value, "excerpt");
    }

    public static CopilotReference parse(JsonObject value) { return new CopilotReference(value); }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("id", postId);
        out.addProperty("title", title);
        out.addProperty("excerpt", excerpt);
        return out;
    }

    private static String str(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) return "";
        return value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
}