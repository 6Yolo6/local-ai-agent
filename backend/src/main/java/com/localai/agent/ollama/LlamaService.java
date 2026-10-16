package com.localai.agent.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * llama.cpp 本地推理服务（主力推理引擎）
 *
 * <p><b>模型</b>：Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf（MoE 架构，35B 参数 / 3B 激活）
 *
 * <p><b>接入方式</b>：通过 llama.cpp 的 OpenAI 兼容 API
 * <pre>
 *   llama-server.exe -m model.gguf --port 8080 --host 127.0.0.1
 *     → POST http://127.0.0.1:8080/v1/chat/completions
 * </pre>
 *
 * <p><b>架构角色</b>：
 * <ul>
 *   <li>LlamaService  → llama.cpp → GGUF 模型（本项目 MCP Agent 推理）</li>
 *   <li>OllamaService → Ollama   → 保留给 AnythingLLM 等工具使用</li>
 * </ul>
 *
 * <p><b>启动 llama.cpp 服务</b>（见 scripts/start-llamacpp.bat）：
 * <pre>
 *   llama-server.exe -m "D:/Dev/models/Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf" ^
 *     --host 127.0.0.1 --port 8080 -c 8192 -t 8 --chat-template chatml
 * </pre>
 */
@Slf4j
@Service
public class LlamaService {

    // ── 配置项（application.yml → local-ai.llamacpp.*） ────────────────
    @Value("${local-ai.llamacpp.base-url:http://127.0.0.1:8080}")
    private String baseUrl;

    @Value("${local-ai.llamacpp.model:local-model}")
    private String modelName;

    @Value("${local-ai.llamacpp.temperature:0.7}")
    private double temperature;

    @Value("${local-ai.llamacpp.max-tokens:4096}")
    private int maxTokens;

    @Value("${local-ai.llamacpp.timeout-seconds:180}")
    private int timeoutSeconds;

    private static final String CHAT_COMPLETIONS = "/v1/chat/completions";
    private final ObjectMapper mapper = new ObjectMapper();

    // ── OkHttp 客户端（懒加载，按配置超时创建） ─────────────────────────
    private OkHttpClient httpClient;

    private synchronized OkHttpClient client() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }

    // ============================================================
    //  公开 API：对话调用
    // ============================================================

    /**
     * 单轮对话（无系统提示词）。
     *
     * @param userPrompt 用户输入
     * @return 模型回复文本
     */
    public String chat(String userPrompt) {
        return chatWithSystem(null, userPrompt);
    }

    /**
     * 带系统提示词的对话（主要入口）。
     *
     * @param systemPrompt 系统提示词（为 null 或空时不传入 system role）
     * @param userPrompt   用户输入
     * @return 模型回复文本
     */
    public String chatWithSystem(String systemPrompt, String userPrompt) {
        log.debug("[Llama] chatWithSystem: system={}, user截取={}",
                systemPrompt != null,
                userPrompt.length() > 60 ? userPrompt.substring(0, 60) + "..." : userPrompt);

        try {
            String requestJson = buildChatRequest(systemPrompt, userPrompt);
            String responseJson = postJson(baseUrl + CHAT_COMPLETIONS, requestJson);
            String reply = parseReply(responseJson);

            log.debug("[Llama] 响应长度={}", reply.length());
            return reply;

        } catch (Exception e) {
            log.error("[Llama] chatWithSystem 失败: {}", e.getMessage(), e);
            String hint = e.getMessage().contains("Connection refused")
                    ? "\n💡 请先启动 llama.cpp：运行 scripts/start-llamacpp.bat"
                    : "";
            return "❌ llama.cpp 推理失败：" + e.getMessage() + hint;
        }
    }

    /**
     * 检查 llama.cpp 服务是否在线（GET /health）。
     *
     * @return true=在线
     */
    public boolean isAlive() {
        try {
            Request req = new Request.Builder()
                    .url(baseUrl + "/health")
                    .get()
                    .build();
            try (Response resp = client().newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("[Llama] 健康检查失败（未启动？）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取 llama.cpp 服务的模型信息（GET /v1/models）。
     *
     * @return 模型信息 JSON
     */
    public String getModelInfo() {
        try {
            Request req = new Request.Builder()
                    .url(baseUrl + "/v1/models")
                    .get()
                    .build();
            try (Response resp = client().newCall(req).execute()) {
                return resp.body() != null ? resp.body().string() : "{}";
            }
        } catch (Exception e) {
            log.warn("[Llama] 获取模型信息失败: {}", e.getMessage());
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取 llama.cpp 实时推理指标（GET /metrics，prometheus 格式）。
     * 可用于监控推理速度（tokens/s）。
     */
    public String getMetrics() {
        try {
            Request req = new Request.Builder()
                    .url(baseUrl + "/metrics")
                    .get()
                    .build();
            try (Response resp = client().newCall(req).execute()) {
                return resp.body() != null ? resp.body().string() : "";
            }
        } catch (Exception e) {
            return "metrics 不可用: " + e.getMessage();
        }
    }

    // ============================================================
    //  私有：构建 OpenAI 兼容请求体
    // ============================================================

    private String buildChatRequest(String systemPrompt, String userPrompt) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", modelName);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        root.put("stream", false);

        ArrayNode messages = root.putArray("messages");

        // system message（可选）
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
        }

        // user message
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        // Qwen3 扩展参数（llama.cpp 支持部分 OpenAI 扩展）
        // 关闭内置思考标签（如使用 Qwen3 非思考模式）
        // root.put("chat_template_kwargs", mapper.createObjectNode().put("enable_thinking", false));

        return mapper.writeValueAsString(root);
    }

    private String parseReply(String responseJson) throws Exception {
        JsonNode root = mapper.readTree(responseJson);

        // 检查是否有 error 字段
        if (root.has("error")) {
            String errMsg = root.path("error").path("message").asText(
                    root.path("error").asText("未知错误"));
            throw new RuntimeException("llama.cpp API 错误: " + errMsg);
        }

        // 解析 choices[0].message.content
        JsonNode content = root
                .path("choices")
                .path(0)
                .path("message")
                .path("content");

        if (content.isMissingNode()) {
            throw new RuntimeException("响应格式异常，无 choices[0].message.content: " + responseJson);
        }

        // Qwen3 思考模式会在 content 中包含 <think>...</think> 标签
        // 去除思考块，只返回最终答案
        String raw = content.asText("");
        return stripThinkingBlock(raw);
    }

    /**
     * 去除 Qwen3 思考模式输出中的 {@code <think>...</think>} 块。
     * 若无思考块则原样返回。
     */
    private String stripThinkingBlock(String text) {
        if (text == null) return "";
        int start = text.indexOf("<think>");
        int end   = text.indexOf("</think>");
        if (start >= 0 && end > start) {
            String beforeThink = text.substring(0, start).trim();
            String afterThink  = text.substring(end + "</think>".length()).trim();
            return (beforeThink.isBlank() ? afterThink : beforeThink + "\n" + afterThink);
        }
        return text.trim();
    }

    // ============================================================
    //  私有：HTTP 工具
    // ============================================================

    private String postJson(String url, String jsonBody) throws Exception {
        RequestBody body = RequestBody.create(
                jsonBody, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client().newCall(request).execute()) {
            String responseStr = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code() + ": " + responseStr);
            }
            return responseStr;
        }
    }
}
