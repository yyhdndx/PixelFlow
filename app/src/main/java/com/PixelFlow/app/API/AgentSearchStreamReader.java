package com.PixelFlow.app.API;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import okio.BufferedSource;

final class AgentSearchStreamReader {
    static AgentSearchResult read(BufferedSource source, Consumer<JsonObject> progress) throws IOException {
        Gson json = new Gson();
        String event = null, requestId = null, sessionId = null, traceId = null;
        long sequence = 0, total = 0;
        StringBuilder answer = new StringBuilder();
        AgentSearchResult.Source[] sources = null;
        while (!source.exhausted()) {
            String line = source.readUtf8LineStrict(16384);
            total += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (total > 262144) throw new IOException("Stream exceeds size limit");
            if (line.startsWith("event:")) { event = line.substring(6).trim(); continue; }
            if (!line.startsWith("data:")) continue;
            JsonObject data;
            try { data = json.fromJson(line.substring(5).trim(), JsonObject.class); }
            catch (RuntimeException error) { throw new IOException("Malformed stream", error); }
            if (data == null || !data.has("request_id") || !data.has("seq")) throw new IOException("Missing stream identity");
            String id = data.get("request_id").getAsString();
            if (data.get("seq").getAsLong() != ++sequence) throw new IOException("Invalid sequence");
            if (sequence == 1) {
                if (!"meta".equals(event)) throw new IOException("Missing metadata");
                requestId = id;
                sessionId = data.get("session_id").getAsString();
                traceId = data.get("trace_id").getAsString();
            } else if (!Objects.equals(requestId, id)) throw new IOException("Changed stream identity");
            switch (event == null ? "" : event) {
                case "meta":
                    if (sequence != 1) throw new IOException("Repeated metadata");
                    break;
                case "phase": break;
                case "sources":
                    if (sources != null) throw new IOException("Repeated sources");
                    sources = json.fromJson(data.get("sources"), AgentSearchResult.Source[].class);
                    if (sources == null || sources.length > 5) throw new IOException("Invalid sources");
                    break;
                case "delta":
                    if (sources == null) throw new IOException("Missing sources");
                    answer.append(data.get("text").getAsString());
                    if (answer.codePointCount(0, answer.length()) > 1500) throw new IOException("Answer exceeds size limit");
                    break;
                case "done":
                    AgentSearchResult result = json.fromJson(data, AgentSearchResult.class);
                    if (!data.has("validated") || !data.get("validated").getAsBoolean()
                            || !Objects.equals(sessionId, result.session_id) || !Objects.equals(traceId, result.trace_id)
                            || !Objects.equals(answer.toString(), result.answer) || sources == null
                            || !json.toJsonTree(sources).equals(json.toJsonTree(result.sources)))
                        throw new IOException("Unvalidated result");
                    result.status_code = 0;
                    if (!result.valid()) throw new IOException("Incomplete result");
                    return result;
                case "error": throw new IOException("AI 搜索未完成，请重试");
                default: throw new IOException("Unknown stream event");
            }
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("phase", "phase".equals(event) ? data.get("phase").getAsString() : event);
            snapshot.addProperty("answer", answer.toString());
            snapshot.add("sources", json.toJsonTree(sources));
            snapshot.addProperty("session_id", sessionId);
            snapshot.addProperty("trace_id", traceId);
            progress.accept(snapshot);
        }
        throw new IOException("AI 搜索已中断，回答未完成");
    }
}
