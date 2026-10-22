package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserPostsController {
    private final UserPostsQueryService queries;

    public UserPostsController(UserPostsQueryService queries) { this.queries=queries; }

    @GetMapping("/api/users/{id}/posts")
    public ResponseEntity<UserPostsQueryService.Page> posts(@PathVariable String id,
            @AuthenticationPrincipal Actor actor,@RequestParam(required=false) String count,
            @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(queries.page(id,actor,count,cursor));
    }
}
