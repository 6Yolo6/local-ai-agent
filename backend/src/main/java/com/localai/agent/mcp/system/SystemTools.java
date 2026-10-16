package com.localai.agent.mcp.system;

import com.localai.agent.ollama.LlamaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 系统类 MCP 工具集
 *
 * <p>包含工具：
 * <ul>
 *   <li>executeCommand  - Windows 命令行执行</li>
 *   <li>parseLog        - AI 辅助日志分析</li>
 *   <li>readLastLines   - 读取文件末尾 N 行（tail 功能）</li>
 *   <li>getSystemInfo   - 获取系统状态信息</li>
 * </ul>
 *
 * <p>安全注意：命令执行有白名单过滤，禁止危险命令（rm -rf、format 等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemTools {

    private final LlamaService llamaService;

    @Value("${local-ai.file.workspace-root:${user.dir}}")
    private String workspaceRoot;

    /**
     * 禁止执行的危险命令关键词（小写匹配）。
     * 安全起见，这些命令被强制拒绝。
     */
    private static final List<String> BLOCKED_COMMANDS = List.of(
            "format", "rd /s /q c:", "del /f /s /q c:",
            "shutdown", "reg delete", "netsh firewall",
            "schtasks /delete", "net user /delete",
            "diskpart", "bcdedit", "bootrec"
    );

    private static final Set<String> ALLOWED_EXECUTABLES = Set.of(
            "dir", "type", "echo", "git", "mvn", "mvn.cmd",
            "npm", "npm.cmd", "node", "node.exe", "java", "java.exe",
            "where", "where.exe", "tasklist", "netstat", "ipconfig", "ping"
    );

    // ============================================================
    //  工具 1：执行 Windows 命令
    // ============================================================

    /**
     * 在 Windows 环境中执行命令行命令。
     *
     * <p>支持的命令类型：
     * <ul>
     *   <li>文件操作：dir, copy, move, mkdir, type</li>
     *   <li>网络诊断：ping, ipconfig, netstat</li>
     *   <li>进程管理：tasklist, taskkill（受限）</li>
     *   <li>Maven/npm 构建命令</li>
     *   <li>Git 命令</li>
     * </ul>
     *
     * @param command    要执行的命令（不含 cmd /c 前缀）
     * @param workingDir 工作目录（可选，为空则使用项目根目录）
     * @return 命令输出文本
     */
    @Tool(description = "在 Windows 命令行中执行命令。危险命令（format/shutdown等）被禁止。命令超时 60 秒。")
    public String executeCommand(String command, String workingDir) {
        log.info("[SystemTool] executeCommand: {}", command);

        // ── 安全检查 ──────────────────────────────────────────
        String lowerCmd = command.toLowerCase().trim();
        for (String blocked : BLOCKED_COMMANDS) {
            if (lowerCmd.contains(blocked)) {
                log.warn("[SystemTool] 危险命令被拦截: {}", command);
                return "🚫 命令被安全策略拒绝（包含危险操作：" + blocked + "）";
            }
        }
        if (containsShellControl(command)) {
            return "❌ 命令被拒绝：不允许使用管道、重定向或命令连接符";
        }
        String executable = firstToken(command);
        if (!ALLOWED_EXECUTABLES.contains(executable)) {
            return "❌ 命令被拒绝：不在允许列表中（" + executable + "）";
        }

        try {
            // Windows 通过 cmd /c 执行命令
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command);

            // 设置工作目录
            Path wdPath = resolveWorkspacePath(".");
            if (workingDir != null && !workingDir.isBlank()) {
                wdPath = resolveWorkspacePath(workingDir);
            }
            if (Files.exists(wdPath) && Files.isDirectory(wdPath)) {
                pb.directory(wdPath.toFile());
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出（Windows 下通常是 GBK 编码）
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(),
                            Charset.forName("GBK")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > 30000) {
                        output.append("\n...[输出已截断，超过30KB]...\n");
                        break;
                    }
                }
            }

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "⚠️ 命令超时（60s），已终止\n输出：\n" + output;
            }

            int exitCode = process.exitValue();
            String header = exitCode == 0
                    ? "✅ 执行成功（exit " + exitCode + "）\n"
                    : "⚠️ 退出码：" + exitCode + "\n";

            return header + output;

        } catch (Exception e) {
            log.error("[SystemTool] executeCommand 失败: {}", e.getMessage(), e);
            return "❌ 命令执行失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 2：AI 辅助日志分析
    // ============================================================

    /**
     * 读取日志文件并使用 AI 分析错误、警告和异常。
     *
     * @param logFilePath 日志文件路径
     * @param maxLines    最多分析的行数（建议 200-500 行）
     * @return AI 分析报告
     */
    @Tool(description = "读取日志文件并使用 AI 分析错误/异常，提供问题摘要和解决建议。")
    public String parseLog(String logFilePath, int maxLines) {
        log.info("[SystemTool] parseLog: {}, maxLines={}", logFilePath, maxLines);
        try {
            Path path = resolveWorkspacePath(logFilePath);
            if (!Files.exists(path)) {
                return "❌ 日志文件不存在：" + logFilePath;
            }

            // 读取末尾 N 行
            List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int limit = Math.min(maxLines > 0 ? maxLines : 200, allLines.size());
            List<String> lines = allLines.subList(Math.max(0, allLines.size() - limit), allLines.size());
            String logContent = String.join("\n", lines);

            if (logContent.isBlank()) {
                return "ℹ️ 日志文件为空";
            }

            // AI 分析
            String prompt = """
                    请分析以下应用日志，重点关注：
                    1. ERROR 和 WARN 级别的日志
                    2. 异常堆栈（Exception / Error）
                    3. 异常发生时间和频率
                    4. 可能的根本原因
                    5. 解决建议
                    
                    日志内容（最新 %d 行）：
                    ```
                    %s
                    ```
                    
                    请给出简洁的分析报告（中文）：
                    """.formatted(lines.size(), logContent);

            return llamaService.chat(prompt);

        } catch (Exception e) {
            log.error("[SystemTool] parseLog 失败: {}", e.getMessage(), e);
            return "❌ 日志分析失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 3：读取文件末尾 N 行（tail）
    // ============================================================

    /**
     * 读取文件末尾 N 行，类似 Linux tail 命令。
     * 适用于实时查看日志。
     *
     * @param filePath  文件路径
     * @param lineCount 读取行数（默认 50）
     * @return 末尾 N 行内容
     */
    @Tool(description = "读取文件末尾 N 行（类似 tail 命令）。用于实时查看日志最新内容。")
    public String readLastLines(String filePath, int lineCount) {
        log.info("[SystemTool] readLastLines: {}, lines={}", filePath, lineCount);
        try {
            Path path = resolveWorkspacePath(filePath);
            if (!Files.exists(path)) {
                return "❌ 文件不存在：" + filePath;
            }

            int n = lineCount > 0 ? Math.min(lineCount, 1000) : 50;
            List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> lastLines = allLines.subList(
                    Math.max(0, allLines.size() - n), allLines.size());

            return "📄 " + filePath + "（末尾 " + lastLines.size() + " 行）：\n"
                    + String.join("\n", lastLines);

        } catch (Exception e) {
            log.error("[SystemTool] readLastLines 失败: {}", e.getMessage(), e);
            return "❌ 读取失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 4：获取系统信息
    // ============================================================

    /**
     * 获取当前 Windows 系统状态信息（内存、CPU、进程等）。
     *
     * @return 系统信息文本
     */
    @Tool(description = "获取 Windows 系统状态：内存使用、JVM 信息、运行进程列表。")
    public String getSystemInfo() {
        log.info("[SystemTool] getSystemInfo");
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory() / (1024 * 1024);
            long freeMemory  = runtime.freeMemory()  / (1024 * 1024);
            long usedMemory  = totalMemory - freeMemory;
            long maxMemory   = runtime.maxMemory()   / (1024 * 1024);

            String jvmInfo = """
                    ─── JVM 内存 ───────────────────────
                    最大内存: %d MB
                    已分配:   %d MB
                    已使用:   %d MB
                    可用:     %d MB
                    CPU 核心: %d
                    Java 版本: %s
                    OS:       %s %s
                    """.formatted(
                    maxMemory, totalMemory, usedMemory, freeMemory,
                    runtime.availableProcessors(),
                    System.getProperty("java.version"),
                    System.getProperty("os.name"),
                    System.getProperty("os.version")
            );

            // 获取 Ollama 进程状态
            String ollamaStatus = checkProcessRunning("ollama") ? "🟢 运行中" : "🔴 未启动";

            return jvmInfo + "\n─── 关键服务状态 ───────────────────\n"
                    + "Ollama:      " + ollamaStatus + "\n";

        } catch (Exception e) {
            return "❌ 获取系统信息失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  私有工具方法
    // ============================================================

    /**
     * 检查指定名称的进程是否在运行（Windows tasklist）。
     */
    private Path resolveWorkspacePath(String filePath) {
        Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        Path p = Paths.get(filePath);
        Path resolved = (p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("路径超出工作区范围：" + resolved);
        }
        return resolved;
    }

    private boolean containsShellControl(String command) {
        return command.contains("&")
                || command.contains("|")
                || command.contains(">")
                || command.contains("<")
                || command.contains(";");
    }

    private String firstToken(String command) {
        String trimmed = command.trim();
        String token;
        if (trimmed.startsWith("\"")) {
            int endQuote = trimmed.indexOf('"', 1);
            token = endQuote > 1 ? trimmed.substring(1, endQuote) : trimmed.substring(1);
        } else {
            int space = trimmed.indexOf(' ');
            token = space > 0 ? trimmed.substring(0, space) : trimmed;
        }
        try {
            Path fileName = Paths.get(token).getFileName();
            return (fileName != null ? fileName.toString() : token).toLowerCase();
        } catch (Exception ignored) {
            return token.toLowerCase();
        }
    }

    private boolean checkProcessRunning(String processName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                    "tasklist /fi \"imagename eq " + processName + ".exe\" /fo csv /nh");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(),
                    Charset.forName("GBK"));
            p.waitFor(5, TimeUnit.SECONDS);
            return output.toLowerCase().contains(processName.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }
}
