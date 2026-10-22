package com.pixelflow.server;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** YAML 语法和本地引用检查，不代替 OpenAPI 全量语义验证。 */
class OpenApiContractTest {
    @Test void contractIsParseableAndAllLocalReferencesResolve() throws Exception {
        Map<String,Object> document;
        try (var reader=Files.newBufferedReader(Path.of("../contracts/http/java-api.yaml"))) {
            document=new Yaml().load(reader);
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
        var favorites=(Map<?,?>)((Map<?,?>)paths.get("/api/users/me/favorites")).get("get");
        var favoriteParameters=(List<?>)favorites.get("parameters");
        assertEquals(2,favoriteParameters.size());
        var count=(Map<?,?>)((Map<?,?>)favoriteParameters.get(0)).get("schema");
        assertEquals(Map.of("type","integer","minimum",1,"maximum",50,"default",20),count);
        var cursor=(Map<?,?>)((Map<?,?>)favoriteParameters.get(1)).get("schema");
        assertEquals("int64",cursor.get("format")); assertEquals(1,cursor.get("minimum"));
        assertEquals(Long.MAX_VALUE,cursor.get("maximum"));
        var favoriteResponses=(Map<?,?>)favorites.get("responses");
        assertEquals(Set.of("200","400","401"),favoriteResponses.keySet());
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
        assertEquals(List.of("post_id","title","content","create_time","status","media_ids","author","clips","hashtag","music","like_count","is_liked","is_favorited"),favoriteCard.get("required"));
        assertEquals(Map.of("type","boolean","enum",List.of(true)),((Map<?,?>)favoriteCard.get("properties")).get("is_favorited"));
        assertFalse(((Map<?,?>)((Map<?,?>)schemas.get("Post")).get("properties")).containsKey("is_favorited"));
        references(document,document);
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
