package com.PixelFlow.app.API;

import com.google.gson.*;
import java.io.IOException;
import java.util.function.Consumer;
import okio.BufferedSource;

final class PostSummaryStreamReader {
    static PostSummary read(BufferedSource source, Consumer<JsonObject> progress) throws IOException {
        String event = null, summary = null;
        JsonArray points = new JsonArray();
        long seq = 0, total = 0;
        while (!source.exhausted()) {
            String line = source.readUtf8LineStrict(16384);
            total += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (total > 262144) throw new IOException("Summary stream too large");
            if (line.startsWith("event:")) { event = line.substring(6).trim(); continue; }
            if (!line.startsWith("data:")) continue;
            JsonObject node = new Gson().fromJson(line.substring(5).trim(), JsonObject.class);
            if (node == null || !node.has("seq") || node.get("seq").getAsLong() != ++seq) throw new IOException("Invalid sequence");
            if ("summary".equals(event)) {
                if (summary != null || !node.has("summary")) throw new IOException("Invalid summary");
                summary = node.get("summary").getAsString();
                if (summary.trim().isEmpty() || summary.codePointCount(0, summary.length()) > 60) throw new IOException("Invalid summary");
            } else if ("point".equals(event)) {
                if (summary == null || points.size() >= 5) throw new IOException("Invalid point");
                String point = node.get("point").getAsString();
                if (point.trim().isEmpty() || point.codePointCount(0, point.length()) > 80) throw new IOException("Invalid point");
                points.add(point);
            } else if ("done".equals(event)) {
                if (summary == null || points.size() < 3 || !node.has("validated") || !node.get("validated").getAsBoolean()
                        || !summary.equals(node.get("summary").getAsString()) || !points.equals(node.get("points")))
                    throw new IOException("Invalid completion");
                return PostSummary.parse(node);
            } else throw new IOException("提炼未完成，可以先读原文");
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("summary", summary);
            snapshot.add("points", points.deepCopy());
            progress.accept(snapshot);
        }
        throw new IOException("提炼已中断，可以先读原文");
    }
}
