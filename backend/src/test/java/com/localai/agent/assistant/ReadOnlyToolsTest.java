package com.localai.agent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReadOnlyToolsTest {
    @TempDir Path temporary;
    Path workspace;
    ReadOnlyTools tools;
    ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() throws Exception {
        workspace = Files.createDirectory(temporary.resolve("workspace"));
        tools = new ReadOnlyTools(workspace.toString(), mapper);
    }
    ObjectNode args(String path) { return mapper.createObjectNode().put("path", path); }
    @Test void readsActualFileAndReportsTruncation() throws Exception {
        String content = "真实内容 ".repeat(700);
        Files.writeString(workspace.resolve("hello.txt"), content);
        var result = tools.execute("readFile", args("hello.txt"));
        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("data").path("content").asText()).isEqualTo(content.substring(0, 3000));
        assertThat(result.path("data").path("truncated").asBoolean()).isTrue();
    }
    @Test void defaultReadIncludesFieldsBeyondTheFirstHundredCharacters() throws Exception {
        String content = "{\"name\":\"demo\",\"description\":\"" + "x".repeat(150)
                + "\",\"scripts\":{\"dev\":\"vite --host 127.0.0.1 --port 3000\"}}";
        Files.writeString(workspace.resolve("package.json"), content);
        var result = tools.execute("readFile", args("package.json"));
        assertThat(result.path("data").path("content").asText()).contains("vite --host 127.0.0.1 --port 3000");
        assertThat(result.path("data").path("truncated").asBoolean()).isFalse();
    }
    @Test void listsActualDirectoryAndTailsLines() throws Exception {
        Files.writeString(workspace.resolve("a.log"), "one\ntwo\nthree\n");
        var listing = tools.execute("listFiles", args("."));
        assertThat(listing.path("data").path("entries").get(0).path("name").asText()).isEqualTo("a.log");
        var tail = tools.execute("readLastLines", args("a.log").put("lineCount", 2));
        assertThat(tail.path("data").path("content").asText()).isEqualTo("two\nthree");
    }
    @Test void rejectsTraversalAbsoluteOutsideAndPrefixSibling() throws Exception {
        Path outside = Files.writeString(temporary.resolve("secret.txt"), "outside");
        Path sibling = Files.createDirectory(temporary.resolve("workspace-other"));
        Files.writeString(sibling.resolve("secret.txt"), "outside");
        for (String path : new String[]{"../secret.txt", outside.toString(), sibling.resolve("secret.txt").toString()})
            assertThat(tools.execute("readFile", args(path)).path("error").path("code").asText()).isEqualTo("PATH_DENIED");
    }
    @Test void rejectsSymbolicLinksEvenWhenTargetIsInsideWorkspace() throws Exception {
        Path target = Files.writeString(workspace.resolve("target.txt"), "secret");
        try { Files.createSymbolicLink(workspace.resolve("link.txt"), target); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException e) { assumeTrue(false, "Symlink creation unavailable: " + e.getMessage()); }
        assertThat(tools.execute("readFile", args("link.txt")).path("error").path("code").asText()).isEqualTo("PATH_DENIED");
    }
    @Test void rejectsNetworkPathsAndAlternateStreams() {
        for (String path : new String[]{"\\\\server\\share\\file", "//server/share", "a.txt:secret", "\\\\?\\C:\\a"})
            assertThat(tools.execute("readFile", args(path)).path("error").path("code").asText()).isEqualTo("PATH_DENIED");
    }
    @Test void validatesToolNamesArgumentTypesAndUnknownFields() {
        assertThat(tools.execute("writeFile", args("owned.txt")).path("error").path("code").asText()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(Files.exists(workspace.resolve("owned.txt"))).isFalse();
        assertThat(tools.execute("executeCommand", mapper.createObjectNode().put("command", "whoami")).path("error").path("code").asText()).isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(tools.execute("readFile", args("a").put("maxChars", "10")).path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(tools.execute("readFile", args("a").put("maxChars", 0)).path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(tools.execute("getSystemInfo", args(".")).path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(tools.execute("readFile", mapper.createArrayNode()).path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
    }
    @Test void rejectsLargeAndBinaryFilesAndReportsMissingFile() throws Exception {
        Files.write(workspace.resolve("large"), new byte[262145]);
        Files.write(workspace.resolve("binary"), new byte[]{0, 1, 2});
        assertThat(tools.execute("readFile", args("large")).path("error").path("code").asText()).isEqualTo("FILE_TOO_LARGE");
        assertThat(tools.execute("readFile", args("binary")).path("error").path("code").asText()).isEqualTo("NOT_TEXT_FILE");
        assertThat(tools.execute("readFile", args("missing")).path("error").path("code").asText()).isEqualTo("NOT_FOUND");
    }
    @Test void refusesDirectoryReadAndLimitsListing() throws Exception {
        for (int i = 0; i < 105; i++) Files.createFile(workspace.resolve("file-" + i));
        var result = tools.execute("listFiles", args("."));
        assertThat(result.path("data").path("entries").size()).isEqualTo(100);
        assertThat(result.path("data").path("truncated").asBoolean()).isTrue();
        assertThat(tools.execute("readFile", args(".")).path("error").path("code").asText()).isEqualTo("NOT_FILE");
    }
    @Test void deniesPrivateConfigurationAndHidesSensitiveEntries() throws Exception {
        Files.writeString(workspace.resolve(".env"), "SECRET=private");
        Files.createDirectory(workspace.resolve(".local"));
        Files.writeString(workspace.resolve(".local/machine.json"), "private");
        Files.createDirectory(workspace.resolve(".git"));
        Files.writeString(workspace.resolve("key.pem"), "private");
        Files.writeString(workspace.resolve("README.md"), "public");
        for (String path : new String[]{".env", ".local/machine.json", ".git/config", "key.pem", "logs/agent.log"})
            assertThat(tools.execute("readFile", args(path)).path("error").path("code").asText()).isEqualTo("SENSITIVE_PATH");
        var entries = tools.execute("listFiles", args(".")).path("data").path("entries");
        assertThat(entries.size()).isEqualTo(1);
        assertThat(entries.get(0).path("name").asText()).isEqualTo("README.md");
    }

}
