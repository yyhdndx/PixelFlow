package com.PixelFlow.app.API;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;
import okio.BufferedSource;

/** 读取创作辅助流式响应：delta 为增量文本，done 为最终结构化结果。 */
final class CreationAssistStreamReader {
    static CreationSuggestion read(BufferedSource source, Consumer<String> delta) throws IOException {
        Gson json = new Gson();
        String event = null;
        long sequence = 0, total = 0;
        StringBuilder text = new StringBuilder();
        while (!source.exhausted()) {
            String line = source.readUtf8LineStrict(16384);
            total += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (total > 262144) throw new IOException("Stream exceeds size limit");
            if (line.startsWith("event:")) { event = line.substring(6).trim(); continue; }
            if (!line.startsWith("data:")) continue;
            JsonObject data;
            try { data = json.fromJson(line.substring(5).trim(), JsonObject.class); }
            catch (RuntimeException error) { throw new IOException("Malformed stream", error); }
            if (data == null || !data.has("seq")) throw new IOException("Missing sequence");
            if (data.get("seq").getAsLong() != ++sequence) throw new IOException("Invalid sequence");
            switch (event == null ? "" : event) {
                case "delta":
                    String piece = data.get("text").getAsString();
                    text.append(piece);
                    if (text.codePointCount(0, text.length()) <= 5000) {
                        delta.accept(piece);
                    }
                    // 超长时忽略超出增量，最终以服务端 done 的截断内容为准
                    break;
                case "done": {
                    CreationSuggestion result = json.fromJson(data, CreationSuggestion.class);
                    if (result == null || !data.has("validated") || !data.get("validated").getAsBoolean()
                            || !result.valid()) throw new IOException("Unvalidated result");
                    return result;
                }
                case "error": throw new IOException("AI 辅助创作未完成，请重试");
                default: throw new IOException("Unknown stream event");
            }
        }
        throw new IOException("AI 辅助创作已中断，请重试");
    }
}
