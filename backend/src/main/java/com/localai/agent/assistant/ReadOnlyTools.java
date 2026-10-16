package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Separate capability boundary: the autonomous assistant never invokes the legacy MCP registry. */
@Service
public class ReadOnlyTools {
    private final Path configuredRoot;
    private final ObjectMapper mapper;
    private static final int MAX_FILE_BYTES = 262144;

    public ReadOnlyTools(@Value("${local-ai.file.workspace-root:${user.dir}}") String workspaceRoot, ObjectMapper mapper) {
        this.configuredRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public String workspaceRoot() { return configuredRoot.toString(); }

    public ArrayNode definitions() {
        ArrayNode tools = mapper.createArrayNode();
        definition(tools, "getSystemInfo", "查询本机操作系统、CPU 核心数、Java 和 JVM 内存；不运行命令。", Map.of(), List.of());
        definition(tools, "listFiles", "列出工作区目录的直接子项。根目录 path 为 .，最多返回 100 项。",
                Map.of("path", stringSchema("工作区内目录路径")), List.of("path"));
        definition(tools, "readFile", "读取工作区 UTF-8 文件前 3000 字符；truncated=true 表示未读完，不能猜测未显示内容。",
                Map.of("path", stringSchema("工作区内文件路径")), List.of("path"));
        definition(tools, "readLastLines", "读取工作区内不超过 256 KiB 的 UTF-8 文本文件末尾，输出最多 6000 字符。",
                Map.of("path", stringSchema("工作区内文件路径"), "lineCount", intSchema(1, 100, 30)), List.of("path"));
        return tools;
    }

    private Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description, "minLength", 1, "maxLength", 1024);
    }
    private Map<String, Object> intSchema(int min, int max, int defaultValue) {
        return Map.of("type", "integer", "minimum", min, "maximum", max, "default", defaultValue);
    }
    private void definition(ArrayNode tools, String name, String description, Map<String, ?> properties, List<String> required) {
        ObjectNode function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", name).put("description", description);
        function.set("parameters", mapper.valueToTree(Map.of("type", "object", "properties", properties,
                "required", required, "additionalProperties", false)));
    }

    public ObjectNode execute(String name, JsonNode arguments) {
        try {
            if (!Set.of("getSystemInfo", "listFiles", "readFile", "readLastLines").contains(name)) {
                throw new ToolFailure("TOOL_NOT_ALLOWED", "工具不在只读允许列表中");
            }
            if (arguments == null || !arguments.isObject()) throw new ToolFailure("INVALID_ARGUMENTS", "参数必须为 JSON 对象");
            Set<String> allowed = switch (name) {
                case "getSystemInfo" -> Set.of();
                case "readFile" -> Set.of("path");
                case "readLastLines" -> Set.of("path", "lineCount");
                default -> Set.of("path");
            };
            Iterator<String> fields = arguments.fieldNames();
            while (fields.hasNext()) if (!allowed.contains(fields.next())) throw new ToolFailure("INVALID_ARGUMENTS", "参数包含未知字段");
            Object data = switch (name) {
                case "getSystemInfo" -> systemInfo();
                case "listFiles" -> listFiles(pathArgument(arguments));
                case "readFile" -> readFile(pathArgument(arguments), 3000);
                case "readLastLines" -> readLastLines(pathArgument(arguments), integerArgument(arguments, "lineCount", 30, 100));
                default -> throw new IllegalStateException();
            };
            ObjectNode result = mapper.createObjectNode().put("ok", true);
            result.set("data", mapper.valueToTree(data));
            return result;
        } catch (ToolFailure e) {
            return error(e.code, e.getMessage());
        } catch (NoSuchFileException e) {
            return error("NOT_FOUND", "文件或目录不存在");
        } catch (AccessDeniedException | SecurityException e) {
            return error("ACCESS_DENIED", "无法访问该文件或目录");
        } catch (InvalidPathException e) {
            return error("INVALID_ARGUMENTS", "无效路径");
        } catch (java.nio.charset.CharacterCodingException e) {
            return error("NOT_TEXT_FILE", "只允许读取 UTF-8 文本文件");
        } catch (IOException e) {
            return error("IO_ERROR", "读取文件失败");
        }
    }

    public ObjectNode error(String code, String message) {
        ObjectNode result = mapper.createObjectNode().put("ok", false);
        result.putObject("error").put("code", code).put("message", message);
        return result;
    }
    private String pathArgument(JsonNode arguments) {
        JsonNode path = arguments.get("path");
        if (path == null || !path.isTextual() || path.asText().isBlank() || path.asText().length() > 1024)
            throw new ToolFailure("INVALID_ARGUMENTS", "path 必须是 1 至 1024 字符的非空字符串");
        return path.asText();
    }
    private int integerArgument(JsonNode arguments, String key, int fallback, int max) {
        JsonNode value = arguments.get(key);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > max)
            throw new ToolFailure("INVALID_ARGUMENTS", key + " 必须是 1 至 " + max + " 的整数");
        return value.intValue();
    }

    private Path resolve(String value) throws IOException {
        // Reject network/device paths and NTFS alternate data streams even on non-Windows hosts.
        if (value.startsWith("\\\\") || value.startsWith("//") || value.indexOf('\0') >= 0
                || value.indexOf(':', value.matches("^[A-Za-z]:.*") ? 2 : 0) >= 0)
            throw new ToolFailure("PATH_DENIED", "不允许网络路径、设备路径或文件数据流");
        Path input = Path.of(value);
        for (Path component : input) {
            if (sensitive(component.toString())) throw new ToolFailure("SENSITIVE_PATH", "该路径属于本机配置、凭据或私有运行目录，不允许读取");
        }
        Path candidate = (input.isAbsolute() ? input : configuredRoot.resolve(input)).toAbsolutePath().normalize();
        if (!candidate.startsWith(configuredRoot)) throw new ToolFailure("PATH_DENIED", "路径超出工作区");
        Path realRoot = configuredRoot.toRealPath();
        Path current = configuredRoot;
        // Every descendant must refer to itself. This also rejects Windows junctions/reparse links.
        for (Path part : configuredRoot.relativize(candidate)) {
            if (sensitive(part.toString())) throw new ToolFailure("SENSITIVE_PATH", "不允许访问私有配置或凭据");
            current = current.resolve(part);
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) throw new ToolFailure("PATH_DENIED", "禁止访问链接或特殊文件");
            Path actual = current.toRealPath();
            Path expected = realRoot.resolve(configuredRoot.relativize(current)).normalize();
            if (!actual.startsWith(realRoot) || !actual.equals(expected))
                throw new ToolFailure("PATH_DENIED", "禁止通过符号链接或目录联接访问文件");
        }
        if (!candidate.toRealPath().startsWith(realRoot)) throw new ToolFailure("PATH_DENIED", "路径超出工作区");
        return candidate;
    }

    private Map<String, Object> systemInfo() {
        Runtime runtime = Runtime.getRuntime();
        return Map.of("os", System.getProperty("os.name"), "osVersion", System.getProperty("os.version"),
                "javaVersion", System.getProperty("java.version"), "cpuCores", runtime.availableProcessors(),
                "jvmUsedBytes", runtime.totalMemory() - runtime.freeMemory(), "jvmMaxBytes", runtime.maxMemory(),
                "workspaceRoot", workspaceRoot(), "mode", "read-only");
    }
    private Map<String, Object> listFiles(String value) throws IOException {
        Path path = resolve(value);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new ToolFailure("NOT_DIRECTORY", "路径不是目录");
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean truncated = false;
        int scanned = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) {
                if (Thread.currentThread().isInterrupted()) throw new ToolFailure("TOOL_TIMEOUT", "工具执行超时");
                if (entries.size() >= 100 || ++scanned > 500) { truncated = true; break; }
                if (sensitive(child.getFileName().toString())) continue;
                BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                String type = attrs.isSymbolicLink() || attrs.isOther() ? "link" : attrs.isDirectory() ? "directory" : "file";
                entries.add(Map.of("name", child.getFileName().toString(), "type", type,
                        "size", attrs.isRegularFile() ? attrs.size() : 0));
            }
        }
        resolve(value);
        entries.sort(Comparator.comparing(entry -> entry.get("name").toString()));
        return Map.of("path", display(path), "entries", entries, "truncated", truncated);
    }
    private Map<String, Object> readFile(String value, int maxChars) throws IOException {
        Path path = resolve(value);
        String text = boundedText(path, maxChars + 1);
        resolve(value);
        boolean truncated = text.length() > maxChars;
        return Map.of("path", display(path), "content", truncated ? text.substring(0, maxChars) : text, "truncated", truncated);
    }
    private Map<String, Object> readLastLines(String value, int count) throws IOException {
        Path path = resolve(value);
        String text = boundedText(path, MAX_FILE_BYTES + 1);
        resolve(value);
        String[] lines = text.split("\\R", -1);
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) end--;
        String content = String.join("\n", Arrays.copyOfRange(lines, Math.max(0, end - count), end));
        boolean truncated = content.length() > 6000;
        if (truncated) content = content.substring(content.length() - 6000);
        return Map.of("path", display(path), "content", content, "truncated", truncated, "lineCount", Math.min(count, end));
    }
    private String boundedText(Path path, int limit) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new ToolFailure("NOT_FILE", "只允许读取普通文件");
        if (attrs.size() > MAX_FILE_BYTES) throw new ToolFailure("FILE_TOO_LARGE", "文件超过 256 KiB 读取上限");
        char[] buffer = new char[limit];
        int read = 0;
        try (Reader reader = Channels.newReader(Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT), -1)) {
            while (read < limit) {
                if (Thread.currentThread().isInterrupted()) throw new ToolFailure("TOOL_TIMEOUT", "工具执行超时");
                int n = reader.read(buffer, read, limit - read);
                if (n == -1) break;
                read += n;
            }
        }
        String text = new String(buffer, 0, read);
        if (text.indexOf('\0') >= 0) throw new ToolFailure("NOT_TEXT_FILE", "只允许读取 UTF-8 文本文件");
        return text;
    }
    private boolean sensitive(String component) {
        String name = component.toLowerCase(Locale.ROOT);
        return Set.of(".local", ".git", ".ssh", ".aws", ".azure", ".codex", "logs", "credentials", "secrets", "settings.xml", "id_rsa", "id_ed25519", "id_ecdsa").contains(name)
                || name.equals(".env") || name.startsWith(".env.") || name.startsWith("credentials.") || name.startsWith("secrets.")
                || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx");
    }
    private String display(Path path) {
        String value = configuredRoot.relativize(path).toString().replace('\\', '/');
        return value.isBlank() ? "." : value;
    }
    private static class ToolFailure extends RuntimeException {
        final String code;
        ToolFailure(String code, String message) { super(message); this.code = code; }
    }
}
