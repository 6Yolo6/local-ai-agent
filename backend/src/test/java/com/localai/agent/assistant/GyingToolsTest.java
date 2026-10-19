package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class GyingToolsTest {
    ObjectMapper mapper = new ObjectMapper();
    HttpServer server;
    GyingTools tools;
    String baseUrl;
    volatile String payload = "{\"records\":[],\"total\":0}";
    volatile int status = 200;
    volatile boolean chunked;
    volatile long delayMillis;
    volatile String requestMethod;
    volatile String requestPath;
    volatile String requestQuery;
    AtomicInteger requests = new AtomicInteger();
    java.util.Set<Integer> clientPorts = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; }));
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            clientPorts.add(exchange.getRemoteAddress().getPort());
            requestMethod = exchange.getRequestMethod();
            requestPath = exchange.getRequestURI().getPath();
            requestQuery = exchange.getRequestURI().getRawQuery();
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", baseUrl + "/forbidden");
                exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        tools = new GyingTools(baseUrl, mapper);
    }
    @AfterEach void cleanup() { tools.close(); server.stop(0); }
    ObjectNode query(String keyword) { return mapper.createObjectNode().put("keyword", keyword); }
    ObjectNode movie(String id) {
        return mapper.createObjectNode().put("id", id).put("titleCn", "测试影片").put("year", 2023).put("status", "ACTIVE");
    }
    ObjectNode resource(String url) {
        return mapper.createObjectNode().put("url", url).put("provider", "QUARK").put("type", "DISK")
                .put("auditStatus", 1).put("status", "ACTIVE").put("linkStatus", "NORMAL").putNull("deletedAt")
                .put("validatedAt", "2026-10-01T12:00:00");
    }
    String code(ObjectNode result) { return result.path("error").path("code").asText(); }

    @Test void consecutiveQueriesUseSeparateConnections() {
        assertThat(tools.execute("searchMovies", query("first")).path("ok").asBoolean()).isTrue();
        assertThat(tools.execute("searchMovies", query("second")).path("ok").asBoolean()).isTrue();
        assertThat(clientPorts).hasSize(2);
        assertThat(requests).hasValue(2);
    }

    @Test void rejectsNonLiteralLoopbackAndConfigWithCredentialsPathsOrQueries() {
        for (String value : List.of("https://127.0.0.1:8880", "http://localhost:8880", "http://127.1:8880",
                "http://192.168.1.2:8880", "http://example.com:8880", "http://127.0.0.1:8880@evil.test:80",
                "http://user:pass@127.0.0.1:8880", "http://127.0.0.1:8880/api", "http://127.0.0.1:8880?url=x",
                "http://127.0.0.1:8880#x", "http://127.0.0.1", "file:///tmp/x", "http://127.0.0.1:0"))
            assertThatThrownBy(() -> GyingTools.checkedOrigin(value)).isInstanceOf(IllegalArgumentException.class);
        assertThat(GyingTools.checkedOrigin("http://[::1]:8880").host()).isEqualTo("::1");
    }

    @Test void searchUsesFixedGetPathEncodesKeywordAndReturnsCompactProvenance() {
        ObjectNode body = mapper.createObjectNode().put("total", 4);
        body.putArray("records").add(movie("m1")).add(movie("m2")).add(movie("m3"));
        payload = body.toString();
        ObjectNode result = tools.execute("searchMovies", query("流浪&size=60").put("year", 2023).put("category", "mv").put("limit", 2));
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(requestMethod).isEqualTo("GET");
        assertThat(requestPath).isEqualTo("/api/movies/list");
        assertThat(requestQuery).contains("size=2", "keyword=", "%26size%3D60", "year=2023", "category=mv");
        assertThat(result.path("data").path("movies").size()).isEqualTo(2);
        assertThat(result.path("data").path("truncated").asBoolean()).isTrue();
        assertThat(result.path("data").path("source").asText()).startsWith(baseUrl + "/api/movies/list?");
        assertThat(result.path("data").path("queriedAt").asText()).endsWith("Z");
        assertThat(result.path("data").path("externalLinksVerifiedNow").asBoolean(true)).isFalse();
    }

    @Test void invalidArgumentsAndUnlistedToolsNeverReachTheServer() {
        for (ObjectNode args : List.of(query(" "), query("x").put("limit", 0), query("x").put("limit", 4),
                query("x").put("year", "2023"), query("x").put("year", 2101), query("x").put("category", "admin"),
                query("x").put("url", "http://evil.test"), query("x").put("page", 2), query("x".repeat(61))))
            assertThat(code(tools.execute("searchMovies", args))).isEqualTo("INVALID_ARGUMENTS");
        for (String id : List.of("../admin", "x?repair=true", "%2f", "http://127.0.0.1", " ", "x".repeat(65)))
            assertThat(code(tools.execute("getMovieDetails", mapper.createObjectNode().put("id", id)))).isEqualTo("INVALID_ARGUMENTS");
        assertThat(code(tools.execute("readFile", mapper.createObjectNode().put("path", "x")))).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(code(tools.execute("repairResource", mapper.createObjectNode()))).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(requests).hasValue(0);
    }

    @Test void detailsKeepOnlyApprovedActiveNormalResourcesAndNeverFetchTheirUrls() {
        ObjectNode body = mapper.createObjectNode();
        body.set("movie", movie("gying_mv_test").put("summary", "简介".repeat(150)));
        body.putArray("resources").add(resource("https://pan.quark.cn/s/example"))
                .add(resource("https://invalid.test").put("linkStatus", "INVALID"))
                .add(resource("https://pending.test").put("auditStatus", 0))
                .add(resource("https://inactive.test").put("status", "INACTIVE"))
                .add(resource("https://deleted.test").put("deletedAt", "2026-10-01"))
                .add(resource("https://unknown.test").putNull("linkStatus"));
        payload = body.toString();
        ObjectNode result = tools.execute("getMovieDetails", mapper.createObjectNode().put("id", "gying_mv_test"));
        assertThat(requestPath).isEqualTo("/api/movies/gying_mv_test");
        assertThat(requestMethod).isEqualTo("GET");
        assertThat(requests).hasValue(1);
        assertThat(result.path("data").path("resources").size()).isEqualTo(1);
        assertThat(result.path("data").path("resources").get(0).path("url").asText()).isEqualTo("https://pan.quark.cn/s/example");
        assertThat(result.path("data").path("resources").get(0).path("validatedAt").asText()).isEqualTo("2026-10-01T12:00:00");
        assertThat(result.path("data").path("resourceSummary").path("excluded").asInt()).isEqualTo(5);
        assertThat(result.path("data").path("movie").path("summary").asText()).hasSize(160);
        assertThat(result.path("data").path("truncated").asBoolean()).isTrue();
    }

    @Test void neverReturnsExecutableOrOversizedUrlsAndNeverInventsReplacementLinks() {
        ObjectNode body = mapper.createObjectNode();
        body.set("movie", movie("m1"));
        body.putArray("resources").add(resource("javascript:alert(1)"))
                .add(resource("https://example.com/" + "x".repeat(610)));
        payload = body.toString();
        ObjectNode result = tools.execute("getMovieDetails", mapper.createObjectNode().put("id", "m1"));
        result.path("data").path("resources").forEach(item -> {
            assertThat(item.has("url")).isFalse();
            assertThat(item.path("urlOmitted").asBoolean()).isTrue();
        });
    }

    @Test void boundedResourceListPrefersCloudLinksAndReportsOmittedEntries() {
        ObjectNode body = mapper.createObjectNode();
        body.set("movie", movie("m1"));
        body.putArray("resources").add(resource("magnet:?xt=urn:btih:abc").put("provider", "P2P"))
                .add(resource("https://pan.xunlei.com/s/example").put("provider", "XUNLEI"))
                .add(resource("https://pan.quark.cn/s/example"));
        payload = body.toString();
        ObjectNode result = tools.execute("getMovieDetails", mapper.createObjectNode().put("id", "m1"));
        assertThat(result.path("data").path("resources").size()).isEqualTo(2);
        assertThat(result.path("data").path("resources").get(0).path("provider").asText()).isEqualTo("QUARK");
        assertThat(result.path("data").path("resourceSummary").path("approvedNormal").asInt()).isEqualTo(3);
        assertThat(result.path("data").path("truncated").asBoolean()).isTrue();
    }

    @Test void excludesAccountRequiredTorrentPagesAcrossMirrorsBeforeApplyingTheResourceLimit() {
        ObjectNode body = mapper.createObjectNode();
        body.set("movie", movie("m1"));
        body.putArray("resources")
                .add(resource("https://www.xn--wcv59z.com/dbt/naWV").put("type", "TORRENT"))
                .add(resource("https://another-mirror.test/DBT/file?download=1"))
                .add(resource("https://mirror.test/%64bt/file"))
                .add(resource("magnet:?xt=urn:btih:0123456789012345678901234567890123456789").put("provider", "P2P"))
                .add(resource("https://pan.quark.cn/s/owned-archive"));
        payload = body.toString();
        ObjectNode result = tools.execute("getMovieDetails", mapper.createObjectNode().put("id", "m1"));
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("data").path("resourceSummary").path("excluded").asInt()).isEqualTo(3);
        assertThat(result.path("data").path("resourceSummary").path("approvedNormal").asInt()).isEqualTo(2);
        assertThat(result.path("data").path("resources").get(0).path("url").asText()).isEqualTo("https://pan.quark.cn/s/owned-archive");
        assertThat(result.path("data").path("resources").get(1).path("url").asText()).startsWith("magnet:");
        assertThat(result.toString()).doesNotContain("xn--wcv59z", "another-mirror", "mirror.test");
        assertThat(requests).hasValue(1);
    }

    @Test void inactiveOrMismatchedMovieDetailsAreNotExposed() {
        ObjectNode body = mapper.createObjectNode();
        body.set("movie", movie("m1").put("status", "INACTIVE"));
        body.putArray("resources");
        payload = body.toString();
        assertThat(code(tools.execute("getMovieDetails", mapper.createObjectNode().put("id", "m1")))).isEqualTo("GYING_NOT_FOUND");
        assertThat(code(tools.execute("getMovieDetails", mapper.createObjectNode().put("id", "m2")))).isEqualTo("GYING_INVALID_RESPONSE");
    }

    @Test void redirectsAreRejectedWithoutFollowingTheLocation() {
        status = 302;
        assertThat(code(tools.execute("searchMovies", query("x")))).isEqualTo("GYING_REDIRECT_DENIED");
        assertThat(requests).hasValue(1);
    }

    @Test void oversizedContentLengthAndChunkedBodiesAreBothBounded() {
        payload = "x".repeat(GyingTools.MAX_RESPONSE_BYTES + 1);
        assertThat(code(tools.execute("searchMovies", query("x")))).isEqualTo("GYING_RESPONSE_TOO_LARGE");
        chunked = true;
        assertThat(code(tools.execute("searchMovies", query("x")))).isEqualTo("GYING_RESPONSE_TOO_LARGE");
    }

    @Test void handlesNotFoundHttpErrorsMalformedJsonAndWrongShapeWithoutExposingBodies() {
        status = 404;
        assertThat(code(tools.execute("getMovieDetails", mapper.createObjectNode().put("id", "missing")))).isEqualTo("GYING_NOT_FOUND");
        status = 503;
        payload = "sensitive internal exception";
        ObjectNode result = tools.execute("searchMovies", query("x"));
        assertThat(code(result)).isEqualTo("GYING_HTTP_ERROR");
        assertThat(result.toString()).doesNotContain(payload);
        status = 200;
        assertThat(code(tools.execute("searchMovies", query("x")))).isEqualTo("GYING_INVALID_RESPONSE");
        payload = "{}";
        assertThat(code(tools.execute("searchMovies", query("x")))).isEqualTo("GYING_INVALID_RESPONSE");
    }

    @Test void timesOutSlowUpstreamAndReportsUnavailableServer() {
        delayMillis = 4500;
        long started = System.nanoTime();
        assertThat(code(tools.execute("searchMovies", query("x")))).isEqualTo("GYING_TIMEOUT");
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(4400);
        server.stop(0);
        assertThat(code(tools.execute("searchMovies", query("x")))).isEqualTo("GYING_UNAVAILABLE");
    }
}
