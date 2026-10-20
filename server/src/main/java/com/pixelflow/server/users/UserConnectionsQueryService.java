package com.pixelflow.server.users;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class UserConnectionsQueryService {
    public enum Direction { FOLLOWERS, FOLLOWING }
    public record Card(String user_id,String nickname,String bio,String avatar,boolean is_following) {}
    public record Page(int status_code,List<Card> user_list,int has_more,String next_cursor) {}
    private final JdbcTemplate db;

    public UserConnectionsQueryService(JdbcTemplate db) { this.db=db; }

    public Page page(Actor actor,Direction direction,String countText,String cursor) {
        int count=20;
        if (countText!=null) {
            if (!countText.matches("[1-9][0-9]?")) throw invalidPage();
            count=Integer.parseInt(countText);
            if (count>50) throw invalidPage();
        }
        if (cursor!=null && !cursor.matches("[A-Za-z0-9_-]{1,36}")) throw invalidPage();
        // Only enum-selected constants form SQL identifiers; client values stay bound.
        String other=direction==Direction.FOLLOWERS ? "relation.user_id" : "relation.target_id";
        String owner=direction==Direction.FOLLOWERS ? "relation.target_id" : "relation.user_id";
        var cards=db.query("SELECT u.id,u.nickname,u.bio,u.avatar,"
                +" CASE WHEN outgoing.user_id IS NULL THEN 0 ELSE 1 END AS following_state"
                +" FROM interactions relation JOIN users u ON u.id="+other
                +" LEFT JOIN interactions outgoing ON outgoing.user_id=?"
                +" AND outgoing.kind='follows' AND outgoing.target_id=u.id"
                +" WHERE relation.kind='follows' AND "+owner+"=? AND u.enabled=TRUE AND u.id>?"
                +" ORDER BY u.id ASC LIMIT ?",
                (row,index) -> new Card(row.getString("id"),row.getString("nickname"),row.getString("bio"),
                        row.getString("avatar"),row.getInt("following_state")==1),
                actor.id(),actor.id(),cursor==null ? "" : cursor,count+1);
        boolean more=cards.size()>count;
        List<Card> selected=List.copyOf(cards.subList(0,Math.min(count,cards.size())));
        return new Page(0,selected,more ? 1 : 0,more ? selected.get(selected.size()-1).user_id() : null);
    }

    private static ApiException invalidPage() {
        return new ApiException(400,"INVALID_ARGUMENT","count 应为 1–50，cursor 应为返回的用户 ID");
    }
}
