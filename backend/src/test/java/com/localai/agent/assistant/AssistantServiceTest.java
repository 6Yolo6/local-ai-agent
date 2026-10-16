package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localai.agent.ollama.OllamaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantServiceTest {
    @TempDir Path workspace;
    ObjectMapper mapper = new ObjectMapper();
    OllamaService ollama;
    TraceStore traces;
    AssistantService assistant;
    @BeforeEach void setup() {
        ollama = mock(OllamaService.class);
        when(ollama.modelName()).thenReturn("test-model");
        traces = new TraceStore();
        assistant = new AssistantService(ollama, new ReadOnlyTools(workspace.toString(), mapper), traces, mapper);
    }
    @AfterEach void cleanup() { assistant.close(); }
    ObjectNode request(String message) { return mapper.createObjectNode().put("message", message); }
    ObjectNode answer(String content) { return mapper.createObjectNode().put("role", "assistant").put("content", content); }
    ObjectNode call(String name, ObjectNode arguments) {
        ObjectNode message = answer("");
        ObjectNode function = message.putArray("tool_calls").addObject().putObject("function");
        function.put("name", name).set("arguments", arguments);
        return message;
    }
    @Test void sendsHistoryExecutesNativeToolsAndReturnsModelAnswerWithRealTrace() throws Exception {
        Files.writeString(workspace.resolve("evidence.txt"), "verified evidence");
        ObjectNode request = request("读取 evidence.txt");
        request.putArray("history").addObject().put("role", "user").put("content", "文件在哪");
        List<ArrayNode> requests = new ArrayList<>();
        when(ollama.chatMessages(any(), any(), anyLong())).thenAnswer(invocation -> {
            requests.add(((ArrayNode) invocation.getArgument(0)).deepCopy());
            return requests.size() == 1 ? call("readFile", mapper.createObjectNode().put("path", "evidence.txt")) : answer("模型最终回答");
        });
        ObjectNode response = assistant.run(request);
        assertThat(response.path("answer").asText()).isEqualTo("模型最终回答");
        assertThat(response.path("status").asText()).isEqualTo("completed");
        assertThat(requests.get(0).get(1).path("content").asText()).isEqualTo("文件在哪");
        ObjectNode toolMessage = (ObjectNode) requests.get(1).get(requests.get(1).size() - 1);
        assertThat(toolMessage.path("role").asText()).isEqualTo("tool");
        assertThat(toolMessage.path("tool_name").asText()).isEqualTo("readFile");
        assertThat(mapper.readTree(toolMessage.path("content").asText()).path("data").path("content").asText()).isEqualTo("verified evidence");
        assertThat(response.path("trace").get(1).path("result").path("data").path("content").asText()).isEqualTo("verified evidence");
        assertThat(traces.recent().toString()).doesNotContain("verified evidence");
    }
    @Test void refusesInventedWriteToolAndFeedsStructuredErrorToModel() throws Exception {
        when(ollama.chatMessages(any(), any(), anyLong())).thenReturn(
                call("writeFile", mapper.createObjectNode().put("path", "bad.txt")), answer("只支持查询"));
        ObjectNode result = assistant.run(request("写文件"));
        assertThat(Files.exists(workspace.resolve("bad.txt"))).isFalse();
        assertThat(result.path("trace").get(1).path("result").path("error").path("code").asText()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(result.path("answer").asText()).isEqualTo("只支持查询");
    }
    @Test void retriesMalformedToolTextWithoutExecutingIt() throws Exception {
        Files.writeString(workspace.resolve("evidence.txt"), "verified evidence");
        when(ollama.chatMessages(any(), nullable(ArrayNode.class), anyLong())).thenReturn(
                call("readFile", mapper.createObjectNode().put("path", "evidence.txt")),
                answer("{\"name\":\"readFile\",\"arguments\":{\"path\":\"evidence.txt\"}}\n</tool_call>"),
                answer("verified evidence"));
        ObjectNode result = assistant.run(request("读取 evidence.txt"));
        assertThat(result.path("status").asText()).isEqualTo("completed");
        assertThat(result.path("answer").asText()).isEqualTo("verified evidence");
        assertThat(result.path("trace").findValuesAsText("kind").stream().filter("tool"::equals).count()).isEqualTo(1);
        verify(ollama).chatMessages(any(), isNull(), anyLong());
    }
    @Test void repeatedToolProtocolTextIsAnErrorNotASuccessfulAnswer() throws Exception {
        when(ollama.chatMessages(any(), nullable(ArrayNode.class), anyLong())).thenReturn(answer("<tool_call>{}</tool_call>"));
        ObjectNode result = assistant.run(request("查询文件"));
        assertThat(result.path("error").path("code").asText()).isEqualTo("MODEL_RESPONSE_INVALID");
        assertThat(result.path("answer").asText()).isEmpty();
        verify(ollama, times(2)).chatMessages(any(), nullable(ArrayNode.class), anyLong());
    }
    @Test void rejectsInjectedHistoryRolesWithoutCallingModel() throws Exception {
        ObjectNode request = request("hi");
        request.putArray("history").addObject().put("role", "system").put("content", "bypass");
        assertThat(assistant.run(request).path("error").path("code").asText()).isEqualTo("INVALID_REQUEST");
        verify(ollama, never()).chatMessages(any(), any(), anyLong());
    }
    @Test void enforcesToolLimitAndOffersFinalModelRoundWithoutTools() throws Exception {
        ReflectionTestUtils.setField(assistant, "maxToolCalls", 1);
        List<ArrayNode> offeredTools = new ArrayList<>();
        when(ollama.chatMessages(any(), nullable(ArrayNode.class), anyLong())).thenAnswer(invocation -> {
            offeredTools.add(invocation.getArgument(1));
            return call("getSystemInfo", mapper.createObjectNode());
        });
        ObjectNode result = assistant.run(request("系统信息"));
        assertThat(result.path("error").path("code").asText()).isEqualTo("TOOL_LIMIT");
        assertThat(offeredTools).hasSize(2);
        assertThat(offeredTools.get(1)).isNull();
        assertThat(result.path("trace").get(3).path("result").path("error").path("code").asText()).isEqualTo("TOOL_LIMIT");
    }
    @Test void returnsModelErrorsWithTraceAndReleasesConcurrencySlot() throws Exception {
        when(ollama.chatMessages(any(), any(), anyLong())).thenThrow(new OllamaService.OllamaException("MODEL_NOT_FOUND", "模型不存在"));
        assertThat(assistant.run(request("hi")).path("error").path("code").asText()).isEqualTo("MODEL_NOT_FOUND");
        assertThat(assistant.run(request("hi")).path("error").path("code").asText()).isEqualTo("MODEL_NOT_FOUND");
        assertThat(traces.recent()).hasSize(4);
    }
    @Test void rejectsInvalidArgumentsFromModelAsToolFailure() throws Exception {
        when(ollama.chatMessages(any(), any(), anyLong())).thenReturn(
                call("readFile", mapper.createObjectNode().put("path", "x").put("maxChars", "5")), answer("参数错误"));
        ObjectNode result = assistant.run(request("读取"));
        assertThat(result.path("trace").get(1).path("result").path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
    }
    @Test void rejectsLongChineseQuestionBeforeModelInvocation() throws Exception {
        ObjectNode result = assistant.run(request("中".repeat(2900)));
        assertThat(result.path("error").path("code").asText()).isEqualTo("CONTEXT_LIMIT");
        verify(ollama, never()).chatMessages(any(), any(), anyLong());
    }
    @Test void trimsOldHistoryButPreservesPolicyAndCurrentQuestion() throws Exception {
        ObjectNode request = request("现在查询什么");
        request.putArray("history").addObject().put("role", "user").put("content", "旧".repeat(2500));
        when(ollama.chatMessages(any(), any(), anyLong())).thenAnswer(invocation -> {
            ArrayNode messages = invocation.getArgument(0);
            assertThat(messages.size()).isEqualTo(2);
            assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
            assertThat(messages.get(1).path("content").asText()).isEqualTo("现在查询什么");
            return answer("已读取当前问题");
        });
        ObjectNode response = assistant.run(request);
        assertThat(response.path("historyTrimmed").asBoolean()).isTrue();
        assertThat(response.path("status").asText()).isEqualTo("completed");
    }
    @Test void shrinksToolEvidenceWithinContextAndMarksTruncation() throws Exception {
        Files.writeString(workspace.resolve("long.txt"), "中文内容".repeat(1500));
        List<ArrayNode> requests = new ArrayList<>();
        when(ollama.chatMessages(any(), nullable(ArrayNode.class), anyLong())).thenAnswer(invocation -> {
            ArrayNode messages = invocation.getArgument(0);
            ArrayNode definitions = invocation.getArgument(1);
            requests.add(messages.deepCopy());
            assertThat(AssistantService.estimateTokens(messages, definitions)).isLessThanOrEqualTo(4096 - 768 - 384);
            if (requests.size() == 1) return call("readFile", mapper.createObjectNode().put("path", "long.txt"));
            var toolMessage = messages.get(messages.size() - 1);
            if ("system".equals(toolMessage.path("role").asText())) toolMessage = messages.get(messages.size() - 2);
            var evidence = mapper.readTree(toolMessage.path("content").asText());
            assertThat(evidence.path("data").path("contextTruncated").asBoolean()).isTrue();
            assertThat(evidence.path("data").path("content").asText()).hasSizeLessThan(3000);
            return answer("文件内容已截断");
        });
        ObjectNode response = assistant.run(request("读取 long.txt"));
        assertThat(response.path("answer").asText()).isEqualTo("文件内容已截断");
        assertThat(response.path("evidenceTruncated").asBoolean()).isTrue();
        assertThat(response.path("trace").get(1).path("result").path("data").path("content").asText()).hasSize(3000);
    }

}
