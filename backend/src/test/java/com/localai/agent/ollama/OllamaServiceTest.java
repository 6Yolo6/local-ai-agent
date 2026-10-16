package com.localai.agent.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class OllamaServiceTest {
    ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    OllamaService ollama;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama = new OllamaService();
        ReflectionTestUtils.setField(ollama, "ollamaBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(ollama, "modelName", "qwen3:1.7b");
        ReflectionTestUtils.setField(ollama, "timeoutSeconds", 3);
        ReflectionTestUtils.setField(ollama, "contextLength", 4096);
        ReflectionTestUtils.setField(ollama, "maxTokens", 768);
    }
    @AfterEach void cleanup() { server.stop(0); }
    @Test void speaksNativeProtocolAndPreservesToolCalls() throws Exception {
        AtomicReference<ObjectNode> sent = new AtomicReference<>();
        server.createContext("/api/chat", exchange -> {
            sent.set((ObjectNode) mapper.readTree(exchange.getRequestBody()));
            byte[] response = "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"getSystemInfo\",\"arguments\":{}}}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        var messages = mapper.createArrayNode();
        messages.addObject().put("role", "user").put("content", "status");
        var tools = mapper.createArrayNode(); tools.addObject().put("type", "function");
        var response = ollama.chatMessages(messages, tools, 2000);
        assertThat(response.path("tool_calls").get(0).path("function").path("name").asText()).isEqualTo("getSystemInfo");
        assertThat(sent.get().path("think").asBoolean()).isFalse();
        assertThat(sent.get().path("stream").asBoolean()).isFalse();
        assertThat(sent.get().path("options").path("num_ctx").asInt()).isEqualTo(4096);
        assertThat(sent.get().path("messages")).isEqualTo(messages);
        assertThat(sent.get().path("tools")).isEqualTo(tools);
    }
    @Test void refusesRemoteModelAndDoesNotFollowRedirects() throws Exception {
        ReflectionTestUtils.setField(ollama, "ollamaBaseUrl", "http://example.com:11434");
        assertThatThrownBy(() -> ollama.chatMessages(mapper.createArrayNode(), null, 1000))
                .isInstanceOf(OllamaService.OllamaException.class).hasMessageContaining("本机");
        ReflectionTestUtils.setField(ollama, "ollamaBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/api/chat", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.com");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> ollama.chatMessages(mapper.createArrayNode(), null, 1000)).hasMessageContaining("302");
    }
    @Test void enforcesDeadline() throws Exception {
        server.createContext("/api/chat", exchange -> {
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        assertThatThrownBy(() -> ollama.chatMessages(mapper.createArrayNode(), null, 40))
                .isInstanceOf(OllamaService.OllamaException.class).hasMessageContaining("超时");
    }
    @Test void distinguishesMissingModel() throws Exception {
        server.createContext("/api/chat", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        server.start();
        try { ollama.chatMessages(mapper.createArrayNode(), null, 1000); fail("Expected failure"); }
        catch (OllamaService.OllamaException error) { assertThat(error.code()).isEqualTo("MODEL_NOT_FOUND"); }
    }
}
