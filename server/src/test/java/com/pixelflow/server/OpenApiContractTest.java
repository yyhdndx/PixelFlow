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
