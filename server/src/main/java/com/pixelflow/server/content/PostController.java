package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class PostController {
    private final PostService posts;
    private final PostCreationIdempotencyService idempotency;
    public PostController(PostService posts,PostCreationIdempotencyService idempotency) { this.posts=posts; this.idempotency=idempotency; }
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status","UP"); }
    @GetMapping("/feed/") public Map<String,Object> feed(@RequestParam(defaultValue="20") int count,
            @RequestParam(required=false) Long cursor,@RequestParam(defaultValue="false") boolean accept_video_clip,
            @AuthenticationPrincipal Actor actor) {
        return posts.feed(count,cursor,accept_video_clip,actor);
    }
    @GetMapping("/api/posts/{id}") public Map<String,Object> detail(@PathVariable long id,@AuthenticationPrincipal Actor actor) {
        return posts.detail(id,actor);
    }
    @GetMapping("/api/posts") public List<Map<String,Object>> mine(@AuthenticationPrincipal Actor actor,
            @RequestParam(defaultValue="20") int count,@RequestParam(required=false) Long cursor) {
        return posts.mine(actor,count,cursor);
    }
    @PostMapping("/api/posts")
    public org.springframework.http.ResponseEntity<Map<String,Object>> create(@AuthenticationPrincipal Actor actor,
            @Valid @RequestBody PostService.Draft draft,jakarta.servlet.http.HttpServletRequest request) {
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.isEmpty()) return org.springframework.http.ResponseEntity.status(201).body(posts.create(actor,draft));
        if (keys.size()!=1 || !keys.get(0).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new com.pixelflow.server.common.ApiException(400,"INVALID_ARGUMENT","Idempotency-Key 须为单个标准 UUID");
        var result=idempotency.create(actor,keys.get(0).toLowerCase(Locale.ROOT),draft);
        return org.springframework.http.ResponseEntity.status(result.created() ? 201 : 200).body(result.post());
    }
    @PutMapping("/api/posts/{id}") public Map<String,Object> edit(@PathVariable long id,@AuthenticationPrincipal Actor actor,@Valid @RequestBody PostService.Draft draft) {
        return posts.edit(id,actor,draft);
    }
    @PostMapping("/api/posts/{id}/publish") public Map<String,Object> publish(@PathVariable long id,@AuthenticationPrincipal Actor actor) {
        return posts.transition(id,actor,true);
    }
    @PostMapping("/api/posts/{id}/withdraw") public Map<String,Object> withdraw(@PathVariable long id,@AuthenticationPrincipal Actor actor) {
        return posts.transition(id,actor,false);
    }
}
