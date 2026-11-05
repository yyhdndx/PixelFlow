package com.pixelflow.server.comment;

import java.util.List;
import java.util.Optional;

/** 判定一级评论是否命中作者配置的场景。实现可以是 Python Agent 网关，测试可替换为桩。 */
public interface CommentReplyAdvisor {
    record Rule(int index,String question,String answer) {}
    record Match(int index,String answer,String reason,String traceId) {}

    /** 无法判定（模型不可用、超时、上游异常）时返回 empty，调用方据此“不自动回复”。 */
    Optional<Match> decide(long postId,String title,String content,String comment,List<Rule> rules);
}
