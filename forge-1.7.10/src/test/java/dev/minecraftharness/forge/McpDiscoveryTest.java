package dev.minecraftharness.forge;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.McpServer;
import java.io.IOException;
import org.junit.Test;

public class McpDiscoveryTest {
    @Test
    public void acceptsHostAdvertisementAndNamespacesServersByOwningMod() throws Exception {
        String json =
                "{\"id\":\"minecraft\",\"transport\":\"streamable-http\","
                        + "\"url\":\"http://127.0.0.1:32123/mcp\","
                        + "\"startup_timeout_sec\":20,\"tool_timeout_sec\":30,"
                        + "\"http_headers\":{\"Authorization\":\"Bearer private-token\"}}";
        McpServer server = McpDiscovery.decode("minecraftagenttoolsmcp", json);
        assertEquals("harness_minecraftagenttoolsmcp__minecraft", server.id);
        assertEquals("http://127.0.0.1:32123/mcp", server.url);
        assertEquals("Bearer private-token", server.headers.get("Authorization"));
        assertEquals(20, server.startupTimeoutSeconds);
        assertEquals(30, server.toolTimeoutSeconds);
        assertNotEquals(server.id, McpDiscovery.decode("anothermod", json).id);
    }

    @Test
    public void malformedAdvertisementsNeverExposeCredentials() throws Exception {
        for (String json :
                new String[] {
                    "not-json-private-token",
                    "{\"id\":\"bad.id\",\"transport\":\"streamable-http\"}",
                    "{\"id\":\"x\",\"transport\":\"stdio\",\"command\":\"private-token\"}",
                    "{\"id\":\"x\",\"transport\":\"streamable-http\",\"url\":\"file:///private-token\"}",
                    "{\"id\":\"x\",\"transport\":\"streamable-http\",\"url\":\"http://localhost/mcp\","
                            + "\"tool_timeout_sec\":0}",
                    "{\"id\":true,\"transport\":\"streamable-http\",\"url\":\"http://localhost/mcp\"}",
                    "{\"id\":\"x\",\"transport\":\"streamable-http\",\"url\":\"http://localhost/mcp\","
                            + "\"tool_timeout_sec\":1.5}",
                    "{\"id\":\"x\",\"transport\":\"streamable-http\",\"url\":\"http://localhost/mcp\","
                            + "\"http_headers\":{\"Authorization\":123}}"
                }) {
            try {
                McpDiscovery.decode("brokenmod", json);
                fail("Accepted invalid advertisement");
            } catch (IOException expected) {
                assertEquals("Invalid MCP advertisement from brokenmod", expected.getMessage());
                assertNull(expected.getCause());
            }
        }
    }
}
