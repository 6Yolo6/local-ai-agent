package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Public catalog GETs only. Model arguments can never select a host, path or HTTP method. */
@Service
public class GyingTools {
    static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private final ObjectMapper mapper;
    private final HttpUrl origin;
    private final OkHttpClient client;

    public GyingTools(@Value("${local-ai.gying.base-url:http://127.0.0.1:8880}") String baseUrl, ObjectMapper mapper) {
        this.mapper = mapper;
        this.origin = checkedOrigin(baseUrl);
        this.client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                // Queries are infrequent; a fresh loopback socket avoids stale Docker-forwarded keep-alives.
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(1, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS).build();
    }

    @PreDestroy public void close() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    static HttpUrl checkedOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "[::1]").contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    || uri.getPort() < 1 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            return HttpUrl.get(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("GYING base-url 必须是带端口的 http 回环地址（127.0.0.1 或 [::1]），不能包含路径、凭据或查询参数");
        }
    }

    public ArrayNode definitions() {
        ArrayNode definitions = mapper.createArrayNode();
        definition(definitions, "searchMovies", "按片名或关键词查询 GYING 片库，最多 3 部；详情需用返回的 id 查询。",
                Map.of("keyword", Map.of("type", "string", "minLength", 1, "maxLength", 60),
                        "year", Map.of("type", "integer", "minimum", 1900, "maximum", 2100),
                        "category", Map.of("type", "string", "enum", List.of("mv", "tv", "ac")),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 3, "default", 3)), List.of("keyword"));
        definition(definitions, "getMovieDetails", "用片库 id 查询详情和已审核 NORMAL 资源；仅返回存量状态，未实时验证外部链接。",
                Map.of("id", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,64}$")), List.of("id"));
        return definitions;
    }

    private void definition(ArrayNode definitions, String name, String description, Map<String, ?> properties, List<String> required) {
        ObjectNode function = definitions.addObject().put("type", "function").putObject("function");
        function.put("name", name).put("description", description);
        function.set("parameters", mapper.valueToTree(Map.of("type", "object", "properties", properties,
                "required", required, "additionalProperties", false)));
    }

    public ObjectNode execute(String name, JsonNode arguments) {
        try {
            if (!Set.of("searchMovies", "getMovieDetails").contains(name))
                throw failure("TOOL_NOT_ALLOWED", "当前模式仅允许查询 GYING 片库");
            if (arguments == null || !arguments.isObject()) throw failure("INVALID_ARGUMENTS", "参数必须为 JSON 对象");
            Set<String> allowed = "searchMovies".equals(name) ? Set.of("keyword", "year", "category", "limit") : Set.of("id");
            Iterator<String> fields = arguments.fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) throw failure("INVALID_ARGUMENTS", "参数包含未知字段");
            ObjectNode data = "searchMovies".equals(name) ? search(arguments) : details(arguments);
            return mapper.createObjectNode().put("ok", true).set("data", data);
        } catch (ToolFailure e) {
            return error(e.code, e.getMessage());
        } catch (InterruptedIOException e) {
            return error("GYING_TIMEOUT", "GYING 查询超时，请稍后重试");
        } catch (IOException e) {
            return error("GYING_UNAVAILABLE", "无法连接本机 GYING 片库");
        }
    }

    private ObjectNode search(JsonNode arguments) throws IOException {
        String keyword = textArgument(arguments, "keyword", 60);
        int limit = integerArgument(arguments, "limit", 3, 1, 3);
        HttpUrl.Builder url = origin.newBuilder().addPathSegments("api/movies/list")
                .addQueryParameter("page", "1").addQueryParameter("size", Integer.toString(limit))
                .addQueryParameter("keyword", keyword);
        if (arguments.has("year")) url.addQueryParameter("year", Integer.toString(integerArgument(arguments, "year", 0, 1900, 2100)));
        if (arguments.has("category")) {
            String category = textArgument(arguments, "category", 2);
            if (!Set.of("mv", "tv", "ac").contains(category)) throw failure("INVALID_ARGUMENTS", "category 仅允许 mv、tv、ac");
            url.addQueryParameter("category", category);
        }
        HttpUrl endpoint = url.build();
        JsonNode body = get(endpoint);
        if (!body.path("records").isArray() || !body.path("total").isIntegralNumber() || !body.path("total").canConvertToLong() || body.path("total").asLong() < 0)
            throw failure("GYING_INVALID_RESPONSE", "GYING 返回了无法识别的搜索数据");
        ObjectNode data = provenance(endpoint).put("total", body.path("total").asLong());
        ArrayNode movies = data.putArray("movies");
        for (JsonNode movie : body.path("records")) {
            if (movies.size() >= limit) break;
            if (!activeMovie(movie)) continue;
            movies.add(movieSummary(movie));
        }
        data.put("returned", movies.size()).put("truncated", body.path("total").asLong() > movies.size());
        return data;
    }

    private ObjectNode details(JsonNode arguments) throws IOException {
        String id = textArgument(arguments, "id", 64);
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) throw failure("INVALID_ARGUMENTS", "id 必须使用搜索结果中的片库标识");
        HttpUrl endpoint = origin.newBuilder().addPathSegments("api/movies").addPathSegment(id).build();
        JsonNode body = get(endpoint);
        JsonNode movie = body.path("movie");
        if (!movie.isObject() || !id.equals(movie.path("id").asText()) || !body.path("resources").isArray())
            throw failure("GYING_INVALID_RESPONSE", "GYING 返回了无法识别的详情数据");
        if (!activeMovie(movie)) throw failure("GYING_NOT_FOUND", "该影片不存在或当前未公开");
        ObjectNode data = provenance(endpoint);
        ObjectNode summary = movieSummary(movie);
        text(summary, movie, "summary", 160);
        textList(summary, movie, "directors", 2);
        textList(summary, movie, "actors", 3);
        textList(summary, movie, "genres", 3);
        data.set("movie", summary);
        ArrayNode resources = data.putArray("resources");
        List<JsonNode> matching = new ArrayList<>();
        for (JsonNode resource : body.path("resources")) {
            if (!"ACTIVE".equals(resource.path("status").asText()) || !"NORMAL".equals(resource.path("linkStatus").asText())
                    || !resource.path("auditStatus").isIntegralNumber() || resource.path("auditStatus").asInt() != 1
                    || !resource.path("deletedAt").isNull() && !resource.path("deletedAt").isMissingNode()) continue;
            if (accountRequiredTorrent(resource.path("url").asText(""))) continue;
            matching.add(resource);
        }
        // Prefer shorter cloud-drive entries before P2P links in the small-model evidence budget.
        matching.sort(Comparator.comparingInt(resource -> providerOrder(resource.path("provider").asText())));
        for (JsonNode resource : matching) {
            if (resources.size() >= 2) break;
            ObjectNode item = resources.addObject().put("linkStatus", "NORMAL");
            text(item, resource, "provider", 24);
            text(item, resource, "type", 16);
            text(item, resource, "quality", 40);
            text(item, resource, "validatedAt", 40);
            String url = resource.path("url").asText("");
            if (safeResourceUrl(url)) {
                item.put("url", url);
                text(item, resource, "code", 20);
            } else item.put("urlOmitted", true);
        }
        data.putObject("resourceSummary").put("approvedNormal", matching.size()).put("returned", resources.size())
                .put("excluded", body.path("resources").size() - matching.size());
        data.put("truncated", matching.size() > resources.size() || summary.path("fieldsTruncated").asBoolean());
        return data;
    }

    private int providerOrder(String provider) {
        return switch (provider) { case "QUARK" -> 0; case "XUNLEI" -> 1; case "BAIDU" -> 2; default -> 3; };
    }

    private boolean activeMovie(JsonNode movie) {
        return movie.isObject() && "ACTIVE".equals(movie.path("status").asText())
                && (movie.path("deletedAt").isNull() || movie.path("deletedAt").isMissingNode())
                && movie.path("id").asText("").matches("[A-Za-z0-9_-]{1,64}");
    }

    private ObjectNode movieSummary(JsonNode movie) {
        ObjectNode result = mapper.createObjectNode();
        for (String key : List.of("id", "titleCn", "titleEn", "category")) text(result, movie, key, 80);
        for (String key : List.of("year", "doubanScore", "tmdbVoteAverage"))
            if (movie.path(key).isNumber()) result.set(key, movie.get(key));
        return result;
    }

    private void text(ObjectNode target, JsonNode source, String key, int max) {
        JsonNode value = source.path(key);
        if (!value.isTextual() || value.asText().isBlank()) return;
        String content = value.asText();
        target.put(key, content.substring(0, Math.min(content.length(), max)));
        if (content.length() > max) target.put("fieldsTruncated", true);
    }

    private void textList(ObjectNode target, JsonNode source, String key, int max) {
        if (!source.path(key).isArray()) return;
        ArrayNode result = target.putArray(key);
        for (JsonNode value : source.path(key)) {
            if (result.size() >= max) { target.put("fieldsTruncated", true); break; }
            if (value.isTextual()) result.add(value.asText().substring(0, Math.min(40, value.asText().length())));
        }
    }

    // GYING /dbt pages require a site account; they are not public torrent files.
    // Match the endpoint independently of rotating mirror domains and resource labels.
    private boolean accountRequiredTorrent(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) return false;
            String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.ROOT).matches("/dbt(?:/.*)?");
        } catch (IllegalArgumentException e) { return false; }
    }

    private boolean safeResourceUrl(String value) {
        if (value.isBlank() || value.length() > 600 || value.chars().anyMatch(c -> c <= 32 || c == 127)) return false;
        try {
            URI uri = URI.create(value);
            if ("magnet".equals(uri.getScheme())) return value.startsWith("magnet:?xt=urn:btih:");
            return uri.getScheme() != null && Set.of("https", "http").contains(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }

    private ObjectNode provenance(HttpUrl endpoint) {
        return mapper.createObjectNode().put("source", endpoint.toString()).put("queriedAt", Instant.now().toString())
                .put("externalLinksVerifiedNow", false);
    }

    private JsonNode get(HttpUrl endpoint) throws IOException {
        Request request = new Request.Builder().url(endpoint).get().header("Accept", "application/json").build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404) throw failure("GYING_NOT_FOUND", "GYING 中没有该影片");
            if (response.code() >= 300 && response.code() < 400) throw failure("GYING_REDIRECT_DENIED", "GYING 返回跳转，已拒绝跟随");
            if (!response.isSuccessful()) throw failure("GYING_HTTP_ERROR", "GYING 查询失败（HTTP " + response.code() + "）");
            ResponseBody body = response.body();
            if (body == null) throw failure("GYING_INVALID_RESPONSE", "GYING 返回空响应");
            if (body.contentLength() > MAX_RESPONSE_BYTES) throw failure("GYING_RESPONSE_TOO_LARGE", "GYING 响应超过 128 KiB 上限");
            byte[] bytes = body.byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw failure("GYING_RESPONSE_TOO_LARGE", "GYING 响应超过 128 KiB 上限");
            try {
                JsonNode data = mapper.readTree(bytes);
                if (data == null || !data.isObject()) throw new IOException();
                return data;
            } catch (IOException e) { throw failure("GYING_INVALID_RESPONSE", "GYING 返回了无效 JSON 数据"); }
        }
    }

    private String textArgument(JsonNode arguments, String name, int max) {
        JsonNode value = arguments.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > max
                || value.asText().chars().anyMatch(c -> c < 32 || c == 127))
            throw failure("INVALID_ARGUMENTS", name + " 必须为 1 至 " + max + " 字符的非空文本");
        return value.asText().trim();
    }

    private int integerArgument(JsonNode arguments, String name, int fallback, int min, int max) {
        JsonNode value = arguments.get(name);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw failure("INVALID_ARGUMENTS", name + " 必须是 " + min + " 至 " + max + " 的整数");
        return value.intValue();
    }

    private ObjectNode error(String code, String message) {
        ObjectNode result = mapper.createObjectNode().put("ok", false);
        result.putObject("error").put("code", code).put("message", message);
        return result;
    }
    private ToolFailure failure(String code, String message) { return new ToolFailure(code, message); }
    private static class ToolFailure extends RuntimeException {
        final String code;
        ToolFailure(String code, String message) { super(message); this.code = code; }
    }
}
