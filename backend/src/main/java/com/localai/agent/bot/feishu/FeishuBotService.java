package com.localai.agent.bot.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localai.agent.ollama.LlamaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 飞书 Bot 服务
 *
 * <p>架构说明：
 * <pre>
 *   飞书消息 → 飞书事件推送 → POST /bot/feishu/webhook
 *           → FeishuBotService → AI 处理 → 飞书消息 API 回复
 * </pre>
 *
 * <p>飞书开放平台配置步骤：
 * <ol>
 *   <li>访问 https://open.feishu.cn/app 创建企业自建应用</li>
 *   <li>权限：im:message（读写消息）</li>
 *   <li>事件订阅 → 请求地址：http://公网IP:5000/bot/feishu/webhook</li>
 *   <li>订阅事件：接收消息 im.message.receive_v1</li>
 *   <li>将 app_id / app_secret / verify_token 填入 application.yml</li>
 * </ol>
 *
 * <p>注意：飞书事件推送需要公网可达地址，本地开发可用 ngrok/frp 内网穿透。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuBotService {

    private final LlamaService llamaService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    @Value("${local-ai.feishu.verify-token:}")
    private String verifyToken;

    @Value("${local-ai.feishu.app-id:}")
    private String appId;

    @Value("${local-ai.feishu.app-secret:}")
    private String appSecret;

    // 飞书 API 基础地址
    private static final String FEISHU_BASE = "https://open.feishu.cn/open-apis";
    // access_token 缓存（简单实现，生产环境应使用 Redis）
    private String cachedAccessToken;
    private long tokenExpireAt;

    // ============================================================
    //  公开 API：事件处理
    // ============================================================

    /**
     * 处理飞书事件推送。
     *
     * <p>飞书推送格式分两类：
     * <ul>
     *   <li>challenge 验证：首次配置时飞书发送 URL 验证请求</li>
     *   <li>事件上报：用户消息、加群等事件</li>
     * </ul>
     *
     * @param eventJson 飞书推送的 JSON 字符串
     * @return 处理结果（challenge 验证时需返回 challenge 值）
     */
    public String handleEvent(String eventJson) {
        try {
            JsonNode root = objectMapper.readTree(eventJson);
            if (!isValidVerifyToken(root)) {
                log.warn("[FeishuBot] verify token mismatch");
                return "forbidden";
            }

            // ── 1. URL 验证（首次配置飞书事件订阅时触发）──────────
            if (root.has("challenge")) {
                String challenge = root.path("challenge").asText();
                log.info("[FeishuBot] 收到 URL 验证请求，challenge={}", challenge);
                // 必须直接返回 {"challenge": "<值>"}
                return "{\"challenge\":\"" + challenge + "\"}";
            }

            // ── 2. 正式事件处理 ────────────────────────────────────
            String eventType = root.path("header").path("event_type").asText(
                    root.path("event").path("type").asText("unknown")
            );

            log.info("[FeishuBot] 收到事件: {}", eventType);

            return switch (eventType) {
                case "im.message.receive_v1" -> handleReceiveMessage(root);
                case "im.chat.member.user.added_v1" -> "member_added";
                default -> {
                    log.debug("[FeishuBot] 忽略未处理事件: {}", eventType);
                    yield "ok";
                }
            };

        } catch (Exception e) {
            log.error("[FeishuBot] 事件处理失败: {}", e.getMessage(), e);
            return "error";
        }
    }

    // ============================================================
    //  私有：消息接收处理
    // ============================================================

    private String handleReceiveMessage(JsonNode root) {
        try {
            JsonNode event = root.path("event");
            String msgType = event.path("message").path("msg_type").asText();
            String chatId  = event.path("message").path("chat_id").asText();
            String msgId   = event.path("message").path("message_id").asText();
            String content = event.path("message").path("content").asText("{}");

            // 只处理文本消息
            if (!"text".equals(msgType)) {
                log.debug("[FeishuBot] 忽略非文本消息: {}", msgType);
                return "ok";
            }

            // 解析消息文本
            JsonNode contentNode = objectMapper.readTree(content);
            String text = contentNode.path("text").asText("").trim();

            log.info("[FeishuBot] 收到文本消息: chatId={}, text={}",
                    chatId, text.length() > 40 ? text.substring(0, 40) + "..." : text);

            if (text.isBlank()) {
                return "empty";
            }

            // 去掉 @机器人 的部分（飞书群消息中 @机器人 会被包含）
            text = text.replaceAll("@[^\\s]+\\s*", "").trim();
            if (text.isBlank()) {
                return "mention_only";
            }

            // AI 处理
            String reply = llamaService.chatWithSystem(
                    "你是企业内部的 AI 助手。在飞书中回复用户问题，语气专业简洁，使用中文。",
                    text
            );

            // 发送回复到飞书
            sendReply(msgId, reply);
            log.info("[FeishuBot] AI 回复已发送（长度={}）", reply.length());

            return "ok";

        } catch (Exception e) {
            log.error("[FeishuBot] 消息处理失败: {}", e.getMessage(), e);
            return "error";
        }
    }

    // ============================================================
    //  私有：飞书 API 调用
    // ============================================================

    /**
     * 通过飞书消息 API 回复消息。
     *
     * <p>流程：
     * <ol>
     *   <li>获取 tenant_access_token（使用 app_id + app_secret）</li>
     *   <li>调用 POST /im/v1/messages 发送回复</li>
     *   <li>将 reply_to_message_id 设为当前消息的 message_id</li>
     * </ol>
     *
     * @param msgId  要回复的消息 ID
     * @param reply  要发送的回复内容
     */
    private void sendReply(String msgId, String reply) {
        // 检查配置
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.warn("[FeishuBot] app_id 或 app_secret 未配置，跳过回复发送");
            return;
        }
        if (msgId == null || msgId.isBlank()) {
            log.warn("[FeishuBot] msgId 为空，无法回复");
            return;
        }

        try {
            // 1. 获取 access_token
            String token = getAccessToken();

            // 2. 构造回复消息 JSON
            ObjectNode root = objectMapper.createObjectNode();
            root.put("receive_id", msgId);  // 使用 message_id 作为 receive_id（自动回复到同一会话）
            root.put("msg_type", "text");

            ObjectNode contentNode = objectMapper.createObjectNode();
            contentNode.put("text", escapeFeishuMarkdown(reply));

            ObjectNode contentWrapper = objectMapper.createObjectNode();
            contentWrapper.set("content", contentNode);

            root.set("content", contentWrapper);

            String jsonBody = objectMapper.writeValueAsString(root);
            log.debug("[FeishuBot] 发送飞书回复: {}", jsonBody);

            // 3. 发送 HTTP 请求
            RequestBody body = RequestBody.create(
                    jsonBody, MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(FEISHU_BASE + "/im/v1/messages")
                    .post(body)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    log.info("[FeishuBot] 回复消息成功: msgId={}", msgId);
                } else {
                    log.error("[FeishuBot] 回复消息失败 HTTP {}: {}", response.code(), responseBody);
                }
            }

        } catch (IOException e) {
            log.error("[FeishuBot] 发送飞书回复失败: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("[FeishuBot] 构造回复消息失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取飞书 tenant_access_token。
     *
     * @return access_token 字符串
     */
    private String getAccessToken() throws IOException {
        // 缓存未过期则直接返回
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpireAt) {
            return cachedAccessToken;
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("app_id", appId);
        params.put("app_secret", appSecret);

        RequestBody body = RequestBody.create(
                objectMapper.writeValueAsString(params),
                MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(FEISHU_BASE + "/auth/v3/app_access_token/internal")
                .post(body)
                .header("Content-Type", "application/json; charset=utf-8")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("获取 token 失败 HTTP " + response.code());
            }

            JsonNode root = objectMapper.readTree(response.body().string());

            if (root.has("code") && root.path("code").asInt() != 0) {
                String errMsg = root.path("msg").asText("未知错误");
                throw new IOException("飞书 API 错误 " + root.path("code").asInt() + ": " + errMsg);
            }

            JsonNode data = root.path("data");
            cachedAccessToken = data.path("app_access_token").asText();
            // token 有效期通常为 2 小时（7200 秒），提前 5 分钟刷新
            long expiresIn = data.path("expire").asLong(7200);
            tokenExpireAt = System.currentTimeMillis() + (expiresIn - 300) * 1000;

            log.debug("[FeishuBot] 获取 access_token 成功，有效期 {} 秒", expiresIn);
            return cachedAccessToken;
        }
    }

    /**
     * 将普通文本转换为飞书 Markdown 格式（简单转义）。
     * 飞书消息支持 Markdown 子格式。
     */
    private String escapeFeishuMarkdown(String text) {
        if (text == null) return "";
        // 飞书 Markdown 中 \ 需要转义为 \\
        return text.replace("\\", "\\\\");
    }

    private boolean isValidVerifyToken(JsonNode root) {
        if (verifyToken == null || verifyToken.isBlank()) {
            return true;
        }
        String token = root.path("token").asText(
                root.path("header").path("token").asText(""));
        return verifyToken.equals(token);
    }
}
