package com.PixelFlow.app.API;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** GET /api/recommendations/me/feed — enriched recommended post cards plus strategy metadata. */
public class RecommendationFeed {
    @SerializedName("post_list") private List<PostItem> postList;
    private String strategy;
    private String reason;
    @SerializedName("profile_version") private long profileVersion;
    @SerializedName("preferences_changed") private boolean preferencesChanged;

    public List<PostItem> getPostList() { return postList; }
    public String getStrategy() { return strategy; }
    public String getReason() { return reason; }
    public long getProfileVersion() { return profileVersion; }
    public boolean isPreferencesChanged() { return preferencesChanged; }
}
