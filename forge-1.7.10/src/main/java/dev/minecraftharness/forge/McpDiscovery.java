package dev.minecraftharness.forge;

import com.google.gson.*;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.event.FMLInterModComms;
import dev.minecraftharness.agent.McpServer;
import java.io.IOException;
import java.util.*;

/** Data-only Forge IMC contract; endpoint owners need no Harness classes. */
public final class McpDiscovery {
    public static final String DISCOVER = "agent-mcp:discover/v1";
    public static final String SERVER = "agent-mcp:server/v1";

    private McpDiscovery() {}

    public static void request() {
        for (ModContainer mod : Loader.instance().getActiveModList())
            FMLInterModComms.sendMessage(mod.getModId(), DISCOVER, "");
    }

    public static List<McpServer> collect(Object owner) throws IOException {
        Map<String, McpServer> servers = new TreeMap<String, McpServer>();
        for (FMLInterModComms.IMCMessage message : FMLInterModComms.fetchRuntimeMessages(owner)) {
            if (!SERVER.equals(message.key)) continue;
            if (!message.isStringMessage())
                throw new IOException("Invalid MCP advertisement from " + message.getSender());
            McpServer server = decode(message.getSender(), message.getStringValue());
            if (servers.put(server.id, server) != null)
                throw new IOException("Duplicate MCP advertisement from " + message.getSender());
        }
        return Collections.unmodifiableList(new ArrayList<McpServer>(servers.values()));
    }

    static McpServer decode(String sender, String json) throws IOException {
        try {
            JsonObject value = new JsonParser().parse(json).getAsJsonObject();
            if (!"streamable-http".equals(string(value.get("transport"))))
                throw new IllegalArgumentException();
            String id = string(value.get("id"));
            if (!sender.matches("[a-zA-Z0-9_-]+") || !id.matches("[a-zA-Z0-9-]+"))
                throw new IllegalArgumentException();
            Map<String, String> headers = new TreeMap<String, String>();
            if (value.has("http_headers"))
                for (Map.Entry<String, JsonElement> entry :
                        value.getAsJsonObject("http_headers").entrySet())
                    headers.put(entry.getKey(), string(entry.getValue()));
            return new McpServer(
                    "harness_" + sender + "__" + id,
                    string(value.get("url")),
                    headers,
                    seconds(value, "startup_timeout_sec", 20),
                    seconds(value, "tool_timeout_sec", 30));
        } catch (RuntimeException invalid) {
            // Do not include provider payloads or credentials in diagnostics.
            throw new IOException("Invalid MCP advertisement from " + sender);
        }
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException();
        return value.getAsString();
    }

    private static int seconds(JsonObject value, String key, int fallback) {
        if (!value.has(key)) return fallback;
        JsonElement seconds = value.get(key);
        if (!seconds.isJsonPrimitive() || !seconds.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException();
        return seconds.getAsBigDecimal().intValueExact();
    }
}
