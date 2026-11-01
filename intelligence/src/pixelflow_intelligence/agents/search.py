"""Agent 编排层：规则规划 → Skill 调用 → 结果校验 → trace/会话落库。

与 preview 的"确定性 Skill 组合"不同，本层按 Agent 验收要求保留了
规划决策、工具选择结果与执行 trace；但流程仍是单轮有界的——
规划器固定产出"检索一次"的计划，不存在 while/not-satisfied 循环。
"""
import uuid

from ..skills import Context, SkillError, create_registry
from ..skills.search_posts import SearchPostsSource

MAX_SOURCES = 5
NO_ANSWER = ("社区内暂未找到与这个问题足够相关的公开作品。"
             "可以换个说法、减少限定词，或到推荐页浏览更多内容。")


def _fallback_answer(selected):
    titles = " ".join(f"〔{i + 1}〕《{item['title']}》" for i, item in enumerate(selected[:3]))
    return ("AI 总结暂不可用，已为你找到相关度最高的作品，点击查看原文：" + titles)


def answer_question(*, user, question, session_id, upstream, token, settings, store, context,
                    answerer=None):
    """单轮智能搜索：返回带出处回答；任何一步失败都不编造，按降级处理。

    answerer 可注入（测试用），签名 answerer(*, question, history, sources, deadline) -> str。
    """
    registry = create_registry(search_handler=SearchPostsSource(upstream, token))
    # 规划（规则）：社区内检索，单轮，取相关度最高的 MAX_SOURCES 篇综合。
    plan = {"intent": "community_search", "retrieval_rounds": 1, "max_sources": MAX_SOURCES}
    context.checkpoint()
    retrieved = registry.invoke("search_posts", {"user_id": user, "question": question}, context)
    steps = [{"step": "plan", "decision": plan},
             {key: retrieved[key] for key in ("skill", "skill_version", "trace_id", "elapsed_ms")},
             {"step": "validate", "selected": min(MAX_SOURCES, len(retrieved["output"]["items"])),
              "inspected": retrieved["output"]["inspected"]}]
    selected = retrieved["output"]["items"][:MAX_SOURCES]

    history = []
    if store is not None and session_id:
        session = store.load_agent_session(session_id, user)
        if session is not None:
            history = session["turns"][-3:]

    degraded = 0
    if not selected:
        answer = NO_ANSWER
    else:
        provider = answerer
        if provider is None and settings.deepseek_api_key:
            from ..clients.deepseek import DeepSeekAnswer
            provider = DeepSeekAnswer(settings)
        if provider is None:
            degraded, answer = 1, _fallback_answer(selected)
        else:
            try:
                answer = provider(question=question, history=history, sources=selected,
                                  deadline=context.deadline)
            except SkillError:
                degraded, answer = 1, _fallback_answer(selected)
            steps.append({"step": "synthesize", "provider": "deepseek", "ok": not degraded})
        context.checkpoint()

    new_session_id = session_id or str(uuid.uuid4())
    if store is not None:
        store.save_agent_trace(trace_id=context.trace_id, user_id=user, question=question,
                               answer=answer, source_count=len(selected), degraded=degraded,
                               steps=steps, created_at=context.now)
        store.save_agent_session(new_session_id, user, history + [
            {"question": question, "answer": answer}], now=context.now)
    return {"user_id": user, "trace_id": context.trace_id, "session_id": new_session_id,
            "answer": answer, "degraded": degraded,
            "sources": [{"post_id": item["post_id"], "title": item["title"],
                         "excerpt": item["excerpt"], "author_name": item["author_name"]}
                        for item in selected],
            "steps": steps}
