package dev.minecraftharness.agent.testing;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/**
 * Deterministic backend: a turn emits a tool, asks a question, then completes. Work runs only on
 * pump. No process, network, Minecraft or Codex dependency.
 */
public final class FakeBackend implements AgentBackend {
    private final String backendId;
    private final int concurrency;
    private Connection connection;
    private Consumer<Event> sink;
    private long generation;
    private int sequence;
    private final Deque<Runnable> queue = new ArrayDeque<Runnable>();
    private final Set<CompletableFuture<?>> pending = new HashSet<CompletableFuture<?>>();
    private final Map<String, Conversation> threads = new LinkedHashMap<String, Conversation>();
    private final Map<String, List<Item>> histories = new HashMap<String, List<Item>>();
    private final Map<String, String> turns = new HashMap<String, String>();
    private final Map<RequestKey, Request> requests = new HashMap<RequestKey, Request>();
    public int answers;
    public int sends;

    public FakeBackend() {
        this("fake", 4);
    }

    public FakeBackend(String id, int concurrency) {
        this.backendId = id;
        this.concurrency = concurrency;
    }

    public String id() {
        return backendId;
    }

    public Connection connection() {
        return connection;
    }

    public CompletableFuture<Connection> connect(File workspace, Consumer<Event> sink) {
        close();
        try {
            connection = new Connection(id(), workspace.getCanonicalPath(), ++generation);
        } catch (Exception e) {
            return failed(Failure.Code.INVALID_INPUT, e.getMessage());
        }
        this.sink = sink;
        return defer(() -> connection);
    }

    private <T> CompletableFuture<T> failed(Failure.Code code, String text) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(new Failure(code, text));
        return f;
    }

    private <T> CompletableFuture<T> defer(Supplier<T> work) {
        if (connection == null) return failed(Failure.Code.DISCONNECTED, "Disconnected");
        CompletableFuture<T> f = new CompletableFuture<T>();
        pending.add(f);
        queue.add(
                () -> {
                    pending.remove(f);
                    try {
                        f.complete(work.get());
                    } catch (Exception e) {
                        f.completeExceptionally(e);
                    }
                });
        return f;
    }

    private void valid(ThreadKey key) {
        if (connection == null || !connection.equals(key.connection))
            throw new Failure(Failure.Code.STALE_HANDLE, "Stale conversation");
        if (!threads.containsKey(key.id))
            throw new Failure(Failure.Code.INVALID_INPUT, "Unknown conversation");
        if (!threads.get(key.id).key.connection.workspace.equals(connection.workspace))
            throw new Failure(
                    Failure.Code.INVALID_INPUT, "Conversation belongs to another workspace");
    }

    public void emit(Event e) {
        queue.add(
                () -> {
                    if (sink != null) sink.accept(e);
                });
    }

    public int pump(int budget) {
        int count = 0;
        while (count < Math.max(0, budget) && !queue.isEmpty()) {
            queue.remove().run();
            count++;
        }
        return count;
    }

    public CompletableFuture<Capabilities> capabilities() {
        return defer(
                () -> {
                    Map<Feature, Availability> features =
                            new EnumMap<Feature, Availability>(Feature.class);
                    for (Feature f : Feature.values()) features.put(f, Availability.yes());
                    features.put(
                            Feature.PARALLEL_TURNS,
                            concurrency > 1
                                    ? Availability.yes()
                                    : Availability.no("One task at a time"));
                    features.put(
                            Feature.ATTACHMENTS,
                            Availability.no("This test backend accepts text only"));
                    return new Capabilities(
                            features,
                            Arrays.asList(
                                    new Action(
                                            "echo",
                                            "Test",
                                            "Echo",
                                            "Return a field",
                                            Availability.yes(),
                                            new Form(
                                                    Arrays.asList(
                                                            Form.Field.text("text", "Text", true))),
                                            false)),
                            concurrency);
                });
    }

    public CompletableFuture<Page<Model>> models(String cursor) {
        return defer(
                () ->
                        new Page<Model>(
                                Arrays.asList(
                                        new Model(
                                                "test-model",
                                                "Test model",
                                                "Deterministic",
                                                Arrays.asList("low", "high"),
                                                Arrays.asList("standard"),
                                                "low",
                                                "standard",
                                                Availability.yes())),
                                null));
    }

    private <T> Page<T> page(List<T> data, String cursor) {
        int from = cursor == null ? 0 : Integer.parseInt(cursor),
                to = Math.min(data.size(), from + 10);
        if (from < 0 || from > data.size())
            throw new Failure(Failure.Code.INVALID_INPUT, "Invalid cursor");
        return new Page<T>(data.subList(from, to), to < data.size() ? Integer.toString(to) : null);
    }

    public CompletableFuture<Page<Conversation>> conversations(
            String query, boolean archived, String cursor) {
        return defer(
                () -> {
                    List<Conversation> data = new ArrayList<Conversation>();
                    for (Conversation c : threads.values())
                        if (c.key.connection.workspace.equals(connection.workspace)
                                && c.archived == archived
                                && c.title
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query.toLowerCase(Locale.ROOT)))
                            data.add(rebind(c));
                    return page(data, cursor);
                });
    }

    private Conversation rebind(Conversation c) {
        return new Conversation(
                connection.thread(c.key.id), c.title, c.updatedAt, c.archived, c.settings);
    }

    public CompletableFuture<Conversation> create(Settings settings) {
        return defer(
                () -> {
                    String id = "thread-" + (++sequence);
                    Conversation c =
                            new Conversation(
                                    connection.thread(id),
                                    "Conversation " + sequence,
                                    sequence,
                                    false,
                                    settings);
                    threads.put(id, c);
                    histories.put(id, new ArrayList<Item>());
                    return c;
                });
    }

    public CompletableFuture<Conversation> resume(ThreadKey thread) {
        return defer(
                () -> {
                    valid(thread);
                    return rebind(threads.get(thread.id));
                });
    }

    public CompletableFuture<Page<Item>> history(ThreadKey thread, String cursor) {
        return defer(
                () -> {
                    valid(thread);
                    List<Item> newest = new ArrayList<Item>(histories.get(thread.id));
                    Collections.reverse(newest);
                    return page(newest, cursor);
                });
    }

    public CompletableFuture<Void> rename(ThreadKey thread, String title) {
        return defer(
                () -> {
                    valid(thread);
                    Conversation c = threads.get(thread.id);
                    threads.put(
                            thread.id,
                            new Conversation(
                                    thread,
                                    AgentData.required(title, "title"),
                                    c.updatedAt,
                                    c.archived,
                                    c.settings));
                    return null;
                });
    }

    public CompletableFuture<Void> archive(ThreadKey thread, boolean archived) {
        return defer(
                () -> {
                    valid(thread);
                    Conversation c = threads.get(thread.id);
                    if (turns.containsKey(thread.id))
                        throw new Failure(Failure.Code.REJECTED, "Task is running");
                    threads.put(
                            thread.id,
                            new Conversation(thread, c.title, c.updatedAt, archived, c.settings));
                    return null;
                });
    }

    public CompletableFuture<Turn> send(ThreadKey thread, Message message, Settings settings) {
        return defer(
                () -> {
                    valid(thread);
                    if (settings.model != null && !settings.model.equals("test-model"))
                        throw new Failure(Failure.Code.REJECTED, "Unknown model");
                    if (!message.resources.isEmpty())
                        throw new Failure(Failure.Code.UNAVAILABLE, "Text only");
                    if (turns.containsKey(thread.id) || turns.size() >= concurrency)
                        throw new Failure(Failure.Code.REJECTED, "Busy");
                    sends++;
                    String turn = "turn-" + (++sequence);
                    turns.put(thread.id, turn);
                    add(
                            thread,
                            new Item(
                                    turn + "-user",
                                    turn,
                                    Kind.USER,
                                    State.COMPLETED,
                                    "You",
                                    message.text,
                                    Collections.<String, Value>emptyMap(),
                                    Collections.<Resource>emptyList(),
                                    null));
                    emit(Event.turn(thread, new Turn(turn, State.RUNNING, null)));
                    add(
                            thread,
                            new Item(
                                    turn + "-tool",
                                    turn,
                                    Kind.TOOL,
                                    State.COMPLETED,
                                    "Inspect world",
                                    "Ready",
                                    Collections.singletonMap("server", Value.text("test")),
                                    Collections.<Resource>emptyList(),
                                    5L));
                    RequestKey key = new RequestKey(thread, turn + "-question");
                    Request request =
                            new Request(
                                    key,
                                    turn,
                                    turn + "-tool",
                                    "Choose a material",
                                    "Test question",
                                    new Form(
                                            Arrays.asList(
                                                    Form.Field.choice(
                                                            "material",
                                                            "Material",
                                                            "stone",
                                                            "wood"))));
                    requests.put(key, request);
                    emit(Event.request(request));
                    return new Turn(turn, State.RUNNING, null);
                });
    }

    private void add(ThreadKey thread, Item item) {
        histories.get(thread.id).add(item);
        emit(Event.item(thread, item));
    }

    public CompletableFuture<Void> steer(ThreadKey thread, String turnId, Message message) {
        return defer(
                () -> {
                    valid(thread);
                    active(thread, turnId);
                    return null;
                });
    }

    private void active(ThreadKey thread, String turnId) {
        if (!turnId.equals(turns.get(thread.id)))
            throw new Failure(Failure.Code.REJECTED, "Turn is not active");
    }

    public CompletableFuture<Void> interrupt(ThreadKey thread, String turnId) {
        return defer(
                () -> {
                    valid(thread);
                    active(thread, turnId);
                    finish(thread, turnId, State.INTERRUPTED);
                    return null;
                });
    }

    private void finish(ThreadKey thread, String turn, State state) {
        turns.remove(thread.id);
        for (RequestKey key : new ArrayList<RequestKey>(requests.keySet()))
            if (key.thread.equals(thread)) {
                requests.remove(key);
                emit(Event.resolved(key));
            }
        emit(Event.turn(thread, new Turn(turn, state, null)));
    }

    public CompletableFuture<Void> answer(RequestKey key, Map<String, Value> fields) {
        return defer(
                () -> {
                    valid(key.thread);
                    Request r = requests.get(key);
                    if (r == null)
                        throw new Failure(Failure.Code.REJECTED, "Request already resolved");
                    Map<String, String> errors = r.form.validate(fields);
                    if (!errors.isEmpty())
                        throw new Failure(Failure.Code.INVALID_INPUT, errors.toString());
                    answers++;
                    add(
                            key.thread,
                            new Item(
                                    r.turnId + "-answer",
                                    r.turnId,
                                    Kind.ASSISTANT,
                                    State.COMPLETED,
                                    "Agent",
                                    "Selected " + fields.get("material").text(),
                                    Collections.<String, Value>emptyMap(),
                                    Collections.<Resource>emptyList(),
                                    null));
                    finish(key.thread, r.turnId, State.COMPLETED);
                    return null;
                });
    }

    public CompletableFuture<ActionResult> act(
            ThreadKey thread, String actionId, Map<String, Value> fields) {
        return defer(
                () -> {
                    if (thread != null) valid(thread);
                    if (!actionId.equals("echo"))
                        throw new Failure(Failure.Code.UNAVAILABLE, "Unknown action");
                    Form form = new Form(Arrays.asList(Form.Field.text("text", "Text", true)));
                    if (!form.validate(fields).isEmpty())
                        throw new Failure(Failure.Code.INVALID_INPUT, "Text required");
                    return new ActionResult(
                            "Echo",
                            fields.get("text").text(),
                            fields,
                            Collections.<Resource>emptyList());
                });
    }

    public void close() {
        Connection old = connection;
        connection = null;
        queue.clear();
        turns.clear();
        requests.clear();
        List<CompletableFuture<?>> abandoned = new ArrayList<CompletableFuture<?>>(pending);
        pending.clear();
        for (CompletableFuture<?> f : abandoned)
            f.completeExceptionally(new Failure(Failure.Code.DISCONNECTED, "Disconnected"));
        if (old != null && sink != null)
            sink.accept(Event.connection(old, EventType.DISCONNECTED, "Disconnected"));
        sink = null;
    }
}
