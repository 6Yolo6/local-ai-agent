package com.localai.agent.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Local Ollama native /api/chat client, including native tool messages. */
@Service
public class OllamaService {
    @Value("${local-ai.ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl;
    @Value("${local-ai.ollama.model:qwen3:1.7b}")
    private String modelName;
    @Value("${local-ai.ollama.timeout-seconds:120}")
    private int timeoutSeconds;
    @Value("${local-ai.ollama.context-length:4096}")
    private int contextLength;
    @Value("${local-ai.ollama.max-tokens:768}")
    private int maxTokens;
    @Value("${local-ai.ollama.think:false}")
    private boolean think;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();

    public String modelName() { return modelName; }

    /** Only loopback endpoints are permitted so workspace evidence cannot leave this machine. */
    private String endpoint(String path) throws OllamaException {
        HttpUrl base = HttpUrl.parse(ollamaBaseUrl);
        if (base == null || !Set.of("localhost", "127.0.0.1", "::1").contains(base.host())
                || !base.username().isEmpty() || !base.password().isEmpty())
            throw new OllamaException("NON_LOCAL_MODEL", "助手仅允许连接本机 Ollama 地址");
        return base.newBuilder().encodedPath(path).query(null).fragment(null).build().toString();
    }

    public ObjectNode chatMessages(ArrayNode messages, ArrayNode tools, long remainingMillis) throws IOException {
        ObjectNode request = baseRequest();
        request.set("messages", messages);
        if (tools != null && !tools.isEmpty()) request.set("tools", tools);
        JsonNode response = post("/api/chat", request, remainingMillis);
        JsonNode message = response.get("message");
        if (message == null || !message.isObject() || !"assistant".equals(message.path("role").asText()))
            throw new OllamaException("MODEL_RESPONSE_INVALID", "Ollama 未返回有效的助手消息");
        if (message.has("content") && !message.get("content").isTextual())
            throw new OllamaException("MODEL_RESPONSE_INVALID", "Ollama 返回的回答格式无效");
        if (message.has("tool_calls") && !message.get("tool_calls").isArray())
            throw new OllamaException("MODEL_RESPONSE_INVALID", "Ollama 返回的工具调用格式无效");
        // Do not expose any reasoning field in client traces or answers.
        ObjectNode safe = mapper.createObjectNode().put("role", "assistant")
                .put("content", message.path("content").asText(""));
        if (message.has("tool_calls")) safe.set("tool_calls", message.get("tool_calls"));
        return safe;
    }

    private ObjectNode baseRequest() {
        ObjectNode request = mapper.createObjectNode().put("model", modelName).put("stream", false).put("think", think);
        request.putObject("options").put("num_ctx", Math.max(1024, Math.min(contextLength, 8192)))
                .put("num_predict", Math.max(128, Math.min(maxTokens, 2048))).put("temperature", 0.0);
        request.put("keep_alive", "10m");
        return request;
    }
    public String chat(String userPrompt) { return chatWithSystem(null, userPrompt); }
    public String chatWithSystem(String systemPrompt, String userPrompt) {
        ArrayNode messages = mapper.createArrayNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        try { return chatMessages(messages, null, timeoutSeconds * 1000L).path("content").asText(); }
        catch (IOException e) { return "Ollama 调用失败：" + e.getMessage(); }
    }
    public String generate(String prompt) {
        ObjectNode body = baseRequest().put("prompt", prompt);
        try { return post("/api/generate", body, timeoutSeconds * 1000L).path("response").asText(); }
        catch (IOException e) { return "Ollama 调用失败：" + e.getMessage(); }
    }
    public boolean isAlive() {
        try { return !get("/api/tags").has("error"); }
        catch (IOException e) { return false; }
    }
    public String listModels() {
        try { return get("/api/tags").toString(); }
        catch (IOException e) { ObjectNode error = mapper.createObjectNode().put("error", e.getMessage()); error.putArray("models"); return error.toString(); }
    }
    private JsonNode get(String path) throws IOException {
        Request request = new Request.Builder().url(endpoint(path)).get().build();
        Call call = client.newCall(request);
        call.timeout().timeout(3, TimeUnit.SECONDS);
        return execute(call);
    }
    private JsonNode post(String path, JsonNode body, long remainingMillis) throws IOException {
        if (remainingMillis <= 0) throw new OllamaException("TIMEOUT", "助手执行超时");
        Request request = new Request.Builder().url(endpoint(path))
                .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"))).build();
        Call call = client.newCall(request);
        call.timeout().timeout(Math.max(1, Math.min(remainingMillis, Math.max(1, timeoutSeconds) * 1000L)), TimeUnit.MILLISECONDS);
        return execute(call);
    }
    private JsonNode execute(Call call) throws IOException {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404) throw new OllamaException("MODEL_NOT_FOUND", "Ollama 未找到配置模型，请先下载 " + modelName);
                throw new OllamaException("MODEL_HTTP_ERROR", "Ollama 返回 HTTP " + response.code());
            }
            if (response.body() == null) throw new OllamaException("MODEL_RESPONSE_INVALID", "Ollama 返回空响应");
            byte[] bytes = response.body().byteStream().readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new OllamaException("MODEL_RESPONSE_INVALID", "Ollama 响应超过大小上限");
            JsonNode json = mapper.readTree(bytes);
            if (json == null || !json.isObject() || json.has("error"))
                throw new OllamaException("MODEL_RESPONSE_INVALID", "Ollama 返回错误或无效响应");
            return json;
        } catch (java.io.InterruptedIOException e) {
            throw new OllamaException("TIMEOUT", "Ollama 推理超时，请稍后重试");
        } catch (java.net.ConnectException e) {
            throw new OllamaException("MODEL_UNAVAILABLE", "无法连接本机 Ollama，请先启动模型服务");
        }
    }
    public static class OllamaException extends IOException {
        private final String code;
        public OllamaException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
