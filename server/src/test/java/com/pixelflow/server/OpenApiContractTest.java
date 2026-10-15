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
