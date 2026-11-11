package com.pixelflow.server.copilot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 应用内 Copilot 的模型侧：只产出提案，不落库；失败返回 empty 由调用方降级。 */
public interface CopilotAdvisor {
    record Result(String reply, List<Map<String,Object>> actions,
                  List<Map<String,Object>> references, String traceId) {
        /** 兼容旧调用点：没有检索结果时 references 为空。 */
        public Result(String reply, List<Map<String,Object>> actions, String traceId) {
            this(reply, actions, List.of(), traceId);
        }
    }

    Optional<Result> chat(String screen, Map<String,Object> context, String message,
                          List<Map<String,Object>> history);

    /** 把更早的若干轮对话压成摘要，失败返回 empty。 */
    Optional<String> compress(String previousSummary, List<Map<String,Object>> messages);
}
