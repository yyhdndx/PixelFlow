package com.PixelFlow.app.API;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** GET/PUT /api/recommendations/me/preferences — the user's recommendation preference snapshot. */
public class PreferenceSnapshot {
    @SerializedName("user_id") private String userId;
    private boolean personalized;
    private long version;
    @SerializedName("excluded_post_ids") private List<String> excludedPostIds;
    @SerializedName("blocked_author_ids") private List<String> blockedAuthorIds;
    @SerializedName("suppressed_topics") private List<String> suppressedTopics;

    public String getUserId() { return userId; }
    public boolean isPersonalized() { return personalized; }
    public long getVersion() { return version; }
    public List<String> getExcludedPostIds() { return excludedPostIds; }
    public List<String> getBlockedAuthorIds() { return blockedAuthorIds; }
    public List<String> getSuppressedTopics() { return suppressedTopics; }
}
