package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import com.pixelflow.server.content.PostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Durable, owner-bound preview and exactly-once application of a selected suggestion. */
@Service
public class CreationSuggestionStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final PostService posts;
    public CreationSuggestionStore(JdbcTemplate db, ObjectMapper json, PostService posts) {
        this.db=db; this.json=json; this.posts=posts;
    }
    @Transactional
    public void save(String id, Actor actor, String title, String content, JsonNode result) {
        db.update("INSERT INTO creation_suggestions(id,user_id,source_title,source_content,result_json,expires_at) VALUES(?,?,?,?,?,?)",
                id,actor.id(),title,content,result.toString(),Instant.now().getEpochSecond()+86400);
    }
    @Transactional
    public Map<String,Object> apply(String suggestionId, long postId, Actor actor, String title, String content, long version) {
        var rows=db.queryForList("SELECT * FROM creation_suggestions WHERE id=? AND user_id=? FOR UPDATE",suggestionId,actor.id());
        if (rows.isEmpty()) throw new ApiException(404,"SUGGESTION_NOT_FOUND","建议不存在，请重新生成");
        var row=rows.get(0);
        String selection=encode(List.of(postId,version,title,content));
        if (row.get("applied_response")!=null) {
            if (!selection.equals(row.get("applied_selection")))
                throw new ApiException(409,"SUGGESTION_ALREADY_APPLIED","此建议已采纳，请重新生成");
            try { return json.readValue((String)row.get("applied_response"),new TypeReference<Map<String,Object>>(){}); }
            catch (Exception e) { throw new IllegalStateException("Invalid stored suggestion response"); }
        }
        if (((Number)row.get("expires_at")).longValue()<Instant.now().getEpochSecond())
            throw new ApiException(409,"SUGGESTION_EXPIRED","建议已过期，请重新生成");
        var current=db.queryForList("SELECT author_id,title,content FROM posts WHERE id=? FOR UPDATE",postId);
        if (current.isEmpty()) throw new ApiException(404,"POST_NOT_FOUND","作品不存在");
        if (!actor.id().equals(current.get(0).get("author_id"))) throw new ApiException(403,"FORBIDDEN","只能修改自己的作品");
        if (!Objects.equals(row.get("source_title"),current.get(0).get("title")) || !Objects.equals(row.get("source_content"),current.get(0).get("content")))
            throw new ApiException(409,"VERSION_CONFLICT","草稿已修改，请重新生成建议");
        try {
            JsonNode result=json.readTree((String)row.get("result_json"));
            if (!allowed(title,(String)row.get("source_title"),result.path("title_candidates"))
                    || !allowed(content,(String)row.get("source_content"),result.path("content_candidates")))
                throw new ApiException(400,"INVALID_SELECTION","请选择本次建议中的候选内容");
        } catch (java.io.IOException e) { throw new IllegalStateException("Invalid stored suggestion"); }
        Map<String,Object> response=posts.applyAssist(postId,actor,title,content,version);
        db.update("UPDATE creation_suggestions SET applied_selection=?,applied_response=? WHERE id=?",selection,encode(response),suggestionId);
        return response;
    }
    private boolean allowed(String selected,String original,JsonNode candidates) {
        if (selected.equals(original)) return true;
        for (JsonNode c:candidates) if (c.isTextual() && selected.equals(c.asText())) return true;
        return false;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Cannot encode suggestion"); }
    }
}
