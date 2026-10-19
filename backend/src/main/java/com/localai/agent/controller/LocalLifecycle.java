package com.localai.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local file handshake only: this bean deliberately exposes no HTTP endpoint. */
@Component
public class LocalLifecycle {
    private static final Logger log = LoggerFactory.getLogger(LocalLifecycle.class);
    private final ConfigurableApplicationContext context;
    private final ObjectMapper mapper;
    private final String instanceId;
    private final String controlDirectory;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private ScheduledExecutorService watcher;
    private JsonNode identity;

    public LocalLifecycle(ConfigurableApplicationContext context, ObjectMapper mapper,
                          @Value("${LOCAL_AI_INSTANCE_ID:}") String instanceId,
                          @Value("${LOCAL_AI_CONTROL_DIR:}") String controlDirectory) {
        this.context = context;
        this.mapper = mapper;
        this.instanceId = instanceId;
        this.controlDirectory = controlDirectory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (instanceId.isBlank() && controlDirectory.isBlank()) {
            log.info("Local lifecycle disabled for a process started outside the managed launcher");
            return;
        }
        UUID.fromString(instanceId);
        Path directory = Path.of(controlDirectory);
        if (!directory.getFileName().toString().equals(instanceId)) {
            throw new IllegalArgumentException("Lifecycle directory does not match its instance");
        }
        watcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "local-lifecycle-watch");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleWithFixedDelay(this::poll, 0, 250, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        if (stopping.get()) return;
        try {
            Path directory = Path.of(controlDirectory);
            if (identity == null) {
                Path identityFile = directory.resolve("identity.json");
                if (!Files.isRegularFile(identityFile)) return;
                JsonNode candidate = mapper.readTree(Files.readString(identityFile).replace("\uFEFF", ""));
                Instant processStart = ProcessHandle.current().info().startInstant().orElseThrow();
                if (!instanceId.equals(candidate.path("instanceId").asText()) ||
                    candidate.path("processId").asLong() != ProcessHandle.current().pid() ||
                    Math.abs(Instant.parse(candidate.path("startedAt").asText()).toEpochMilli() - processStart.toEpochMilli()) > 1000) {
                    throw new IllegalStateException("Launcher identity does not match this JVM");
                }
                identity = candidate;
                write("ready.json", "ready", null);
            }
            Path requestFile = directory.resolve("stop.request.json");
            if (!Files.isRegularFile(requestFile)) return;
            JsonNode request = mapper.readTree(Files.readString(requestFile).replace("\uFEFF", ""));
            if (!validRequest(request, identity, Instant.now())) {
                write("stop.rejected.json", "rejected", "Instance, process identity or request time mismatch");
                return;
            }
            if (!stopping.compareAndSet(false, true)) return;
            write("stop.accepted.json", "draining", null);
            Thread closer = new Thread(() -> {
                try {
                    log.info("Managed graceful shutdown requested for instance {}", instanceId);
                    context.close();
                    write("stop.completed.json", "stopped", null);
                } catch (Exception error) {
                    log.error("Graceful shutdown failed; no force-exit attempted", error);
                    try { write("stop.failed.json", "failed", error.getMessage()); }
                    catch (Exception writeError) { log.error("Cannot write lifecycle failure", writeError); }
                }
            }, "local-lifecycle-close");
            closer.setDaemon(false);
            closer.start();
        } catch (Exception error) {
            log.error("Local lifecycle handshake rejected: {}", error.getMessage());
        }
    }

    static boolean validRequest(JsonNode request, JsonNode identity, Instant now) {
        try {
            Instant requestedAt = Instant.parse(request.path("requestedAt").asText());
            return identity.path("instanceId").asText().equals(request.path("instanceId").asText()) &&
                identity.path("processId").asLong() == request.path("processId").asLong() &&
                identity.path("startedAt").asText().equals(request.path("startedAt").asText()) &&
                !requestedAt.isBefore(Instant.parse(identity.path("startedAt").asText())) &&
                Math.abs(now.toEpochMilli() - requestedAt.toEpochMilli()) <= 600000;
        } catch (RuntimeException error) { return false; }
    }

    private void write(String file, String status, String error) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("instanceId", instanceId);
        value.put("processId", ProcessHandle.current().pid());
        value.put("startedAt", identity.path("startedAt").asText());
        value.put("status", status);
        value.put("updatedAt", Instant.now().toString());
        if (error != null) value.put("error", error);
        Path target = Path.of(controlDirectory).resolve(file);
        Path temporary = target.resolveSibling(file + ".tmp");
        Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @PreDestroy
    public void stopWatcher() {
        if (watcher != null) watcher.shutdownNow();
    }
}
