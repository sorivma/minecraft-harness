package dev.minecraftharness.core;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** World-scoped owner. Game ticks pump this controller even while the panel is closed. */
public final class WorkspaceController implements Closeable {
    public static final class Pending {
        public enum Status {
            OPEN,
            SENDING,
            RESOLVED,
            DISCONNECTED
        }

        public final Request request;
        public Status status = Status.OPEN;
        public String error = "";

        Pending(Request request) {
            this.request = request;
        }
    }

    public static final class Queued {
        public final long id;
        public final Message message;
        public final Settings settings;
        public final boolean steer;

        Queued(long id, Message message, Settings settings, boolean steer) {
            this.id = id;
            this.message = message;
            this.settings = settings;
            this.steer = steer;
        }
    }

    public static final class Chat {
        public Conversation conversation;
        public final Timeline timeline = new Timeline();
        public Item usage;
        public final Map<RequestKey, Pending> requests = new LinkedHashMap<RequestKey, Pending>();
        public final List<Queued> queue = new ArrayList<Queued>();
        public final List<String> inputHistory = new ArrayList<String>();
        public final UiStateStore.State ui;
        public Turn turn;
        public Settings runningSettings;
        public String error = "", historyCursor;
        public boolean historyLoaded, historyLoading, submitting, loaded, queuePaused;
        public int unread;
        public long startedNanos;
        private boolean steering;

        public long elapsedSeconds() {
            return startedNanos == 0
                    ? 0
                    : Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000_000L);
        }

        Chat(Conversation c, UiStateStore.State ui) {
            conversation = c;
            this.ui = ui;
        }

        public boolean busy() {
            return submitting
                    || turn != null
                            && (turn.state == State.RUNNING
                                    || turn.state == State.WAITING_FOR_USER
                                    || turn.state == State.QUEUED);
        }

        public boolean needsAnswer() {
            for (Pending p : requests.values())
                if (p.status == Pending.Status.OPEN || p.status == Pending.Status.SENDING)
                    return true;
            return false;
        }
    }

    public static final class BackendState {
        public final AgentBackend backend;
        public Capabilities capabilities;
        public final List<Model> models = new ArrayList<Model>();
        public String modelCursor, catalogCursor, error = "";
        public boolean connecting, ready, catalogLoading;

        BackendState(AgentBackend backend) {
            this.backend = backend;
        }
    }

    private final File workspace;
    private final UiStateStore store;
    private final LinkedHashMap<String, BackendState> backends =
            new LinkedHashMap<String, BackendState>();
    private final LinkedHashMap<String, Chat> chats = new LinkedHashMap<String, Chat>();
    private final ArrayDeque<Chat> runnable = new ArrayDeque<Chat>();
    private final Set<Chat> scheduled =
            Collections.newSetFromMap(new IdentityHashMap<Chat, Boolean>());
    private Chat selected;
    private boolean closed;
    private long queuedSequence;
    private int pumpIndex;
    public String notice = "";
    public final List<String> warnings = new ArrayList<String>();

    public WorkspaceController(File workspace, Collection<AgentBackend> backends)
            throws IOException {
        this.workspace = workspace.getCanonicalFile();
        this.store = new UiStateStore(this.workspace);
        for (AgentBackend b : backends) {
            if (this.backends.put(b.id(), new BackendState(b)) != null)
                throw new IllegalArgumentException("Duplicate backend id");
        }
    }

    public File workspace() {
        return workspace;
    }

    public Collection<BackendState> backends() {
        return Collections.unmodifiableCollection(backends.values());
    }

    public Collection<Chat> chats() {
        return Collections.unmodifiableCollection(chats.values());
    }

    public Chat selected() {
        return selected;
    }

    public BackendState backend(String id) {
        return backends.get(id);
    }

    private String chatId(String backend, String id) {
        return backend + "\u0000" + id;
    }

    private BackendState owner(Chat chat) {
        return backend(chat.conversation.key.connection.backend);
    }

    private static String error(Throwable e) {
        while (e instanceof CompletionException && e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public CompletableFuture<Connection> connect(String backendId) {
        BackendState state = Objects.requireNonNull(backend(backendId));
        if (closed) return failed("Workspace is closed");
        state.connecting = true;
        state.ready = false;
        state.error = "";
        CompletableFuture<Connection> f = state.backend.connect(workspace, this::receive);
        f.whenComplete(
                (connection, problem) -> {
                    state.connecting = false;
                    if (problem != null) {
                        state.error = error(problem);
                        return;
                    }
                    state.ready = true;
                    state.backend
                            .capabilities()
                            .whenComplete(
                                    (c, e) -> {
                                        if (e == null) state.capabilities = c;
                                        else state.error = error(e);
                                    });
                    state.models.clear();
                    loadModels(backendId, null);
                    loadCatalog(backendId, "", false, null);
                });
        return f;
    }

    private static <T> CompletableFuture<T> failed(String text) {
        CompletableFuture<T> f = new CompletableFuture<T>();
        f.completeExceptionally(new Failure(Failure.Code.REJECTED, text));
        return f;
    }

    public CompletableFuture<Page<Model>> loadModels(String backendId, String cursor) {
        BackendState state = backend(backendId);
        CompletableFuture<Page<Model>> f = state.backend.models(cursor);
        return f.whenComplete(
                (page, e) -> {
                    if (e != null) {
                        state.error = error(e);
                        return;
                    }
                    state.models.addAll(page.entries);
                    state.modelCursor = page.nextCursor;
                });
    }

    public CompletableFuture<Page<Conversation>> loadCatalog(
            String backendId, String query, boolean archived, String cursor) {
        BackendState state = backend(backendId);
        state.catalogLoading = true;
        CompletableFuture<Page<Conversation>> f =
                state.backend.conversations(query, archived, cursor);
        return f.whenComplete(
                (page, e) -> {
                    state.catalogLoading = false;
                    if (e != null) {
                        state.error = error(e);
                        return;
                    }
                    state.catalogCursor = page.nextCursor;
                    for (Conversation c : page.entries) register(c);
                });
    }

    private Chat register(Conversation c) {
        String key = chatId(c.key.connection.backend, c.key.id);
        Chat chat = chats.get(key);
        if (chat == null) {
            UiStateStore.State ui;
            try {
                ui = store.read(c.key.connection.backend, c.key.id);
            } catch (IOException e) {
                ui = new UiStateStore.State();
                notice = "Could not restore draft: " + e.getMessage();
            }
            if (ui.settings.model == null) ui.settings = c.settings;
            chat = new Chat(c, ui);
            chats.put(key, chat);
        } else chat.conversation = c;
        return chat;
    }

    public CompletableFuture<Chat> create(String backendId) {
        return backend(backendId)
                .backend
                .create(Settings.defaults())
                .thenApply(
                        c -> {
                            Chat chat = register(c);
                            chat.loaded = true;
                            select(chat);
                            return chat;
                        });
    }

    public void select(Chat chat) {
        if (selected != null) save(selected);
        selected = chat;
        chat.unread = 0;
    }

    public CompletableFuture<Void> open(Chat chat) {
        select(chat);
        if (chat.loaded) return CompletableFuture.completedFuture(null);
        return owner(chat)
                .backend
                .resume(chat.conversation.key)
                .thenCompose(
                        c -> {
                            chat.conversation = c;
                            chat.loaded = true;
                            chat.historyLoaded = false;
                            chat.historyCursor = null;
                            return loadHistory(chat).thenApply(page -> null);
                        });
    }

    public CompletableFuture<Page<Item>> loadHistory(Chat chat) {
        if (chat.historyLoading) return failed("History page is already loading");
        if (chat.historyLoaded && chat.historyCursor == null)
            return CompletableFuture.completedFuture(
                    new Page<Item>(Collections.<Item>emptyList(), null));
        chat.historyLoading = true;
        long beforeRequest = chat.timeline.revision();
        CompletableFuture<Page<Item>> f =
                owner(chat).backend.history(chat.conversation.key, chat.historyCursor);
        return f.whenComplete(
                (page, e) -> {
                    chat.historyLoading = false;
                    if (e != null) {
                        chat.error = error(e);
                        return;
                    }
                    chat.timeline.prependHistory(page.entries, beforeRequest);
                    chat.historyLoaded = true;
                    chat.historyCursor = page.nextCursor;
                });
    }

    public void draft(Chat chat, String text, int cursor) {
        chat.ui.draft = text;
        chat.ui.cursor = Math.max(0, Math.min(cursor, text.length()));
    }

    public void save(Chat chat) {
        try {
            store.write(
                    chat.conversation.key.connection.backend, chat.conversation.key.id, chat.ui);
        } catch (IOException e) {
            notice = "Could not save draft: " + e.getMessage();
        }
    }

    public CompletableFuture<Void> submit(Chat chat, Message message, boolean steer) {
        BackendState state = owner(chat);
        if (!state.ready || !chat.loaded) return failed("Connect and open this conversation first");
        if (chat.busy()
                || chat.steering
                || !chat.queue.isEmpty()
                || slotsUsed(state) >= maxTurns(state)) {
            chat.queue.add(new Queued(++queuedSequence, message, chat.ui.settings, steer));
            accepted(chat, message);
            schedule(chat);
            return CompletableFuture.completedFuture(null);
        }
        return send(chat, message, chat.ui.settings);
    }

    private void accepted(Chat chat, Message message) {
        chat.inputHistory.add(message.text);
        if (chat.ui.draft.equals(message.text)) draft(chat, "", 0);
        chat.ui.resources.removeAll(message.resources);
        save(chat);
    }

    private CompletableFuture<Void> send(Chat chat, Message message, Settings settings) {
        chat.submitting = true;
        chat.startedNanos = System.nanoTime();
        chat.error = "";
        chat.runningSettings = settings;
        return owner(chat)
                .backend
                .send(chat.conversation.key, message, settings)
                .handle(
                        (turn, e) -> {
                            chat.submitting = false;
                            if (e != null) {
                                chat.error = error(e);
                                throw new CompletionException(e);
                            }
                            // A terminal notification may precede the start response.
                            if (chat.turn == null || !chat.turn.id.equals(turn.id) || chat.busy())
                                chat.turn = turn;
                            accepted(chat, message);
                            return null;
                        });
    }

    private int maxTurns(BackendState b) {
        return b.capabilities == null ? 1 : b.capabilities.concurrentTurns;
    }

    private int slotsUsed(BackendState b) {
        int n = 0;
        for (Chat c : chats.values()) if (owner(c) == b && c.busy()) n++;
        return n;
    }

    private void schedule(Chat chat) {
        if (!chat.queuePaused && !chat.queue.isEmpty() && scheduled.add(chat)) runnable.add(chat);
    }

    public void retryQueue(Chat chat) {
        chat.queuePaused = false;
        schedule(chat);
    }

    public void cancelQueued(Chat chat, long id) {
        chat.queue.removeIf(q -> q.id == id);
    }

    public CompletableFuture<Void> interrupt(Chat chat) {
        if (chat.turn == null) return failed("No active turn");
        return owner(chat).backend.interrupt(chat.conversation.key, chat.turn.id);
    }

    public CompletableFuture<Void> answer(Chat chat, Pending pending, Map<String, Value> fields) {
        if (pending.status != Pending.Status.OPEN) return failed("Request is no longer open");
        Map<String, String> errors = pending.request.form.validate(fields);
        if (!errors.isEmpty()) {
            pending.error = errors.toString();
            return failed(pending.error);
        }
        pending.status = Pending.Status.SENDING;
        CompletableFuture<Void> f = owner(chat).backend.answer(pending.request.key, fields);
        return f.whenComplete(
                (r, e) -> {
                    if (e == null) pending.status = Pending.Status.RESOLVED;
                    else {
                        pending.error = error(e);
                        if (pending.status == Pending.Status.SENDING)
                            pending.status =
                                    owner(chat).ready
                                            ? Pending.Status.OPEN
                                            : Pending.Status.DISCONNECTED;
                    }
                });
    }

    public CompletableFuture<Void> rename(Chat chat, String title) {
        return owner(chat)
                .backend
                .rename(chat.conversation.key, title)
                .thenRun(
                        () -> {
                            Conversation c = chat.conversation;
                            chat.conversation =
                                    new Conversation(
                                            c.key, title, c.updatedAt, c.archived, c.settings);
                        });
    }

    public CompletableFuture<Void> archive(Chat chat, boolean archived) {
        if (chat.busy()) return failed("Stop the current task before archiving");
        return owner(chat)
                .backend
                .archive(chat.conversation.key, archived)
                .thenRun(
                        () -> {
                            Conversation c = chat.conversation;
                            chat.conversation =
                                    new Conversation(
                                            c.key, c.title, c.updatedAt, archived, c.settings);
                        });
    }

    public CompletableFuture<ActionResult> act(
            String backendId, Chat chat, String action, Map<String, Value> fields) {
        return backend(backendId)
                .backend
                .act(chat == null ? null : chat.conversation.key, action, fields)
                .thenCompose(
                        result -> {
                            if (chat != null && result.settings != null) {
                                Settings s = chat.ui.settings, n = result.settings;
                                chat.ui.settings =
                                        new Settings(
                                                n.model == null ? s.model : n.model,
                                                n.effort == null ? s.effort : n.effort,
                                                n.tier == null ? s.tier : n.tier,
                                                n.mode == null ? s.mode : n.mode,
                                                n.permissionProfile == null
                                                        ? s.permissionProfile
                                                        : n.permissionProfile,
                                                n.accessProgram == null
                                                        ? s.accessProgram
                                                        : n.accessProgram);
                                save(chat);
                            }
                            if (chat != null && result.conversationRemoved) {
                                chats.remove(chatId(backendId, chat.conversation.key.id));
                                if (selected == chat) selected = null;
                            }
                            if (result.conversation != null) {
                                Chat created = register(result.conversation);
                                return open(created).thenApply(ignored -> result);
                            }
                            return CompletableFuture.completedFuture(result);
                        });
    }

    public CompletableFuture<String> export(Chat chat) {
        return loadHistory(chat)
                .thenCompose(
                        page ->
                                chat.historyCursor == null
                                        ? CompletableFuture.completedFuture(transcript(chat))
                                        : export(chat));
    }

    public CompletableFuture<Void> openRelated(String backendId, String threadId) {
        Chat chat = chats.get(chatId(backendId, threadId));
        if (chat != null) return open(chat);
        return findRelated(backendId, threadId, null);
    }

    private CompletableFuture<Void> findRelated(String backendId, String threadId, String cursor) {
        return loadCatalog(backendId, "", false, cursor)
                .thenCompose(
                        page -> {
                            Chat chat = chats.get(chatId(backendId, threadId));
                            if (chat != null) return open(chat);
                            return page.nextCursor == null
                                    ? failed(
                                            "Related conversation is not available in this workspace")
                                    : findRelated(backendId, threadId, page.nextCursor);
                        });
    }

    public String transcript(Chat chat) {
        StringBuilder result = new StringBuilder("# ").append(chat.conversation.title).append('\n');
        for (int i = 0; i < chat.timeline.size(); i++) {
            Item item = chat.timeline.get(i);
            result.append("\n## ")
                    .append(item.title)
                    .append(" — ")
                    .append(item.state)
                    .append("\n\n")
                    .append(item.text)
                    .append('\n');
            for (Map.Entry<String, Value> entry : item.details.entrySet())
                result.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return result.toString();
    }

    public Resource workspaceResource(Resource.Type type, String path) throws IOException {
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(workspace, path);
        file = file.getCanonicalFile();
        if (!file.toPath().startsWith(workspace.toPath()) || !file.isFile())
            throw new IOException("Choose a file inside the world workspace");
        return new Resource(type, file.getName(), file.getPath());
    }

    private void receive(Event e) {
        if (closed || e.connection == null) return;
        BackendState state = backend(e.connection.backend);
        if (state == null || !e.connection.workspace.equals(workspace.getPath())) return;
        Connection current = state.backend.connection();
        if (current != null && !e.connection.equals(current)) return;
        if (e.type == EventType.DISCONNECTED) {
            state.ready = false;
            state.error = e.message;
            for (Chat c : chats.values())
                if (owner(c) == state) {
                    c.loaded = false;
                    c.submitting = false;
                    c.queue.clear();
                    c.queuePaused = false;
                    if (c.busy()) c.turn = new Turn(c.turn.id, State.DISCONNECTED, e.message);
                    for (Pending p : c.requests.values())
                        if (p.status != Pending.Status.RESOLVED)
                            p.status = Pending.Status.DISCONNECTED;
                    for (int i = 0; i < c.timeline.size(); i++) {
                        Item item = c.timeline.get(i);
                        if (item.state == State.RUNNING
                                || item.state == State.WAITING_FOR_USER
                                || item.state == State.QUEUED)
                            c.timeline.snapshot(
                                    new Item(
                                            item.id,
                                            item.turnId,
                                            item.kind,
                                            State.DISCONNECTED,
                                            item.title,
                                            item.text,
                                            item.details,
                                            item.resources,
                                            item.elapsedMillis));
                    }
                    save(c);
                }
            return;
        }
        if (current == null) return;
        // Connection diagnostics can arrive before connect() completes and marks us ready.
        if (e.type == EventType.WARNING) {
            notice = e.message;
            warnings.add(e.message);
            if (warnings.size() > 100) warnings.remove(0);
            return;
        }
        if (!state.ready) return;
        if (e.type == EventType.CAPABILITIES_CHANGED) {
            state.backend.capabilities().thenAccept(c -> state.capabilities = c);
            return;
        }
        if (e.thread == null) return;
        Chat chat = chats.get(chatId(e.thread.connection.backend, e.thread.id));
        if (chat == null) return;
        if (!chat.conversation.key.equals(e.thread)) return;
        if (e.item != null && e.item.kind == Kind.USAGE) {
            chat.usage = e.item;
            return;
        }
        switch (e.type) {
            case ITEM:
                chat.timeline.snapshot(e.item);
                break;
            case UPDATE:
                chat.timeline.update(e.item);
                break;
            case DELTA:
                chat.timeline.delta(e.itemId, e.delta);
                break;
            case TURN:
                if (chat.startedNanos == 0 || chat.turn != null && !chat.turn.id.equals(e.turn.id))
                    chat.startedNanos = System.nanoTime();
                chat.turn = e.turn;
                if (!chat.busy()) schedule(chat);
                break;
            case REQUEST:
                if (!chat.requests.containsKey(e.request.key))
                    chat.requests.put(e.request.key, new Pending(e.request));
                chat.timeline.snapshot(
                        new Item(
                                "request:" + e.request.key.token,
                                e.request.turnId,
                                Kind.REQUEST,
                                State.WAITING_FOR_USER,
                                e.request.title,
                                e.request.description,
                                Collections.<String, Value>emptyMap(),
                                Collections.<Resource>emptyList(),
                                null));
                break;
            case REQUEST_RESOLVED:
                Pending p = chat.requests.get(e.resolved);
                if (p != null) {
                    p.status = Pending.Status.RESOLVED;
                    chat.timeline.snapshot(
                            new Item(
                                    "request:" + p.request.key.token,
                                    p.request.turnId,
                                    Kind.REQUEST,
                                    State.COMPLETED,
                                    p.request.title,
                                    "Decision sent",
                                    Collections.<String, Value>emptyMap(),
                                    Collections.<Resource>emptyList(),
                                    null));
                }
                break;
            default:
                break;
        }
        if (chat != selected) chat.unread++;
    }

    public int tick(int budget) {
        if (closed || backends.isEmpty()) return 0;
        List<BackendState> list = new ArrayList<BackendState>(backends.values());
        int count = 0;
        int eventBudget = Math.max(0, budget - (runnable.isEmpty() ? 0 : 1));
        for (int i = 0; i < list.size() && count < eventBudget; i++) {
            BackendState b = list.get((pumpIndex + i) % list.size());
            count += b.backend.pump(Math.min(32, eventBudget - count));
        }
        pumpIndex = (pumpIndex + 1) % list.size();
        int attempts = Math.min(runnable.size(), Math.max(0, budget - count));
        for (int i = 0; i < attempts; i++) {
            Chat chat = runnable.remove();
            scheduled.remove(chat);
            count++;
            if (chat.queue.isEmpty() || chat.queuePaused || !owner(chat).ready) continue;
            if (chat.steering) {
                schedule(chat);
                continue;
            }
            Queued head = chat.queue.get(0);
            if (head.steer
                    && chat.busy()
                    && !chat.submitting
                    && chat.turn != null
                    && !chat.needsAnswer()
                    && !chat.steering
                    && owner(chat).capabilities != null
                    && owner(chat).capabilities.feature(Feature.STEER).available) {
                chat.steering = true;
                owner(chat)
                        .backend
                        .steer(chat.conversation.key, chat.turn.id, head.message)
                        .whenComplete(
                                (r, e) -> {
                                    chat.steering = false;
                                    if (e == null) chat.queue.remove(head);
                                    else {
                                        chat.queuePaused = true;
                                        chat.error =
                                                "Followup retained; retry the queue: " + error(e);
                                    }
                                    schedule(chat);
                                });
                continue;
            }
            if (chat.busy() || slotsUsed(owner(chat)) >= maxTurns(owner(chat))) {
                schedule(chat);
                continue;
            }
            Queued queued = chat.queue.remove(0);
            send(chat, queued.message, queued.settings)
                    .whenComplete(
                            (r, e) -> {
                                if (e != null) {
                                    chat.queuePaused = true;
                                    chat.queue.add(0, queued);
                                    chat.error =
                                            "Queued message failed; retained for retry or cancellation: "
                                                    + error(e);
                                } else schedule(chat);
                            });
        }
        return count;
    }

    public void close() {
        if (closed) return;
        for (Chat c : chats.values()) save(c);
        for (BackendState b : backends.values()) b.backend.close();
        closed = true;
        runnable.clear();
        scheduled.clear();
    }
}
