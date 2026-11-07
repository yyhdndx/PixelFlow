package com.PixelFlow.app.API;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Copilot 返回的一条动作提案；status 由客户端维护（pending/applied/ignored/stale）。 */
public final class CopilotAction {
    public static final String TYPE_COMMENT = "POST_COMMENT";
    public static final String TYPE_DRAFT = "UPDATE_DRAFT";
    public static final String TYPE_MESSAGES = "SEND_MESSAGES";

    public static final String PENDING = "pending";
    public static final String APPLIED = "applied";
    public static final String IGNORED = "ignored";
    public static final String STALE = "stale";

    public final String type;
    public final String text;
    public final String title;
    public final String content;
    public final String rationale;
    public final List<Message> messages;
    public String status = PENDING;

    public static final class Message {
        public final String conversationId;
        public final String text;

        Message(String conversationId, String text) {
            this.conversationId = conversationId;
            this.text = text;
        }
    }

    private CopilotAction(JsonObject value) {
        type = string(value, "type");
        text = string(value, "text");
        title = string(value, "title");
        content = string(value, "content");
        rationale = string(value, "rationale");
        List<Message> parsed = new ArrayList<>();
        if (value.has("items") && value.get("items").isJsonArray()) {
            for (com.google.gson.JsonElement item : value.getAsJsonArray("items")) {
                if (!item.isJsonObject()) continue;
                parsed.add(new Message(string(item.getAsJsonObject(), "conversation_id"),
                        string(item.getAsJsonObject(), "text")));
            }
        }
        messages = parsed;
    }

    public static CopilotAction parse(JsonObject value) { return new CopilotAction(value); }

    public static CopilotAction restore(JsonObject value) {
        CopilotAction action = new CopilotAction(value);
        String saved = string(value, "status");
        if (!saved.isEmpty()) action.status = saved;
        return action;
    }

    /** 用于本地历史持久化。 */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("type", type);
        if (!text.isEmpty()) out.addProperty("text", text);
        if (!title.isEmpty()) out.addProperty("title", title);
        if (!content.isEmpty()) out.addProperty("content", content);
        if (!rationale.isEmpty()) out.addProperty("rationale", rationale);
        if (!messages.isEmpty()) {
            JsonArray array = new JsonArray();
            for (Message message : messages) {
                JsonObject item = new JsonObject();
                item.addProperty("conversation_id", message.conversationId);
                item.addProperty("text", message.text);
                array.add(item);
            }
            out.add("items", array);
        }
        out.addProperty("status", status);
        return out;
    }

    public String summaryTitle() {
        if (TYPE_COMMENT.equals(type)) return "发送评论";
        if (TYPE_DRAFT.equals(type)) return "修改草稿";
        if (TYPE_MESSAGES.equals(type)) return "发送 " + messages.size() + " 条私信";
        return type;
    }

    private static String string(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) return "";
        return value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
}
