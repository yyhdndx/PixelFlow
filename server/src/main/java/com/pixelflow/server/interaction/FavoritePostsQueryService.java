package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class FavoritePostsQueryService {
    public record Page(int status_code,List<Map<String,Object>> post_list,int has_more,String next_cursor) {}
    private final JdbcTemplate db;
    private final PostService posts;

    public FavoritePostsQueryService(JdbcTemplate db,PostService posts) { this.db=db; this.posts=posts; }

    public Page page(Actor actor,String countText,String cursor) {
        int count=20;
        if (countText!=null) {
            if (!countText.matches("[1-9][0-9]?")) throw invalidPage();
            count=Integer.parseInt(countText);
            if (count>50) throw invalidPage();
        }
        Long before=null;
        if (cursor!=null) {
            if (!cursor.matches("[1-9][0-9]{0,18}")) throw invalidPage();
            try { before=Long.parseLong(cursor); }
            catch (NumberFormatException e) { throw invalidPage(); }
        }
        List<Object> arguments=new ArrayList<>(List.of(actor.id(),actor.id()));
        if (before!=null) arguments.add(before);
        arguments.add(count+1);
        var rows=db.queryForList("SELECT p.*,u.nickname,u.avatar,"
                +" CASE WHEN liked.user_id IS NULL THEN 0 ELSE 1 END AS liked_state"
                +" FROM interactions favorite JOIN posts p ON favorite.target_id=CONCAT('',p.id)"
                +" JOIN users u ON p.author_id=u.id"
                +" LEFT JOIN interactions liked ON liked.user_id=? AND liked.kind='likes'"
                +" AND liked.target_id=CONCAT('',p.id)"
                +" WHERE favorite.user_id=? AND favorite.kind='favorites'"
                +" AND p.status='PUBLISHED' AND u.enabled=TRUE"
                +(before==null ? "" : " AND p.id<?")+" ORDER BY p.id DESC LIMIT ?",arguments.toArray());
        boolean more=rows.size()>count;
        var selected=rows.subList(0,Math.min(count,rows.size()));
        var cards=selected.stream().map(row -> {
            var card=posts.view(row);
            card.put("is_liked",((Number)row.get("liked_state")).intValue()==1);
            card.put("is_favorited",true);
            return card;
        }).toList();
        return new Page(0,cards,more ? 1 : 0,more ? selected.get(selected.size()-1).get("id").toString() : null);
    }

    private static ApiException invalidPage() {
        return new ApiException(400,"INVALID_ARGUMENT","count 应为 1–50，cursor 应为正 long 整数");
    }
}
