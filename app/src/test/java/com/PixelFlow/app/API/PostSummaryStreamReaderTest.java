package com.PixelFlow.app.API;

import org.junit.Test;
import okio.Buffer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PostSummaryStreamReaderTest {
    private String frame(String event, int seq, String fields) {
        return "event: " + event + "\ndata: {\"seq\":" + seq + "," + fields + "}\n\n";
    }

    @Test public void summaryAndCompletePointsArePublishedBeforeDone() throws Exception {
        String body = frame("summary", 1, "\"summary\":\"概括\"");
        for (int i = 0; i < 3; i++) body += frame("point", i + 2, "\"point\":\"要点" + i + "\"");
        body += frame("done", 5, "\"summary\":\"概括\",\"points\":[\"要点0\",\"要点1\",\"要点2\"],\"validated\":true");
        List<String> summaries = new ArrayList<>();
        PostSummary result = PostSummaryStreamReader.read(new Buffer().writeUtf8(body), partial -> summaries.add(partial.toString()));
        assertEquals(4, summaries.size());
        assertTrue(summaries.get(0).contains("概括"));
        assertFalse(summaries.get(0).contains("要点0"));
        assertEquals(3, result.points.size());
    }

    @Test public void missingCompletionDoesNotReturnSuccessfulSummary() {
        assertThrows(IOException.class, () -> PostSummaryStreamReader.read(new Buffer().writeUtf8(frame("summary", 1, "\"summary\":\"概括\"")), ignored -> {}));
    }
}
