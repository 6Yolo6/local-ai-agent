package com.localai.agent.config;

import com.localai.agent.mcp.community.CommunityTools;
import com.localai.agent.mcp.dev.DevTools;
import com.localai.agent.mcp.system.SystemTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置
 *
 * <p>
 * Spring AI 1.0.0 的工具注册机制：
 * <ol>
 * <li>在 Service 类方法上添加 {@code @Tool} 注解描述工具</li>
 * <li>通过 {@code MethodToolCallbackProvider} 扫描 @Tool 方法</li>
 * <li>MCP Server 自动接收 {@code ToolCallbackProvider} Bean 并注册工具</li>
 * </ol>
 *
 * <p>
 * 工具分类：
 * <ul>
 * <li>DevTools - 开发类（代码生成、文件读写、SQL优化、编译）</li>
 * <li>SystemTools - 系统类（命令执行、日志解析）</li>
 * <li>CommunityTools - 社群类（QQ频道发帖、定时任务、消息推送）</li>
 * </ul>
 */
@Slf4j
@Configuration
public class McpToolsConfig {

    /**
     * 注册所有 MCP 工具到 Spring AI MCP Server。
     *
     * <p>
     * MCP Server 自动扫描类型为 {@code ToolCallbackProvider} 的 Bean，
     * 并将其中所有 {@code @Tool} 方法注册为可调用工具。
     */
    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(
            DevTools devTools,
            SystemTools systemTools,
            CommunityTools communityTools) {

        log.info("[MCP] 注册工具集 → DevTools / SystemTools / CommunityTools");

        return MethodToolCallbackProvider.builder()
                .toolObjects(devTools, systemTools, communityTools)
                .build();
    }
}
