package com.pixelflow.server.users;

import com.pixelflow.server.auth.AuthService.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class UserConnectionsController {
    private final UserConnectionsQueryService queries;

    public UserConnectionsController(UserConnectionsQueryService queries) {
        this.queries=queries;
    }

    @GetMapping("/api/users/me/followers")
    public ResponseEntity<UserConnectionsQueryService.Page> followers(@AuthenticationPrincipal Actor actor,
            @RequestParam(required=false) String count, @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(queries.page(actor,UserConnectionsQueryService.Direction.FOLLOWERS,count,cursor));
    }

    @GetMapping("/api/users/me/following")
    public ResponseEntity<UserConnectionsQueryService.Page> following(@AuthenticationPrincipal Actor actor,
            @RequestParam(required=false) String count, @RequestParam(required=false) String cursor) {
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .body(queries.page(actor,UserConnectionsQueryService.Direction.FOLLOWING,count,cursor));
    }
}
