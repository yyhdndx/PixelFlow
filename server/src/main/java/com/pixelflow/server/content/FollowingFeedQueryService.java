package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class FollowingFeedQueryService {
    public record Page(int status_code,List<Map<String,Object>> post_list,int has_more,String next_cursor) {}
    private final JdbcTemplate db;
    private final PostService posts;

    public FollowingFeedQueryService(JdbcTemplate db,PostService posts) { this.db=db; this.posts=posts; }

    public Page page(Actor actor,String rawCount,String rawCursor) {
        long parsedCount=rawCount==null ? 20 : positive(rawCount);
        if (parsedCount>50) throw invalid();
        int count=(int)parsedCount;
        Long cursor=rawCursor==null ? null : positive(rawCursor);
        var arguments=new ArrayList<Object>();
        arguments.add(actor.id());
        if (cursor!=null) arguments.add(cursor);
        arguments.add(count+1);
        var rows=db.queryForList("SELECT p.*,u.nickname,u.avatar FROM posts p JOIN users u ON p.author_id=u.id "
                +"JOIN interactions f ON f.target_id=p.author_id AND f.kind='follows' AND f.user_id=? "
                +"WHERE p.status='PUBLISHED' AND u.enabled=TRUE "
                +(cursor==null ? "" : "AND p.id<? ")+"ORDER BY p.id DESC LIMIT ?",arguments.toArray());
        boolean more=rows.size()>count;
        var selected=rows.subList(0,Math.min(count,rows.size()));
        return new Page(0,selected.stream().map(row -> posts.withViewerState(posts.view(row),actor)).toList(),
                more ? 1 : 0,more ? selected.get(selected.size()-1).get("id").toString() : null);
    }

    private long positive(String value) {
        try {
            if (!value.matches("[0-9]+")) throw invalid();
            long parsed=Long.parseLong(value);
            if (parsed<1) throw invalid();
            return parsed;
        } catch (NumberFormatException e) { throw invalid(); }
    }

    private static ApiException invalid() {
        return new ApiException(400,"INVALID_ARGUMENT","count 应为 1–50，cursor 应为正整数且不超过 Long.MAX_VALUE");
    }
}
