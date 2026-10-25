package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class GyingCatalogClientTest {
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper();
    final AtomicLong delayMillis=new AtomicLong();
    final AtomicInteger requests=new AtomicInteger();
    HttpServer server;
    ExecutorService executor;
    GyingCatalogClient client;

    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        server.createContext("/api/internal/ai/catalog/query", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(delayMillis.get());
                byte[] body="{\"movies\":[],\"returned\":0}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The deadline test intentionally disconnects from this synthetic server.
            } finally { exchange.close(); }
        });
        server.start();
        Path token=temp.resolve("bridge.txt");
        Files.writeString(token,UUID.randomUUID().toString());
        client=new GyingCatalogClient(mapper,"http://127.0.0.1:"+server.getAddress().getPort(),token.toString());
    }

    @AfterEach void stop() throws Exception {
        client.close();
        server.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
    }

    @Test void aColdCatalogCanRespondAfterTheFormerDefaultReadTimeout() throws Exception {
        delayMillis.set(11000);
        assertThat(client.query(mapper.createObjectNode()).path("returned").asInt(-1)).isZero();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void aShorterOverallDeadlineStillStopsTheRequest() {
        delayMillis.set(5000);
        long start=System.nanoTime();
        assertThatThrownBy(() -> client.query(mapper.createObjectNode(),start+TimeUnit.MILLISECONDS.toNanos(250)))
                .isInstanceOf(IOException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(2000);
    }

    @Test void anExpiredDeadlineDoesNotContactTheCatalog() {
        assertThatThrownBy(() -> client.query(mapper.createObjectNode(),System.nanoTime()-1))
                .isInstanceOf(IOException.class).hasMessageContaining("deadline expired");
        assertThat(requests.get()).isZero();
    }
}
