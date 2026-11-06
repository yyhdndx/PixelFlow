package com.PixelFlow.app.API;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonObject;
import okio.Buffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentSearchStreamReaderTest {
    private String frame(String name, int seq, String fields) {
        return "event: " + name + "\ndata: {\"request_id\":\"request\",\"seq\":" + seq + "," + fields + "}\n\n";
    }
    private String prefix() {
        return frame("meta", 1, "\"session_id\":\"session\",\"trace_id\":\"trace\"")
                + frame("sources", 2, "\"sources\":[]")
                + frame("delta", 3, "\"text\":\"首段\"");
    }
    @Test public void publishesPartialBeforeReadingDone() throws Exception {
        Buffer input = new Buffer().writeUtf8(prefix() + frame("done", 4,
                "\"answer\":\"首段\",\"sources\":[],\"session_id\":\"session\",\"trace_id\":\"trace\",\"degraded\":0,\"validated\":true"));
        List<JsonObject> snapshots = new ArrayList<>();
        AgentSearchResult result = AgentSearchStreamReader.read(input, snapshot -> {
            snapshots.add(snapshot);
            assertTrue(input.size() > 0);
        });
        assertTrue(result.valid());
        assertEquals("首段", snapshots.get(2).get("answer").getAsString());
    }
    @Test public void disconnectKeepsPartialButDoesNotReturnSuccess() {
        List<JsonObject> snapshots = new ArrayList<>();
        assertThrows(IOException.class, () -> AgentSearchStreamReader.read(new Buffer().writeUtf8(prefix()), snapshots::add));
        assertEquals("首段", snapshots.get(2).get("answer").getAsString());
    }
    @Test public void rejectsUnvalidatedOrChangedCompletion() {
        for (String fields : new String[]{
                "\"answer\":\"首段\",\"sources\":[],\"session_id\":\"session\",\"trace_id\":\"trace\",\"validated\":false",
                "\"answer\":\"别的问题\",\"sources\":[],\"session_id\":\"session\",\"trace_id\":\"trace\",\"validated\":true"}) {
            assertThrows(IOException.class, () -> AgentSearchStreamReader.read(new Buffer().writeUtf8(prefix() + frame("done", 4, fields)), ignored -> {}));
        }
    }
    @Test public void rejectsOutOfOrderAndOversizedFrames() {
        assertThrows(IOException.class, () -> AgentSearchStreamReader.read(new Buffer().writeUtf8(prefix() + frame("delta", 5, "\"text\":\"迟到\"")), ignored -> {}));
        assertThrows(IOException.class, () -> AgentSearchStreamReader.read(new Buffer().writeUtf8("data: " + new String(new char[16385]).replace('\0', 'x') + "\n"), ignored -> {}));
    }
}
