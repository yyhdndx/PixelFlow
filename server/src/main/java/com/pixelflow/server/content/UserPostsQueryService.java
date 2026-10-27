package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class UserPostsQueryService {
    public record Page(int status_code,List<Map<String,Object>> post_list,int has_more,String next_cursor) {}
    private final JdbcTemplate db;
    private final PostService posts;

    public UserPostsQueryService(JdbcTemplate db,PostService posts) { this.db=db; this.posts=posts; }

    public Page page(String id,Actor actor,String rawCount,String rawCursor) {
        if (!id.matches("[A-Za-z0-9_-]{1,36}"))
            throw new ApiException(400,"INVALID_ARGUMENT","用户 ID 格式不正确");
        long parsedCount=rawCount==null ? 20 : positive(rawCount);
        if (parsedCount>50) throw invalid();
        int count=(int)parsedCount;
        Long cursor=rawCursor==null ? null : positive(rawCursor);
        if (db.queryForObject("SELECT COUNT(*) FROM users WHERE id=? AND enabled=TRUE",Integer.class,id)==0)
            throw new ApiException(404,"USER_NOT_FOUND","用户不存在或不可见");
        var arguments=new ArrayList<Object>();
        arguments.add(id);
        if (cursor!=null) arguments.add(cursor);
        arguments.add(count+1);
        var rows=db.queryForList("SELECT p.*,u.nickname,u.avatar FROM posts p JOIN users u ON p.author_id=u.id "
                +"WHERE p.author_id=? AND u.enabled=TRUE AND p.status='PUBLISHED' "
                +(cursor==null ? "" : "AND p.id<? ")+"ORDER BY p.id DESC LIMIT ?",arguments.toArray());
        boolean more=rows.size()>count;
        var selected=rows.subList(0,Math.min(count,rows.size()));
        var cards=selected.stream().map(row -> {
            var card=posts.view(row);
            card.put("is_liked",db.queryForObject(
                    "SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind='likes' AND target_id=?",
                    Integer.class,actor.id(),card.get("post_id"))>0);
            card.put("is_favorited",db.queryForObject(
                    "SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind='favorites' AND target_id=?",
                    Integer.class,actor.id(),card.get("post_id"))>0);
            return card;
        }).toList();
        return new Page(0,cards,more ? 1 : 0,more ? selected.get(selected.size()-1).get("id").toString() : null);
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
