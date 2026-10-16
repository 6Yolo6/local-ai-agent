package com.localai.agent.controller;

import com.localai.agent.bot.feishu.FeishuBotService;
import com.localai.agent.bot.qq.QQBotService;
import com.localai.agent.model.ApiResponse;
import com.localai.agent.assistant.ReadOnlyTools;
import java.util.LinkedHashMap;
import com.localai.agent.ollama.LlamaService;
import com.localai.agent.ollama.OllamaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 管理后台 REST API 控制器
 *
 * <p>接口列表：
 * <ul>
 *   <li>GET  /api/status          - 服务总览状态</li>
 *   <li>GET  /api/models          - llama.cpp 模型信息</li>
 *   <li>GET  /api/metrics         - llama.cpp 推理指标（tokens/s）</li>
 *   <li>POST /api/chat            - 直接对话（前端 Playground）</li>
 *   <li>POST /bot/qq/webhook      - go-cqhttp 事件上报</li>
 *   <li>POST /bot/feishu/webhook  - 飞书事件订阅</li>
 *   <li>GET  /api/health          - 健康检查</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final LlamaService  llamaService;
    private final OllamaService ollamaService;
    private final QQBotService  qqBotService;
    private final FeishuBotService feishuBotService;
    private final ReadOnlyTools readOnlyTools;

    @Value("${local-ai.llamacpp.enabled:false}")
    private boolean llamaEnabled;
    @Value("${local-ai.llamacpp.model:local-model}")
    private String llamaModel;
    @Value("${server.port:5000}")
    private int serverPort;
    @Value("${spring.ai.mcp.server.sse-endpoint:/sse}")
    private String mcpSseEndpoint;

    @Value("${local-ai.qq.webhook-secret:}")
    private String qqWebhookSecret;

    // ── 服务状态 ──────────────────────────────────────────────────

    @GetMapping("/api/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        boolean ollamaAlive = ollamaService.isAlive();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("server", "running");
        status.put("engine", "ollama");
        status.put("model", ollamaService.modelName());
        status.put("ollamaModel", ollamaService.modelName());
        status.put("ollama", ollamaAlive ? "online" : "offline");
        status.put("llamacpp", llamaEnabled ? (llamaService.isAlive() ? "online" : "offline") : "disabled");
        status.put("llamaModel", llamaEnabled ? llamaModel : "未启用");
        status.put("mcpEndpoint", "http://127.0.0.1:" + serverPort + mcpSseEndpoint);
        status.put("assistantMode", "read-only");
        status.put("workspaceRoot", readOnlyTools.workspaceRoot());
        status.put("toolCount", readOnlyTools.definitions().size());
        status.put("timestamp", LocalDateTime.now().toString());
        return ApiResponse.success(status);
    }

    @GetMapping("/api/models")
    public ApiResponse<String> getModels() {
        return ApiResponse.success(ollamaService.listModels());
    }

    /** llama.cpp 实时推理速度（tokens/s）等指标 */
    @GetMapping("/api/metrics")
    public ApiResponse<String> getMetrics() {
        return ApiResponse.success(llamaEnabled ? llamaService.getMetrics() : "llama.cpp 未启用；当前使用 Ollama");
    }

    // ── 对话接口（Playground）────────────────────────────────────

    /**
     * 向 llama.cpp（主力）发送对话请求。
     * 请求体：{"prompt": "...", "system": "可选系统提示"}
     */
    @PostMapping("/api/chat")
    public ApiResponse<String> chat(@RequestBody Map<String, String> body) {
        String prompt = body.getOrDefault("prompt", "").trim();
        String system = body.getOrDefault("system", "").trim();
        String engine = body.getOrDefault("engine", "ollama").trim(); // llama / ollama

        if (prompt.isBlank()) {
            return ApiResponse.error("prompt 不能为空");
        }

        log.info("[Admin] chat: engine={}, prompt={}", engine,
                prompt.length() > 40 ? prompt.substring(0, 40) + "..." : prompt);

        String result;
        if ("ollama".equalsIgnoreCase(engine)) {
            // 切换到 Ollama 备用引擎
            result = system.isBlank()
                    ? ollamaService.chat(prompt)
                    : ollamaService.chatWithSystem(system, prompt);
        } else if ("llama".equalsIgnoreCase(engine) && llamaEnabled) {
            // 显式启用的可选 llama.cpp 引擎
            result = system.isBlank()
                    ? llamaService.chat(prompt)
                    : llamaService.chatWithSystem(system, prompt);
        } else {
            return ApiResponse.of(400, "不支持的引擎或 llama.cpp 尚未启用", null);
        }

        return ApiResponse.success(result);
    }

    // ── Bot Webhooks ──────────────────────────────────────────────

    @PostMapping("/bot/qq/webhook")
    public String qqWebhook(@RequestBody String eventJson,
                            @RequestHeader(value = "X-Signature", required = false) String signature) {
        log.debug("[QQ Webhook] 事件长度={}", eventJson.length());
        if (!isValidOneBotSignature(eventJson, signature)) {
            log.warn("[QQ Webhook] signature verification failed");
            return "forbidden";
        }
        return qqBotService.handleEvent(eventJson);
    }

    @PostMapping("/bot/feishu/webhook")
    public String feishuWebhook(@RequestBody String eventJson) {
        log.debug("[Feishu Webhook] 事件长度={}", eventJson.length());
        return feishuBotService.handleEvent(eventJson);
    }

    // ── 健康检查 ──────────────────────────────────────────────────

    @GetMapping("/api/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("ok");
    }

    private boolean isValidOneBotSignature(String body, String signature) {
        if (qqWebhookSecret == null || qqWebhookSecret.isBlank()) {
            return true;
        }
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(
                    qqWebhookSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA1"));
            String expected = "sha1=" + toHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("[QQ Webhook] signature check failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
