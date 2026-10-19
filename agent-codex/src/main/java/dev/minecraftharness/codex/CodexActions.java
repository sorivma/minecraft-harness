package dev.minecraftharness.codex;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.util.*;

/** Explicit allowlist of named operations, never a raw RPC entry field. */
final class CodexActions {
    static final class Definition {
        final String id, method, category, title;
        final boolean threadScoped, workspaceScoped, confirm;
        final Form form;

        Definition(
                String id,
                String method,
                String category,
                String title,
                boolean thread,
                boolean workspace,
                boolean confirm,
                Form.Field... fields) {
            this.id = id;
            this.method = method;
            this.category = category;
            this.title = title;
            this.threadScoped = thread;
            this.workspaceScoped = workspace;
            this.confirm = confirm;
            List<Form.Field> parameters = new ArrayList<Form.Field>(Arrays.asList(fields));
            if (Arrays.asList(
                            "thread/backgroundTerminals/list",
                            "app/list",
                            "experimentalFeature/list",
                            "permissionProfile/list",
                            "mcpServerStatus/list")
                    .contains(method))
                parameters.add(
                        Form.Field.text(
                                "cursor", "Next page cursor (leave empty for first page)", false));
            this.form = new Form(parameters);
        }
    }

    static final List<Definition> DEFINITIONS =
            Arrays.asList(
                    new Definition(
                            "compact",
                            "thread/compact/start",
                            "Context",
                            "Compact context",
                            true,
                            false,
                            false),
                    new Definition(
                            "fork",
                            "thread/fork",
                            "Conversations",
                            "Fork conversation",
                            true,
                            true,
                            false),
                    new Definition(
                            "delete",
                            "thread/delete",
                            "Conversations",
                            "Delete conversation and descendants",
                            true,
                            false,
                            true),
                    new Definition(
                            "goal.read",
                            "thread/goal/get",
                            "Goal",
                            "View goal",
                            true,
                            false,
                            false),
                    new Definition(
                            "goal.clear",
                            "thread/goal/clear",
                            "Goal",
                            "Clear goal",
                            true,
                            false,
                            true),
                    new Definition(
                            "account", "account/read", "Account", "Account", false, false, false),
                    new Definition(
                            "limits",
                            "account/rateLimits/read",
                            "Account",
                            "Rate limits",
                            false,
                            false,
                            false),
                    new Definition(
                            "logout", "account/logout", "Account", "Sign out", false, false, true),
                    new Definition(
                            "config",
                            "config/read",
                            "Settings",
                            "Configuration",
                            false,
                            true,
                            false),
                    new Definition(
                            "requirements",
                            "configRequirements/read",
                            "Settings",
                            "Policy requirements",
                            false,
                            false,
                            false),
                    new Definition(
                            "permissions",
                            "permissionProfile/list",
                            "Settings",
                            "Permission profiles",
                            false,
                            true,
                            false),
                    new Definition(
                            "modes",
                            "collaborationMode/list",
                            "Settings",
                            "Work modes",
                            false,
                            false,
                            false),
                    new Definition(
                            "features",
                            "experimentalFeature/list",
                            "Settings",
                            "Experimental features",
                            false,
                            false,
                            false),
                    new Definition(
                            "skills", "skills/list", "Extensions", "Skills", false, false, false),
                    new Definition(
                            "mcp",
                            "mcpServerStatus/list",
                            "Extensions",
                            "MCP servers",
                            false,
                            false,
                            false),
                    new Definition(
                            "mcp.reload",
                            "config/mcpServer/reload",
                            "Extensions",
                            "Reload MCP",
                            false,
                            false,
                            true),
                    new Definition(
                            "mcp.oauth",
                            "mcpServer/oauth/login",
                            "Extensions",
                            "Sign in to MCP server",
                            false,
                            false,
                            true,
                            Form.Field.text("name", "Server", true)),
                    new Definition("apps", "app/list", "Extensions", "Apps", false, false, false),
                    new Definition(
                            "plugins",
                            "plugin/list",
                            "Extensions",
                            "Available plugins",
                            false,
                            false,
                            false),
                    new Definition(
                            "plugins.installed",
                            "plugin/installed",
                            "Extensions",
                            "Installed plugins",
                            false,
                            false,
                            false),
                    new Definition(
                            "hooks", "hooks/list", "Extensions", "Hooks", false, false, false),
                    new Definition(
                            "terminals",
                            "thread/backgroundTerminals/list",
                            "Execution",
                            "Background terminals",
                            true,
                            false,
                            false),
                    new Definition(
                            "terminals.stop",
                            "thread/backgroundTerminals/clean",
                            "Execution",
                            "Stop background terminals",
                            true,
                            false,
                            true),
                    new Definition(
                            "memory",
                            "memory/status",
                            "Settings",
                            "Memory status",
                            false,
                            false,
                            false),
                    new Definition(
                            "sandbox",
                            "windowsSandbox/readiness",
                            "Settings",
                            "Windows sandbox status",
                            false,
                            false,
                            false),
                    new Definition(
                            "diagnostics",
                            "server/diagnostics",
                            "Help",
                            "Server diagnostics",
                            false,
                            false,
                            false),
                    new Definition(
                            "side",
                            "thread/fork",
                            "Conversations",
                            "Side conversation (ephemeral)",
                            true,
                            true,
                            false),
                    new Definition(
                            "review",
                            "review/start",
                            "Review",
                            "Review changes",
                            true,
                            false,
                            false,
                            Form.Field.choice(
                                    "target",
                                    "Review target",
                                    "uncommittedChanges",
                                    "baseBranch",
                                    "commit",
                                    "custom"),
                            text("value", "Branch / commit / instructions", false)),
                    new Definition(
                            "goal.set",
                            "thread/goal/set",
                            "Goal",
                            "Set or update goal",
                            true,
                            false,
                            true,
                            text("objective", "Objective", true),
                            number("tokenBudget", "Token budget (optional)", false)),
                    new Definition(
                            "goal.status",
                            "thread/goal/set",
                            "Goal",
                            "Change goal status",
                            true,
                            false,
                            true,
                            Form.Field.choice("status", "Status", "active", "paused", "complete")),
                    new Definition(
                            "usage",
                            "account/usage/read",
                            "Account",
                            "Usage and credits",
                            false,
                            false,
                            false),
                    new Definition(
                            "login",
                            "account/login/start",
                            "Account",
                            "Sign in using browser",
                            false,
                            false,
                            true),
                    new Definition(
                            "login.cancel",
                            "account/login/cancel",
                            "Account",
                            "Cancel pending sign-in",
                            false,
                            false,
                            false,
                            text("loginId", "Login id", true)),
                    new Definition(
                            "apps.read",
                            "app/read",
                            "Extensions",
                            "Read app and tools",
                            false,
                            false,
                            false,
                            text("appId", "App id", true)),
                    new Definition(
                            "apps.installed",
                            "app/installed",
                            "Extensions",
                            "Installed apps",
                            false,
                            false,
                            false),
                    new Definition(
                            "skills.configure",
                            "skills/config/write",
                            "Extensions",
                            "Enable or disable skill",
                            false,
                            false,
                            true,
                            text("name", "Skill name", true),
                            bool("enabled", "Enabled")),
                    new Definition(
                            "plugins.search",
                            "plugin/search",
                            "Extensions",
                            "Search plugins",
                            false,
                            false,
                            false,
                            text("searchTerm", "Search", true),
                            text("cursor", "Next page cursor", false)),
                    new Definition(
                            "plugins.read",
                            "plugin/read",
                            "Extensions",
                            "Read plugin",
                            false,
                            false,
                            false,
                            text("pluginName", "Plugin name", true),
                            text("remoteMarketplaceName", "Marketplace name", false),
                            text("marketplacePath", "Local marketplace path", false)),
                    new Definition(
                            "plugins.install",
                            "plugin/install",
                            "Extensions",
                            "Install plugin",
                            false,
                            false,
                            true,
                            text("pluginName", "Plugin name", true),
                            text("remoteMarketplaceName", "Marketplace name", false),
                            text("marketplacePath", "Local marketplace path", false)),
                    new Definition(
                            "plugins.uninstall",
                            "plugin/uninstall",
                            "Extensions",
                            "Uninstall plugin",
                            false,
                            false,
                            true,
                            text("pluginId", "Installed plugin id", true)),
                    new Definition(
                            "plugins.shares",
                            "plugin/share/list",
                            "Extensions",
                            "Shared plugins",
                            false,
                            false,
                            false),
                    new Definition(
                            "plugins.share",
                            "plugin/share/save",
                            "Extensions",
                            "Share plugin",
                            false,
                            false,
                            true,
                            text("pluginPath", "Plugin path", true)),
                    new Definition(
                            "plugins.checkout",
                            "plugin/share/checkout",
                            "Extensions",
                            "Download shared plugin",
                            false,
                            false,
                            true,
                            text("remotePluginId", "Remote plugin id", true)),
                    new Definition(
                            "plugins.unshare",
                            "plugin/share/delete",
                            "Extensions",
                            "Delete shared plugin",
                            false,
                            false,
                            true,
                            text("remotePluginId", "Remote plugin id", true)),
                    new Definition(
                            "features.set",
                            "experimentalFeature/enablement/set",
                            "Settings",
                            "Enable or disable feature for this process",
                            false,
                            false,
                            true,
                            text("name", "Feature name", true),
                            bool("enabled", "Enabled")),
                    new Definition(
                            "memory.mode",
                            "thread/memoryMode/set",
                            "Settings",
                            "Memory for this conversation",
                            true,
                            false,
                            true,
                            Form.Field.choice("mode", "Memory", "enabled", "disabled")),
                    new Definition(
                            "memory.reset",
                            "memory/reset",
                            "Settings",
                            "Reset stored memory",
                            false,
                            false,
                            true),
                    new Definition(
                            "config.write",
                            "config/value/write",
                            "Settings",
                            "Write reviewed configuration value",
                            false,
                            false,
                            true,
                            text("keyPath", "Configuration key", true),
                            Form.Field.choice(
                                    "valueType", "Value type", "string", "boolean", "number"),
                            text("value", "Value", true),
                            text("expectedVersion", "Version from Configuration", true)),
                    new Definition(
                            "import.detect",
                            "externalAgentConfig/detect",
                            "Settings",
                            "Detect importable configuration",
                            false,
                            false,
                            false,
                            text("migrationSource", "Source", false),
                            bool("includeHome", "Include home configuration")),
                    new Definition(
                            "import.apply",
                            "externalAgentConfig/import",
                            "Settings",
                            "Import a detected configuration item",
                            false,
                            false,
                            true,
                            text("selection", "Item number from detection", true)),
                    new Definition(
                            "sandbox.setup",
                            "windowsSandbox/setupStart",
                            "Settings",
                            "Set up Windows sandbox",
                            false,
                            true,
                            true,
                            Form.Field.choice("mode", "Setup mode", "unelevated", "elevated")),
                    new Definition(
                            "feedback",
                            "feedback/upload",
                            "Help",
                            "Submit feedback",
                            false,
                            false,
                            true,
                            text("classification", "Category", true),
                            text("reason", "Feedback", true),
                            bool("includeLogs", "Include logs in upload")),
                    new Definition(
                            "terminal.stop",
                            "thread/backgroundTerminals/terminate",
                            "Execution",
                            "Stop one background terminal",
                            true,
                            false,
                            true,
                            text("processId", "Process id", true)));

    private static Form.Field text(String id, String label, boolean required) {
        return Form.Field.text(id, label, required);
    }

    private static Form.Field bool(String id, String label) {
        return new Form.Field(
                id,
                label,
                "",
                Form.Type.BOOLEAN,
                true,
                Collections.<String>emptyList(),
                null,
                null,
                null,
                null,
                null);
    }

    private static Form.Field number(String id, String label, boolean required) {
        return new Form.Field(
                id,
                label,
                "",
                Form.Type.NUMBER,
                required,
                Collections.<String>emptyList(),
                1.0,
                null,
                null,
                null,
                null);
    }

    static Definition find(String id) {
        for (Definition d : DEFINITIONS) if (d.id.equals(id)) return d;
        return null;
    }

    static List<Action> catalog(Map<String, Availability> runtime) {
        List<Action> actions = new ArrayList<Action>();
        for (Definition d : DEFINITIONS)
            actions.add(
                    new Action(
                            d.id,
                            d.category,
                            d.title,
                            d.title,
                            runtime.containsKey(d.method)
                                    ? runtime.get(d.method)
                                    : Availability.yes(),
                            d.form,
                            d.confirm));
        return actions;
    }
}
