package com.pixelflow.server.notify;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class NotificationController {
    public record ReadInput(List<@NotBlank @Pattern(regexp="[1-9][0-9]{0,18}") String> ids) {}
    public record PrefsInput(@NotNull Set<@Pattern(regexp="MENTION|REPLY|LIKE") String> mutedTypes) {}
    private final JdbcTemplate db;
    private final NotificationService notifications;
    public NotificationController(JdbcTemplate db,NotificationService notifications) { this.db = db; this.notifications = notifications; }

    @GetMapping("/api/users/me/notifications")
    public Map<String,Object> list(@AuthenticationPrincipal Actor actor,
            @RequestParam String type,@RequestParam(defaultValue="20") int count,
            @RequestParam(required=false) Long cursor) {
        if (!NotificationService.TYPES.contains(type)) throw new ApiException(400,"INVALID_ARGUMENT","通知类型须为 MENTION/REPLY/LIKE");
        PostService.checkPage(count,cursor);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status_code",0);
        if (notifications.isMuted(actor.id(),type)) {
            result.put("notification_list",List.of());
            result.put("has_more",0);
            result.put("next_cursor",null);
            return result;
        }
        var rows=db.queryForList("SELECT n.*,u.nickname,u.avatar,p.title AS post_title FROM notifications n "
                +"JOIN users u ON n.actor_id=u.id JOIN posts p ON n.post_id=p.id "
                +"WHERE n.recipient_id=? AND n.type=? AND n.notification_id<? ORDER BY n.notification_id DESC LIMIT ?",
                actor.id(),type,cursor==null ? Long.MAX_VALUE : cursor,count+1);
        boolean more=rows.size()>count;
        var selected=rows.subList(0,Math.min(count,rows.size()));
        result.put("notification_list",selected.stream().map(this::view).toList());
        result.put("has_more",more ? 1 : 0);
        result.put("next_cursor",more ? selected.get(selected.size()-1).get("notification_id").toString() : null);
        return result;
    }

    @GetMapping("/api/users/me/notifications/unread-count")
    public Map<String,Object> unread(@AuthenticationPrincipal Actor actor) {
        Set<String> muted = notifications.mutedTypes(actor.id());
        List<Object> args = new ArrayList<>();
        args.add(actor.id());
        String sql = "SELECT COUNT(*) FROM notifications WHERE recipient_id=? AND read_flag=FALSE";
        if (!muted.isEmpty()) { sql += " AND type NOT IN (" + String.join(",", Collections.nCopies(muted.size(), "?")) + ")" ; args.addAll(muted); }
        return Map.of("count",db.queryForObject(sql,Long.class,args.toArray()));
    }

    @PostMapping("/api/users/me/notifications/read")
    public Map<String,Object> markRead(@AuthenticationPrincipal Actor actor,@Valid @RequestBody(required=false) ReadInput input) {
        if (input == null || input.ids() == null || input.ids().isEmpty())
            db.update("UPDATE notifications SET read_flag=TRUE WHERE recipient_id=?",actor.id());
        else for (String id : new LinkedHashSet<>(input.ids()))
            db.update("UPDATE notifications SET read_flag=TRUE WHERE recipient_id=? AND notification_id=?",actor.id(),Long.parseLong(id));
        return Map.of("updated",Boolean.TRUE);
    }

    @GetMapping("/api/users/me/notification-preferences")
    public Map<String,Object> prefs(@AuthenticationPrincipal Actor actor) {
        return Map.of("muted_types",notifications.mutedTypes(actor.id()));
    }

    @PutMapping("/api/users/me/notification-preferences")
    public Map<String,Object> savePrefs(@AuthenticationPrincipal Actor actor,@Valid @RequestBody PrefsInput input) {
        notifications.saveMutedTypes(actor.id(),input.mutedTypes());
        return Map.of("muted_types",input.mutedTypes());
    }

    private Map<String,Object> view(Map<String,Object> row) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("notification_id",row.get("notification_id").toString());
        result.put("type",row.get("type"));
        result.put("actor",Map.of("user_id",row.get("actor_id"),"nickname",row.get("nickname"),
                "avatar",row.getOrDefault("avatar","")));
        result.put("post_id",row.get("post_id").toString());
        result.put("post_title",row.get("post_title"));
        if (row.get("comment_id") != null) result.put("comment_id",row.get("comment_id").toString());
        result.put("read",row.get("read_flag"));
        result.put("create_time",row.get("create_time"));
        return result;
    }
}
