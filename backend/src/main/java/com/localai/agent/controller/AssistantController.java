package com.localai.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localai.agent.assistant.AssistantService;
import com.localai.agent.assistant.TraceStore;
import com.localai.agent.model.ApiResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class AssistantController {
    private final AssistantService assistant;
    private final TraceStore traces;
    public AssistantController(AssistantService assistant, TraceStore traces) {
        this.assistant = assistant; this.traces = traces;
    }
    @PostMapping("/api/assistant")
    public ApiResponse<ObjectNode> assistant(@RequestBody JsonNode body) {
        ObjectNode result = assistant.run(body);
        if (!result.has("error")) return ApiResponse.success(result);
        String code = result.path("error").path("code").asText();
        int status = "INVALID_REQUEST".equals(code) ? 400 : "ASSISTANT_BUSY".equals(code) ? 429 : 500;
        return ApiResponse.of(status, result.path("error").path("message").asText(), result);
    }
    @GetMapping("/api/assistant/tools")
    public ApiResponse<ArrayNode> tools(@RequestParam(defaultValue = "local") String scope) {
        try {
            ArrayNode definitions = assistant.definitions(scope);
            ArrayNode functions = definitions.arrayNode();
            definitions.forEach(tool -> functions.add(tool.get("function")));
            return ApiResponse.success(functions);
        } catch (IllegalArgumentException e) { return ApiResponse.of(400, e.getMessage(), null); }
    }
    @GetMapping("/api/traces")
    public ApiResponse<List<ObjectNode>> traces() { return ApiResponse.success(traces.recent()); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Object> invalidJson() { return ApiResponse.of(400, "请求必须为有效 JSON", null); }
}
