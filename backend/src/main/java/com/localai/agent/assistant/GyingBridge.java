package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
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
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "gying-catalog-bridge"); t.setDaemon(true); return t;
    });
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r,"gying-bridge-maintenance"); t.setDaemon(true); return t;
    });
    private volatile boolean stopping;
    private volatile ObjectNode pending;
    private final EncryptedAiState state;
    private volatile boolean stale;
    private volatile boolean blocked;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GyingBridge.class);
    public GyingBridge(AssistantService assistant,ObjectMapper mapper,String baseUrl,String tokenFile) {
        this(assistant,mapper,baseUrl,tokenFile,"","");
    }
    @Autowired
    public GyingBridge(AssistantService assistant, ObjectMapper mapper,
            @Value("${LOCAL_AI_GYING_BRIDGE_BASE_URL:${local-ai.gying.base-url:http://127.0.0.1:8880}}") String baseUrl,
            @Value("${LOCAL_AI_GYING_BRIDGE_TOKEN_FILE:}") String tokenFile,
            @Value("${LOCAL_AI_GYING_STATE_FILE:}") String filename,
            @Value("${LOCAL_AI_GYING_STATE_KEY_FILE:}") String keyFile) {
        this.assistant=assistant; this.mapper=mapper;
        this.origin=GyingTools.checkedOrigin(baseUrl); this.tokenFile=tokenFile;
        if(filename.isBlank()!=keyFile.isBlank())throw new IllegalStateException("Both bridge state settings are required");
        state=filename.isBlank()?null:new EncryptedAiState(mapper,filename,keyFile);
        if(state!=null)try {
            JsonNode saved=state.read();
            if(!saved.isEmpty()) {
                if(saved.path("version").asInt()!=1 || !origin.toString().equals(saved.path("origin").asText())
                        || !saved.path("pending").isObject())throw new IllegalStateException("Invalid bridge checkpoint");
                pending=(ObjectNode)saved.path("pending").deepCopy();
                if(!validId(pending.path("jobId").asText()) || !validId(pending.path("lease").asText())
                        || !pending.path("retainUntil").isTextual())throw new IllegalStateException("Invalid bridge receipt");
                Instant.parse(pending.path("retainUntil").asText());
                if(!Instant.now().isBefore(Instant.parse(pending.path("retainUntil").asText()))) { clear(); }
                else if(!pending.has("result")) {
                    pending.set("result",failure("BRIDGE_INTERRUPTED","本机执行中断，请重新提问"));
                    checkpoint();
                }
            }
        } catch(RuntimeException e) { state.close(); throw e; }
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        if (!tokenFile.isBlank()) {
            worker.scheduleWithFixedDelay(this::pollOnce, 0, 2, TimeUnit.SECONDS);
            maintenance.scheduleWithFixedDelay(() -> {
                if(blocked) return;
                renewOnce();
                if(stopping) return;
                try { post("api/internal/ai/heartbeat",mapper.createObjectNode(),Files.readString(Path.of(tokenFile)).trim()); }
                catch(Exception ignored) { }
            },0,5,TimeUnit.SECONDS);
        }
    }
    synchronized void pollOnce() {
        if (stopping || blocked) return;
        try {
            if(stale || pending!=null && !Instant.now().isBefore(Instant.parse(pending.path("retainUntil").asText()))) {
                clear(); stale=false;
            }
            String token=Files.readString(Path.of(tokenFile)).trim();
            if(token.isEmpty())return;
            if(pending==null) {
                JsonNode claim=post("api/internal/ai/claim",mapper.createObjectNode().put("leaseProtocol",1),token);
                if(!claim.has("jobId"))return;
                String id=claim.path("jobId").asText(),lease=claim.path("lease").asText();
                if(!validId(id) || !validId(lease))return;
                JsonNode request=claim.path("request");
                ObjectNode input=mapper.createObjectNode().put("scope","gying");
                input.set("message",request.path("message")); input.set("history",request.path("history")); if(request.path("context").isObject())input.set("context",request.path("context"));
                pending=mapper.createObjectNode().put("jobId",id).put("lease",lease)
                        .put("retainUntil",Instant.now().plusSeconds(900).toString());
                checkpoint(); // Persist the attempt before model execution; an interrupted attempt is never replayed.
                ObjectNode result;
                try { result=request.has("purpose")?assistant.interpretSearch(request):assistant.runForJob(input,id,lease); }
                catch(Exception e) { result=failure("BRIDGE_EXECUTION_FAILED","本机查询未完成，请重试"); }
                pending.set("result",result);
                checkpoint(); // Delivery is allowed only after the result is durable.
            }
            if(stale) { clear(); stale=false; return; }
            ObjectNode body=mapper.createObjectNode().put("lease",pending.path("lease").asText());
            body.set("result",pending.path("result"));
            post("api/internal/ai/jobs/"+pending.path("jobId").asText(),body,token);
            clear();
        } catch(StaleJob e) {
            try { clear(); stale=false; } catch(RuntimeException failure) { block(); }
        } catch(RuntimeException e) { block(); }
        catch(Exception e) { /* Keep the durable receipt and retry transport only. */ }
    }
    void renewOnce() {
        ObjectNode current=pending;
        if(current==null || blocked || stale)return;
        try {
            post("api/internal/ai/jobs/"+current.path("jobId").asText()+"/renew",
                    mapper.createObjectNode().put("lease",current.path("lease").asText()),
                    Files.readString(Path.of(tokenFile)).trim());
        } catch(StaleJob e) { if(pending==current)stale=true; }
        catch(Exception ignored) { }
    }
    private ObjectNode failure(String code,String message) {
        return mapper.createObjectNode().put("scope","gying").put("answer","").put("status","error")
                .set("error",mapper.createObjectNode().put("code",code).put("message",message));
    }
    private static boolean validId(String value) { return value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private void checkpoint() {
        if(state!=null) {
            ObjectNode saved=mapper.createObjectNode().put("version",1).put("origin",origin.toString());
            saved.set("pending",pending.deepCopy()); state.write(saved);
        }
    }
    private void clear() { if(state!=null)state.write(mapper.createObjectNode()); pending=null; }
    private void block() { blocked=true; log.error("GYing bridge stopped: private checkpoint could not be committed; operator review required"); }
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
        maintenance.shutdown();
        try { if(!maintenance.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Bridge maintenance still running"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Bridge maintenance drain interrupted"); }
        if(state!=null)state.close();
        client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
    }
    private static class StaleJob extends IOException { }
}
