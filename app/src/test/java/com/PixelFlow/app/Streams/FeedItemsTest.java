package com.PixelFlow.app.Streams;

import com.PixelFlow.app.API.PostItem;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class FeedItemsTest {
    private WorkItem item(String id) {
        PostItem post = new PostItem();
        post.setPostId(id);
        return new WorkItem(post);
    }

    @Test public void removesDuplicatesAcrossPagesAndWithinIncomingPage() {
        WorkItem first = item("1");
        WorkItem second = item("2");
        List<WorkItem> existing = Collections.singletonList(first);
        List<WorkItem> result = FeedItems.unique(
                Arrays.asList(item("1"), second, item("2")), existing);
        assertEquals(Collections.singletonList(second), result);
        assertEquals(Collections.singletonList(first), existing);
    }

    @Test public void refreshCanReturnPreviouslyDisplayedRecords() {
        WorkItem record = item("1");
        assertEquals(Collections.singletonList(record),
                FeedItems.unique(Collections.singletonList(record), Collections.emptyList()));
    }

    @Test public void invalidRecordsAreSkipped() {
        assertTrue(FeedItems.unique(Arrays.asList(null, item(null), item(" ")),
                Collections.emptyList()).isEmpty());
    }

    @Test public void emptyPageProducesNoRecords() {
        assertTrue(FeedItems.unique(Collections.emptyList(),
                Collections.singletonList(item("1"))).isEmpty());
    }
}
