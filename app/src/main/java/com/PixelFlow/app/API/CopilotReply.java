package com.PixelFlow.app.API;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Copilot 一次调用的结果：一句话回复 + 若干动作提案（可能为空）。 */
public final class CopilotReply {
    public final String reply;
    public final List<CopilotAction> actions;
    public final List<CopilotReference> references;

    private CopilotReply(JsonObject value) {
        String text = value.has("reply") && value.get("reply").isJsonPrimitive()
                ? value.get("reply").getAsString() : "";
        reply = text;
        List<CopilotAction> parsed = new ArrayList<>();
        if (value.has("actions") && value.get("actions").isJsonArray()) {
            JsonArray array = value.getAsJsonArray("actions");
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) parsed.add(CopilotAction.parse(array.get(i).getAsJsonObject()));
            }
        }
        actions = parsed;
        List<CopilotReference> refs = new ArrayList<>();
        if (value.has("references") && value.get("references").isJsonArray()) {
            JsonArray array = value.getAsJsonArray("references");
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isJsonObject()) refs.add(CopilotReference.parse(array.get(i).getAsJsonObject()));
            }
        }
        references = refs;
    }

    public static CopilotReply parse(JsonObject value) { return new CopilotReply(value); }
}
