package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.NumericPageRequest;
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

    public Page page(Actor actor,NumericPageRequest page) {
        List<Object> args=new ArrayList<>();
        args.add(actor.id()); args.add(actor.id());
        if (page.cursor()!=null) args.add(page.cursor());
        args.add(page.count()+1);
        // Compare canonical strings: MySQL must never coerce targets like "9x" or "09" to post 9.
        var rows=db.queryForList("SELECT p.*,u.nickname,u.avatar,"
                +" CASE WHEN liked.user_id IS NULL THEN 0 ELSE 1 END AS liked_state"
                +" FROM posts p JOIN users u ON u.id=p.author_id"
                +" JOIN interactions favorite ON favorite.user_id=? AND favorite.kind='favorites'"
                +" AND favorite.target_id=TRIM(CAST(p.id AS CHAR(20)))"
                +" LEFT JOIN interactions liked ON liked.user_id=? AND liked.kind='likes'"
                +" AND liked.target_id=TRIM(CAST(p.id AS CHAR(20)))"
                +" WHERE p.status='PUBLISHED' AND u.enabled=TRUE"
                +(page.cursor()==null ? "" : " AND p.id<?")
                +" ORDER BY p.id DESC LIMIT ?",args.toArray());
        boolean more=rows.size()>page.count();
        var cards=rows.subList(0,Math.min(page.count(),rows.size())).stream().map(row -> {
            var card=posts.view(row);
            card.put("is_liked",((Number)row.get("liked_state")).intValue()==1);
            card.put("is_favorited",true);
            return card;
        }).toList();
        return new Page(0,cards,more ? 1 : 0,more ? cards.get(cards.size()-1).get("post_id").toString() : null);
    }
}
