package com.pixelflow.server.intelligence;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixelflow.server.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** ADMIN authorization is enforced by the existing admin-path security rule. */
@RestController
@RequestMapping("/api/admin/agent/traces")
public class AgentTraceController {
    static final Duration BUDGET=Duration.ofSeconds(10);
    private final ObjectMapper json;
    private final URI origin;
    private final String serviceToken;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public AgentTraceController(ObjectMapper json, @Value("${pixelflow.intelligence-base-url:}") String base,
            @Value("${PIXELFLOW_INTELLIGENCE_TOKEN:}") String token) {
        this.json=json; serviceToken=token;
        if (base.isBlank()) { origin=null; return; }
        URI uri=URI.create(base);
        if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new IllegalStateException("Intelligence URL must be an HTTP(S) origin");
        origin=uri;
    }

    @GetMapping
    public ResponseEntity<JsonNode> list(@RequestParam MultiValueMap<String,String> params) {
        for(var entry:params.entrySet()) if (!Set.of("count","cursor").contains(entry.getKey()) || entry.getValue().size()!=1) throw argument();
        long count=params.containsKey("count") ? positive(params.getFirst("count"),false) : 20;
        if (count>50) throw argument();
        Long cursor=params.containsKey("cursor") ? positive(params.getFirst("cursor"),false) : null;
        var root=fetch("/internal/traces?count="+count+(cursor==null ? "" : "&cursor="+cursor),false,BUDGET);
        envelope(root);
        var rows=root.path("traces");
        if (!rows.isArray() || rows.size()>count || !root.has("next_cursor")) throw invalid();
        var result=json.createObjectNode().put("status_code",0);
        var output=result.putArray("traces"); Set<String> seen=new HashSet<>();
        for(var row:rows) {
            var item=common(row,true);
            if (!seen.add(item.path("trace_id").asText().toLowerCase(Locale.ROOT))) throw invalid();
            item.put("source_count",number(row,"source_count",5)); output.add(item);
        }
        var next=root.get("next_cursor");
        if (next.isNull()) result.putNull("next_cursor");
        else {
            if (!next.isTextual() || rows.isEmpty()) throw invalid();
            long value=positive(next.asText(),true);
            if (cursor!=null && value>=cursor) throw invalid();
            result.put("next_cursor",Long.toString(value));
        }
        return response(result);
    }

    @GetMapping("/{trace_id}")
    public ResponseEntity<JsonNode> detail(@PathVariable("trace_id") String id) {
        if (!uuid(id)) throw argument();
        var root=fetch("/internal/traces/"+id,true,BUDGET); envelope(root);
        var trace=root.path("trace"); var result=common(trace,false);
        if (!id.equalsIgnoreCase(result.path("trace_id").asText())) throw invalid();
        String session=text(trace,"session_id",36);
        if (!session.isEmpty() && !uuid(session)) throw invalid();
        result.put("session_id",session).put("degraded_reason",text(trace,"degraded_reason",500))
                .put("answer",text(trace,"answer",1500));
        var retrieval=trace.path("retrieval");
        var outRetrieval=result.putObject("retrieval").put("query",text(retrieval,"query",200))
                .put("candidate_count",number(retrieval,"candidate_count",Integer.MAX_VALUE));
        var ids=retrieval.path("selected_ids");
        if (!ids.isArray() || ids.size()>5) throw invalid();
        Set<String> selected=new HashSet<>(); var outIds=outRetrieval.putArray("selected_ids");
        for(var value:ids) {
            if (!value.isTextual() || !postId(value.asText()) || !selected.add(value.asText())) throw invalid();
            outIds.add(value.asText());
        }
        var llm=trace.path("llm");
        result.putObject("llm").put("model",text(llm,"model",64)).put("latency_ms",number(llm,"latency_ms",Long.MAX_VALUE));
        var sources=trace.path("sources");
        if (!sources.isArray() || sources.size()>5) throw invalid();
        Set<String> sourceIds=new HashSet<>(); var outSources=result.putArray("sources");
        for(var source:sources) {
            String post=text(source,"post_id",18);
            if (!postId(post) || !sourceIds.add(post)) throw invalid();
            outSources.addObject().put("post_id",post).put("title",text(source,"title",120));
        }
        return response(json.createObjectNode().put("status_code",0).set("trace",result));
    }

    // Package-visible budget for fast slow-body deadline tests; production always uses 10 seconds.
    JsonNode fetch(String path, boolean detail, Duration budget) {
        if (origin==null || serviceToken.length()<32 || !serviceToken.chars().allMatch(c->c>=33 && c<=126)) throw unavailable();
        long deadline=System.nanoTime()+budget.toNanos();
        CompletableFuture<HttpResponse<String>> pending=null;
        try {
            var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(budget).header("Authorization","Bearer "+serviceToken)
                    .header("Accept","application/json").GET().build();
            pending=client.sendAsync(request,info->new SearchResponseBody());
            var reply=pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            if (reply.statusCode()==404 && detail) throw new ApiException(404,"TRACE_NOT_FOUND","轨迹不存在");
            if (reply.statusCode()!=200) throw unavailable();
            try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(reply.body()); }
            catch(java.io.IOException error) { throw invalid(); }
        } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch(ExecutionException | TimeoutException error) { throw unavailable(); }
        finally { if(pending!=null && !pending.isDone()) pending.cancel(true); }
    }

    private ObjectNode common(JsonNode row, boolean summary) {
        String id=text(row,"trace_id",36), user=text(row,"user_id",36), question=text(row,"question",200);
        if (!uuid(id) || user.isBlank()) throw invalid();
        if (summary && question.codePointCount(0,question.length())>80) question=question.substring(0,question.offsetByCodePoints(0,80));
        return json.createObjectNode().put("trace_id",id).put("user_id",user).put("question",question)
                .put("created_at",number(row,"created_at",Long.MAX_VALUE)).put("latency_ms",number(row,"latency_ms",Long.MAX_VALUE))
                .put("degraded",number(row,"degraded",1)).put("provider",text(row,"provider",64));
    }
    private void envelope(JsonNode root) {
        if(root==null || !root.isObject()) throw invalid();
        if(root.has("status_code") && (!root.get("status_code").isIntegralNumber() || !root.get("status_code").asText().equals("0"))) throw invalid();
    }
    private ResponseEntity<JsonNode> response(JsonNode result) {
        // Never echo our shared credential even if an upstream field accidentally contains it.
        if (!serviceToken.isEmpty() && result.toString().contains(serviceToken)) throw invalid();
        return ResponseEntity.ok().header("Cache-Control","no-store").body(result);
    }
    private String text(JsonNode row,String key,int limit) {
        var value=row.path(key);
        if(!value.isTextual() || value.asText().codePointCount(0,value.asText().length())>limit) throw invalid();
        if (!serviceToken.isEmpty() && value.asText().contains(serviceToken)) throw invalid();
        return value.asText();
    }
    private long number(JsonNode row,String key,long max) {
        var value=row.path(key);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0 || value.longValue()>max) throw invalid();
        return value.longValue();
    }
    private long positive(String raw,boolean upstream) {
        try {
            if(raw==null || !raw.matches("[0-9]+")) throw new NumberFormatException();
            long value=Long.parseLong(raw); if(value<=0) throw new NumberFormatException(); return value;
        } catch(NumberFormatException error) { throw upstream ? invalid() : argument(); }
    }
    private boolean uuid(String value) { return value.matches(AgentSearchController.UUID_PATTERN); }
    private boolean postId(String value) { return value.matches("[1-9][0-9]{0,17}"); }
    private static ApiException argument() { return new ApiException(400,"INVALID_ARGUMENT","count 应为 1–50，cursor 应为正整数 long，trace_id 应为 UUID"); }
    private static ApiException unavailable() { return new ApiException(502,"AGENT_TRACE_UNAVAILABLE","轨迹查询服务暂时不可用"); }
    private static ApiException invalid() { return new ApiException(502,"INVALID_AGENT_TRACE_RESPONSE","轨迹查询响应无效"); }
}
