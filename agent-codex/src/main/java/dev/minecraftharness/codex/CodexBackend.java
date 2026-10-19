package dev.minecraftharness.codex;

import static dev.minecraftharness.codex.Wire.*;

import com.google.gson.*;
import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Local Codex 0.163.0 adapter. Its protocol types never escape agent-codex. */
public final class CodexBackend implements AgentBackend {
    public static final String SUPPORTED_VERSION = "0.163.0";
    private final List<String> command;
    private final String widgetInstructions;
    private RpcConnection rpc;
    private Connection connection;
    private Consumer<Event> sink;
    private long generation;
    private boolean ready;
    private final Set<String> knownThreads = new HashSet<String>();
    private final Map<RequestKey, PendingRequest> requests =
            new LinkedHashMap<RequestKey, PendingRequest>();
    private final Map<String, Availability> actionAvailability =
            new HashMap<String, Availability>();
    private final Map<String, Settings> threadSettings = new HashMap<String, Settings>();
    private final Map<String, JsonObject> profiles = new LinkedHashMap<String, JsonObject>(),
            modes = new LinkedHashMap<String, JsonObject>();
    private JsonArray importItems = new JsonArray();
    private String importSource;
    private boolean catalogsRequested;
    private final Map<String, CompletableFuture<ActionResult>> inspections =
            new HashMap<String, CompletableFuture<ActionResult>>();
    private int inspectionSequence;

    private static final class PendingRequest {
        final JsonElement wireId;
        final String method;
        final Request request;
        boolean sending;

        PendingRequest(JsonElement id, String method, Request request) {
            this.wireId = id;
            this.method = method;
            this.request = request;
        }
    }

    public CodexBackend(String executable) {
        this(Arrays.asList(executable, "app-server"));
    }

    public CodexBackend(String executable, String widgetInstructions) {
        this(Arrays.asList(executable, "app-server"), widgetInstructions);
    }

    /** Injection seam for a deterministic protocol process. */
    public CodexBackend(List<String> command) {
        this(command, CraftingRecipe.INSTRUCTIONS);
    }

    public CodexBackend(List<String> command, String widgetInstructions) {
        this.command = Collections.unmodifiableList(new ArrayList<String>(command));
        this.widgetInstructions = Objects.requireNonNull(widgetInstructions);
    }

    public String id() {
        return "codex";
    }

    public Connection connection() {
        return connection;
    }

    private static <T> CompletableFuture<T> failure(Failure.Code code, String message) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(new Failure(code, message));
        return f;
    }

    public CompletableFuture<Connection> connect(File workspace, Consumer<Event> sink) {
        close();
        this.sink = sink;
        try {
            File cwd = workspace.getCanonicalFile();
            if (!cwd.isDirectory())
                return failure(Failure.Code.INVALID_INPUT, "Workspace does not exist");
            connection = new Connection(id(), cwd.getPath(), ++generation);
            rpc = new RpcConnection(command, cwd);
            JsonObject p = new JsonObject(), info = params("name", "minecraft_harness");
            info.addProperty("title", "Minecraft Harness");
            info.addProperty("version", "0.2.0");
            p.add("clientInfo", info);
            JsonObject capabilities = new JsonObject();
            capabilities.addProperty("experimentalApi", true);
            p.add("capabilities", capabilities);
            final Connection expected = connection;
            return call("initialize", p, true)
                    .thenApply(
                            result -> {
                                String userAgent = text(result, "userAgent");
                                if (!userAgent.matches(
                                        "(?s).*[/ ]"
                                                + SUPPORTED_VERSION.replace(".", "\\.")
                                                + "(?:[ /)].*|$)")) {
                                    close();
                                    throw new Failure(
                                            Failure.Code.INCOMPATIBLE,
                                            "Expected Codex "
                                                    + SUPPORTED_VERSION
                                                    + "; server reports "
                                                    + userAgent);
                                }
                                try {
                                    rpc.notify("initialized", new JsonObject());
                                } catch (IOException e) {
                                    close();
                                    throw new Failure(Failure.Code.DISCONNECTED, e.getMessage());
                                }
                                ready = true;
                                return expected;
                            });
        } catch (IOException e) {
            close();
            return failure(Failure.Code.DISCONNECTED, e.getMessage());
        }
    }

    private CompletableFuture<JsonObject> call(String method, JsonObject params) {
        return call(method, params, false);
    }

    private CompletableFuture<JsonObject> call(
            String method, JsonObject params, boolean initializing) {
        if (rpc == null || (!ready && !initializing))
            return failure(Failure.Code.DISCONNECTED, "Connect first");
        final Connection expected = connection;
        CompletableFuture<JsonObject> f = new CompletableFuture<JsonObject>();
        try {
            rpc.request(
                    method,
                    params,
                    (result, error) -> {
                        if (!expected.equals(connection)) {
                            f.completeExceptionally(
                                    new Failure(Failure.Code.STALE_HANDLE, "Old connection"));
                            return;
                        }
                        if (!error.entrySet().isEmpty()) {
                            String code = text(error, "code");
                            Failure.Code reason =
                                    code.equals("-32000")
                                            ? Failure.Code.DISCONNECTED
                                            : code.equals("-32601")
                                                    ? Failure.Code.UNAVAILABLE
                                                    : Failure.Code.REJECTED;
                            if (reason == Failure.Code.UNAVAILABLE) {
                                actionAvailability.put(
                                        method, Availability.no(text(error, "message")));
                                emit(
                                        Event.connection(
                                                expected,
                                                EventType.CAPABILITIES_CHANGED,
                                                "Runtime operation unavailable"));
                            }
                            f.completeExceptionally(new Failure(reason, text(error, "message")));
                        } else f.complete(result);
                    });
        } catch (IOException e) {
            f.completeExceptionally(new Failure(Failure.Code.DISCONNECTED, e.getMessage()));
            close();
        }
        return f;
    }

    private void valid(ThreadKey key) {
        if (key == null || !key.connection.equals(connection))
            throw new Failure(Failure.Code.STALE_HANDLE, "Conversation is from another connection");
        if (!knownThreads.contains(key.id))
            throw new Failure(
                    Failure.Code.INVALID_INPUT, "Load the conversation from this workspace first");
    }

    private JsonObject scope(ThreadKey key) {
        valid(key);
        return params("threadId", key.id);
    }

    private <T> CompletableFuture<T> guarded(Supplier<CompletableFuture<T>> work) {
        try {
            return work.get();
        } catch (Failure e) {
            CompletableFuture<T> f = new CompletableFuture<T>();
            f.completeExceptionally(e);
            return f;
        }
    }

    public int pump(int budget) {
        RpcConnection current = rpc;
        return current == null ? 0 : current.pump(this::receive, budget);
    }

    private void emit(Event e) {
        if (sink != null) sink.accept(e);
    }

    public CompletableFuture<Capabilities> capabilities() {
        if (!ready) return failure(Failure.Code.DISCONNECTED, "Connect first");
        if (!catalogsRequested) {
            catalogsRequested = true;
            loadProfiles(null);
            call("collaborationMode/list", new JsonObject())
                    .thenAccept(
                            r -> {
                                for (JsonElement e : array(r, "data")) {
                                    JsonObject mode = e.getAsJsonObject();
                                    if (!text(mode, "mode").isEmpty())
                                        modes.put(text(mode, "mode"), mode);
                                }
                                emit(
                                        Event.connection(
                                                connection,
                                                EventType.CAPABILITIES_CHANGED,
                                                "Work modes loaded"));
                            });
        }
        Map<Feature, Availability> f = new EnumMap<Feature, Availability>(Feature.class);
        for (Feature feature : Feature.values()) f.put(feature, Availability.yes());
        return CompletableFuture.completedFuture(new Capabilities(f, actions(), Integer.MAX_VALUE));
    }

    private void loadProfiles(String cursor) {
        JsonObject p = params("cwd", connection.workspace);
        put(p, "cursor", cursor);
        call("permissionProfile/list", p)
                .thenAccept(
                        r -> {
                            for (JsonElement e : array(r, "data"))
                                profiles.put(text(e.getAsJsonObject(), "id"), e.getAsJsonObject());
                            String next = nullable(r, "nextCursor");
                            if (next != null) loadProfiles(next);
                            else
                                emit(
                                        Event.connection(
                                                connection,
                                                EventType.CAPABILITIES_CHANGED,
                                                "Permission profiles loaded"));
                        });
    }

    public CompletableFuture<Page<Model>> models(String cursor) {
        JsonObject p = params("cursor", cursor);
        p.addProperty("limit", 50);
        return call("model/list", p)
                .thenApply(
                        r -> {
                            List<Model> models = new ArrayList<Model>();
                            for (JsonElement e : array(r, "data")) {
                                JsonObject m = e.getAsJsonObject();
                                List<String> efforts = new ArrayList<String>(),
                                        tiers = new ArrayList<String>(),
                                        programs = new ArrayList<String>();
                                for (JsonElement v : array(m, "supportedReasoningEfforts"))
                                    efforts.add(text(v.getAsJsonObject(), "reasoningEffort"));
                                for (JsonElement v : array(m, "serviceTiers"))
                                    tiers.add(
                                            v.isJsonPrimitive()
                                                    ? v.getAsString()
                                                    : text(v.getAsJsonObject(), "id"));
                                for (JsonElement v :
                                        array(object(m, "availableAccessPrograms"), "cyber"))
                                    programs.add(v.getAsString());
                                models.add(
                                        new Model(
                                                text(m, "model"),
                                                text(m, "displayName"),
                                                text(m, "description"),
                                                efforts,
                                                tiers,
                                                nullable(m, "defaultReasoningEffort"),
                                                nullable(m, "defaultServiceTier"),
                                                Availability.yes(),
                                                programs));
                            }
                            return new Page<Model>(models, nullable(r, "nextCursor"));
                        });
    }

    private Conversation conversation(JsonObject t, JsonObject response, boolean archived) {
        String cwd = text(t, "cwd");
        try {
            if (!new File(cwd).getCanonicalPath().equals(connection.workspace))
                throw new Failure(
                        Failure.Code.REJECTED, "Conversation belongs to another workspace");
        } catch (IOException e) {
            throw new Failure(Failure.Code.INVALID_INPUT, "Invalid conversation workspace");
        }
        String id = text(t, "id");
        knownThreads.add(id);
        String title = text(t, "name");
        if (title.isEmpty()) title = text(t, "preview");
        Settings settings =
                new Settings(
                        nullable(response, "model"),
                        nullable(response, "reasoningEffort"),
                        nullable(response, "serviceTier"),
                        null,
                        null);
        if (settings.model != null) threadSettings.put(id, settings);
        return new Conversation(
                connection.thread(id),
                title.isEmpty() ? "New conversation" : title,
                t.has("updatedAt") ? t.get("updatedAt").getAsLong() : 0,
                archived,
                settings);
    }

    public CompletableFuture<Page<Conversation>> conversations(
            String query, boolean archived, String cursor) {
        if (connection == null) return failure(Failure.Code.DISCONNECTED, "Connect first");
        JsonObject p = params("cwd", connection.workspace);
        put(p, "cursor", cursor);
        p.addProperty("searchTerm", query);
        p.addProperty("archived", archived);
        p.addProperty("limit", 30);
        JsonArray sources = new JsonArray();
        for (String source :
                Arrays.asList(
                        "cli",
                        "vscode",
                        "exec",
                        "appServer",
                        "subAgent",
                        "subAgentReview",
                        "subAgentCompact",
                        "subAgentThreadSpawn",
                        "subAgentOther",
                        "unknown")) sources.add(new JsonPrimitive(source));
        p.add("sourceKinds", sources);
        return call("thread/list", p)
                .thenApply(
                        r -> {
                            List<Conversation> data = new ArrayList<Conversation>();
                            for (JsonElement e : array(r, "data"))
                                data.add(
                                        conversation(
                                                e.getAsJsonObject(), new JsonObject(), archived));
                            return new Page<Conversation>(data, nullable(r, "nextCursor"));
                        });
    }

    public CompletableFuture<Conversation> create(Settings settings) {
        if (connection == null) return failure(Failure.Code.DISCONNECTED, "Connect first");
        JsonObject p = params("cwd", connection.workspace);
        p.addProperty("sandbox", "workspace-write");
        p.addProperty("approvalPolicy", "on-request");
        p.addProperty("developerInstructions", widgetInstructions);
        put(p, "model", settings.model);
        put(p, "serviceTier", settings.tier);
        return call("thread/start", p).thenApply(r -> conversation(object(r, "thread"), r, false));
    }

    public CompletableFuture<Conversation> resume(ThreadKey thread) {
        return guarded(
                () -> {
                    JsonObject p = scope(thread);
                    p.addProperty("excludeTurns", true);
                    p.addProperty("developerInstructions", widgetInstructions);
                    return call("thread/resume", p)
                            .thenApply(r -> conversation(object(r, "thread"), r, false));
                });
    }

    public CompletableFuture<Page<Item>> history(ThreadKey thread, String cursor) {
        return guarded(
                () -> {
                    JsonObject p = scope(thread);
                    put(p, "cursor", cursor);
                    p.addProperty("limit", 50);
                    p.addProperty("sortDirection", "desc");
                    return call("thread/items/list", p)
                            .thenApply(
                                    r -> {
                                        List<Item> items = new ArrayList<Item>();
                                        for (JsonElement e : array(r, "data")) {
                                            JsonObject v = e.getAsJsonObject();
                                            items.add(
                                                    item(
                                                            object(v, "item"),
                                                            text(v, "turnId"),
                                                            State.COMPLETED));
                                        }
                                        return new Page<Item>(items, nullable(r, "nextCursor"));
                                    });
                });
    }

    public CompletableFuture<Void> rename(ThreadKey thread, String title) {
        return guarded(
                () -> {
                    JsonObject p = scope(thread);
                    p.addProperty("name", title);
                    return call("thread/name/set", p).thenApply(r -> null);
                });
    }

    public CompletableFuture<Void> archive(ThreadKey thread, boolean archived) {
        return guarded(
                () ->
                        call(archived ? "thread/archive" : "thread/unarchive", scope(thread))
                                .thenApply(r -> null));
    }

    public CompletableFuture<Turn> send(ThreadKey thread, Message message, Settings settings) {
        return guarded(
                () -> {
                    if (settings.permissionProfile != null
                            && profiles.containsKey(settings.permissionProfile)
                            && !profiles.get(settings.permissionProfile)
                                    .get("allowed")
                                    .getAsBoolean())
                        return failure(
                                Failure.Code.REJECTED,
                                "Permission profile is forbidden by server policy");
                    JsonObject p = scope(thread);
                    p.add("input", input(message));
                    put(p, "model", settings.model);
                    put(p, "effort", settings.effort);
                    put(p, "serviceTier", settings.tier);
                    put(p, "permissions", settings.permissionProfile);
                    put(p, "cyberAccessProgram", settings.accessProgram);
                    if (settings.mode != null) {
                        if (settings.model == null)
                            throw new Failure(
                                    Failure.Code.INVALID_INPUT,
                                    "Select a model for collaboration mode");
                        JsonObject mode = params("mode", settings.mode),
                                s = params("model", settings.model);
                        s.add(
                                "reasoning_effort",
                                settings.effort == null
                                        ? JsonNull.INSTANCE
                                        : new JsonPrimitive(settings.effort));
                        s.add("developer_instructions", JsonNull.INSTANCE);
                        mode.add("settings", s);
                        p.add("collaborationMode", mode);
                    }
                    return call("turn/start", p)
                            .thenApply(
                                    r -> {
                                        threadSettings.put(thread.id, settings);
                                        return turn(object(r, "turn"));
                                    });
                });
    }

    private Turn turn(JsonObject t) {
        return new Turn(
                text(t, "id"),
                state(text(t, "status"), State.RUNNING),
                nullable(object(t, "error"), "message"));
    }

    public CompletableFuture<Void> steer(ThreadKey thread, String turnId, Message message) {
        return guarded(
                () -> {
                    JsonObject p = scope(thread);
                    p.addProperty("expectedTurnId", turnId);
                    p.add("input", input(message));
                    return call("turn/steer", p).thenApply(r -> null);
                });
    }

    public CompletableFuture<Void> interrupt(ThreadKey thread, String turnId) {
        return guarded(
                () -> {
                    JsonObject p = scope(thread);
                    p.addProperty("turnId", turnId);
                    return call("turn/interrupt", p).thenApply(r -> null);
                });
    }

    private static String requestToken(JsonElement id) {
        return (id.getAsJsonPrimitive().isNumber() ? "number:" : "string:") + id.toString();
    }

    private void receive(JsonObject message) {
        String method = text(message, "method");
        JsonObject p = object(message, "params");
        if (method.equals("harness/disconnected")) {
            close();
            return;
        }
        if (message.has("id")) {
            receiveRequest(message, method, p);
            return;
        }
        if (method.equals("process/exited")) {
            CompletableFuture<ActionResult> result = inspections.remove(text(p, "processHandle"));
            if (result != null) {
                String output = text(p, "stdout") + text(p, "stderr");
                if (p.has("exitCode") && p.get("exitCode").getAsInt() != 0)
                    result.completeExceptionally(new Failure(Failure.Code.REJECTED, output));
                else
                    result.complete(
                            new ActionResult(
                                    "Workspace changes",
                                    output,
                                    value(p).object(),
                                    Collections.<Resource>emptyList()));
            }
            return;
        }
        if (method.equals("mcpServer/startupStatus/updated")
                && text(p, "status").equals("failed")) {
            emit(
                    Event.connection(
                            connection,
                            EventType.WARNING,
                            "MCP " + text(p, "name") + ": " + text(p, "error")));
            return;
        }
        // Bookkeeping notifications are neither conversation activity nor errors.
        if (method.equals("account/updated")
                || method.equals("account/rateLimits/updated")
                || method.equals("thread/status/changed")
                || method.equals("thread/readState/changed")
                || method.equals("mcpServer/startupStatus/updated")
                || method.equals("remoteControl/status/changed")
                || method.equals("turn/moderationMetadata")) return;
        if (method.equals("thread/started")) {
            try {
                conversation(object(p, "thread"), p, false);
            } catch (Failure ignored) {
            }
            return;
        }
        String threadId = text(p, "threadId");
        if (threadId.isEmpty()) {
            String description = text(p, "message");
            if (description.isEmpty()) description = text(object(p, "error"), "message");
            emit(
                    Event.connection(
                            connection,
                            EventType.WARNING,
                            description.isEmpty()
                                    ? "Backend notification: " + method
                                    : description));
            return;
        }
        if (!knownThreads.contains(threadId)) return;
        ThreadKey thread = connection.thread(threadId);
        String turnId = text(p, "turnId");
        if (method.equals("item/started") || method.equals("item/completed")) {
            emit(
                    Event.item(
                            thread,
                            item(
                                    object(p, "item"),
                                    turnId,
                                    method.endsWith("completed")
                                            ? State.COMPLETED
                                            : State.RUNNING)));
        } else if (method.equals("turn/started") || method.equals("turn/completed"))
            emit(Event.turn(thread, turn(object(p, "turn"))));
        else if (method.equals("serverRequest/resolved")) {
            RequestKey key = new RequestKey(thread, requestToken(p.get("requestId")));
            requests.remove(key);
            emit(Event.resolved(key));
        } else if (method.equals("item/agentMessage/delta")
                || method.equals("item/commandExecution/outputDelta")
                || method.equals("item/plan/delta"))
            emit(Event.delta(thread, text(p, "itemId"), text(p, "delta")));
        else {
            Kind kind =
                    method.contains("plan")
                            ? Kind.PLAN
                            : method.contains("diff")
                                    ? Kind.FILE_CHANGE
                                    : method.contains("tokenUsage")
                                            ? Kind.USAGE
                                            : method.contains("mcpToolCall")
                                                    ? Kind.TOOL
                                                    : method.contains("reasoning/summary")
                                                            ? Kind.SUMMARY
                                                            : method.equals("error")
                                                                    ? Kind.ERROR
                                                                    : Kind.GENERIC;
            if (method.contains("reasoning/text")) return;
            String id = text(p, "itemId");
            if (id.isEmpty()) id = turnId + ":" + method;
            String body = text(p, "delta");
            if (body.isEmpty()) body = text(p, "diff");
            if (body.isEmpty()) body = text(p, "message");
            if (method.equals("turn/plan/updated")) {
                StringBuilder plan = new StringBuilder(text(p, "explanation"));
                for (JsonElement entry : array(p, "plan")) {
                    JsonObject step = entry.getAsJsonObject();
                    plan.append('\n')
                            .append(text(step, "status"))
                            .append(" · ")
                            .append(text(step, "step"));
                }
                body = plan.toString();
            }
            if (kind == Kind.ERROR && body.isEmpty()) body = text(object(p, "error"), "message");
            String title =
                    kind == Kind.PLAN
                            ? "Plan"
                            : kind == Kind.FILE_CHANGE
                                    ? "Changes"
                                    : kind == Kind.USAGE
                                            ? "Token usage"
                                            : kind == Kind.TOOL
                                                    ? "Tool progress"
                                                    : kind == Kind.SUMMARY
                                                            ? "Summary"
                                                            : kind == Kind.ERROR ? "Error" : method;
            Item update =
                    new Item(
                            id,
                            turnId,
                            kind,
                            kind == Kind.ERROR
                                    ? State.FAILED
                                    : kind == Kind.USAGE ? State.COMPLETED : State.RUNNING,
                            title,
                            body,
                            value(p).object(),
                            Collections.<Resource>emptyList(),
                            null);
            if (method.contains("reasoning/summary") && !text(p, "delta").isEmpty())
                emit(Event.delta(thread, id, text(p, "delta")));
            else emit(Event.update(thread, update));
        }
    }

    private void receiveRequest(JsonObject envelope, String method, JsonObject p) {
        JsonElement wireId = envelope.get("id");
        try {
            String threadId = text(p, "threadId");
            if (!knownThreads.contains(threadId))
                throw new Failure(
                        Failure.Code.INVALID_INPUT,
                        "Request belongs to an unknown workspace conversation");
            RequestKey key = new RequestKey(connection.thread(threadId), requestToken(wireId));
            Form form = CodexForms.request(method, p);
            Request request =
                    new Request(
                            key,
                            text(p, "turnId"),
                            text(p, "itemId"),
                            CodexForms.title(method),
                            CodexForms.description(p),
                            form);
            if (requests.containsKey(key)) return;
            requests.put(key, new PendingRequest(wireId, method, request));
            emit(Event.request(request));
        } catch (Failure unsupported) {
            emit(Event.connection(connection, EventType.WARNING, unsupported.getMessage()));
            try {
                rpc.unsupported(wireId);
            } catch (IOException e) {
                close();
            }
        }
    }

    public CompletableFuture<Void> answer(RequestKey key, Map<String, Value> fields) {
        return guarded(
                () -> {
                    valid(key.thread);
                    PendingRequest pending = requests.get(key);
                    if (pending == null || pending.sending)
                        return failure(
                                Failure.Code.REJECTED, "Request already resolved or sending");
                    Map<String, String> errors = pending.request.form.validate(fields);
                    if (!errors.isEmpty())
                        return failure(Failure.Code.INVALID_INPUT, errors.toString());
                    JsonObject response = CodexForms.answer(pending.method, fields);
                    pending.sending = true;
                    try {
                        rpc.respond(pending.wireId, response);
                        requests.remove(key);
                        emit(Event.resolved(key));
                        return CompletableFuture.completedFuture(null);
                    } catch (IOException e) {
                        close();
                        return failure(Failure.Code.DISCONNECTED, e.getMessage());
                    }
                });
    }

    private List<Action> actions() {
        List<Action> actions = CodexActions.catalog(actionAvailability);
        actions.addAll(ExternalActions.catalog());
        for (String id : Arrays.asList("diff", "gitStatus"))
            actions.add(
                    new Action(
                            id,
                            "Changes",
                            id.equals("diff")
                                    ? "Tracked working tree diff"
                                    : "Git status including untracked files",
                            "Read-only Git inspection in this world's workspace",
                            actionAvailability.getOrDefault("process/spawn", Availability.yes()),
                            Form.empty(),
                            false));
        for (Map.Entry<String, JsonObject> e : profiles.entrySet()) {
            boolean allowed =
                    e.getValue().has("allowed") && e.getValue().get("allowed").getAsBoolean();
            actions.add(
                    new Action(
                            "profile:" + e.getKey(),
                            "Permissions",
                            e.getKey(),
                            text(e.getValue(), "description"),
                            allowed
                                    ? Availability.yes()
                                    : Availability.no("Forbidden by server policy"),
                            Form.empty(),
                            true));
        }
        for (Map.Entry<String, JsonObject> e : modes.entrySet())
            actions.add(
                    new Action(
                            "mode:" + e.getKey(),
                            "Work mode",
                            text(e.getValue(), "name"),
                            "Applies to the next message",
                            Availability.yes(),
                            Form.empty(),
                            false));
        return actions;
    }

    public CompletableFuture<ActionResult> act(
            ThreadKey thread, String actionId, Map<String, Value> fields) {
        return guarded(
                () -> {
                    if (connection == null)
                        return failure(Failure.Code.DISCONNECTED, "Connect first");
                    ActionResult external = ExternalActions.result(actionId, connection.workspace);
                    if (external != null) return CompletableFuture.completedFuture(external);
                    if (actionId.equals("diff") || actionId.equals("gitStatus"))
                        return inspectGit(actionId);
                    if (actionId.startsWith("profile:") || actionId.startsWith("mode:")) {
                        valid(thread);
                        Settings s = threadSettings.getOrDefault(thread.id, Settings.defaults());
                        String id = actionId.substring(actionId.indexOf(':') + 1);
                        Settings next;
                        if (actionId.startsWith("profile:")) {
                            JsonObject profile = profiles.get(id);
                            if (profile == null || !profile.get("allowed").getAsBoolean())
                                return failure(
                                        Failure.Code.REJECTED, "Permission profile is not allowed");
                            next = new Settings(null, null, null, null, id);
                        } else {
                            JsonObject mode = modes.get(id);
                            if (mode == null)
                                return failure(Failure.Code.UNAVAILABLE, "Work mode unavailable");
                            next = new Settings(null, null, null, id, null);
                        }
                        threadSettings.put(thread.id, next);
                        return CompletableFuture.completedFuture(
                                new ActionResult(
                                        "Settings",
                                        "Applies to the next message",
                                        Collections.<String, Value>emptyMap(),
                                        Collections.<Resource>emptyList(),
                                        null,
                                        next,
                                        false));
                    }
                    CodexActions.Definition def = CodexActions.find(actionId);
                    if (def == null) return failure(Failure.Code.UNAVAILABLE, "Unknown action");
                    if (!def.form.validate(fields).isEmpty())
                        return failure(
                                Failure.Code.INVALID_INPUT, def.form.validate(fields).toString());
                    JsonObject p = json(Value.object(fields)).getAsJsonObject();
                    if (def.threadScoped) {
                        valid(thread);
                        p.addProperty("threadId", thread.id);
                    }
                    if (def.workspaceScoped) p.addProperty("cwd", connection.workspace);
                    prepareAction(actionId, p);
                    JsonObject requestParams =
                            Arrays.asList(
                                                    "memory/reset",
                                                    "config/mcpServer/reload",
                                                    "windowsSandbox/readiness",
                                                    "account/logout",
                                                    "configRequirements/read")
                                            .contains(def.method)
                                    ? null
                                    : p;
                    return call(def.method, requestParams)
                            .thenApply(
                                    r -> {
                                        if (actionId.equals("import.detect")) {
                                            importItems = array(r, "items");
                                            importSource = nullable(p, "migrationSource");
                                        }
                                        Conversation created =
                                                actionId.equals("fork") || actionId.equals("side")
                                                        ? conversation(
                                                                object(r, "thread"), r, false)
                                                        : null;
                                        List<Resource> links = new ArrayList<Resource>();
                                        Wire.links(r, links);
                                        return new ActionResult(
                                                def.title,
                                                "",
                                                value(redact(r)).object(),
                                                links,
                                                created,
                                                null,
                                                actionId.equals("delete"));
                                    });
                });
    }

    private CompletableFuture<ActionResult> inspectGit(String action) {
        String handle = "harness-git-" + generation + "-" + (++inspectionSequence);
        CompletableFuture<ActionResult> result = new CompletableFuture<ActionResult>();
        inspections.put(handle, result);
        JsonObject p = params("processHandle", handle);
        p.addProperty("cwd", connection.workspace);
        JsonArray command = new JsonArray();
        for (String arg : Arrays.asList("git", "--no-pager", "-c", "core.fsmonitor=false"))
            command.add(new JsonPrimitive(arg));
        for (String arg :
                action.equals("diff")
                        ? Arrays.asList("diff", "--no-ext-diff", "--no-textconv", "HEAD", "--")
                        : Arrays.asList("status", "--short", "--untracked-files=all"))
            command.add(new JsonPrimitive(arg));
        p.add("command", command);
        p.addProperty("timeoutMs", 15000);
        p.addProperty("outputBytesCap", 2_000_000);
        call("process/spawn", p)
                .whenComplete(
                        (r, e) -> {
                            if (e != null) {
                                inspections.remove(handle);
                                result.completeExceptionally(e);
                            }
                        });
        return result;
    }

    private void prepareAction(String id, JsonObject p) {
        if (id.equals("side")) p.addProperty("ephemeral", true);
        if (id.equals("goal.set") || id.equals("goal.status") || id.equals("goal.clear"))
            p.addProperty("origin", "user");
        if (id.equals("login")) p.addProperty("type", "chatgpt");
        if (id.equals("review")) {
            String type = text(p, "target"), value = text(p, "value");
            p.remove("value");
            JsonObject target = params("type", type);
            if (!type.equals("uncommittedChanges")) {
                if (value.trim().isEmpty())
                    throw new Failure(
                            Failure.Code.INVALID_INPUT,
                            "Enter review branch, commit or instructions");
                target.addProperty(
                        type.equals("baseBranch")
                                ? "branch"
                                : type.equals("commit") ? "sha" : "instructions",
                        value);
            }
            p.add("target", target);
        }
        if (id.equals("apps.read")) {
            JsonArray ids = new JsonArray();
            ids.add(p.remove("appId"));
            p.add("appIds", ids);
            p.addProperty("includeTools", true);
        }
        if (id.equals("features.set")) {
            JsonObject enabled = new JsonObject();
            enabled.add(text(p, "name"), p.remove("enabled"));
            p.remove("name");
            p.add("enablement", enabled);
        }
        if (id.equals("config.write")) {
            String type = text(p, "valueType"), v = text(p, "value");
            p.remove("valueType");
            if (type.equals("boolean")) {
                if (!v.equals("true") && !v.equals("false"))
                    throw new Failure(Failure.Code.INVALID_INPUT, "Use true or false");
                p.addProperty("value", Boolean.parseBoolean(v));
            }
            if (type.equals("number"))
                try {
                    p.addProperty("value", Double.parseDouble(v));
                } catch (NumberFormatException e) {
                    throw new Failure(Failure.Code.INVALID_INPUT, "Enter a number");
                }
            p.addProperty("mergeStrategy", "replace");
            p.addProperty(
                    "filePath", new File(connection.workspace, ".codex/config.toml").getPath());
        }
        if (id.equals("import.apply")) {
            int selected;
            try {
                selected = Integer.parseInt(text(p, "selection"));
            } catch (NumberFormatException e) {
                throw new Failure(
                        Failure.Code.INVALID_INPUT,
                        "Choose the zero-based item number from detection");
            }
            if (selected < 0 || selected >= importItems.size())
                throw new Failure(
                        Failure.Code.INVALID_INPUT,
                        "Detect configurations first and choose a listed item");
            p.remove("selection");
            JsonArray items = new JsonArray();
            items.add(importItems.get(selected));
            p.add("migrationItems", items);
            put(p, "migrationSource", importSource);
        }
    }

    private JsonElement redact(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                out.add(
                        e.getKey(),
                        key.contains("secret")
                                        || key.contains("password")
                                        || key.equals("apikey")
                                        || key.equals("access_token")
                                        || key.equals("accesstoken")
                                        || key.contains("refreshtoken")
                                ? new JsonPrimitive("[redacted]")
                                : redact(e.getValue()));
            }
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : value.getAsJsonArray()) out.add(redact(e));
            return out;
        }
        return value;
    }

    public void close() {
        Connection old = connection;
        RpcConnection oldRpc = rpc;
        rpc = null;
        ready = false;
        if (oldRpc != null) oldRpc.close();
        for (CompletableFuture<ActionResult> inspection : inspections.values())
            inspection.completeExceptionally(
                    new Failure(Failure.Code.DISCONNECTED, "Connection closed"));
        inspections.clear();
        connection = null;
        requests.clear();
        knownThreads.clear();
        actionAvailability.clear();
        threadSettings.clear();
        profiles.clear();
        modes.clear();
        catalogsRequested = false;
        importItems = new JsonArray();
        if (old != null)
            emit(
                    Event.connection(
                            old, EventType.DISCONNECTED, "Connection closed; resume explicitly"));
    }
}
