package com.PixelFlow.app.Streams;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Returns new records without mutating the current page. */
public final class FeedItems {
    private FeedItems() {}

    public static List<WorkItem> unique(List<WorkItem> incoming, List<WorkItem> existing) {
        Set<String> ids = new HashSet<>();
        for (WorkItem item : existing) {
            if (item != null && item.getId() != null) ids.add(item.getId());
        }
        List<WorkItem> result = new ArrayList<>();
        for (WorkItem item : incoming) {
            if (item != null && item.getId() != null && !item.getId().trim().isEmpty()
                    && ids.add(item.getId())) result.add(item);
        }
        return result;
    }
}
