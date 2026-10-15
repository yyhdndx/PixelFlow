package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class PostController {
    private final PostService posts;
    public PostController(PostService posts) { this.posts=posts; }
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
    @PostMapping("/api/posts") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String,Object> create(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PostService.Draft draft) {
        return posts.create(actor,draft);
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
