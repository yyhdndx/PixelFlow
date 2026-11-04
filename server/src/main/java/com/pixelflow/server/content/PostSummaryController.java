package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * 单篇作品「提炼要点」。只读已公开作品正文，不读取评论、不跨作品归纳。
 * 正文过短时直接拒绝，避免摘要比原文还长。
 */
@RestController
public class PostSummaryController {
    /** 与 Python 侧一致的阈值：低于这个长度不生成摘要。 */
    static final int MIN_CONTENT_LENGTH = 200;

    private final JdbcTemplate db;
    private final PostSummaryEngine engine;

    public PostSummaryController(JdbcTemplate db, PostSummaryEngine engine) {
        this.db = db; this.engine = engine;
    }

    @GetMapping("/api/posts/{id}/summary")
    public Map<String,Object> summary(@PathVariable long id,@AuthenticationPrincipal Actor actor) {
        var posts = db.queryForList("SELECT title,content,status FROM posts WHERE id=?",id);
        if (posts.isEmpty()) throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        var post = posts.get(0);
        if (!"PUBLISHED".equals(post.get("status")))
            throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        String title = String.valueOf(post.get("title"));
        String content = String.valueOf(post.get("content"));
        if (content == null || content.strip().length() < MIN_CONTENT_LENGTH)
            throw new ApiException(400,"SHORT_TEXT","正文较短，不需要提炼");

        var result = engine.summarize(id,title,content);
        if (result.isEmpty())
            throw new ApiException(503,"AI_UNAVAILABLE","AI 提炼暂不可用，可以先读原文");
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("status_code",0);
        response.put("summary",result.get().summary());
        response.put("points",result.get().points());
        response.put("content_length",content.length());
        response.put("trace_id",result.get().traceId());
        return response;
    }
}
