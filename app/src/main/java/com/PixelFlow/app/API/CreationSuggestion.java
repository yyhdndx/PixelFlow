package com.PixelFlow.app.API;

import java.util.List;

/** Preview only: accepting candidates edits the local draft, never publishes a post. */
public final class CreationSuggestion {
    public String suggestion_id, provider, policy_version;
    public List<String> title_candidates, content_candidates, tag_suggestions, edit_suggestions;
    public Safety safety;
    public static final class Safety {
        public String risk_level;
        public List<String> suggestions;
        public boolean requires_human_review;
    }
    public boolean valid() {
        return suggestion_id != null && !suggestion_id.isEmpty() && safety != null
                && java.util.Arrays.asList("low", "medium", "high", "unknown").contains(safety.risk_level)
                && validList(title_candidates, 3, 120) && validList(content_candidates, 3, 5000)
                && validList(tag_suggestions, 20, 32) && validList(edit_suggestions, 10, 500)
                && validList(safety.suggestions, 10, 500)
                && ("deepseek".equals(provider) || "rules".equals(provider));
    }
    private static boolean validList(List<String> values, int count, int length) {
        if (values == null || values.size() > count) return false;
        for (String value : values) if (value == null || value.trim().isEmpty() || value.length() > length) return false;
        return true;
    }
}
