package dev.minecraftharness.codex;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.util.*;

/** Routes requiring a different host, with explicit reasons; never synthetic RPCs. */
final class ExternalActions {
    static final Map<String, String[]> ROUTES = new LinkedHashMap<String, String[]>();

    static {
        add(
                "app",
                "Desktop app",
                "The Desktop app owns its own window and session host.",
                "codex app");
        add(
                "ide",
                "Editor integration",
                "Minecraft does not provide an IDE editor bridge. Open the workspace in your editor and use its Codex integration.",
                "codex --cd <workspace>");
        add(
                "tui",
                "Terminal UI / terminal pets",
                "Terminal rendering, terminal pets and shell integration require a terminal host.",
                "codex --cd <workspace>");
        add(
                "daemon",
                "Shared daemon / remote control",
                "This client owns a world-scoped stdio process. Shared daemon maintenance affects other clients and uses a separate lifecycle.",
                "codex remote-control --help");
        add(
                "completion",
                "Shell completion",
                "Shell completion must be installed in the user's shell, outside game chat.",
                "codex completion --help");
        add(
                "update",
                "Update Codex",
                "Updating the executable requires stopping its running sessions. This release expects 0.163.0; newer versions require a compatibility check.",
                "codex update --help");
        add(
                "doctor",
                "Installation diagnostics",
                "CLI doctor inspects the installation outside the app-server protocol.",
                "codex doctor");
        add(
                "debug",
                "Developer tools",
                "Schema generators, transport servers and low-level debug tools are CLI host operations.",
                "codex debug --help");
        add(
                "sandbox",
                "Standalone sandbox / uninstall",
                "Running a standalone shell or uninstalling machine-wide sandbox accounts is a CLI host operation. In-game permissions and setup have separate menu entries.",
                "codex sandbox --help");
        add(
                "cloud",
                "Cloud tasks / apply patch",
                "The pinned app-server has no cloud task listing or cloud patch application operation. Use the CLI to select a cloud task and review its patch.",
                "codex cloud --help\ncodex apply --help");
        add(
                "rollouts",
                "History maintenance",
                "Offline rollout migration must not race an active app-server reading the same history.",
                "codex migrate-rollouts --help");
        add(
                "exec-server",
                "Execution service",
                "A separate execution server is not a conversation operation on this stdio app-server.",
                "codex exec-server --help");
        add(
                "approve",
                "Retry guardian-denied action",
                "The retry RPC requires the original serialized GuardianAssessmentEvent. The pinned public notifications expose guardianWarning text, not that assessment payload. Inline approvals remain available in the conversation.",
                "codex --cd <workspace>\nUse /approve in the original CLI session.");
        add(
                "voice",
                "Voice / microphone",
                "Audio files can be attached in Context. Live microphone capture belongs to the CLI voice host; this game adapter exposes text and resource input, not an audio-device stream.",
                "codex --cd <workspace>\nUse /voice in the terminal client.");
        add(
                "mcp-config",
                "MCP configuration / sign out",
                "The pinned protocol exposes MCP status, OAuth sign-in and reload, but no named server deletion or OAuth sign-out operation. CLI configuration management owns those operations; review the named server before changing it.",
                "codex mcp add --help\ncodex mcp remove --help\ncodex mcp logout --help");
        add(
                "marketplaces",
                "Plugin marketplace sources",
                "The pinned protocol lists and installs plugins but has no marketplace add, upgrade or remove operation. Git marketplace snapshots and source registration are maintained by the CLI.",
                "codex plugin marketplace --help");
        add(
                "cd",
                "Change directory / worktree",
                "Approved product exception: workspaces stay bound to the world save. Directory changes and worktrees are available in the external CLI.",
                "codex --cd <directory>");
    }

    private static void add(String id, String title, String reason, String command) {
        ROUTES.put("external." + id, new String[] {title, reason, command});
    }

    static List<Action> catalog() {
        List<Action> out = new ArrayList<Action>();
        for (Map.Entry<String, String[]> e : ROUTES.entrySet())
            out.add(
                    new Action(
                            e.getKey(),
                            "External",
                            e.getValue()[0],
                            e.getValue()[1],
                            Availability.yes(),
                            Form.empty(),
                            false));
        return out;
    }

    static ActionResult result(String id, String workspace) {
        String[] route = ROUTES.get(id);
        if (route == null) return null;
        return new ActionResult(
                route[0],
                route[1]
                        + "\n\nRun externally:\n"
                        + route[2]
                        + "\n\nWorld workspace:\n"
                        + workspace,
                Collections.<String, Value>emptyMap(),
                Collections.<Resource>emptyList());
    }
}
