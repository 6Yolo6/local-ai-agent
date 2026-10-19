package com.localai.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalLifecycleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyTheSameInstanceProcessAndStartTime() {
        Instant now = Instant.now();
        ObjectNode identity = mapper.createObjectNode().put("instanceId", UUID.randomUUID().toString())
            .put("processId", 42).put("startedAt", now.minusSeconds(5).toString());
        ObjectNode request = identity.deepCopy().put("requestedAt", now.toString());
        assertTrue(LocalLifecycle.validRequest(request, identity, now));
        assertFalse(LocalLifecycle.validRequest(request.deepCopy().put("instanceId", UUID.randomUUID().toString()), identity, now));
        assertFalse(LocalLifecycle.validRequest(request.deepCopy().put("processId", 43), identity, now));
        assertFalse(LocalLifecycle.validRequest(request.deepCopy().put("startedAt", now.toString()), identity, now));
    }

    @Test
    void rejectsOldFutureMalformedAndPreStartRequests() {
        Instant now = Instant.now();
        ObjectNode identity = mapper.createObjectNode().put("instanceId", UUID.randomUUID().toString())
            .put("processId", 42).put("startedAt", now.minusSeconds(5).toString());
        for (String requestedAt : new String[] { now.minusSeconds(700).toString(), now.plusSeconds(700).toString(), "bad-date", now.minusSeconds(6).toString() }) {
            assertFalse(LocalLifecycle.validRequest(identity.deepCopy().put("requestedAt", requestedAt), identity, now));
        }
    }
}
