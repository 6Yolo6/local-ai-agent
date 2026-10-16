package com.localai.agent.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Ollama 备用推理服务
 *
 * <p><b>角色</b>：本项目主力推理已切换为 llama.cpp（LlamaService）。
 * OllamaService 保留用于：
 * <ul>
 *   <li>AnythingLLM / Open WebUI 等工具的 Ollama 集成调用</li>
 *   <li>需要 Ollama 特有 API（如 /api/embeddings）的场景</li>
 *   <li>llama.cpp 不可用时的降级备份</li>
 * </ul>
 *
 * <p><b>Ollama 地址</b>：http://127.0.0.1:11434（固定，不与 llama.cpp 冲突）
 * <p><b>推荐模型</b>：qwen3:14b（通过 ollama pull qwen3:14b 拉取）
 *
 * <p>使用纯 OkHttp 直连，无 Spring AI 依赖，完全离线。
 */
@Slf4j
@Service
public class OllamaService {

    @Value("${local-ai.ollama.base-url:http://127.0.0.1:11434}")
    private String ollamaBaseUrl;

    @Value("${local-ai.ollama.model:qwen3:14b}")
    private String modelName;

    @Value("${local-ai.ollama.timeout-seconds:120}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OkHttpClient httpClient;

    private OkHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }

    // ============================================================
    //  公开 API：Ollama /api/chat（OpenAI 兼容格式）
    // ============================================================

    /**
     * 调用 Ollama chat API（与 LlamaService 同签名，方便降级替换）。
     */
    public String chat(String userPrompt) {
        return chatWithSystem(null, userPrompt);
    }

    /**
     * 带 system 提示词的 Ollama 对话。
     */
    public String chatWithSystem(String systemPrompt, String userPrompt) {
        log.debug("[Ollama] chat: model={}", modelName);
        try {
            // 构造请求（Ollama /api/chat 格式）
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", modelName);
            root.put("stream", false);
            ArrayNode messages = root.putArray("messages");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.addObject().put("role", "system").put("content", systemPrompt);
            }
            messages.addObject().put("role", "user").put("content", userPrompt);

            String responseJson = postJson(ollamaBaseUrl + "/api/chat", root.toString());
            JsonNode resp = objectMapper.readTree(responseJson);
            return resp.path("message").path("content").asText("");
        } catch (Exception e) {
            log.error("[Ollama] chatWithSystem 失败: {}", e.getMessage(), e);
            return "❌ Ollama 调用失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  公开 API：OkHttp 直连方式（底层）
    // ============================================================

    /**
     * 通过 Ollama /api/generate 接口直接生成文本（非 chat 格式）。
     *
     * @param prompt 完整提示词
     * @return 生成的文本
     */
    public String generate(String prompt) {
        log.debug("[Ollama] 直连 /api/generate 请求");
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", modelName);
            requestBody.put("prompt", prompt);
            requestBody.put("stream", false);

            String responseJson = postJson(ollamaBaseUrl + "/api/generate", requestBody.toString());
            JsonNode responseNode = objectMapper.readTree(responseJson);
            return responseNode.path("response").asText("");
        } catch (Exception e) {
            log.error("[Ollama] generate 调用失败: {}", e.getMessage(), e);
            return "❌ Ollama 直连失败：" + e.getMessage();
        }
    }

    /**
     * 检查 Ollama 服务是否在线。
     *
     * @return true=在线，false=离线
     */
    public boolean isAlive() {
        try {
            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/tags")
                    .get()
                    .build();
            try (Response response = getHttpClient().newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.warn("[Ollama] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取当前已加载的模型列表。
     *
     * @return 模型名称列表 JSON 字符串
     */
    public String listModels() {
        try {
            Request request = new Request.Builder()
                    .url(ollamaBaseUrl + "/api/tags")
                    .get()
                    .build();
            try (Response response = getHttpClient().newCall(request).execute()) {
                if (response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (Exception e) {
            log.error("[Ollama] 获取模型列表失败: {}", e.getMessage());
        }
        return "{\"models\":[]}";
    }

    // ============================================================
    //  私有工具方法
    // ============================================================

    private String postJson(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        try (Response response = getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " - " + response.message());
            }
            return response.body() != null ? response.body().string() : "";
        }
    }
}
