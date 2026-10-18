package com.pixelflow.server.events;

import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

@RestController
public class AnalyticsExportController {
    private final JdbcTemplate db;
    public AnalyticsExportController(JdbcTemplate db) { this.db=db; }
    @GetMapping("/api/internal/analytics/events") @Transactional
    public Map<String,Object> events(@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="50") int count,HttpServletRequest request) {
        PostService.checkPage(count,null);
        var state=db.queryForMap("SELECT * FROM event_export_state WHERE id=1 FOR UPDATE");
        if (after<0 || after>((Number)state.get("last_seq")).longValue())
            throw new ApiException(400,"INVALID_CURSOR","游标无效或源数据库已更换");
        var rows=db.queryForList("SELECT e.seq,e.event_id,e.user_id,e.post_id,e.event_type,e.occurred_at,e.received_at,"+
                "e.duration_ms,e.scene,e.source,e.recommendation_id,p.author_id,p.has_video,p.title,p.status,u.enabled FROM behavior_events e "+
                "JOIN posts p ON e.post_id=p.id JOIN users u ON p.author_id=u.id WHERE e.seq>? ORDER BY e.seq LIMIT ?",after,count+1);
        boolean more=rows.size()>count;
        var items=new ArrayList<Map<String,Object>>();
        for (var row:rows.subList(0,Math.min(count,rows.size()))) {
            Object status=row.remove("status"), enabled=row.remove("enabled");
            boolean eligible="PUBLISHED".equals(status) && Boolean.TRUE.equals(enabled);
            row.put("eligible",eligible);
            if (!eligible) row.put("title","");
            items.add(row);
        }
        long next=items.isEmpty() ? after : ((Number)items.get(items.size()-1).get("seq")).longValue();
        db.update("INSERT INTO event_export_audit(after_seq,next_seq,row_count,request_id,created_at) VALUES(?,?,?,?,?)",
                after,next,items.size(),request.getAttribute("requestId"),Instant.now().getEpochSecond());
        return Map.of("source_id",state.get("source_id"),"items",items,"next_cursor",next,"has_more",more);
    }
}
