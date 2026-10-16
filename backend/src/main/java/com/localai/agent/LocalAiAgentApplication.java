package com.localai.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Local assistant and separately registered legacy MCP tools. */
@Slf4j
@SpringBootApplication
@EnableScheduling
public class LocalAiAgentApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(LocalAiAgentApplication.class, args);
        var env = context.getEnvironment();
        log.info("Local assistant ready: model={}, port={}, MCP SSE={}",
                env.getProperty("local-ai.ollama.model"),
                env.getProperty("server.port"),
                env.getProperty("spring.ai.mcp.server.sse-endpoint", "/sse"));
    }
}
