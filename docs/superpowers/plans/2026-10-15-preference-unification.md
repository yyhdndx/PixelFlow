# 偏好统一（Task 1）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把推荐偏好（个性化开关、作品不感兴趣、作者屏蔽、主题抑制）的唯一写入来源收敛到 Java，Python 改为从 Java 读取偏好、停用旧写入口，消除 Java/Python 双写分歧。

**Architecture:** Java 新增权威偏好表（主表 + 作者子表 + 主题子表），单作品排除复用 `interactions` 表的 `not-interested`（不做第三份存储）。新增 `PreferenceService`（版本自增 + 幂等）与客户端入口 `RecommendationController`；`not-interested` 增强为在变更时同事务递增偏好版本并写 SERVER 反馈事件。Python 删除本地 `Preferences` 写逻辑，预览流水线改为经 `JavaClient.preferences` 读取 Java，旧写端点返回 410。

**Tech Stack:** Java 22 + Spring Boot 3 + Spring JDBC + H2（Flyway 迁移）；Python 3.10 + FastAPI + httpx；pytest；Maven；JUnit 5 + MockMvc。

**Spec:** [docs/architecture/后续接口与实现方案.md](../../architecture/后续接口与实现方案.md)（第 4 节「推荐反馈与画像控制接口」及第 10 节任务 1「偏好统一」）。

## Global Constraints

- 新接口 JSON 用 snake_case；作品 ID 为正整数字符串；时间为 UTC Unix 秒。
- 保留 `/feed/` 与 `PostItem` 字段结构，推荐新增信息以附加字段承载。
- 个性化/偏好响应一律 `Cache-Control: no-store`。
- 偏好 `version` 仅在**实际变更**时递增；幂等重复设置不递增。
- 上限：作品排除 ≤1000、作者屏蔽 ≤500；超限返回 **409**，不静默截断。
- 单作品「不感兴趣」复用 `interactions(kind='not-interested')`，**不**新建第三份排除存储。
- 用户身份一律取自已验证请求上下文（Java `Actor` / Python `upstream.me`），**绝不**信任请求体中的 user_id。
- Java 对外错误沿用 `ApiErrors`（`{status_code, code, message, requestId}`）；参数错误用 `ApiException(400,"INVALID_ARGUMENT",…)`。
- Python 旧偏好写端点只可「代理 Java」或「明确返回停用错误」，**不得**继续本地落盘。
- 支持的主题集合（冻结，与 Python `analytics/profile.py` 的 `TOPICS` 键一致）：`美食, 宠物, 旅行, 阅读, 数码, 运动, 园艺`。

---

## File Structure

**Java（`server/src/main/java/com/pixelflow/server/`）**
- Create `interaction/PreferenceService.java` — 权威偏好读写、版本自增、限制校验。
- Create `interaction/RecommendationController.java` — `/api/recommendations/me/preferences`（GET/PUT）、`/api/recommendations/me/blocked-authors/{author_id}`（PUT）。
- Modify `interaction/InteractionController.java` — `not-interested` 增强（归因字段、版本自增、SERVER 事件、排除上限）。
- Modify `events/EventWriter.java` — `append` 增加可空 `recommendationId`（重载保留旧签名）。
- Modify `users/UserController.java` — `PUT /api/users/me/profile/topics/{topic}`。
- Modify `intelligence/ProfileController.java` — `GET /api/users/me/profile` 附加 `suppressed_topics` 并过滤已抑制主题。

**Java 资源（`server/src/main/resources/db/migration/`）**
- Create `V6__recommendation_preferences.sql` — 3 张偏好表 + `behavior_events.recommendation_id` 列。

**Java 测试（`server/src/test/java/com/pixelflow/server/`）**
- Create `PreferenceApiTest.java` — 端到端偏好/主题/不感兴趣测试。

**Python（`intelligence/src/pixelflow_intelligence/`）**
- Modify `clients/java.py` — 新增 `preferences(token)` 读取 Java 偏好。
- Modify `api/app.py` — GET 偏好代理 Java；三个 PUT 写端点返回 410；预览改用 Java 偏好。
- Delete `recommendation/preferences.py` — 本地偏好写逻辑已废弃。
- Modify `recommendation/preview.py` — 无需改签名（`read_preferences` 参数保留）。

**Python 测试（`intelligence/tests/`）**
- Rewrite `test_preferences.py`、`test_author_blocks.py` — 删除本地 `Preferences` 测试，改测代理/410/预览过滤。
- Modify `test_recommendation_preview.py` — `Source` mock 增加 `preferences` 方法。

**契约**
- Modify `contracts/http/java-api.yaml` — 新增 3 个端点 + 增强 2 个端点。
- Modify `contracts/http/python-analytics.md` — 标注旧写端点已停用、Java 为权威。

---

## Task 1: Java 偏好 schema + PreferenceService + RecommendationController

**Files:**
- Create: `server/src/main/resources/db/migration/V6__recommendation_preferences.sql`
- Create: `server/src/main/java/com/pixelflow/server/interaction/PreferenceService.java`
- Create: `server/src/main/java/com/pixelflow/server/interaction/RecommendationController.java`
- Test: `server/src/test/java/com/pixelflow/server/PreferenceApiTest.java`

**Interfaces:**
- Consumes: `com.pixelflow.server.auth.AuthService.Actor`（record：`id()`、`role()`、`sessionId()`）；`com.pixelflow.server.common.ApiException(int status, String code, String message)`。
- Produces:
  - `PreferenceService.read(Actor) → Map<String,Object>` 键：`user_id, personalized, version, excluded_post_ids, blocked_author_ids, suppressed_topics`。
  - `PreferenceService.setPersonalized(Actor, boolean) → Map`（同上形状）。
  - `PreferenceService.setAuthorBlocked(Actor, String authorId, boolean) → Map`。
  - `PreferenceService.setTopicSuppressed(Actor, String topic, boolean) → Map`。
  - `PreferenceService.suppressedTopics(String userId) → List<String>`（供 ProfileController）。
  - `PreferenceService.bumpVersion(Actor)`（供 InteractionController 在自身事务内调用）。
  - `PreferenceService.MAX_EXCLUSIONS`、`MAX_BLOCKED_AUTHORS`、`SUPPORTED_TOPICS` 常量。

- [ ] **Step 1: 写 V6 迁移脚本**

```sql
CREATE TABLE recommendation_preferences (
  user_id VARCHAR(36) PRIMARY KEY REFERENCES users(id),
  personalized BOOLEAN NOT NULL DEFAULT TRUE,
  version INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE recommendation_blocked_authors (
  user_id VARCHAR(36) NOT NULL REFERENCES users(id),
  author_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (user_id, author_id)
);
CREATE TABLE recommendation_suppressed_topics (
  user_id VARCHAR(36) NOT NULL REFERENCES users(id),
  topic VARCHAR(64) NOT NULL,
  PRIMARY KEY (user_id, topic)
);
ALTER TABLE behavior_events ADD COLUMN recommendation_id VARCHAR(36);
```

- [ ] **Step 2: 写 `PreferenceService.java`**

```java
package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class PreferenceService {
    public static final int MAX_EXCLUSIONS = 1000;
    public static final int MAX_BLOCKED_AUTHORS = 500;
    public static final Set<String> SUPPORTED_TOPICS =
            Set.of("美食","宠物","旅行","阅读","数码","运动","园艺");
    private final JdbcTemplate db;
    public PreferenceService(JdbcTemplate db) { this.db = db; }

    public Map<String,Object> read(Actor actor) { return snapshot(actor.id()); }

    public List<String> suppressedTopics(String userId) {
        return db.queryForList("SELECT topic FROM recommendation_suppressed_topics WHERE user_id=? ORDER BY topic",
                String.class, userId);
    }

    @Transactional
    public Map<String,Object> setPersonalized(Actor actor, boolean personalized) {
        lock(actor);
        boolean before = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_preferences WHERE user_id=? AND personalized=FALSE",
                Integer.class, actor.id()) == 0;
        if (before != personalized) {
            insertRowIfAbsent(actor.id());
            db.update("UPDATE recommendation_preferences SET personalized=?,version=version+1 WHERE user_id=?",
                    personalized, actor.id());
        }
        return snapshot(actor.id());
    }

    @Transactional
    public Map<String,Object> setAuthorBlocked(Actor actor, String authorId, boolean blocked) {
        lock(actor);
        boolean exists = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_blocked_authors WHERE user_id=? AND author_id=?",
                Integer.class, actor.id(), authorId) > 0;
        if (exists != blocked) {
            if (blocked) {
                if (db.queryForObject("SELECT COUNT(*) FROM recommendation_blocked_authors WHERE user_id=?",
                        Integer.class, actor.id()) >= MAX_BLOCKED_AUTHORS)
                    throw new ApiException(409,"BLOCK_LIMIT","屏蔽作者最多 "+MAX_BLOCKED_AUTHORS+" 个");
                db.update("INSERT INTO recommendation_blocked_authors(user_id,author_id) VALUES(?,?)", actor.id(), authorId);
            } else {
                db.update("DELETE FROM recommendation_blocked_authors WHERE user_id=? AND author_id=?", actor.id(), authorId);
            }
            bump(actor.id());
        }
        return snapshot(actor.id());
    }

    @Transactional
    public Map<String,Object> setTopicSuppressed(Actor actor, String topic, boolean suppressed) {
        lock(actor);
        if (!SUPPORTED_TOPICS.contains(topic)) throw new ApiException(400,"UNSUPPORTED_TOPIC","不支持的主题："+topic);
        boolean exists = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_suppressed_topics WHERE user_id=? AND topic=?",
                Integer.class, actor.id(), topic) > 0;
        if (exists != suppressed) {
            if (suppressed) db.update("INSERT INTO recommendation_suppressed_topics(user_id,topic) VALUES(?,?)", actor.id(), topic);
            else db.update("DELETE FROM recommendation_suppressed_topics WHERE user_id=? AND topic=?", actor.id(), topic);
            bump(actor.id());
        }
        return snapshot(actor.id());
    }

    /** 供 InteractionController 在自身事务内调用：不感兴趣状态变化后递增版本。 */
    public void bumpVersion(Actor actor) { bump(actor.id()); }

    private void lock(Actor actor) {
        db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, actor.id());
    }
    private void insertRowIfAbsent(String userId) {
        if (db.queryForObject("SELECT COUNT(*) FROM recommendation_preferences WHERE user_id=?", Integer.class, userId) == 0)
            db.update("INSERT INTO recommendation_preferences(user_id) VALUES(?)", userId);
    }
    private void bump(String userId) {
        insertRowIfAbsent(userId);
        db.update("UPDATE recommendation_preferences SET version=version+1 WHERE user_id=?", userId);
    }
    private Map<String,Object> snapshot(String userId) {
        boolean personalized = db.queryForObject(
                "SELECT COUNT(*) FROM recommendation_preferences WHERE user_id=? AND personalized=FALSE",
                Integer.class, userId) == 0;
        long version = 0L;
        var rows = db.queryForList("SELECT version FROM recommendation_preferences WHERE user_id=?", userId);
        if (!rows.isEmpty()) version = ((Number) rows.get(0).get("version")).longValue();
        return Map.of("user_id", userId, "personalized", personalized, "version", version,
                "excluded_post_ids", db.queryForList(
                        "SELECT target_id FROM interactions WHERE user_id=? AND kind='not-interested' ORDER BY length(target_id),target_id",
                        String.class, userId),
                "blocked_author_ids", db.queryForList(
                        "SELECT author_id FROM recommendation_blocked_authors WHERE user_id=? ORDER BY author_id",
                        String.class, userId),
                "suppressed_topics", db.queryForList(
                        "SELECT topic FROM recommendation_suppressed_topics WHERE user_id=? ORDER BY topic",
                        String.class, userId));
    }
}
```

- [ ] **Step 3: 写 `RecommendationController.java`**

```java
package com.pixelflow.server.interaction;

import com.pixelflow.server.auth.AuthService.Actor;
import com.pixelflow.server.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/recommendations/me")
public class RecommendationController {
    public record Personalized(@NotNull Boolean personalized) {}
    public record Blocked(@NotNull Boolean blocked) {}
    private final PreferenceService preferences;
    public RecommendationController(PreferenceService preferences) { this.preferences = preferences; }

    @GetMapping("/preferences")
    public Map<String,Object> get(@AuthenticationPrincipal Actor actor) { return preferences.read(actor); }

    @PutMapping("/preferences")
    public Map<String,Object> set(@Valid @RequestBody Personalized input, @AuthenticationPrincipal Actor actor) {
        return preferences.setPersonalized(actor, input.personalized());
    }

    @PutMapping("/blocked-authors/{author_id}")
    public Map<String,Object> block(@PathVariable("author_id") String authorId,
            @Valid @RequestBody Blocked input, @AuthenticationPrincipal Actor actor) {
        if (!authorId.matches("[A-Za-z0-9_-]{1,36}"))
            throw new ApiException(400,"INVALID_ARGUMENT","作者 ID 格式无效");
        return preferences.setAuthorBlocked(actor, authorId, input.blocked());
    }
}
```

- [ ] **Step 4: 写 `PreferenceApiTest.java`（先覆盖 Task 1 的行为）**

```java
package com.pixelflow.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:pref-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "pixelflow.seed=false","pixelflow.admin-password=","pixelflow.jwt-secret=test-secret-at-least-thirty-two-bytes-long"})
@AutoConfigureMockMvc
class PreferenceApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    private static final String PASSWORD="test-password-2026";
    @BeforeEach void clean() {
        for (String t:List.of("recommendation_suppressed_topics","recommendation_blocked_authors","recommendation_preferences",
                "event_export_audit","post_media","media","user_status_audit","moderation_records","behavior_events",
                "interactions","posts","sessions","users")) db.update("DELETE FROM "+t);
    }
    private String encode(Object v) throws Exception { return json.writeValueAsString(v); }
    private JsonNode body(MvcResult r) throws Exception { return json.readTree(r.getResponse().getContentAsString()); }
    private String token(String name) throws Exception {
        mvc.perform(post("/api/auth/register").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD,"nickname",name)))).andExpect(status().isCreated());
        return "Bearer "+body(mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(encode(Map.of("username",name,"password",PASSWORD)))).andExpect(status().isOk()).andReturn())
                .get("accessToken").asText();
    }

    @Test void defaultsAndPersonalizedToggleAreIdempotent() throws Exception {
        String alice=token("alice");
        mvc.perform(get("/api/recommendations/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/recommendations/me/preferences").header("Authorization",alice))
                .andExpect(jsonPath("personalized").value(true))
                .andExpect(jsonPath("version").value(0))
                .andExpect(jsonPath("excluded_post_ids.length()").value(0))
                .andExpect(jsonPath("blocked_author_ids.length()").value(0))
                .andExpect(jsonPath("suppressed_topics.length()").value(0));
        mvc.perform(put("/api/recommendations/me/preferences").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("personalized",false))))
                .andExpect(jsonPath("version").value(1)).andExpect(jsonPath("personalized").value(false));
        mvc.perform(put("/api/recommendations/me/preferences").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("personalized",false))))
                .andExpect(jsonPath("version").value(1));
        mvc.perform(put("/api/recommendations/me/preferences").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("personalized",true))))
                .andExpect(jsonPath("version").value(2));
    }

    @Test void authorBlocksBumpVersionAndAreIsolatedPerUser() throws Exception {
        String alice=token("alice"), bob=token("bob");
        mvc.perform(put("/api/recommendations/me/blocked-authors/a-1").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",true))))
                .andExpect(jsonPath("version").value(1)).andExpect(jsonPath("blocked_author_ids[0]").value("a-1"));
        mvc.perform(put("/api/recommendations/me/blocked-authors/a-1").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",true))))
                .andExpect(jsonPath("version").value(1));
        mvc.perform(get("/api/recommendations/me/preferences").header("Authorization",bob))
                .andExpect(jsonPath("blocked_author_ids.length()").value(0)).andExpect(jsonPath("version").value(0));
        mvc.perform(put("/api/recommendations/me/blocked-authors/a-1").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",false))))
                .andExpect(jsonPath("version").value(2)).andExpect(jsonPath("blocked_author_ids.length()").value(0));
    }

    @Test void authorBlockLimitReturns409AndIsNotTruncated() throws Exception {
        String alice=token("alice");
        for (int i=0;i<500;i++)
            mvc.perform(put("/api/recommendations/me/blocked-authors/author-"+i).header("Authorization",alice)
                    .contentType("application/json").content(encode(Map.of("blocked",true)))).andExpect(status().isOk());
        mvc.perform(put("/api/recommendations/me/blocked-authors/overflow").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("blocked",true))))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("BLOCK_LIMIT"));
        assertEquals(500,db.queryForObject("SELECT COUNT(*) FROM recommendation_blocked_authors",Integer.class));
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd server && mvn -q -Dtest=PreferenceApiTest test`
Expected: 4 个测试全部通过（BUILD SUCCESS）。

- [ ] **Step 6: Commit**

```bash
cd server
git add src/main/resources/db/migration/V6__recommendation_preferences.sql \
        src/main/java/com/pixelflow/server/interaction/PreferenceService.java \
        src/main/java/com/pixelflow/server/interaction/RecommendationController.java \
        src/test/java/com/pixelflow/server/PreferenceApiTest.java
git commit -m "feat(server): add authoritative recommendation preferences (schema, service, client endpoints)"
```

---

## Task 2: 主题抑制 + 画像附加抑制信息

**Files:**
- Modify: `server/src/main/java/com/pixelflow/server/users/UserController.java`
- Modify: `server/src/main/java/com/pixelflow/server/intelligence/ProfileController.java`
- Test: `server/src/test/java/com/pixelflow/server/PreferenceApiTest.java`
- Modify: `server/src/test/java/com/pixelflow/server/ProfileGatewayTest.java`

**Interfaces:**
- Consumes: `PreferenceService.setTopicSuppressed(Actor, String, boolean)`；`PreferenceService.suppressedTopics(String userId)`。
- Produces: `PUT /api/users/me/profile/topics/{topic}` 请求体 `{suppressed: bool}`；`GET /api/users/me/profile` 响应新增 `suppressed_topics` 数组且 `topics` 数组已剔除被抑制主题。

- [ ] **Step 1: 写失败测试（在 PreferenceApiTest 追加）**

```java
    @Test void topicSuppressionIsValidatedAndReflectedInProfile() throws Exception {
        String alice=token("alice");
        mvc.perform(put("/api/users/me/profile/topics/未知").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",true))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("UNSUPPORTED_TOPIC"));
        mvc.perform(put("/api/users/me/profile/topics/美食").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",true))))
                .andExpect(status().isOk()).andExpect(jsonPath("version").value(1))
                .andExpect(jsonPath("suppressed_topics[0]").value("美食"));
        mvc.perform(put("/api/users/me/profile/topics/美食").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",true))))
                .andExpect(jsonPath("version").value(1));
        mvc.perform(put("/api/users/me/profile/topics/美食").header("Authorization",alice)
                .contentType("application/json").content(encode(Map.of("suppressed",false))))
                .andExpect(jsonPath("version").value(2));
    }
```

同时在 `ProfileGatewayTest.java` 追加画像合并测试（直接向 `recommendation_suppressed_topics` 插入抑制态，聚焦合并逻辑；主题端点的写入已在 PreferenceApiTest 覆盖）：

```java
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;

    @Test void profileMergesSuppressedTopicsAndFiltersThem() throws Exception {
        status=200;
        String creds=json.writeValueAsString(Map.of("username","suppress_user","password","test-password-2026","nickname","S"));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(creds)).andExpect(status().isCreated());
        var login=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username","suppress_user","password","test-password-2026"))))
                .andExpect(status().isOk()).andReturn();
        String token="Bearer "+json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
        String id=json.readTree(mvc.perform(get("/api/users/me").header("Authorization",token)).andReturn()
                .getResponse().getContentAsString()).get("id").asText();
        response="{\"user_id\":\""+id+"\",\"version\":1,\"topics\":[{\"name\":\"美食\",\"score\":3.0,\"share\":1.0},{\"name\":\"宠物\",\"score\":1.0,\"share\":0.5}]}";
        db.update("INSERT INTO recommendation_suppressed_topics(user_id,topic) VALUES(?,?)", id, "美食");
        mvc.perform(get("/api/users/me/profile").header("Authorization",token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("suppressed_topics[0]").value("美食"))
                .andExpect(jsonPath("topics.length()").value(1))
                .andExpect(jsonPath("topics[0].name").value("宠物"));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=PreferenceApiTest,ProfileGatewayTest test`
Expected: 两个新增测试失败——PreferenceApiTest 的主题测试（`PUT /api/users/me/profile/topics/...` 返回 405/404，`code` 不为 `UNSUPPORTED_TOPIC`）；ProfileGatewayTest 的 `profileMergesSuppressedTopicsAndFiltersThem`（`suppressed_topics` 字段缺失、`topics` 未过滤）。

- [ ] **Step 3: 改 `UserController.java`**

在类中新增注入与端点（构造器签名同步修改）：

```java
import com.pixelflow.server.interaction.PreferenceService;
// ...
public class UserController {
    public record Status(@NotNull Boolean enabled,@NotBlank @Size(max=500) String reason) {}
    public record Topic(@NotNull Boolean suppressed) {}
    private final JdbcTemplate db;
    private final PreferenceService preferences;
    public UserController(JdbcTemplate db,PreferenceService preferences) { this.db=db; this.preferences=preferences; }
    // ...现有方法不变...
    @PutMapping("/api/users/me/profile/topics/{topic}")
    public Map<String,Object> topic(@PathVariable String topic,@Valid @RequestBody Topic input,
            @AuthenticationPrincipal Actor actor) {
        return preferences.setTopicSuppressed(actor, topic, input.suppressed());
    }
}
```

- [ ] **Step 4: 改 `ProfileController.java` 合并抑制信息**

构造器注入 `PreferenceService`，并在返回前合并：

```java
import com.pixelflow.server.interaction.PreferenceService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
// 构造器增加参数 PreferenceService preferences
// 在 return ResponseEntity.ok()... 之前：
    ObjectNode result = (ObjectNode) body;
    ArrayNode topics = (ArrayNode) result.get("topics");
    ArrayNode filtered = json.createArrayNode();
    List<String> suppressed = preferences.suppressedTopics(actor.id());
    for (JsonNode topic : topics)
        if (!suppressed.contains(topic.path("name").asText())) filtered.add(topic);
    result.set("topics", filtered);
    ArrayNode suppressedArr = json.createArrayNode();
    for (String s : suppressed) suppressedArr.add(s);
    result.set("suppressed_topics", suppressedArr);
    return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
```

（`ProfileController` 当前无 `Preferences` 依赖；需在 `@Value` 注入之外新增 `PreferenceService preferences` 字段与构造器参数。）

- [ ] **Step 5: 运行测试确认通过**

Run: `cd server && mvn -q -Dtest=PreferenceApiTest,ProfileGatewayTest test`
Expected: 全部通过。ProfileGatewayTest 通过 `@DynamicPropertySource` 注入本地 HttpServer 画像桩，`profileMergesSuppressedTopicsAndFiltersThem` 验证画像合并逻辑；PreferenceApiTest 因未配置 `pixelflow.intelligence-base-url` 走 503 分支，只验证主题端点。

- [ ] **Step 6: Commit**

```bash
cd server
git add src/main/java/com/pixelflow/server/users/UserController.java \
        src/main/java/com/pixelflow/server/intelligence/ProfileController.java \
        src/test/java/com/pixelflow/server/PreferenceApiTest.java \
        src/test/java/com/pixelflow/server/ProfileGatewayTest.java
git commit -m "feat(server): add topic suppression endpoint and suppress info on profile"
```

---

## Task 3: not-interested 增强（归因 + 版本自增 + SERVER 事件 + 排除上限）

**Files:**
- Modify: `server/src/main/java/com/pixelflow/server/interaction/InteractionController.java`
- Modify: `server/src/main/java/com/pixelflow/server/events/EventWriter.java`
- Test: `server/src/test/java/com/pixelflow/server/PreferenceApiTest.java`

**Interfaces:**
- Consumes: `PreferenceService.bumpVersion(Actor)`；`EventWriter.append(eventId,userId,postId,type,occurredAt,duration,scene,source,recommendationId)`。
- Produces: `PUT /api/interactions/not-interested` 请求体新增可选 `recommendationId`（≤36 字符）、`eventId`（`[A-Za-z0-9_-]{1,64}`）；变更时递增偏好版本、写 `NOT_INTERESTED` SERVER 事件（携带 recommendation_id）。

- [ ] **Step 1: 改 `EventWriter.java` 增加可空 recommendationId**

保留旧 8 参签名作为重载委托到新 9 参方法：

```java
@Transactional(propagation=Propagation.MANDATORY)
public void append(String eventId,String userId,String postId,String type,long occurredAt,
        Long duration,String scene,String source) {
    append(eventId,userId,postId,type,occurredAt,duration,scene,source,null);
}
@Transactional(propagation=Propagation.MANDATORY)
public void append(String eventId,String userId,String postId,String type,long occurredAt,
        Long duration,String scene,String source,String recommendationId) {
    long seq=db.queryForObject("SELECT last_seq FROM event_export_state WHERE id=1 FOR UPDATE",Long.class)+1;
    db.update("UPDATE event_export_state SET last_seq=? WHERE id=1",seq);
    db.update("INSERT INTO behavior_events(seq,event_id,user_id,post_id,event_type,occurred_at,received_at,duration_ms,scene,source,recommendation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            seq,eventId,userId,Long.parseLong(postId),type,occurredAt,Instant.now().getEpochSecond(),duration,scene,source,recommendationId);
}
```

- [ ] **Step 2: 改 `InteractionController.java`**

`Input` 记录新增两个可空字段，`set()` 中为 `not-interested` 增加上限校验、版本自增与 SERVER 事件：

```java
public record Input(@NotBlank @Size(max=36) String targetId,@NotNull Boolean active,
        @Size(max=36) String recommendationId,
        @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String eventId) {}
// 构造器注入 PreferenceService preferences
// set() 中，在 INSERT 之前（kind.equals("not-interested") 且 active 且 !before 时）：
    if (kind.equals("not-interested") && input.active() && !before
            && db.queryForObject("SELECT COUNT(*) FROM interactions WHERE user_id=? AND kind='not-interested'",
                    Integer.class,actor.id()) >= PreferenceService.MAX_EXCLUSIONS)
        throw new ApiException(409,"EXCLUSION_LIMIT","不感兴趣的作品最多 "+PreferenceService.MAX_EXCLUSIONS+" 个");
// 在 likes 事件分支之后追加：
    if (kind.equals("not-interested") && before!=input.active()) {
        preferences.bumpVersion(actor);
        if (input.active())
            events.append(input.eventId()==null ? UUID.randomUUID().toString() : input.eventId(),
                    actor.id(), input.targetId(), "NOT_INTERESTED",
                    java.time.Instant.now().getEpochSecond(), null, null, "SERVER", input.recommendationId());
    }
```

（`before` 变量已在 `set()` 中计算，表示变更前该互动是否存在。）

- [ ] **Step 3: 写失败测试（在 PreferenceApiTest 追加）**

```java
    private String publish(String owner,String admin) throws Exception {
        String id=body(mvc.perform(post("/api/posts").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("title","t","content","","clips",List.of(Map.of("type",0,"width",1,"height",1,"url","https://e.com/a.png"))))))
                .andExpect(status().isCreated()).andReturn()).get("post_id").asText();
        mvc.perform(post("/api/posts/"+id+"/publish").header("Authorization",owner)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/posts/"+id+"/moderation").header("Authorization",admin).contentType("application/json")
                .content(encode(Map.of("status","PUBLISHED","reason","ok")))).andExpect(status().isOk());
        return id;
    }
    private String admin() throws Exception {
        String t=token("adminx"); db.update("UPDATE users SET role='ADMIN' WHERE username='adminx'"); return t;
    }

    @Test void notInterestedBumpsVersionAndWritesServerEvent() throws Exception {
        String owner=token("alice"), id=publish(owner,admin());
        mvc.perform(put("/api/interactions/not-interested").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("targetId",id,"active",true,"recommendationId","rec-1","eventId","ni-1"))))
                .andExpect(status().isOk());
        assertEquals(1,db.queryForObject("SELECT version FROM recommendation_preferences WHERE user_id=(SELECT id FROM users WHERE username='alice')",Integer.class));
        assertEquals("rec-1",db.queryForObject("SELECT recommendation_id FROM behavior_events WHERE event_type='NOT_INTERESTED'",String.class));
        mvc.perform(get("/api/recommendations/me/preferences").header("Authorization",owner))
                .andExpect(jsonPath("excluded_post_ids[0]").value(id));
        // 撤销恢复资格并再次递增版本
        mvc.perform(put("/api/interactions/not-interested").header("Authorization",owner).contentType("application/json")
                .content(encode(Map.of("targetId",id,"active",false)))).andExpect(status().isOk());
        assertEquals(2,db.queryForObject("SELECT version FROM recommendation_preferences WHERE user_id=(SELECT id FROM users WHERE username='alice')",Integer.class));
        mvc.perform(get("/api/recommendations/me/preferences").header("Authorization",owner))
                .andExpect(jsonPath("excluded_post_ids.length()").value(0));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM behavior_events WHERE event_type='NOT_INTERESTED' AND source='SERVER'",Integer.class));
    }
```

- [ ] **Step 4: 运行测试确认失败**

Run: `cd server && mvn -q -Dtest=PreferenceApiTest test`
Expected: `notInterestedBumpsVersionAndWritesServerEvent` 失败（`recommendation_id` 列不存在 / 版本未递增）。

- [ ] **Step 5: 运行全部测试确认通过**

Run: `cd server && mvn -q test`
Expected: 全部通过（含既有 `BusinessApiTest` 的点赞事件断言，因为 `append` 旧签名保持不变）。

- [ ] **Step 6: Commit**

```bash
cd server
git add src/main/java/com/pixelflow/server/interaction/InteractionController.java \
        src/main/java/com/pixelflow/server/events/EventWriter.java \
        src/test/java/com/pixelflow/server/PreferenceApiTest.java
git commit -m "feat(server): enhance not-interested with attribution, version bump and SERVER event"
```

---

## Task 4: Python 改为从 Java 读偏好、停用旧写端点

**Files:**
- Modify: `intelligence/src/pixelflow_intelligence/clients/java.py`
- Modify: `intelligence/src/pixelflow_intelligence/api/app.py`
- Modify: `intelligence/src/pixelflow_intelligence/recommendation/preview.py`（仅确认 `read_preferences` 参数不变）
- Delete: `intelligence/src/pixelflow_intelligence/recommendation/preferences.py`
- Rewrite: `intelligence/tests/test_preferences.py`
- Rewrite: `intelligence/tests/test_author_blocks.py`
- Modify: `intelligence/tests/test_recommendation_preview.py`
- Modify: `intelligence/tests/test_profile_refresh.py`（移除对已删除 `Preferences` 的引用）

**Interfaces:**
- Consumes: Java `GET /api/recommendations/me/preferences`（用户 JWT，返回 `user_id/personalized/version/excluded_post_ids/blocked_author_ids/suppressed_topics`）。
- Produces: `JavaClient.preferences(token) → dict`；Python `GET /recommendations/me/preferences` 代理 Java；三个 PUT 写端点返回 410。

- [ ] **Step 1: 在 `clients/java.py` 新增 `preferences`**

```python
    def preferences(self, token):
        result = self._get("/api/recommendations/me/preferences", token)
        if (not isinstance(result, dict) or not isinstance(result.get("personalized"), bool)
                or not isinstance(result.get("version"), int)
                or not isinstance(result.get("excluded_post_ids"), list)
                or not isinstance(result.get("blocked_author_ids"), list)
                or not isinstance(result.get("suppressed_topics"), list)):
            raise UpstreamError("Invalid preference snapshot")
        return result
```

- [ ] **Step 2: 改 `api/app.py`**

删除 `from ..recommendation.preferences import Preferences, ExclusionLimit` 与 `preferences = Preferences(store)`；新增 GET 代理与三个 410 端点；预览改传 Java 读取闭包。同时删除随之失去引用的三个死模型类 `PersonalizationInput`、`ExclusionInput`、`AuthorBlockInput`（`ProfileRefreshInput` 仍被画像重算端点使用，**保留**）：

```python
    @app.get("/recommendations/me/preferences")
    def get_preferences(response: Response, credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        response.headers["Cache-Control"] = "no-store"
        if credentials is None:
            raise HTTPException(401, "User authentication required")
        try:
            user = upstream.me(credentials.credentials)
        except AuthenticationError:
            raise HTTPException(401, "Invalid or disabled user session") from None
        except UpstreamError:
            raise HTTPException(503, "Java identity service unavailable") from None
        try:
            prefs = upstream.preferences(credentials.credentials)
            if prefs.get("user_id") != user:
                raise UpstreamError("Preference owner mismatch")
            return prefs
        except UpstreamError:
            raise HTTPException(503, "Java recommendation preferences unavailable") from None

    @app.put("/recommendations/me/preferences")
    def set_preferences_disabled():
        raise HTTPException(410, "Recommendation preferences are managed by Java")

    @app.put("/recommendations/me/exclusions/{post_id}")
    def set_exclusion_disabled(post_id: str):
        raise HTTPException(410, "Post exclusions are managed by Java (not-interested)")

    @app.put("/recommendations/me/blocked-authors/{author_id}")
    def set_author_block_disabled(author_id: str):
        raise HTTPException(410, "Author blocks are managed by Java")
```

预览路由改为：

```python
        try:
            user = upstream.me(credentials.credentials)
            return preview(user, store.profile(user), upstream, credentials.credentials,
                           lambda _u: upstream.preferences(credentials.credentials))
```

- [ ] **Step 3: 删除 `recommendation/preferences.py`**

```bash
cd intelligence && rm src/pixelflow_intelligence/recommendation/preferences.py
```

- [ ] **Step 4: 重写 `test_preferences.py`**

```python
from fastapi.testclient import TestClient
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.clients.java import UpstreamError, AuthenticationError
from pixelflow_intelligence.common.settings import Settings


class Java:
    def __init__(self, prefs=None):
        self._prefs = prefs or {"user_id": "alice", "personalized": True, "version": 0,
                                "excluded_post_ids": [], "blocked_author_ids": [], "suppressed_topics": []}

    def me(self, token):
        if token != "alice":
            raise AuthenticationError()
        return "alice"

    def preferences(self, token):
        if token != "alice":
            raise AuthenticationError()
        return self._prefs

    def public_candidates(self, token):
        return [dict(post_id="1", author_id="author", title="咖啡", media_type="image",
                     like_count=0, create_time=100, status="PUBLISHED", author_enabled=True)]


def test_get_preferences_proxies_java(tmp_path):
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java())) as client:
        r = client.get("/recommendations/me/preferences", headers={"Authorization": "Bearer alice"})
        assert r.status_code == 200
        assert r.headers["cache-control"] == "no-store"
        assert r.json()["user_id"] == "alice" and r.json()["version"] == 0
        assert client.get("/recommendations/me/preferences").status_code == 401


def test_write_endpoints_are_disabled(tmp_path):
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java())) as client:
        assert client.put("/recommendations/me/preferences", json={"personalized": False}).status_code == 410
        assert client.put("/recommendations/me/exclusions/1", json={"excluded": True}).status_code == 410
        assert client.put("/recommendations/me/blocked-authors/a", json={"blocked": True}).status_code == 410


def test_preview_filters_using_java_preferences(tmp_path):
    prefs = {"user_id": "alice", "personalized": True, "version": 1,
             "excluded_post_ids": ["1"], "blocked_author_ids": [], "suppressed_topics": []}
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java(prefs))) as client:
        r = client.get("/recommendations/me/preview", headers={"Authorization": "Bearer alice"})
        assert r.status_code == 200 and r.json()["items"] == []
```

- [ ] **Step 5: 重写 `test_author_blocks.py`**

```python
from fastapi.testclient import TestClient
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from test_preferences import Java


def test_author_block_via_java_preferences_filters_preview(tmp_path):
    prefs = {"user_id": "alice", "personalized": True, "version": 1,
             "excluded_post_ids": [], "blocked_author_ids": ["author"], "suppressed_topics": []}
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java(prefs))) as client:
        r = client.get("/recommendations/me/preview", headers={"Authorization": "Bearer alice"})
        assert r.status_code == 200 and r.json()["items"] == []
    prefs["blocked_author_ids"] = []
    with TestClient(create_app(Settings(database=str(tmp_path / "db")), Java(prefs))) as client:
        r = client.get("/recommendations/me/preview", headers={"Authorization": "Bearer alice"})
        assert len(r.json()["items"]) == 1
```

- [ ] **Step 6: 改 `test_recommendation_preview.py` 的 `Source`**

`Source` 类新增 `preferences` 方法（供 `create_app` 预览路由经 `upstream.preferences` 读取）：

```python
    def preferences(self, token):
        return {"user_id": "alice", "personalized": True, "version": 0,
                "excluded_post_ids": [], "blocked_author_ids": [], "suppressed_topics": []}
```

（直接调用 `preview(...)` 的测试仍传自己的 `read_preferences` lambda，签名未变，无需改动。）

- [ ] **Step 7: 改 `test_profile_refresh.py` 移除对已删除 `Preferences` 的引用**

`test_profile_refresh.py` 的 `test_aging_removes_old_interest_and_does_not_touch_cursor_or_preferences` 目前本地导入并使用 `Preferences` 来断言「画像重算不触碰偏好」。偏好已迁至 Java，Python 本地无偏好存储，该断言失去意义。删除 4 处偏好相关行并把函数改名（光标/老化断言保留，仍是本测试的核心价值）：

```python
def test_aging_removes_old_interest_and_does_not_touch_cursor(tmp_path):
    store = Store(str(tmp_path / "db"))
    store.sync_page(lambda _: page([event()]), NOW)
    result = store.refresh_profiles(now=NOW + 31 * 86400)
    assert result["updated_users"] == 1
    profile = store.profile("alice")
    assert profile["version"] == 2 and profile["cold_start"]
    assert profile["topics"] == [] and profile["event_counts"] == {}
    def fetch(after):
        assert after == 1
        return page([], after)
    store.sync_page(fetch, NOW + 31 * 86400)
    assert store.refresh_profiles(now=NOW + 31 * 86400)["updated_users"] == 0
    assert store.profile("alice")["version"] == 2
```

（即删除原函数内的 `from pixelflow_intelligence.recommendation.preferences import Preferences`、`preferences = Preferences(store)`、`preferences.update(...)`、`assert preferences.read(...)...` 四行。）

- [ ] **Step 8: 运行 Python 测试确认通过**

Run: `cd intelligence && python -m pytest tests/ -q`
Expected: 全部通过。若仍残留已删除 `Preferences` 的导入，以 `grep -rn "recommendation.preferences import\|from .*preferences import Preferences" tests/` 确认并一并修正（本计划已覆盖 test_preferences/test_author_blocks/test_recommendation_preview/test_profile_refresh 四处）。

- [ ] **Step 9: Commit**

```bash
cd intelligence
git add src/pixelflow_intelligence/clients/java.py src/pixelflow_intelligence/api/app.py \
        src/pixelflow_intelligence/recommendation/preferences.py tests/test_preferences.py \
        tests/test_author_blocks.py tests/test_recommendation_preview.py tests/test_profile_refresh.py
git commit -m "feat(intelligence): read preferences from Java, disable local preference writes"
```

---

## Task 5: 契约更新 + 全量验证

**Files:**
- Modify: `contracts/http/java-api.yaml`
- Modify: `contracts/http/python-analytics.md`

- [ ] **Step 1: 更新 `java-api.yaml`**

在 `paths` 中新增：

```yaml
  /api/recommendations/me/preferences:
    get:
      description: 登录用户本人推荐偏好快照；Cache-Control no-store。
      responses:
        '200':
          description: 本人偏好
          content:
            application/json:
              schema:
                type: object
                required: [user_id, personalized, version, excluded_post_ids, blocked_author_ids, suppressed_topics]
                properties:
                  user_id: {type: string}
                  personalized: {type: boolean}
                  version: {type: integer}
                  excluded_post_ids: {type: array, items: {type: string}}
                  blocked_author_ids: {type: array, items: {type: string}}
                  suppressed_topics: {type: array, items: {type: string}}
        default: {$ref: '#/components/responses/Error'}
    put:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [personalized]
              properties:
                personalized: {type: boolean}
      responses:
        '200': {description: 更新后的本人偏好，同 GET 形状}
        default: {$ref: '#/components/responses/Error'}
  /api/recommendations/me/blocked-authors/{author_id}:
    parameters:
      - {name: author_id, in: path, required: true, schema: {type: string, pattern: '^[A-Za-z0-9_-]{1,36}$'}}
    put:
      description: 推荐专用作者屏蔽，不是封禁或社交访问控制。
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [blocked]
              properties:
                blocked: {type: boolean}
      responses:
        '200': {description: 更新后的本人偏好，同 GET 形状；超 500 个返回 409 BLOCK_LIMIT}
        default: {$ref: '#/components/responses/Error'}
  /api/users/me/profile/topics/{topic}:
    parameters:
      - {name: topic, in: path, required: true, schema: {type: string}}
    put:
      description: 记录兴趣标签抑制；topic 必须在支持集合内，否则 400 UNSUPPORTED_TOPIC。
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              additionalProperties: false
              required: [suppressed]
              properties:
                suppressed: {type: boolean}
      responses:
        '200': {description: 更新后的本人偏好，同推荐偏好形状}
        default: {$ref: '#/components/responses/Error'}
```

增强 `/api/interactions/{kind}` 的 `put` requestBody schema（在 `targetId`/`active` 后追加）：

```yaml
                recommendationId: {type: string, maxLength: 36, description: 可选推荐批次归因}
                eventId: {type: string, pattern: '^[A-Za-z0-9_-]{1,64}$', description: 可选客户端反馈幂等键}
```

增强 `/api/users/me/profile` 的 GET 200 schema（在 `topics` 后追加）：

```yaml
                  suppressed_topics: {type: array, items: {type: string}, description: 已抑制主题；topics 已剔除这些主题}
```

- [ ] **Step 2: 更新 `python-analytics.md` 的「本人推荐偏好」小节**

将「本人推荐偏好」整节替换为停用说明：

```markdown
## 本人推荐偏好

推荐偏好已迁移至 Java（`GET/PUT /api/recommendations/me/*`），Java 为唯一写入来源。本服务只读代理：

- `GET /recommendations/me/preferences`：使用用户 access token，代理 Java 同名端点返回本人偏好快照。
- 旧写端点 `PUT /recommendations/me/preferences`、`PUT /recommendations/me/exclusions/{post_id}`、`PUT /recommendations/me/blocked-authors/{author_id}`：一律返回 410，不再本地落盘。请改用 Java 的 `/api/recommendations/me/preferences`、`/api/interactions/not-interested`、`/api/recommendations/me/blocked-authors/{author_id}`。

预览流水线（`GET /recommendations/me/preview`）通过上述 GET 端点读取 Java 权威偏好；单作品排除、作者屏蔽、个性化开关均来自 Java。
```

- [ ] **Step 3: 运行契约测试与全量测试**

```bash
cd server && mvn -q test
cd ../intelligence && python -m pytest tests/ -q
```

Expected: Java 全部通过（含 `OpenApiContractTest` 校验 YAML 引用与新增路径）；Python 全部通过。

- [ ] **Step 4: Commit**

```bash
git add contracts/http/java-api.yaml contracts/http/python-analytics.md
git commit -m "docs(contracts): document authoritative preference endpoints and Python deprecation"
```

---

## Self-Review（已内联修正）

- **Spec 覆盖**：第 4.1 节 6 个客户端入口全部落地（preferences GET/PUT、blocked-authors PUT、topics PUT、not-interested 增强、profile 增强）；第 4.2 节「复用 interactions 不做第三份存储」「仅实际变更递增 version」「1000/500 上限返回 409」均实现；「Python 旧写入口停用」通过 410 达成。
- **占位符扫描**：无 TBD/TODO；所有代码步骤均给出完整实现。
- **类型一致性**：`PreferenceService` 的 `read`/`set*` 返回值形状与 `JavaClient.preferences` 校验字段、契约 schema 三处一致（`user_id/personalized/version/excluded_post_ids/blocked_author_ids/suppressed_topics`）；`EventWriter.append` 旧签名重载保证既有调用不破坏。
- **范围边界**：内部候选/校验接口（第 5 节）、推荐交付快照、Agent/MCP、Web、Android 偏好 UI 均**不在**本任务范围，留待任务 2+。

---

## Execution Handoff

计划已保存。开始实现前需确认：当前在 `master` 分支直接提交（用户已选择「继续在 master」）。
