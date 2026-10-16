package com.localai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 本地 AI Agent 启动类
 *
 * <p>架构说明：
 * <ul>
 *   <li>MCP Server：暴露 /mcp 端点，供 ClaudeCode / OpenClaw 连接</li>
 *   <li>Ollama 客户端：对接本地 Qwen3:14b 模型推理</li>
 *   <li>QQ / 飞书 Bot：接收指令，返回结果</li>
 * </ul>
 *
 * <p>启动后访问：
 * <ul>
 *   <li>MCP SSE：  http://127.0.0.1:5000/mcp</li>
 *   <li>管理 API：  http://127.0.0.1:5000/api</li>
 *   <li>健康检查：  http://127.0.0.1:5000/actuator/health</li>
 * </ul>
 */
@Slf4j
@SpringBootApplication
@EnableScheduling   // 启用定时任务（Quartz 补充 @Scheduled）
public class LocalAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(LocalAiAgentApplication.class);
        app.run(args);

        log.info("""
                
                ╔══════════════════════════════════════════════════╗
                ║        本地 AI Agent MCP 服务已启动               ║
                ║  MCP SSE  : http://127.0.0.1:5000/mcp            ║
                ║  Admin API: http://127.0.0.1:5000/api            ║
                ║  模型     : llama / Qwen_Qwen3.6-35B-A3B-Q4_K_M                   ║
                ╚══════════════════════════════════════════════════╝
                """);
    }
}
