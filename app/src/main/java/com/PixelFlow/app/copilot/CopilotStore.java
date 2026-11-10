package com.PixelFlow.app.copilot;

import android.content.Context;
import android.content.SharedPreferences;
import com.PixelFlow.app.API.CopilotAction;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Copilot 对话的本地存储（仅存本机，不上传）。
 * 每条会话带上下文标签（帖子《…》/草稿《…》/私信 · N 位联系人），同一目标复用同一条会话。
 */
public final class CopilotStore {
    private static final String PREFS = "copilot_history";
    private static final String KEY = "sessions";
    private static final int MAX_ENTRIES = 40;
    private static final Gson GSON = new Gson();

    private CopilotStore() { }

    public static final class Entry {
        public String role = "user";
        public String text = "";
        public long at;
        public final List<CopilotAction> actions = new ArrayList<>();
    }

    public static final class Session {
        public String key = "";
        public String label = "";
        public String screen = "";
        public String postId;
        public String draftId;
        public int draftVersion;
        public long updatedAt;
        public final List<Entry> entries = new ArrayList<>();

        public JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("key", key);
            out.addProperty("label", label);
            out.addProperty("screen", screen);
            if (postId != null) out.addProperty("postId", postId);
            if (draftId != null) out.addProperty("draftId", draftId);
            out.addProperty("draftVersion", draftVersion);
            out.addProperty("updatedAt", updatedAt);
            JsonArray list = new JsonArray();
            for (Entry entry : entries) {
                JsonObject row = new JsonObject();
                row.addProperty("role", entry.role);
                row.addProperty("text", entry.text);
                row.addProperty("at", entry.at);
                JsonArray actions = new JsonArray();
                for (CopilotAction action : entry.actions) actions.add(action.toJson());
                row.add("actions", actions);
                list.add(row);
            }
            out.add("entries", list);
            return out;
        }

        public static Session fromJson(JsonObject value) {
            Session session = new Session();
            session.key = string(value, "key");
            session.label = string(value, "label");
            session.screen = string(value, "screen");
            session.postId = value.has("postId") ? string(value, "postId") : null;
            session.draftId = value.has("draftId") ? string(value, "draftId") : null;
            session.draftVersion = value.has("draftVersion") ? value.get("draftVersion").getAsInt() : 0;
            session.updatedAt = value.has("updatedAt") ? value.get("updatedAt").getAsLong() : 0;
            for (com.google.gson.JsonElement row : value.getAsJsonArray("entries")) {
                if (!row.isJsonObject()) continue;
                JsonObject entryJson = row.getAsJsonObject();
                Entry entry = new Entry();
                entry.role = string(entryJson, "role");
                entry.text = string(entryJson, "text");
                entry.at = entryJson.has("at") ? entryJson.get("at").getAsLong() : 0;
                for (com.google.gson.JsonElement action : entryJson.getAsJsonArray("actions"))
                    if (action.isJsonObject()) entry.actions.add(CopilotAction.restore(action.getAsJsonObject()));
                session.entries.add(entry);
            }
            return session;
        }
    }

    public static String sessionKey(String screen, String targetId) {
        return screen + ":" + (targetId == null ? "" : targetId);
    }

    /** 标签过长时截断，保证列表里一眼能看出对话是关于什么的。 */
    public static String label(String screen, String title, int contacts) {
        String name = title == null ? "" : title.trim();
        if (name.isEmpty()) name = "detail".equals(screen) ? "无标题帖子" : "无标题草稿";
        if (name.length() > 12) name = name.substring(0, 12) + "…";
        if ("detail".equals(screen)) return "帖子《" + name + "》";
        if ("create".equals(screen)) return "草稿《" + name + "》";
        return "私信 · " + Math.max(1, contacts) + " 位联系人";
    }

    public static Session load(Context context, String key) {
        List<Session> all = all(context);
        for (Session session : all) if (session.key.equals(key)) return session;
        return null;
    }

    public static List<Session> all(Context context) {
        String raw = prefs(context).getString(KEY, "");
        List<Session> sessions = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return sessions;
        try {
            JsonArray array = JsonParser.parseString(raw).getAsJsonArray();
            for (com.google.gson.JsonElement item : array) if (item.isJsonObject()) sessions.add(Session.fromJson(item.getAsJsonObject()));
        } catch (RuntimeException ignored) { }
        sessions.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return sessions;
    }

    public static void save(Context context, Session session) {
        List<Session> all = all(context);
        all.removeIf(item -> item.key.equals(session.key));
        while (session.entries.size() > MAX_ENTRIES) session.entries.remove(0);
        all.add(session);
        Collections.sort(all, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        JsonArray array = new JsonArray();
        for (Session item : all) array.add(item.toJson());
        prefs(context).edit().putString(KEY, GSON.toJson(array)).apply();
    }

    /** 按 key 批量删除会话。 */
    public static void delete(Context context, java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        List<Session> all = all(context);
        all.removeIf(item -> keys.contains(item.key));
        JsonArray array = new JsonArray();
        for (Session item : all) array.add(item.toJson());
        prefs(context).edit().putString(KEY, GSON.toJson(array)).apply();
    }
    public static void clearAll(Context context) {
        prefs(context).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String string(JsonObject value, String key) {
        if (value == null || !value.has(key) || value.get(key).isJsonNull()) return "";
        return value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : "";
    }
}
