package com.pixelflow.server.comment;

import java.util.List;
import java.util.Optional;

/** 让 Agent 依据作品上下文，把作者的自然语言描述拆成「场景 → 答案」候选。 */
public interface AutoReplySuggester {
    record Rule(String question,String answer) {}
    record Result(List<Rule> rules,boolean degraded,String reason,String traceId) {}

    /** 模型不可用时返回 empty，界面提示手动填写。 */
    Optional<Result> suggest(long postId,String title,String content,String prompt);
}
