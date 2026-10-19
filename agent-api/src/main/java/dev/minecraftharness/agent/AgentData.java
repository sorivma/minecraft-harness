package dev.minecraftharness.agent;

import java.util.*;

/** Immutable values crossing a backend boundary. No transport or platform types. */
public final class AgentData {
    private AgentData() {}

    public static <T> List<T> list(Collection<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public static <K, V> Map<K, V> map(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(values));
    }

    public static String required(String value, String name) {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public enum State {
        QUEUED,
        RUNNING,
        WAITING_FOR_USER,
        COMPLETED,
        FAILED,
        INTERRUPTED,
        DISCONNECTED
    }

    public enum Kind {
        USER,
        ASSISTANT,
        SUMMARY,
        PLAN,
        WEB,
        TOOL,
        EXECUTION,
        FILE_CHANGE,
        CHILD_TASK,
        REVIEW,
        COMPACTION,
        USAGE,
        REQUEST,
        ERROR,
        GENERIC
    }

    public enum EventType {
        ITEM,
        UPDATE,
        DELTA,
        TURN,
        REQUEST,
        REQUEST_RESOLVED,
        CAPABILITIES_CHANGED,
        WARNING,
        DISCONNECTED
    }

    public enum Feature {
        PARALLEL_TURNS,
        STEER,
        RENAME,
        ARCHIVE,
        RESTORE,
        MODELS,
        EFFORT,
        SERVICE_TIER,
        ATTACHMENTS
    }

    public static final class Connection {
        public final String backend, workspace;
        public final long generation;

        public Connection(String backend, String workspace, long generation) {
            this.backend = required(backend, "backend");
            this.workspace = required(workspace, "workspace");
            this.generation = generation;
        }

        public ThreadKey thread(String id) {
            return new ThreadKey(this, id);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Connection)) return false;
            Connection c = (Connection) other;
            return backend.equals(c.backend)
                    && workspace.equals(c.workspace)
                    && generation == c.generation;
        }

        @Override
        public int hashCode() {
            return Objects.hash(backend, workspace, generation);
        }
    }

    public static final class ThreadKey {
        public final Connection connection;
        public final String id;

        public ThreadKey(Connection connection, String id) {
            this.connection = Objects.requireNonNull(connection);
            this.id = required(id, "thread id");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ThreadKey
                    && connection.equals(((ThreadKey) other).connection)
                    && id.equals(((ThreadKey) other).id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connection, id);
        }
    }

    /** Opaque backend token; the adapter retains the original wire-id type. */
    public static final class RequestKey {
        public final ThreadKey thread;
        public final String token;

        public RequestKey(ThreadKey thread, String token) {
            this.thread = Objects.requireNonNull(thread);
            this.token = required(token, "request token");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RequestKey
                    && thread.equals(((RequestKey) other).thread)
                    && token.equals(((RequestKey) other).token);
        }

        @Override
        public int hashCode() {
            return Objects.hash(thread, token);
        }
    }

    public static final class Page<T> {
        public final List<T> entries;
        public final String nextCursor;

        public Page(Collection<T> entries, String nextCursor) {
            this.entries = list(entries);
            this.nextCursor = nextCursor;
        }
    }

    public static final class Availability {
        public final boolean available;
        public final String reason;

        private Availability(boolean available, String reason) {
            this.available = available;
            this.reason = reason;
        }

        public static Availability yes() {
            return new Availability(true, "");
        }

        public static Availability no(String reason) {
            return new Availability(false, required(reason, "unavailability reason"));
        }
    }

    public static final class Capabilities {
        public final Map<Feature, Availability> features;
        public final List<Action> actions;
        public final int concurrentTurns;

        public Capabilities(
                Map<Feature, Availability> features,
                Collection<Action> actions,
                int concurrentTurns) {
            if (concurrentTurns < 1)
                throw new IllegalArgumentException("concurrentTurns must be positive");
            this.features = map(features);
            this.actions = list(actions);
            this.concurrentTurns = concurrentTurns;
        }

        public Availability feature(Feature feature) {
            Availability value = features.get(feature);
            return value == null ? Availability.no("Not advertised by this backend") : value;
        }
    }

    public static final class Model {
        public final String id, name, description, defaultEffort, defaultTier;
        public final List<String> efforts, tiers;
        public final List<String> accessPrograms;
        public final Availability availability;

        public Model(
                String id,
                String name,
                String description,
                Collection<String> efforts,
                Collection<String> tiers,
                String defaultEffort,
                String defaultTier,
                Availability availability) {
            this(
                    id,
                    name,
                    description,
                    efforts,
                    tiers,
                    defaultEffort,
                    defaultTier,
                    availability,
                    Collections.<String>emptyList());
        }

        public Model(
                String id,
                String name,
                String description,
                Collection<String> efforts,
                Collection<String> tiers,
                String defaultEffort,
                String defaultTier,
                Availability availability,
                Collection<String> accessPrograms) {
            this.id = required(id, "model id");
            this.name = name;
            this.description = description;
            this.efforts = list(efforts);
            this.tiers = list(tiers);
            this.defaultEffort = defaultEffort;
            this.defaultTier = defaultTier;
            this.availability = availability;
            this.accessPrograms = list(accessPrograms);
        }
    }

    public static final class Settings {
        public final String model, effort, tier, mode, permissionProfile, accessProgram;

        public Settings(
                String model, String effort, String tier, String mode, String permissionProfile) {
            this(model, effort, tier, mode, permissionProfile, null);
        }

        public Settings(
                String model,
                String effort,
                String tier,
                String mode,
                String permissionProfile,
                String accessProgram) {
            this.model = model;
            this.effort = effort;
            this.tier = tier;
            this.mode = mode;
            this.permissionProfile = permissionProfile;
            this.accessProgram = accessProgram;
        }

        public static Settings defaults() {
            return new Settings(null, null, null, null, null);
        }
    }

    public static final class Resource {
        public enum Type {
            FILE,
            IMAGE,
            AUDIO,
            SKILL,
            APP,
            LINK,
            CONVERSATION
        }

        public final Type type;
        public final String name, location;

        public Resource(Type type, String name, String location) {
            this.type = Objects.requireNonNull(type);
            this.name = name;
            this.location = required(location, "resource location");
        }
    }

    public static final class Message {
        public final String text;
        public final List<Resource> resources;

        public Message(String text, Collection<Resource> resources) {
            this.text = Objects.requireNonNull(text);
            this.resources = list(resources);
            if (text.trim().isEmpty() && resources.isEmpty())
                throw new IllegalArgumentException("Message is empty");
        }

        public Message(String text) {
            this(text, Collections.<Resource>emptyList());
        }
    }

    public static final class Conversation {
        public final ThreadKey key;
        public final String title;
        public final long updatedAt;
        public final boolean archived;
        public final Settings settings;

        public Conversation(
                ThreadKey key, String title, long updatedAt, boolean archived, Settings settings) {
            this.key = Objects.requireNonNull(key);
            this.title = title;
            this.updatedAt = updatedAt;
            this.archived = archived;
            this.settings = Objects.requireNonNull(settings);
        }
    }

    public static final class Turn {
        public final String id;
        public final State state;
        public final String error;

        public Turn(String id, State state, String error) {
            this.id = required(id, "turn id");
            this.state = state;
            this.error = error;
        }
    }

    /** Details are presentation data: labelled sections, resources and scalar fields. */
    public static final class Item {
        public final String id, turnId, title, text;
        public final Kind kind;
        public final State state;
        public final Map<String, Value> details;
        public final List<Resource> resources;
        public final Long elapsedMillis;

        public Item(
                String id,
                String turnId,
                Kind kind,
                State state,
                String title,
                String text,
                Map<String, Value> details,
                Collection<Resource> resources,
                Long elapsedMillis) {
            this.id = required(id, "item id");
            this.turnId = turnId;
            this.kind = kind;
            this.state = state;
            this.title = title;
            this.text = text == null ? "" : text;
            this.details = map(details);
            this.resources = list(resources);
            this.elapsedMillis = elapsedMillis;
        }

        public Item withText(String text) {
            return new Item(
                    id, turnId, kind, state, title, text, details, resources, elapsedMillis);
        }
    }

    public static final class Request {
        public final RequestKey key;
        public final String turnId, itemId, title, description;
        public final Form form;

        public Request(
                RequestKey key,
                String turnId,
                String itemId,
                String title,
                String description,
                Form form) {
            this.key = key;
            this.turnId = turnId;
            this.itemId = itemId;
            this.title = title;
            this.description = description;
            this.form = Objects.requireNonNull(form);
        }
    }

    public static final class Action {
        public final String id, category, title, description;
        public final Availability availability;
        public final Form form;
        public final boolean confirmationRequired;

        public Action(
                String id,
                String category,
                String title,
                String description,
                Availability availability,
                Form form,
                boolean confirmationRequired) {
            this.id = required(id, "action id");
            this.category = category;
            this.title = title;
            this.description = description;
            this.availability = availability;
            this.form = form;
            this.confirmationRequired = confirmationRequired;
        }
    }

    public static final class ActionResult {
        public final String title, text;
        public final List<Resource> resources;
        public final Map<String, Value> fields;
        public final Conversation conversation;
        public final Settings settings;
        public final boolean conversationRemoved;

        public ActionResult(
                String title,
                String text,
                Map<String, Value> fields,
                Collection<Resource> resources) {
            this(title, text, fields, resources, null, null, false);
        }

        public ActionResult(
                String title,
                String text,
                Map<String, Value> fields,
                Collection<Resource> resources,
                Conversation conversation,
                Settings settings,
                boolean removed) {
            this.title = title;
            this.text = text;
            this.fields = map(fields);
            this.resources = list(resources);
            this.conversation = conversation;
            this.settings = settings;
            this.conversationRemoved = removed;
        }
    }

    public static final class Event {
        public final Connection connection;
        public final ThreadKey thread;
        public final EventType type;
        public final Item item;
        public final Turn turn;
        public final Request request;
        public final RequestKey resolved;
        public final String itemId, delta, message;

        private Event(
                Connection connection,
                ThreadKey thread,
                EventType type,
                Item item,
                Turn turn,
                Request request,
                RequestKey resolved,
                String itemId,
                String delta,
                String message) {
            this.connection = connection;
            this.thread = thread;
            this.type = type;
            this.item = item;
            this.turn = turn;
            this.request = request;
            this.resolved = resolved;
            this.itemId = itemId;
            this.delta = delta;
            this.message = message;
        }

        public static Event item(ThreadKey t, Item item) {
            return new Event(
                    t.connection, t, EventType.ITEM, item, null, null, null, null, null, null);
        }

        public static Event update(ThreadKey t, Item item) {
            return new Event(
                    t.connection, t, EventType.UPDATE, item, null, null, null, null, null, null);
        }

        public static Event delta(ThreadKey t, String id, String delta) {
            return new Event(
                    t.connection, t, EventType.DELTA, null, null, null, null, id, delta, null);
        }

        public static Event turn(ThreadKey t, Turn turn) {
            return new Event(
                    t.connection, t, EventType.TURN, null, turn, null, null, null, null, null);
        }

        public static Event request(Request r) {
            return new Event(
                    r.key.thread.connection,
                    r.key.thread,
                    EventType.REQUEST,
                    null,
                    null,
                    r,
                    null,
                    null,
                    null,
                    null);
        }

        public static Event resolved(RequestKey r) {
            return new Event(
                    r.thread.connection,
                    r.thread,
                    EventType.REQUEST_RESOLVED,
                    null,
                    null,
                    null,
                    r,
                    null,
                    null,
                    null);
        }

        public static Event connection(Connection c, EventType type, String message) {
            if (type != EventType.DISCONNECTED
                    && type != EventType.WARNING
                    && type != EventType.CAPABILITIES_CHANGED)
                throw new IllegalArgumentException("Not a connection event");
            return new Event(c, null, type, null, null, null, null, null, null, message);
        }
    }

    public static final class Failure extends RuntimeException {
        public enum Code {
            DISCONNECTED,
            STALE_HANDLE,
            UNAVAILABLE,
            INVALID_INPUT,
            REJECTED,
            INCOMPATIBLE,
            INTERNAL
        }

        public final Code code;

        public Failure(Code code, String message) {
            super(message);
            this.code = code;
        }
    }
}
