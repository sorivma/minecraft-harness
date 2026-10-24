package dev.minecraftharness.codex;

import com.google.gson.JsonPrimitive;
import dev.minecraftharness.agent.McpServer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Owns only the delimited MCP block; other project settings remain user-owned. */
public final class CodexWorkspaceConfig {
    private static final String BEGIN = "# BEGIN HARNESS MCP";
    private static final String END = "# END HARNESS MCP";

    private CodexWorkspaceConfig() {}

    public static void write(File workspace, Collection<McpServer> servers) throws IOException {
        Path root = workspace.toPath().toRealPath();
        Path directory = root.resolve(".codex");
        Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(root))
            throw new IOException("Codex configuration directory escapes workspace");
        Path config = directory.resolve("config.toml");
        if (Files.isSymbolicLink(config))
            throw new IOException("Codex configuration must not be a symbolic link");
        String before =
                Files.exists(config)
                        ? new String(Files.readAllBytes(config), StandardCharsets.UTF_8)
                        : "";
        String after = update(before, servers);
        if (before.equals(after)) return;
        Path temporary = Files.createTempFile(directory, "harness-mcp-", ".tmp");
        try {
            Files.write(temporary, after.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(
                        temporary,
                        config,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String update(String existing, Collection<McpServer> servers) throws IOException {
        // The reserved markers must occupy their own comment lines.
        java.util.regex.Pattern markers =
                java.util.regex.Pattern.compile("(?m)^" + BEGIN + "\\r?$|^" + END + "\\r?$");
        java.util.regex.Matcher match = markers.matcher(existing);
        int start = -1, end = -1;
        if (match.find()) {
            if (!match.group().equals(BEGIN) && !match.group().equals(BEGIN + "\r"))
                throw new IOException("Malformed Harness MCP config block");
            start = match.start();
            if (!match.find() || !match.group().startsWith(END))
                throw new IOException("Malformed Harness MCP config block");
            end = match.end();
            if (end < existing.length() && existing.charAt(end) == '\n') end++;
            if (match.find()) throw new IOException("Multiple Harness MCP config blocks");
        }
        TreeMap<String, McpServer> sorted = new TreeMap<String, McpServer>();
        for (McpServer server : servers)
            if (sorted.put(server.id, server) != null)
                throw new IOException("Duplicate MCP server id: " + server.id);
        StringBuilder block = new StringBuilder(BEGIN).append('\n');
        block.append("# Generated from installed mods; edit settings outside this block.\n");
        for (McpServer server : sorted.values()) {
            String table = "mcp_servers." + quote(server.id);
            block.append('\n').append('[').append(table).append("]\n");
            block.append("url = ").append(quote(server.url)).append('\n');
            block.append("startup_timeout_sec = ")
                    .append(server.startupTimeoutSeconds)
                    .append('\n');
            block.append("tool_timeout_sec = ").append(server.toolTimeoutSeconds).append('\n');
            if (!server.headers.isEmpty()) {
                block.append('[').append(table).append(".http_headers]\n");
                for (Map.Entry<String, String> header : server.headers.entrySet())
                    block.append(quote(header.getKey()))
                            .append(" = ")
                            .append(quote(header.getValue()))
                            .append('\n');
            }
        }
        block.append(END).append('\n');
        if (start >= 0) return existing.substring(0, start) + block + existing.substring(end);
        return existing + (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n") + block;
    }

    /** Trust this world workspace for this process only, so its generated project config loads. */
    public static List<String> command(String executable, File workspace) throws IOException {
        return Arrays.asList(
                executable,
                "app-server",
                "--config",
                "projects." + quote(workspace.getCanonicalPath()) + ".trust_level=\"trusted\"",
                "--config",
                "project_root_markers=[]");
    }

    private static String quote(String value) {
        return new JsonPrimitive(value).toString();
    }
}
