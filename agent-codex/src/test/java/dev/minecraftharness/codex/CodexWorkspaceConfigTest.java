package dev.minecraftharness.codex;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;
import dev.minecraftharness.agent.McpServer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class CodexWorkspaceConfigTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private McpServer server(String id, int port, String token) {
        return new McpServer(
                id,
                "http://127.0.0.1:" + port + "/mcp",
                Collections.singletonMap("Authorization", "Bearer " + token),
                20,
                30);
    }

    @Test
    public void createsConfigForEachNewWorldBeforeCodexStarts() throws Exception {
        for (String name :
                Arrays.asList("first world", "second world", "forge-1.7.10 Debug WORLD")) {
            File workspace = temporary.newFolder(name);
            CodexWorkspaceConfig.write(
                    workspace,
                    Arrays.asList(server("harness_host__minecraft", 25581, "private-token")));
            String config = read(workspace);
            assertTrue(config.contains("[mcp_servers.\"harness_host__minecraft\"]"));
            assertTrue(config.contains("url = \"http://127.0.0.1:25581/mcp\""));
            assertTrue(config.contains("\"Authorization\" = \"Bearer private-token\""));
            List<String> command = CodexWorkspaceConfig.command("codex native.exe", workspace);
            assertEquals("codex native.exe", command.get(0));
            assertEquals("app-server", command.get(1));
            assertEquals("--config", command.get(2));
            String trust = command.get(3);
            assertEquals("projects", trust.substring(0, trust.indexOf('=')));
            assertTrue(trust.startsWith("projects={"));
            String trusted = "={trust_level=\"trusted\"}}";
            assertTrue(trust.endsWith(trusted));
            String path = trust.substring("projects={".length(), trust.length() - trusted.length());
            assertEquals(workspace.getCanonicalPath(), new JsonParser().parse(path).getAsString());
            assertEquals("project_root_markers=[]", command.get(5));
        }
    }

    @Test
    public void refreshesChangedCredentialsAndRemovesUninstalledModsWithoutChangingUserSettings()
            throws Exception {
        File workspace = temporary.newFolder();
        Path config =
                Files.createDirectories(workspace.toPath().resolve(".codex"))
                        .resolve("config.toml");
        String user =
                "# My settings\r\nmodel = \"my-model\"\r\n"
                        + "[mcp_servers.personal]\r\nurl = \"https://example.com/mcp\"\r\n";
        Files.write(config, user.getBytes(StandardCharsets.UTF_8));
        CodexWorkspaceConfig.write(
                workspace,
                Arrays.asList(
                        server("harness_first__a", 25581, "old-token"),
                        server("harness_second__b", 25582, "removed-token")));
        String suffix = "\n[features]\nexample = true\n";
        Files.write(config, suffix.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        List<McpServer> current = Arrays.asList(server("harness_first__a", 32123, "new-token"));
        CodexWorkspaceConfig.write(workspace, current);
        String updated = read(workspace);
        assertTrue(updated.startsWith(user));
        assertTrue(updated.endsWith(suffix));
        assertFalse(updated.contains("old-token"));
        assertFalse(updated.contains("removed-token"));
        assertFalse(updated.contains("harness_second__b"));
        assertTrue(updated.contains("32123/mcp"));
        assertTrue(updated.contains("new-token"));
        CodexWorkspaceConfig.write(workspace, current);
        assertEquals(updated, read(workspace));
        CodexWorkspaceConfig.write(workspace, Collections.<McpServer>emptyList());
        assertFalse(read(workspace).contains("harness_first__a"));
        assertTrue(read(workspace).contains("mcp_servers.personal"));
    }

    @Test
    public void escapesTomlValuesAndKeepsDiscoveryOrderIrrelevant() throws Exception {
        McpServer a = server("harness_a__a", 1234, "quote\"slash\\tab\t");
        McpServer b = server("harness_b__b", 4321, "another");
        String first = CodexWorkspaceConfig.update("", Arrays.asList(b, a));
        assertEquals(first, CodexWorkspaceConfig.update("", Arrays.asList(a, b)));
        assertTrue(first.contains("Bearer quote\\\"slash\\\\tab\\t"));
    }

    @Test
    public void leavesFileIntactOnMalformedManagedBlockOrDuplicateServer() throws Exception {
        File workspace = temporary.newFolder();
        Path config =
                Files.createDirectories(workspace.toPath().resolve(".codex"))
                        .resolve("config.toml");
        String broken = "model = \"keep-me\"\n# BEGIN HARNESS MCP\n";
        Files.write(config, broken.getBytes(StandardCharsets.UTF_8));
        try {
            CodexWorkspaceConfig.write(workspace, Collections.<McpServer>emptyList());
            fail("Overwrote a malformed block");
        } catch (IOException expected) {
            assertEquals(broken, read(workspace));
        }
        McpServer a = server("same", 1234, "private-token");
        try {
            CodexWorkspaceConfig.update("", Arrays.asList(a, a));
            fail("Accepted duplicate endpoint id");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("private-token"));
        }
    }

    private String read(File workspace) throws IOException {
        return new String(
                Files.readAllBytes(workspace.toPath().resolve(".codex/config.toml")),
                StandardCharsets.UTF_8);
    }
}
