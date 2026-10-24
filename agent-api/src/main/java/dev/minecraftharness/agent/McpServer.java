package dev.minecraftharness.agent;

import java.net.URI;
import java.util.*;

/** A mod-owned Streamable HTTP endpoint, independent of the agent backend. */
public final class McpServer {
    public final String id, url;
    public final Map<String, String> headers;
    public final int startupTimeoutSeconds, toolTimeoutSeconds;

    public McpServer(
            String id,
            String url,
            Map<String, String> headers,
            int startupTimeoutSeconds,
            int toolTimeoutSeconds) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]+"))
            throw new IllegalArgumentException("Invalid MCP server id");
        URI address = URI.create(url);
        if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme()))
                || address.getHost() == null
                || address.getUserInfo() != null
                || address.getFragment() != null)
            throw new IllegalArgumentException("Invalid MCP HTTP URL");
        if (startupTimeoutSeconds <= 0 || toolTimeoutSeconds <= 0)
            throw new IllegalArgumentException("Invalid MCP timeouts");
        TreeMap<String, String> copy = new TreeMap<String, String>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!header.getKey().matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")
                    || header.getValue() == null
                    || header.getValue().contains("\r")
                    || header.getValue().contains("\n"))
                throw new IllegalArgumentException("Invalid MCP HTTP header");
            copy.put(header.getKey(), header.getValue());
        }
        this.id = id;
        this.url = url;
        this.headers = Collections.unmodifiableMap(copy);
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
    }
}
