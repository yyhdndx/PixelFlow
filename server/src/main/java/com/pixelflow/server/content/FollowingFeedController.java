package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class FollowingFeedController {
    private final FollowingFeedQueryService queries;

    public FollowingFeedController(FollowingFeedQueryService queries) { this.queries=queries; }

    @GetMapping("/api/feed/following")
    public ResponseEntity<FollowingFeedQueryService.Page> following(@AuthenticationPrincipal Actor actor,
            @RequestParam(required=false) String count,@RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(queries.page(actor,count,cursor));
    }
}
