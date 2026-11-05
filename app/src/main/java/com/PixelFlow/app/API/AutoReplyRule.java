package com.PixelFlow.app.API;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** 作品级「评论自动回复」规则：场景 → 答案，由 Agent 判定是否命中。 */
public final class AutoReplyRule {
    public final String id;
    public final String question;
    public final String answer;
    public final boolean enabled;

    private AutoReplyRule(JsonObject value) {
        id = value.has("id") && value.get("id").isJsonPrimitive() ? value.get("id").getAsString() : "";
        question = value.has("question") && value.get("question").isJsonPrimitive()
                ? value.get("question").getAsString() : "";
        answer = value.has("answer") && value.get("answer").isJsonPrimitive()
                ? value.get("answer").getAsString() : "";
        enabled = !value.has("enabled") || value.get("enabled").isJsonPrimitive()
                && value.get("enabled").getAsJsonPrimitive().isBoolean()
                && value.get("enabled").getAsBoolean();
    }

    public static AutoReplyRule parse(JsonObject value) { return new AutoReplyRule(value); }

    public static List<AutoReplyRule> parseList(JsonObject root) {
        List<AutoReplyRule> rules = new ArrayList<>();
        if (root == null || !root.has("rules") || !root.get("rules").isJsonArray()) return rules;
        JsonArray array = root.getAsJsonArray("rules");
        for (int i = 0; i < array.size(); i++) {
            if (array.get(i).isJsonObject()) rules.add(new AutoReplyRule(array.get(i).getAsJsonObject()));
        }
        return rules;
    }
}
