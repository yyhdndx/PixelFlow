package com.pixelflow.server.content;

import java.util.List;
import java.util.Optional;

/** 单篇作品的长文提炼；模型不可用时返回 empty，由调用方提示“先读原文”。 */
public interface PostSummaryEngine {
    record Result(String summary,List<String> points,boolean degraded,String reason,String traceId) {}
    Optional<Result> summarize(long postId,String title,String content);
}
