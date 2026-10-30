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
        var userPosts=(Map<?,?>)((Map<?,?>)paths.get("/api/users/{id}/posts")).get("get");
        var postResponses=(Map<?,?>)userPosts.get("responses");
        assertEquals(Set.of("200","400","401","404"),postResponses.keySet());
        var success=(Map<?,?>)postResponses.get("200");
        assertTrue(((Map<?,?>)success.get("headers")).containsKey("Cache-Control"));
        var successSchema=(Map<?,?>)((Map<?,?>)((Map<?,?>)success.get("content")).get("application/json")).get("schema");
        assertEquals("#/components/schemas/UserPostsPage",successSchema.get("$ref"));
        var postsPage=(Map<?,?>)schemas.get("UserPostsPage");
        assertEquals(false,postsPage.get("additionalProperties"));
        assertEquals(List.of("status_code","post_list","has_more","next_cursor"),postsPage.get("required"));
        var postProperties=(Map<?,?>)postsPage.get("properties");
        assertEquals("integer",((Map<?,?>)postProperties.get("has_more")).get("type"));
        assertEquals("string",((Map<?,?>)postProperties.get("next_cursor")).get("type"));
        assertEquals(true,((Map<?,?>)postProperties.get("next_cursor")).get("nullable"));
        assertEquals("#/components/schemas/Post",((Map<?,?>)((Map<?,?>)postProperties.get("post_list")).get("items")).get("$ref"));
        assertEquals(List.of(Map.of("bearerAuth",List.of())),document.get("security"));
        var favorites=(Map<?,?>)((Map<?,?>)paths.get("/api/users/me/favorites")).get("get");
        var favoriteParameters=(List<?>)favorites.get("parameters");
        assertEquals(2,favoriteParameters.size());
        assertEquals(Map.of("$ref","#/components/parameters/NumericPageCount"),favoriteParameters.get(0));
        var cursor=(Map<?,?>)((Map<?,?>)favoriteParameters.get(1)).get("schema");
        assertEquals("string",cursor.get("type")); assertEquals("^[0-9]+$",cursor.get("pattern"));
        var favoriteResponses=(Map<?,?>)favorites.get("responses");
        assertTrue(favoriteResponses.keySet().containsAll(List.of("200","400","401")));
        var favoriteOk=(Map<?,?>)favoriteResponses.get("200");
        var cache=(Map<?,?>)((Map<?,?>)((Map<?,?>)favoriteOk.get("headers")).get("Cache-Control")).get("schema");
        assertEquals(List.of("no-store"),cache.get("enum"));
        var favoriteContent=(Map<?,?>)((Map<?,?>)favoriteOk.get("content")).get("application/json");
        assertEquals(Map.of("$ref","#/components/schemas/FavoritesPage"),favoriteContent.get("schema"));
        var favoritesPage=(Map<?,?>)schemas.get("FavoritesPage");
        assertEquals(false,favoritesPage.get("additionalProperties"));
        assertEquals(List.of("status_code","post_list","has_more","next_cursor"),favoritesPage.get("required"));
        var favoriteProperties=(Map<?,?>)favoritesPage.get("properties");
        assertEquals(List.of(0),((Map<?,?>)favoriteProperties.get("status_code")).get("enum"));
        assertEquals(List.of(0,1),((Map<?,?>)favoriteProperties.get("has_more")).get("enum"));
        assertEquals(true,((Map<?,?>)favoriteProperties.get("next_cursor")).get("nullable"));
        assertEquals("string",((Map<?,?>)favoriteProperties.get("next_cursor")).get("type"));
        assertEquals(Map.of("$ref","#/components/schemas/FavoritePost"),((Map<?,?>)favoriteProperties.get("post_list")).get("items"));
        var favoriteAllOf=(List<?>)((Map<?,?>)schemas.get("FavoritePost")).get("allOf");
        assertEquals(Map.of("$ref","#/components/schemas/Post"),favoriteAllOf.get(0));
        var favoriteCard=(Map<?,?>)favoriteAllOf.get(1);
        assertEquals(Set.of("post_id","title","content","create_time","status","media_ids","author","clips","hashtag","music","like_count","is_liked","is_favorited"),new HashSet<>((List<?>)favoriteCard.get("required")));
        assertEquals(Map.of("type","boolean","enum",List.of(true)),((Map<?,?>)favoriteCard.get("properties")).get("is_favorited"));
        var basePost=(Map<?,?>)((Map<?,?>)schemas.get("Post")).get("properties");
        assertTrue(basePost.containsKey("is_favorited"));
        assertEquals("boolean",((Map<?,?>)basePost.get("is_favorited")).get("type"));
        assertTrue(paths.containsKey("/api/users/me/profile/history"));
        assertTrue(schemas.containsKey("ProfileHistoryPage"));
        for (String endpoint:List.of("/api/auth/login","/api/auth/register","/api/media")) {
            var responses=(Map<?,?>)((Map<?,?>)((Map<?,?>)paths.get(endpoint)).get("post")).get("responses");
            assertEquals(Map.of("$ref","#/components/responses/RateLimited"),responses.get("429"));
        }
        var rateLimited=(Map<?,?>)((Map<?,?>)((Map<?,?>)document.get("components")).get("responses")).get("RateLimited");
        assertTrue(((Map<?,?>)rateLimited.get("headers")).keySet().containsAll(List.of("Retry-After","Cache-Control","X-Request-Id")));
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
