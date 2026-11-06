package com.pixelflow.server.message;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class PrivateMessageController {
    public record CreateInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,36}") String targetUserId) {}
    public record SendInput(@NotBlank @Size(max=1000) String content) {}
    public record ReadInput(@Pattern(regexp="[1-9][0-9]{0,18}") String lastReadMessageId) {}

    private final PrivateMessageService messages;

    public PrivateMessageController(PrivateMessageService messages) { this.messages = messages; }

    @PostMapping("/api/conversations")
    public Map<String, Object> create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody CreateInput input) {
        return messages.getOrCreate(actor.id(), input.targetUserId());
    }

    @GetMapping("/api/conversations")
    public Map<String, Object> list(@AuthenticationPrincipal Actor actor,
            @RequestParam(defaultValue = "20") int count, @RequestParam(required = false) String cursor) {
        checkPage(count);
        return messages.list(actor.id(), count, cursor);
    }

    @GetMapping("/api/conversations/{id}/messages")
    public Map<String, Object> history(@AuthenticationPrincipal Actor actor, @PathVariable long id,
            @RequestParam(defaultValue = "30") int count, @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Long after) {
        checkPage(count);
        if (cursor != null && cursor < 1) throw new ApiException(400, "INVALID_ARGUMENT", "cursor 应为正整数");
        if (after != null && after < 0) throw new ApiException(400, "INVALID_ARGUMENT", "after 应为非负整数");
        return messages.history(actor.id(), id, count, cursor, after);
    }

    @PostMapping("/api/conversations/{id}/messages")
    public Map<String, Object> send(@AuthenticationPrincipal Actor actor, @PathVariable long id,
            @Valid @RequestBody SendInput input) {
        String content = input.content() == null ? "" : input.content().trim();
        if (content.isEmpty()) throw new ApiException(400, "INVALID_ARGUMENT", "消息不能为空");
        if (content.length() > 1000) throw new ApiException(400, "INVALID_ARGUMENT", "消息最多 1000 字");
        return messages.send(actor.id(), id, content);
    }

    @PutMapping("/api/conversations/{id}/read")
    public Map<String, Object> markRead(@AuthenticationPrincipal Actor actor, @PathVariable long id,
            @Valid @RequestBody(required = false) ReadInput input) {
        messages.markRead(actor.id(), id, input == null ? null : parseLong(input.lastReadMessageId()));
        return Map.of("updated", Boolean.TRUE);
    }

    @GetMapping("/api/users/me/messages/unread-count")
    public Map<String, Object> unread(@AuthenticationPrincipal Actor actor) {
        return Map.of("count", messages.unreadTotal(actor.id()));
    }

    private static Long parseLong(String value) {
        if (value == null) return null;
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new ApiException(400, "INVALID_ARGUMENT", "lastReadMessageId 应为正整数"); }
    }

    private static void checkPage(int count) {
        if (count < 1 || count > 50) throw new ApiException(400, "INVALID_ARGUMENT", "count 应为 1–50");
    }
}
