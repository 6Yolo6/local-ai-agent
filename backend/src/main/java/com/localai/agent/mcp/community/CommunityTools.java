package com.localai.agent.mcp.community;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localai.agent.ollama.LlamaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 社群类 MCP 工具集
 *
 * <p>包含工具：
 * <ul>
 *   <li>postToQQChannel    - QQ 频道发帖（通过 go-cqhttp / OneBot11）</li>
 *   <li>pushToFeishu       - 飞书 Webhook 消息推送</li>
 *   <li>sendQQMessage      - 发送 QQ 私聊/群消息</li>
 * <li>notifyResult       - 将 Agent 执行结果回传给用户</li>
 *   <li>generatePost       - AI 生成社群帖子内容</li>
 * </ul>
 *
 * <p>前置依赖：
 * <ul>
 *   <li>QQ 功能需先启动 go-cqhttp，并配置 HTTP API 端口为 5700</li>
 *   <li>飞书功能需在群里添加自定义 Webhook 机器人</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityTools {

    private final LlamaService llamaService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${local-ai.qq.cqhttp-url:http://127.0.0.1:5700}")
    private String cqhttpUrl;

    @Value("${local-ai.qq.official-api-base-url:https://api.sgroup.qq.com}")
    private String qqOfficialApiBaseUrl;

    @Value("${local-ai.qq.bot-token:}")
    private String qqBotToken;

    @Value("${local-ai.qq.guild-id:}")
    private String qqGuildId;

    @Value("${local-ai.qq.channel-id:}")
    private String qqChannelId;

    @Value("${local-ai.feishu.webhook-url:}")
    private String feishuWebhookUrl;

    // OkHttp 客户端（Bot 消息发送专用，超时较短）
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();

    // ============================================================
    //  工具 1：QQ 频道发帖
    // ============================================================

    /**
     * 向 QQ 频道（Guild）发送消息。
     *
     * <p>需要 go-cqhttp 在本地运行，并配置频道 ID。
     * go-cqhttp 文档：https://docs.go-cqhttp.org/
     *
     * @param content   发帖内容（支持 CQ 码，如图片、@等）
     * @param channelId QQ 频道子频道 ID（为空则使用配置中的默认频道）
     * @return 发送结果
     */
    @Tool(description = "向 QQ 频道发送消息（需运行 go-cqhttp）。支持文字和 CQ 码（图片/at 等）。")
    public String postToQQChannel(String content, String channelId) {
        log.info("[CommunityTool] postToQQChannel: channelId={}, content截取={}",
                channelId, content.length() > 40 ? content.substring(0, 40) + "..." : content);

        String targetChannelId = (channelId != null && !channelId.isBlank()) ? channelId : qqChannelId;
        if (targetChannelId == null || targetChannelId.isBlank()) {
            return "❌ QQ 频道 ID 未配置，请在 application.yml 中设置 local-ai.qq.channel-id";
        }

        if (qqBotToken != null && !qqBotToken.isBlank()) {
            return sendTencentChannelMessage(targetChannelId, content);
        }

        try {
            // go-cqhttp API：发送频道消息
            ObjectNode params = objectMapper.createObjectNode();
            params.put("guild_id", qqGuildId);
            params.put("channel_id", targetChannelId);
            params.put("message", content);

            String result = callCqHttp("/send_guild_channel_msg", params.toString());
            log.info("[CommunityTool] QQ 频道发帖结果: {}", result);
            return "✅ QQ 频道发帖成功\n" + result;

        } catch (Exception e) {
            log.error("[CommunityTool] postToQQChannel 失败: {}", e.getMessage(), e);
            return "❌ QQ 频道发帖失败：" + e.getMessage()
                    + "\n提示：请确保 go-cqhttp 已启动并配置正确";
        }
    }

    @Tool(description = "使用腾讯频道官方 Bot Token 向指定子频道发送文本消息。优先读取 local-ai.qq.bot-token / LOCAL_AI_QQ_BOT_TOKEN。")
    public String sendTencentChannelMessage(String channelId, String content) {
        log.info("[CommunityTool] sendTencentChannelMessage: channelId={}", channelId);

        if (qqBotToken == null || qqBotToken.isBlank()) {
            return "❌ 腾讯频道 Bot Token 未配置，请设置 LOCAL_AI_QQ_BOT_TOKEN 或 TENCENT_CHANNEL_BOT_TOKEN";
        }
        if (channelId == null || channelId.isBlank()) {
            return "❌ 腾讯频道 channelId 不能为空";
        }

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("content", content);

            RequestBody body = RequestBody.create(
                    objectMapper.writeValueAsString(payload),
                    MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(qqOfficialApiBaseUrl + "/channels/" + channelId + "/messages")
                    .post(body)
                    .addHeader("Authorization", "QQBot " + qqBotToken)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    return "✅ 腾讯频道消息发送成功\n" + responseBody;
                }
                return "❌ 腾讯频道消息发送失败（HTTP " + response.code() + "）：" + responseBody;
            }
        } catch (Exception e) {
            log.error("[CommunityTool] sendTencentChannelMessage failed: {}", e.getMessage(), e);
            return "❌ 腾讯频道消息发送异常：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 2：发送 QQ 私聊/群消息
    // ============================================================

    /**
     * 通过 go-cqhttp 发送 QQ 私聊或群消息。
     *
     * @param targetId   目标 QQ 号（私聊）或群号（群消息）
     * @param messageType 消息类型："private"（私聊）或 "group"（群聊）
     * @param content    消息内容
     * @return 发送结果
     */
    @Tool(description = "发送 QQ 消息：messageType=private 发私聊，messageType=group 发群消息。需 go-cqhttp 运行。")
    public String sendQQMessage(String targetId, String messageType, String content) {
        log.info("[CommunityTool] sendQQMessage: type={}, target={}", messageType, targetId);
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("message", content);

            String api;
            if ("group".equalsIgnoreCase(messageType)) {
                params.put("group_id", Long.parseLong(targetId));
                api = "/send_group_msg";
            } else {
                params.put("user_id", Long.parseLong(targetId));
                api = "/send_private_msg";
            }

            String result = callCqHttp(api, params.toString());
            return "✅ QQ 消息发送成功\n" + result;

        } catch (Exception e) {
            log.error("[CommunityTool] sendQQMessage 失败: {}", e.getMessage(), e);
            return "❌ QQ 消息发送失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 3：飞书 Webhook 推送
    // ============================================================

    /**
     * 通过飞书自定义机器人 Webhook 发送消息到飞书群。
     *
     * <p>设置方法：飞书群 → 群设置 → 机器人 → 添加自定义机器人 → 复制 Webhook 地址
     * 填入 application.yml 的 local-ai.feishu.webhook-url。
     *
     * @param title   消息标题（可选）
     * @param content 消息正文（Markdown 格式）
     * @return 推送结果
     */
    @Tool(description = "通过飞书 Webhook 机器人推送消息到飞书群。支持 Markdown 格式。需配置 webhook-url。")
    public String pushToFeishu(String title, String content) {
        log.info("[CommunityTool] pushToFeishu: title={}", title);

        if (feishuWebhookUrl == null || feishuWebhookUrl.isBlank()) {
            return "❌ 飞书 Webhook URL 未配置，请在 application.yml 中设置 local-ai.feishu.webhook-url";
        }

        try {
            // 飞书机器人消息格式（富文本）
            String messageJson;
            if (title != null && !title.isBlank()) {
                // 带标题的富文本消息
                messageJson = """
                        {
                          "msg_type": "post",
                          "content": {
                            "post": {
                              "zh_cn": {
                                "title": "%s",
                                "content": [[{"tag": "text", "text": "%s"}]]
                              }
                            }
                          }
                        }
                        """.formatted(
                        escapeJson(title),
                        escapeJson(content)
                );
            } else {
                // 简单文本消息
                messageJson = """
                        {
                          "msg_type": "text",
                          "content": {"text": "%s"}
                        }
                        """.formatted(escapeJson(content));
            }

            RequestBody body = RequestBody.create(messageJson,
                    MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(feishuWebhookUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    return "✅ 飞书消息推送成功：" + responseBody;
                } else {
                    return "❌ 飞书推送失败（HTTP " + response.code() + "）：" + responseBody;
                }
            }

        } catch (Exception e) {
            log.error("[CommunityTool] pushToFeishu 失败: {}", e.getMessage(), e);
            return "❌ 飞书推送异常：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 4：AI 生成社群帖子内容
    // ============================================================

    /**
     * 使用 AI 根据主题和风格生成适合社群发布的帖子内容。
     *
     * @param topic    帖子主题或要传达的信息
     * @param style    风格（"official"正式/"casual"随意/"tech"技术/"announcement"公告）
     * @param platform 目标平台（"qq"/"feishu"/"both"）
     * @return 生成的帖子内容
     */
    @Tool(description = "使用 AI 生成社群帖子内容。输入主题和风格，输出可直接发布的帖子文本。")
    public String generatePost(String topic, String style, String platform) {
        log.info("[CommunityTool] generatePost: topic={}, style={}, platform={}", topic, style, platform);

        String styleDesc = switch (style != null ? style.toLowerCase() : "casual") {
            case "official", "announcement" -> "正式/公告风格，语气庄重，信息完整";
            case "tech" -> "技术社群风格，专业简洁，重点突出";
            default -> "轻松随意，亲切自然，适合日常互动";
        };

        String platformDesc = "feishu".equalsIgnoreCase(platform)
                ? "飞书（支持 Markdown）"
                : "QQ 频道（纯文本 + Emoji）";

        String prompt = """
                你是一个社群运营助手，请为以下主题生成一条适合在 %s 发布的内容：
                
                主题：%s
                风格：%s
                
                要求：
                1. 内容简洁有吸引力，不超过 300 字
                2. 适当加入 Emoji 增加活跃度
                3. 结尾加上互动引导语句
                4. 直接输出帖子内容，无需解释
                """.formatted(platformDesc, topic, styleDesc);

        return llamaService.chat(prompt);
    }

    // ============================================================
    //  定时任务示例（@Scheduled，不作为 MCP 工具）
    // ============================================================

    /**
     * 每天 09:00 执行早报推送（示例定时任务）。
     * 需在 application.yml 开启 spring.task.scheduling.pool.size
     */
    @Scheduled(cron = "0 0 9 * * ?")
    public void scheduledMorningReport() {
        log.info("[CommunityTool] 定时任务：早报推送触发");
        // 在这里添加早报内容生成和推送逻辑
        // String report = llamaService.chat("生成今日技术社群早报");
        // pushToFeishu("📰 今日早报", report);
    }

    // ============================================================
    //  工具 5：推送结果回传（指令响应）
    // ============================================================

    /**
     * 将 Agent 执行结果推送回用户设备（QQ 消息或飞书消息）。
     * 这是整个执行流程最后一步：任务完成后通知用户。
     *
     * @param channel  回传渠道（"qq_private" / "qq_group" / "feishu"）
     * @param targetId 目标 ID（QQ号/群号，飞书则忽略此参数）
     * @param result   要回传的结果内容
     * @return 回传状态
     */
    @Tool(description = "将 Agent 任务执行结果回传给用户。支持 QQ私聊/群/飞书三种渠道。")
    public String notifyResult(String channel, String targetId, String result) {
        log.info("[CommunityTool] notifyResult: channel={}, target={}", channel, targetId);

        // 格式化结果消息
        String message = "🤖 AI Agent 任务完成通知\n"
                + "━━━━━━━━━━━━━━\n"
                + result
                + "\n━━━━━━━━━━━━━━\n"
                + "执行时间：" + java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        return switch (channel != null ? channel.toLowerCase() : "") {
            case "qq_private" -> sendQQMessage(targetId, "private", message);
            case "qq_group"   -> sendQQMessage(targetId, "group",   message);
            case "feishu"     -> pushToFeishu("AI Agent 通知", message);
            default -> "❌ 未知回传渠道：" + channel + "（支持：qq_private/qq_group/feishu）";
        };
    }

    // ============================================================
    //  私有工具方法
    // ============================================================

    /**
     * 调用 go-cqhttp HTTP API。
     *
     * @param endpoint API 路径（如 /send_private_msg）
     * @param jsonBody 请求体 JSON
     * @return 响应 JSON
     */
    private String callCqHttp(String endpoint, String jsonBody) throws Exception {
        RequestBody body = RequestBody.create(jsonBody,
                MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(cqhttpUrl + endpoint)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code());
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符（防止注入）。
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
