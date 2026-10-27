package com.PixelFlow.app.API;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/** GET /api/users/me/profile/history — paged with before_version cursor and boolean has_more. */
public class ProfileHistoryPage {
    @SerializedName("user_id") private String userId;
    private List<Item> items;
    @SerializedName("has_more") private boolean hasMore;
    @SerializedName("next_cursor") private String nextCursor;

    public String getUserId() { return userId; }
    public List<Item> getItems() { return items; }
    public boolean isHasMore() { return hasMore; }
    public String getNextCursor() { return nextCursor; }

    public static class Item {
        private long version;
        @SerializedName("rule_version") private String ruleVersion;
        @SerializedName("generated_at") private long generatedAt;
        @SerializedName("window_days") private long windowDays;
        private String timezone;
        @SerializedName("cold_start") private boolean coldStart;
        private List<Weight> topics;
        private List<Weight> media;
        @SerializedName("event_counts") private Map<String, Long> eventCounts;
        private String explanation;

        public long getVersion() { return version; }
        public String getRuleVersion() { return ruleVersion; }
        public long getGeneratedAt() { return generatedAt; }
        public long getWindowDays() { return windowDays; }
        public String getTimezone() { return timezone; }
        public boolean isColdStart() { return coldStart; }
        public List<Weight> getTopics() { return topics; }
        public List<Weight> getMedia() { return media; }
        public Map<String, Long> getEventCounts() { return eventCounts; }
        public String getExplanation() { return explanation; }
    }

    public static class Weight {
        private String name;
        private double score;
        private double share;

        public String getName() { return name; }
        public double getScore() { return score; }
        public double getShare() { return share; }
    }
}
