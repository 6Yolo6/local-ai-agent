package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Outbound-only catalog bridge: the local assistant remains bound to loopback. */
@Component
public class GyingBridge {
    private final AssistantService assistant; private final ObjectMapper mapper;
    private final HttpUrl origin; private final String tokenFile;
    private final OkHttpClient client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS)).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(5, TimeUnit.SECONDS).build();
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "gying-catalog-bridge"); t.setDaemon(true); return t;
    });
    private volatile boolean stopping;
    private ObjectNode pending;
    public GyingBridge(AssistantService assistant, ObjectMapper mapper,
            @Value("${LOCAL_AI_GYING_BRIDGE_BASE_URL:${local-ai.gying.base-url:http://127.0.0.1:8880}}") String baseUrl,
            @Value("${LOCAL_AI_GYING_BRIDGE_TOKEN_FILE:}") String tokenFile) {
        this.assistant=assistant; this.mapper=mapper;
        this.origin=GyingTools.checkedOrigin(baseUrl); this.tokenFile=tokenFile;
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        if (!tokenFile.isBlank()) {
            worker.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
            worker.scheduleWithFixedDelay(() -> {
                if(stopping) return;
                try { post("api/internal/ai/heartbeat",mapper.createObjectNode(),Files.readString(Path.of(tokenFile)).trim()); }
                catch(Exception ignored) { }
            },0,5,TimeUnit.SECONDS);
        }
    }
    private void poll() {
        if (stopping) return;
        try {
            String token=Files.readString(Path.of(tokenFile)).trim();
            if (token.isEmpty()) return;
            if (pending == null) {
                JsonNode claim=post("api/internal/ai/claim", mapper.createObjectNode(), token);
                if (!claim.has("jobId")) return;
                String id=claim.path("jobId").asText(), lease=claim.path("lease").asText();
                if (!id.matches("[a-f0-9-]{36}") || !lease.matches("[a-f0-9-]{36}")) return;
                JsonNode request=claim.path("request");
                ObjectNode input=mapper.createObjectNode().put("scope", "gying");
                input.set("message", request.path("message")); input.set("history", request.path("history"));
                ObjectNode result=request.has("purpose") ? assistant.interpretSearch(request) : assistant.run(input);
                pending=mapper.createObjectNode().put("jobId", id).put("lease", lease);
                pending.set("result", result);
            }
            String id=pending.path("jobId").asText();
            ObjectNode body=pending.deepCopy(); body.remove("jobId");
            post("api/internal/ai/jobs/"+id, body, token);
            pending=null;
        } catch (StaleJob e) { pending=null; }
        catch (Exception e) { /* Retry delivery, never replay inference or log private questions/tokens. */ }
    }
    private JsonNode post(String path, ObjectNode body, String token) throws IOException {
        Request request=new Request.Builder().url(origin.newBuilder().addPathSegments(path).build())
                .header("X-AI-Bridge-Token", token).post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
        try (Response response=client.newCall(request).execute()) {
            if (response.code()==409) throw new StaleJob();
            if (!response.isSuccessful() || response.body()==null) throw new IOException("Bridge unavailable");
            byte[] bytes=response.body().byteStream().readNBytes(65537);
            if (bytes.length>65536) throw new IOException("Bridge response too large");
            JsonNode result=mapper.readTree(bytes);
            if (result==null || !result.isObject()) throw new IOException("Invalid bridge response");
            return result;
        }
    }
    @PreDestroy public void close() {
        stopping=true; worker.shutdown();
        try { if (!worker.awaitTermination(185, TimeUnit.SECONDS)) throw new IllegalStateException("Catalog bridge still processing a request"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Bridge drain interrupted"); }
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
    private static class StaleJob extends IOException { }
}
