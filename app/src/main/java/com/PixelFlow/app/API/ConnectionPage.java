package com.PixelFlow.app.API;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** One page of the followers or following list. */
public final class ConnectionPage {
    public final List<ConnectionUser> users;
    public final boolean hasMore;
    public final String nextCursor;
    private ConnectionPage(JsonObject value) {
        if (value == null || !value.has("user_list") || !value.get("user_list").isJsonArray())
            throw new IllegalArgumentException("Invalid connection page");
        JsonArray array = value.getAsJsonArray("user_list");
        users = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) throw new IllegalArgumentException("Invalid connection item");
            users.add(ConnectionUser.parse(array.get(i).getAsJsonObject()));
        }
        hasMore = value.has("has_more") && value.get("has_more").isJsonPrimitive()
                && value.getAsJsonPrimitive("has_more").isNumber()
                && value.getAsJsonPrimitive("has_more").getAsInt() == 1;
        nextCursor = value.has("next_cursor") && value.get("next_cursor").isJsonPrimitive()
                && value.getAsJsonPrimitive("next_cursor").isString()
                ? value.getAsJsonPrimitive("next_cursor").getAsString() : null;
    }
    static ConnectionPage parse(JsonObject value) { return new ConnectionPage(value); }
}
