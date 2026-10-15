package com.pixelflow.server.moderation;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class ModerationController {
    public record Decision(@NotNull @Pattern(regexp="PUBLISHED|REJECTED|WITHDRAWN") String status,
            @NotBlank @Size(max=500) String reason) {}
    private final JdbcTemplate db;
    private final PostService posts;
    public ModerationController(JdbcTemplate db,PostService posts) { this.db=db; this.posts=posts; }
    @GetMapping("/posts") public List<Map<String,Object>> list(@RequestParam(defaultValue="PENDING") String status,
            @RequestParam(defaultValue="20") int count,@RequestParam(required=false) Long cursor) {
        PostService.checkPage(count,cursor);
        if (!Set.of("DRAFT","PENDING","PUBLISHED","REJECTED","WITHDRAWN").contains(status)) throw new ApiException(400,"INVALID_ARGUMENT","状态无效");
        return db.queryForList("SELECT p.*,u.nickname,u.avatar FROM posts p JOIN users u ON p.author_id=u.id WHERE p.status=? AND p.id<? ORDER BY p.id DESC LIMIT ?",
                status,cursor==null ? Long.MAX_VALUE : cursor,count).stream().map(posts::view).toList();
    }
    @PostMapping("/posts/{id}/moderation") @Transactional
    public Map<String,Object> decide(@PathVariable long id,@Valid @RequestBody Decision input,@AuthenticationPrincipal Actor actor) {
        var rows=db.queryForList("SELECT status FROM posts WHERE id=? FOR UPDATE",id);
        if (rows.isEmpty()) throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        String before=(String)rows.get(0).get("status");
        if (!before.equals(input.status())) {
            if (!(before.equals("PENDING") && Set.of("PUBLISHED","REJECTED").contains(input.status()))
                    && !(before.equals("PUBLISHED") && input.status().equals("WITHDRAWN")))
                throw new ApiException(409,"INVALID_STATE","不能执行该审核状态流转");
            db.update("UPDATE posts SET status=? WHERE id=?",input.status(),id);
            db.update("INSERT INTO moderation_records(actor_id,post_id,before_status,after_status,reason,created_at) VALUES(?,?,?,?,?,?)",
                    actor.id(),id,before,input.status(),input.reason(),Instant.now().getEpochSecond());
        }
        return Map.of("postId",Long.toString(id),"status",input.status());
    }
    @GetMapping("/moderation") public List<Map<String,Object>> audit(@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="50") int count) {
        PostService.checkPage(count,null);
        if (after<0) throw new ApiException(400,"INVALID_ARGUMENT","after 不得为负数");
        return db.queryForList("SELECT * FROM moderation_records WHERE id>? ORDER BY id LIMIT ?",after,count);
    }
    @GetMapping("/users") public List<Map<String,Object>> users(@RequestParam(defaultValue="") String after,
            @RequestParam(defaultValue="50") int count) {
        PostService.checkPage(count,null);
        if (after.length()>36) throw new ApiException(400,"INVALID_ARGUMENT","游标过长");
        return db.queryForList("SELECT id,username,nickname,role,enabled FROM users WHERE id>? ORDER BY id LIMIT ?",after,count);
    }
}
