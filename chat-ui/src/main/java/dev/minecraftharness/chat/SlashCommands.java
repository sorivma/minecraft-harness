package dev.minecraftharness.chat;

import java.util.*;

/** Presentation aliases only; execution remains in the portable action contract. */
final class SlashCommands {
    static final Map<String, String> ROUTES = new LinkedHashMap<String, String>();

    static {
        add("new clear", "new");
        add("model", "models");
        add("resume agents", "conversations");
        add("mcp", "action:mcp");
        add("skills", "action:skills");
        add("apps", "action:apps");
        add("plugins", "action:plugins");
        add("help keymap", "help");
        add("stop", "stop");
        add("connect", "connect");
        add("disconnect", "disconnect");
        add("queue", "queue");
        add("steer", "steer");
        add("fast daybreak", "parameters");
        add("permissions", "filter:profile:");
        add("plan", "filter:mode:");
        add("goal", "filter:goal.");
        add("mention attach", "attachments");
        add("subagents", "children");
        add("pwd cwd", "workspace");
        add("theme title statusline", "presentation");
        add("experimental", "action:features");
        add("memories", "action:memory");
        add("import", "filter:import.");
        add("ps", "action:terminals");
        add("debug-config", "action:config");
        add("setup-default-sandbox", "action:sandbox.setup");
        add("usage", "action:usage");
        add("voice", "action:external.voice");
        add("approve", "action:external.approve");
        add("app", "action:external.app");
        add("ide", "action:external.ide");
        add("daemon", "action:external.daemon");
        add("tui pets pet", "action:external.tui");
        add("cd worktree", "action:external.cd");
        add("exit quit chat", "chat");
        add("expand", "expand");
        add("backend", "backends");
        add("commands", "actions");
        for (String name :
                Arrays.asList(
                        "compact",
                        "fork",
                        "side",
                        "review",
                        "delete",
                        "config",
                        "hooks",
                        "logout",
                        "login",
                        "feedback")) add(name, "action:" + name);
        add("btw", "action:side");
        add("diff", "action:diff");
        for (String name :
                Arrays.asList(
                        "rename",
                        "archive",
                        "copy",
                        "export",
                        "raw",
                        "init",
                        "recap",
                        "status",
                        "warnings",
                        "vim")) add(name, name);
    }

    private static void add(String names, String route) {
        for (String name : names.split(" ")) ROUTES.put(name, route);
    }
}
