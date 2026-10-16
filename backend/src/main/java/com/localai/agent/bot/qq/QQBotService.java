package com.localai.agent.bot.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localai.agent.ollama.LlamaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * QQ Bot 服务
 *
 * <p>架构说明（go-cqhttp OneBot11 协议）：
 * <pre>
 *   QQ 消息 → go-cqhttp → POST /bot/qq/webhook → QQBotService → AI 处理 → 回复
 * </pre>
 *
 * <p>go-cqhttp 配置文件 config.yml 中需添加：
 * <pre>
 *   servers:
 *     - http:
 *         host: 127.0.0.1
 *         port: 5700
 *         post:
 *           - url: http://127.0.0.1:5000/bot/qq/webhook
 *             secret: ''
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QQBotService {

    private final LlamaService llamaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 触发 AI 响应的命令前缀（如 /ai 你好）
    @Value("${local-ai.qq.command-prefix:/ai}")
    private String commandPrefix;

    /**
     * 处理 go-cqhttp 推送过来的上报事件。
     *
     * @param eventJson go-cqhttp 上报的 JSON 字符串
     * @return 处理结果描述
     */
    public String handleEvent(String eventJson) {
        try {
            JsonNode event = objectMapper.readTree(eventJson);
            String postType = event.path("post_type").asText("");

            return switch (postType) {
                case "message"      -> handleMessage(event);
                case "notice"       -> handleNotice(event);
                case "request"      -> handleRequest(event);
                case "meta_event"   -> "heart_beat";   // 心跳，忽略
                default             -> "unknown_event";
            };

        } catch (Exception e) {
            log.error("[QQBot] 事件处理失败: {}", e.getMessage(), e);
            return "error: " + e.getMessage();
        }
    }

    // ── 消息事件处理 ──────────────────────────────────────────────

    private String handleMessage(JsonNode event) {
        String messageType = event.path("message_type").asText();
        String rawMessage  = event.path("raw_message").asText("");
        String senderNick  = event.path("sender").path("nickname").asText("用户");
        long   userId      = event.path("user_id").asLong();
        long   groupId     = event.path("group_id").asLong(0);

        log.info("[QQBot] 收到消息: type={}, from={}, content={}",
                messageType, senderNick,
                rawMessage.length() > 40 ? rawMessage.substring(0, 40) + "..." : rawMessage);

        // 只处理以触发前缀开头的消息（防止无限响应）
        if (!rawMessage.startsWith(commandPrefix)) {
            return "ignored";
        }

        String prompt = rawMessage.substring(commandPrefix.length()).trim();
        if (prompt.isBlank()) {
            return "empty_prompt";
        }

        // 调用 AI 生成回复
        String reply = llamaService.chatWithSystem(
                "你是一个有帮助的 AI 助手，在 QQ 群里回答用户问题。回答要简洁友好。",
                prompt
        );

        log.info("[QQBot] AI 回复长度: {}", reply.length());
        // 实际发送通过 CommunityTools.sendQQMessage 完成
        // 这里返回生成的回复供 Controller 使用
        return reply;
    }

    private String handleNotice(JsonNode event) {
        String noticeType = event.path("notice_type").asText();
        log.debug("[QQBot] 通知事件: {}", noticeType);
        return "notice_" + noticeType;
    }

    private String handleRequest(JsonNode event) {
        String requestType = event.path("request_type").asText();
        log.info("[QQBot] 请求事件: {}", requestType);
        return "request_" + requestType;
    }
}
