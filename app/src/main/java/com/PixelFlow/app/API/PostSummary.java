package com.PixelFlow.app.API;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** 单篇作品的长文提炼结果：一句话概括 + 3–5 条要点。 */
public final class PostSummary {
    public final String summary;
    public final List<String> points;
    public final int contentLength;

    private PostSummary(JsonObject value) {
        summary = value.has("summary") && value.get("summary").isJsonPrimitive()
                ? value.get("summary").getAsString() : "";
        List<String> parsed = new ArrayList<>();
        if (value.has("points") && value.get("points").isJsonArray()) {
            JsonArray array = value.getAsJsonArray("points");
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonPrimitive()) parsed.add(array.get(i).getAsString());
            }
        }
        points = parsed;
        contentLength = value.has("content_length") && value.get("content_length").isJsonPrimitive()
                ? value.get("content_length").getAsInt() : 0;
    }

    public static PostSummary parse(JsonObject value) { return new PostSummary(value); }
}
