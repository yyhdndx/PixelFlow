package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class SearchPostsController {
    private final SearchPostsQueryService queries;

    public SearchPostsController(SearchPostsQueryService queries) { this.queries=queries; }

    @GetMapping("/api/search/posts")
    public SearchPostsQueryService.Page search(@RequestParam(required=false) String q,
            @RequestParam(required=false) String count,@RequestParam(required=false) String cursor,
            @AuthenticationPrincipal Actor actor) {
        return queries.page(q,count,cursor,actor);
    }
}
