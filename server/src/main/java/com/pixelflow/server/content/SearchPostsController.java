package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class SearchPostsController {
    private final SearchPostsQueryService queries;

    public SearchPostsController(SearchPostsQueryService queries) { this.queries=queries; }

    @GetMapping("/api/search/posts")
    public SearchPostsQueryService.Page search(@RequestParam(required=false) String q,
            @RequestParam(required=false) String count,@RequestParam(required=false) String cursor,
            @AuthenticationPrincipal Actor actor,HttpServletResponse response) {
        // 游客可缓存；登录用户的点赞/收藏状态个性化，禁止缓存
        if (actor == null) response.setHeader("Cache-Control","public, max-age=60");
        else response.setHeader("Cache-Control","no-store");
        return queries.page(q,count,cursor,actor);
    }
}
