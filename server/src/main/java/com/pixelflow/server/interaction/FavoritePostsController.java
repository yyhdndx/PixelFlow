package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class FavoritePostsController {
    private final FavoritePostsQueryService queries;

    public FavoritePostsController(FavoritePostsQueryService queries) { this.queries=queries; }

    @GetMapping("/api/users/me/favorites")
    public ResponseEntity<FavoritePostsQueryService.Page> favorites(@AuthenticationPrincipal Actor actor,
            @RequestParam(required=false) String count,@RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(queries.page(actor,count,cursor));
    }
}
