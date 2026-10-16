package com.localai.agent.mcp.dev;

import com.localai.agent.ollama.LlamaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 开发类 MCP 工具集
 *
 * <p>包含工具：
 * <ul>
 *   <li>generateCode     - Java/Vue 代码生成（调用本地 llama.cpp / Qwen3.6-35B）</li>
 *   <li>readFile         - 读取本地文件内容</li>
 *   <li>writeFile        - 写入/创建文件</li>
 *   <li>listFiles        - 列出目录内容</li>
 *   <li>deleteFile       - 删除文件/目录</li>
 *   <li>optimizeSql      - SQL 语句优化（调用 AI）</li>
 *   <li>compileProject   - 执行 Maven/npm 编译</li>
 * </ul>
 *
 * <p>所有方法通过 {@code @Tool} 注解暴露给 MCP 协议，
 * ClaudeCode / OpenClaw 可直接调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevTools {

    private final LlamaService llamaService;

    @Value("${local-ai.file.workspace-root:D:/Dev/project/local-ai-agent}")
    private String workspaceRoot;

    @Value("${local-ai.maven.executable:D:/Dev/Maven/apache-maven-3.9.9/bin/mvn.cmd}")
    private String mavenExecutable;

    @Value("${local-ai.nodejs.npm-executable:D:/Dev/NodeJs-24/npm.cmd}")
    private String npmExecutable;

    // ============================================================
    //  工具 1：代码生成（llama.cpp AI 驱动）
    // ============================================================

    /**
     * 根据需求描述，使用本地 Qwen3.6-35B 模型（通过 llama.cpp）生成 Java 或 Vue3 代码。
     *
     * @param language    编程语言（java / vue / javascript / sql 等）
     * @param requirement 功能需求描述（自然语言）
     * @param context     可选的上下文信息（已有类名、接口定义等）
     * @return 生成的代码字符串
     */
    @Tool(description = "根据需求描述生成代码，支持 Java/Vue3/SQL。使用本地 llama.cpp / Qwen3.6-35B 模型推理，完全离线。")
    public String generateCode(String language, String requirement, String context) {
        log.info("[DevTool] generateCode: language={}, requirement截取={}", language,
                requirement.length() > 50 ? requirement.substring(0, 50) + "..." : requirement);

        String systemPrompt = """
                你是一个专业的软件工程师，精通 Java 17、Spring Boot 3、Vue 3、MyBatis Plus。
                请根据用户需求生成高质量代码，要求：
                1. 代码结构清晰，注释完整（中文注释）
                2. 遵循最佳实践和设计模式
                3. 只输出代码，不需要额外解释
                4. 代码要可以直接运行
                """;

        String userPrompt = String.format("""
                请用 %s 生成以下功能的代码：
                
                需求：%s
                
                %s
                
                请直接输出完整可运行的代码：
                """,
                language,
                requirement,
                context != null && !context.isBlank() ? "上下文：\n" + context : ""
        );

        return llamaService.chatWithSystem(systemPrompt, userPrompt);
    }

    // ============================================================
    //  工具 2：读取文件
    // ============================================================

    /**
     * 读取指定路径的文件内容。
     *
     * @param filePath 文件的绝对路径或相对于工作区的相对路径
     * @return 文件文本内容
     */
    @Tool(description = "读取本地文件内容。支持绝对路径（D:/Dev/...）和相对路径（相对于项目工作区根目录）。")
    public String readFile(String filePath) {
        log.info("[DevTool] readFile: {}", filePath);
        try {
            Path path = resolvePath(filePath);
            if (!Files.exists(path)) {
                return "❌ 文件不存在：" + path.toAbsolutePath();
            }
            if (Files.isDirectory(path)) {
                return "❌ 指定路径是目录，请使用 listFiles 工具";
            }
            // 限制读取大小（5MB 以下）
            long size = Files.size(path);
            if (size > 5 * 1024 * 1024) {
                return "❌ 文件过大（" + size / 1024 + "KB），超过 5MB 限制";
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            log.debug("[DevTool] 读取成功，长度={}", content.length());
            return content;
        } catch (Exception e) {
            log.error("[DevTool] readFile 失败: {}", e.getMessage(), e);
            return "❌ 读取文件失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 3：写入文件
    // ============================================================

    /**
     * 将内容写入指定文件（不存在则创建，已存在则覆盖）。
     *
     * @param filePath 目标文件路径
     * @param content  要写入的文本内容
     * @param append   是否追加（true=追加到末尾，false=覆盖）
     * @return 操作结果描述
     */
    @Tool(description = "将文本内容写入文件。append=false 覆盖文件，append=true 追加到末尾。自动创建父目录。")
    public String writeFile(String filePath, String content, boolean append) {
        log.info("[DevTool] writeFile: {}, append={}", filePath, append);
        try {
            Path path = resolvePath(filePath);
            // 自动创建父目录
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            long size = Files.size(path);
            return "✅ 写入成功：" + path.toAbsolutePath() + "（" + size + " 字节）";
        } catch (Exception e) {
            log.error("[DevTool] writeFile 失败: {}", e.getMessage(), e);
            return "❌ 写入文件失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 4：列出目录内容
    // ============================================================

    /**
     * 列出目录下的文件和子目录。
     *
     * @param dirPath 目录路径
     * @return 目录内容列表（文本格式）
     */
    @Tool(description = "列出目录下的所有文件和子目录，返回树形结构文本。")
    public String listFiles(String dirPath) {
        log.info("[DevTool] listFiles: {}", dirPath);
        try {
            Path path = resolvePath(dirPath);
            if (!Files.exists(path)) {
                return "❌ 目录不存在：" + path.toAbsolutePath();
            }
            if (!Files.isDirectory(path)) {
                return "❌ 指定路径不是目录";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("📁 ").append(path.toAbsolutePath()).append("\n");
            try (var stream = Files.list(path)) {
                stream.sorted().forEach(p -> {
                    String prefix = Files.isDirectory(p) ? "  📁 " : "  📄 ";
                    sb.append(prefix).append(p.getFileName());
                    if (!Files.isDirectory(p)) {
                        try {
                            sb.append("  (").append(Files.size(p) / 1024).append("KB)");
                        } catch (Exception ignored) {}
                    }
                    sb.append("\n");
                });
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[DevTool] listFiles 失败: {}", e.getMessage(), e);
            return "❌ 列出目录失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 5：删除文件
    // ============================================================

    /**
     * 删除指定文件（不删除非空目录，需手动确认）。
     *
     * @param filePath 要删除的文件路径
     * @return 操作结果
     */
    @Tool(description = "删除指定文件。注意：只删除文件，不会递归删除非空目录，以防误操作。")
    public String deleteFile(String filePath) {
        log.info("[DevTool] deleteFile: {}", filePath);
        try {
            Path path = resolvePath(filePath);
            if (!Files.exists(path)) {
                return "⚠️ 文件不存在：" + path.toAbsolutePath();
            }
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    if (stream.findAny().isPresent()) {
                        return "❌ 目录非空，拒绝删除（安全保护）：" + path.toAbsolutePath();
                    }
                }
            }
            Files.delete(path);
            return "✅ 已删除：" + path.toAbsolutePath();
        } catch (Exception e) {
            log.error("[DevTool] deleteFile 失败: {}", e.getMessage(), e);
            return "❌ 删除失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  工具 6：SQL 优化
    // ============================================================

    /**
     * 使用 AI 分析并优化 SQL 语句，提供优化建议和改写后的 SQL。
     *
     * @param sql            原始 SQL 语句
     * @param tableStructure 可选的表结构信息（CREATE TABLE 语句）
     * @return 优化分析报告和优化后的 SQL
     */
    @Tool(description = "分析 SQL 语句性能问题并提供优化建议，支持 MySQL/PostgreSQL。使用本地 AI 模型分析。")
    public String optimizeSql(String sql, String tableStructure) {
        log.info("[DevTool] optimizeSql: sql截取={}", sql.length() > 60 ? sql.substring(0, 60) + "..." : sql);

        String prompt = String.format("""
                请分析以下 SQL 语句并提供优化建议：
                
                原始 SQL：
                ```sql
                %s
                ```
                
                %s
                
                请从以下角度分析：
                1. SQL 语法是否正确
                2. 是否存在全表扫描（缺少索引）
                3. JOIN 顺序是否最优
                4. 是否有冗余子查询可以重写
                5. 建议添加的索引
                
                最后提供优化后的 SQL（如果需要改写）。
                """,
                sql,
                tableStructure != null && !tableStructure.isBlank()
                        ? "表结构：\n```sql\n" + tableStructure + "\n```"
                        : ""
        );

        return llamaService.chat(prompt);
    }

    // ============================================================
    //  工具 7：项目编译
    // ============================================================

    /**
     * 执行项目编译命令（Maven 或 npm）。
     *
     * @param projectPath 项目根目录（包含 pom.xml 或 package.json）
     * @param command     编译命令（maven: "package" / "install"；npm: "build" / "install"）
     * @return 编译输出日志
     */
    @Tool(description = "编译项目。Maven 项目执行 mvn 命令，Vue/Node 项目执行 npm 命令。projectPath 为项目根目录。")
    public String compileProject(String projectPath, String command) {
        log.info("[DevTool] compileProject: path={}, command={}", projectPath, command);
        try {
            Path path = resolvePath(projectPath);
            if (!Files.exists(path)) {
                return "❌ 项目目录不存在：" + path.toAbsolutePath();
            }

            ProcessBuilder pb;
            boolean isMaven = Files.exists(path.resolve("pom.xml"));
            boolean isNode = Files.exists(path.resolve("package.json"));

            if (isMaven) {
                // Maven 项目
                pb = new ProcessBuilder(mavenExecutable, command, "-f",
                        path.resolve("pom.xml").toString());
            } else if (isNode) {
                // Node.js / Vue 项目
                pb = new ProcessBuilder(npmExecutable, "run", command);
            } else {
                return "❌ 未识别的项目类型（需要 pom.xml 或 package.json）";
            }

            pb.directory(path.toFile());
            pb.redirectErrorStream(true);

            return executeProcess(pb, 300); // 5 分钟超时

        } catch (Exception e) {
            log.error("[DevTool] compileProject 失败: {}", e.getMessage(), e);
            return "❌ 编译失败：" + e.getMessage();
        }
    }

    // ============================================================
    //  私有工具方法
    // ============================================================

    /**
     * 解析文件路径：绝对路径直接使用，相对路径相对于工作区根目录。
     */
    private Path resolvePath(String filePath) {
        Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        Path p = Paths.get(filePath);
        Path resolved = (p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("路径超出工作区范围：" + resolved);
        }
        return resolved;
    }

    /**
     * 执行外部进程并捕获输出。
     *
     * @param pb           ProcessBuilder 实例
     * @param timeoutSecs  超时秒数
     * @return 输出文本
     */
    private String executeProcess(ProcessBuilder pb, int timeoutSecs) throws Exception {
        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // 输出限制（防止超长）
                if (output.length() > 50000) {
                    output.append("\n...[输出已截断]...\n");
                    break;
                }
            }
        }

        boolean finished = process.waitFor(timeoutSecs, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "⚠️ 命令超时（" + timeoutSecs + "s），已强制终止\n" + output;
        }

        int exitCode = process.exitValue();
        String status = exitCode == 0 ? "✅ 成功（exit 0）" : "❌ 失败（exit " + exitCode + "）";
        return status + "\n\n" + output;
    }
}
