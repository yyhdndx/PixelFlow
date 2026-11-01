package com.PixelFlow.app.API;

import java.util.List;

/**
 * Agent 智能搜索与多帖总结响应；
 * 契约见 docs/issues/21-java-agent-搜索接口.md（POST /api/agent/search）。
 * answer 中以〔1〕〔2〕标注引用，下标对应 sources 列表（从 1 开始）。
 */
public final class AgentSearchResult {
    public int status_code = -1;
    public String answer;
    public List<Source> sources;
    public String session_id;
    public String trace_id;
    public int degraded;

    public static final class Source {
        public String post_id;
        public String title;
        public String excerpt;
        public String author_name;
    }

    /** 业务成功且有实质回答。sources 允许为空（零结果时 answer 会说明未找到）。 */
    public boolean valid() {
        return status_code == 0 && answer != null && !answer.trim().isEmpty();
    }
}
