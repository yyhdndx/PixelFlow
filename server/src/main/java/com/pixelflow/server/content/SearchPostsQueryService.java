package com.pixelflow.server.content;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class SearchPostsQueryService {
    public record Page(int status_code,List<Map<String,Object>> post_list,int has_more,String next_cursor) {}
    private final JdbcTemplate db;
    private final PostService posts;

    public SearchPostsQueryService(JdbcTemplate db,PostService posts) { this.db=db; this.posts=posts; }

    public Page page(String query,String rawCount,String rawCursor,Actor actor) {
        if (query==null) throw invalid();
        String keyword=query.strip();
        if (keyword.isEmpty() || keyword.length()>60) throw invalid();
        if (keyword.startsWith("#")) keyword=keyword.substring(1);
        if (keyword.isBlank()) throw invalid();
        long parsedCount=rawCount==null ? 20 : positive(rawCount);
        if (parsedCount>50) throw invalid();
        int count=(int)parsedCount;
        Long cursor=rawCursor==null ? null : positive(rawCursor);
        String escaped=keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_");
        var arguments=new ArrayList<Object>();
        // 与 AI 智能搜索（intelligence skills/search_posts）保持一致的子串匹配：标题/正文任意位置命中即可
        arguments.add("%"+escaped+"%"); arguments.add("%"+escaped+"%");
        if (cursor!=null) arguments.add(cursor);
        arguments.add(count+1);
        var rows=db.queryForList(sql(cursor!=null),arguments.toArray());
        boolean more=rows.size()>count;
        var selected=rows.subList(0,Math.min(count,rows.size()));
        return new Page(0,selected.stream().map(row -> posts.withViewerState(posts.view(row),actor)).toList(),
                more ? 1 : 0,more ? selected.get(selected.size()-1).get("id").toString() : null);
    }

    public static String sql(boolean cursor) {
        return "SELECT p.*,u.nickname,u.avatar FROM posts p JOIN users u ON p.author_id=u.id "
                +"WHERE p.status='PUBLISHED' AND u.enabled=TRUE "
                +"AND (LOWER(p.title) LIKE ? ESCAPE '!' OR LOWER(p.content) LIKE ? ESCAPE '!') "
                +(cursor ? "AND p.id<? " : "")+"ORDER BY p.id DESC LIMIT ?";
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
        return new ApiException(400,"INVALID_ARGUMENT","q 去除首尾空白后应为 1–60 个 UTF-16 单位且话题不能为空；count 应为 1–50，cursor 应为正整数 long");
    }
}
