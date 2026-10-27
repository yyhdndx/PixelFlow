package com.PixelFlow.app.API;

import com.google.gson.JsonObject;

/** Uploaded-media metadata returned by POST /api/media (camelCase, unlike posts). */
public final class MediaItem {
    public final String mediaId, url, contentType;
    public final int type, width, height, size;
    private MediaItem(JsonObject value) {
        mediaId = text(value, "mediaId");
        url = text(value, "url");
        type = (int) number(value, "type");
        width = (int) number(value, "width");
        height = (int) number(value, "height");
        size = (int) number(value, "size");
        contentType = value != null && value.has("contentType") && value.get("contentType").isJsonPrimitive()
                ? value.getAsJsonPrimitive("contentType").getAsString() : "image/png";
        if (mediaId.isEmpty()) throw new IllegalArgumentException("Missing mediaId");
    }
    static MediaItem parse(JsonObject value) { return new MediaItem(value); }
    private static String text(JsonObject value, String field) {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
                || !value.getAsJsonPrimitive(field).isString())
            throw new IllegalArgumentException("Invalid media field: " + field);
        return value.get(field).getAsString();
    }
    private static long number(JsonObject value, String field) {
        if (value == null || !value.has(field) || !value.get(field).isJsonPrimitive()
                || !value.getAsJsonPrimitive(field).isNumber())
            throw new IllegalArgumentException("Invalid media field: " + field);
        return value.getAsJsonPrimitive(field).getAsLong();
    }
}
