package com.pixelflow.server;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** YAML 语法和本地引用检查，不代替 OpenAPI 全量语义验证。 */
class OpenApiContractTest {
    @Test void contractIsParseableAndAllLocalReferencesResolve() throws Exception {
        Map<String,Object> document;
        try (var reader=Files.newBufferedReader(Path.of("../contracts/http/java-api.yaml"))) {
            var options=new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            document=new Yaml(options).load(reader);
        }
        assertEquals("3.0.3",document.get("openapi"));
        assertTrue(((Map<?,?>)document.get("paths")).containsKey("/feed/"));
        var profile=(Map<?,?>)((Map<?,?>)document.get("paths")).get("/api/users/me");
        var put=(Map<?,?>)profile.get("put");
        var request=(Map<?,?>)put.get("requestBody");
        var content=(Map<?,?>)request.get("content");
        var example=(Map<?,?>)((Map<?,?>)content.get("application/json")).get("example");
        assertEquals("摄影与旅行\n记录生活",example.get("bio"));
        var schemas=(Map<?,?>)((Map<?,?>)document.get("components")).get("schemas");
        var timeProperties=(Map<?,?>)((Map<?,?>)schemas.get("Post")).get("properties");
        for (String field:List.of("submit_time","review_time","publish_time")) {
            var time=(Map<?,?>)timeProperties.get(field);
            assertEquals("integer",time.get("type")); assertEquals("int64",time.get("format"));
            assertEquals(true,time.get("nullable"));
        }
        var updateProperties=(Map<?,?>)((Map<?,?>)schemas.get("UserProfileUpdate")).get("properties");
        var nickname=(Map<?,?>)updateProperties.get("nickname");
        assertEquals(1,nickname.get("x-utf16-minLength"));
        assertEquals(40,nickname.get("x-utf16-maxLength"));
        assertFalse(nickname.containsKey("minLength"));
        assertFalse(nickname.containsKey("maxLength"));
        var bio=(Map<?,?>)updateProperties.get("bio");
        assertEquals(60,bio.get("x-utf16-maxLength"));
        assertFalse(bio.containsKey("maxLength"));
        var userBio=(Map<?,?>)((Map<?,?>)((Map<?,?>)schemas.get("User")).get("properties")).get("bio");
        assertEquals(60,userBio.get("x-utf16-maxLength"));
        assertFalse(userBio.containsKey("maxLength"));
        var paths=(Map<?,?>)document.get("paths");
        for (String tracePath:List.of("/api/admin/agent/traces","/api/admin/agent/traces/{trace_id}")) {
            var traceOperation=(Map<?,?>)((Map<?,?>)paths.get(tracePath)).get("get");
            var traceResponses=(Map<?,?>)traceOperation.get("responses");
            assertTrue(traceResponses.keySet().containsAll(List.of("200","400","401","403","502")));
        }
        var agent=(Map<?,?>)((Map<?,?>)paths.get("/api/agent/search")).get("post");
        var agentResponses=(Map<?,?>)agent.get("responses");
        assertTrue(agentResponses.keySet().containsAll(List.of("200","400","401","502")));
        assertTrue(agent.get("description").toString().contains("55 秒"));
        var suggestion=(Map<?,?>)((Map<?,?>)paths.get("/api/admin/agent/moderation-suggestion")).get("post");
        var suggestionResponses=(Map<?,?>)suggestion.get("responses");
        assertTrue(suggestionResponses.keySet().containsAll(List.of("200","400","401","403","404","502")));
        assertTrue(suggestion.get("description").toString().contains("15 秒"));
        var suggestionBody=(Map<?,?>)suggestion.get("requestBody");
        assertEquals(true,suggestionBody.get("required"));
        var following=(Map<?,?>)((Map<?,?>)paths.get("/api/feed/following")).get("get");
        assertFalse(following.containsKey("security"));
        assertTrue(following.get("description").toString().contains("重新发布不改变排序位置"));
        assertEquals(Set.of("200","400","401"),((Map<?,?>)following.get("responses")).keySet());
        var followingPage=(Map<?,?>)schemas.get("FollowingFeedPage");
        assertEquals(List.of("status_code","post_list","has_more","next_cursor"),followingPage.get("required"));
        var followingFields=(Map<?,?>)followingPage.get("properties");
        assertEquals(Map.of("$ref","#/components/schemas/Post"),((Map<?,?>)followingFields.get("post_list")).get("items"));
        assertEquals(List.of(0,1),((Map<?,?>)followingFields.get("has_more")).get("enum"));
        assertEquals(true,((Map<?,?>)followingFields.get("next_cursor")).get("nullable"));
        var search=(Map<?,?>)((Map<?,?>)paths.get("/api/search/posts")).get("get");
        assertEquals(List.of(),search.get("security"));
        assertTrue(search.get("description").toString().contains("数字 ID 倒序"));
        var searchQuery=(Map<?,?>)((List<?>)search.get("parameters")).get(0);
        assertEquals("q",searchQuery.get("name")); assertEquals(true,searchQuery.get("required"));
        assertEquals(60,((Map<?,?>)searchQuery.get("schema")).get("x-utf16-maxLength"));
        var createPost=(Map<?,?>)((Map<?,?>)paths.get("/api/posts")).get("post");
        var idempotency=(Map<?,?>)((List<?>)createPost.get("parameters")).get(0);
        assertEquals("Idempotency-Key",idempotency.get("name"));
        assertEquals("header",idempotency.get("in")); assertEquals(false,idempotency.get("required"));
        assertEquals("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",((Map<?,?>)idempotency.get("schema")).get("pattern"));
        var createResponses=(Map<?,?>)createPost.get("responses");
        assertTrue(createResponses.keySet().containsAll(List.of("201","200","400","401","404","409")));
        for (String code:List.of("200","201")) assertEquals(Map.of("$ref","#/components/responses/Post"),createResponses.get(code));
        var conflictExample=(Map<?,?>)((Map<?,?>)((Map<?,?>)((Map<?,?>)createResponses.get("409")).get("content")).get("application/json")).get("example");
        assertEquals("IDEMPOTENCY_CONFLICT",conflictExample.get("code"));
        for (String kind:List.of("followers","following")) {
            var operation=(Map<?,?>)((Map<?,?>)paths.get("/api/users/me/"+kind)).get("get");
            var responses=(Map<?,?>)operation.get("responses");
            assertTrue(responses.keySet().containsAll(List.of("200","400","401")));
        }
        var card=(Map<?,?>)schemas.get("UserConnectionCard");
        assertEquals(false,card.get("additionalProperties"));
        assertEquals(List.of("user_id","nickname","bio","avatar","is_following"),card.get("required"));
        var page=(Map<?,?>)schemas.get("UserConnectionsPage");
        assertEquals(List.of("status_code","user_list","has_more","next_cursor"),page.get("required"));
        assertTrue(paths.containsKey("/api/users/{id}/profile"));
        var publicProfile=(Map<?,?>)schemas.get("PublicUserProfile");
        assertEquals(false,publicProfile.get("additionalProperties"));
        assertEquals(List.of("user_id","nickname","bio","avatar","post_count","follower_count","following_count","is_following"),publicProfile.get("required"));
        var rateLimited=(Map<?,?>)((Map<?,?>)((Map<?,?>)document.get("components")).get("responses")).get("RateLimited");
        assertTrue(((Map<?,?>)rateLimited.get("headers")).keySet().containsAll(List.of("Retry-After","Cache-Control","X-Request-Id")));
        var avatarPath=(Map<?,?>)paths.get("/api/users/me/avatar");
        assertTrue(avatarPath.keySet().containsAll(List.of("put","delete")));
        var avatar=(Map<?,?>)schemas.get("UserAvatar");
        assertEquals(List.of("user_id","avatar"),avatar.get("required"));
        assertEquals(false,avatar.get("additionalProperties"));
        references(document,document);
    }
    @Test void backendWorkflowRequiresRealRedisAndPreservesExistingChecks() throws Exception {
        Map<String,Object> workflow;
        try (var reader=Files.newBufferedReader(Path.of("../.github/workflows/backend.yml"))) {
            var options=new LoaderOptions(); options.setAllowDuplicateKeys(false);
            workflow=new Yaml(options).load(reader);
        }
        var verify=(Map<?,?>)((Map<?,?>)workflow.get("jobs")).get("verify");
        var services=(Map<?,?>)verify.get("services");
        assertEquals("redis:7.4",((Map<?,?>)services.get("redis")).get("image"));
        assertTrue(services.containsKey("mysql"));
        var steps=(List<?>)verify.get("steps");
        String commands=steps.stream().map(step -> Objects.toString(((Map<?,?>)step).get("run"),"")).reduce("",(left,right)->left+"\n"+right);
        assertTrue(commands.contains("-Predis-integration verify"));
        assertTrue(commands.contains("pytest")); assertTrue(commands.contains("verify_profile_pipeline.py"));
    }
    private void references(Object node,Map<String,Object> root) {
        if (node instanceof Map<?,?> map) {
            if (map.get("$ref") instanceof String ref) {
                assertTrue(ref.startsWith("#/"));
                Object target=root;
                for (String part:ref.substring(2).split("/")) {
                    assertInstanceOf(Map.class,target);
                    target=((Map<?,?>)target).get(part.replace("~1","/").replace("~0","~"));
                    assertNotNull(target,ref);
                }
            }
            map.values().forEach(value -> references(value,root));
        } else if (node instanceof List<?> list) list.forEach(value -> references(value,root));
    }
}
