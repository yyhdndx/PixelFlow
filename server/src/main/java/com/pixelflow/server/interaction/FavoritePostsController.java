package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.NumericPageRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class FavoritePostsController {
    private final FavoritePostsQueryService queries;

    public FavoritePostsController(FavoritePostsQueryService queries) { this.queries=queries; }

    @GetMapping("/api/users/me/favorites")
    public FavoritePostsQueryService.Page favorites(@AuthenticationPrincipal Actor actor,
            @RequestParam MultiValueMap<String,String> params,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return queries.page(actor,NumericPageRequest.parse(params,"cursor"));
    }
}
