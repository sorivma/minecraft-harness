package dev.minecraftharness.codex;

import com.google.gson.*;
import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.util.*;

/** All wire interpretation stays on this side of the provider boundary. */
final class Wire {
    private Wire() {}

    static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).isJsonPrimitive()
                ? o.get(key).getAsString()
                : "";
    }

    static String nullable(JsonObject o, String key) {
        String s = text(o, key);
        return s.isEmpty() ? null : s;
    }

    static JsonObject object(JsonObject o, String key) {
        return RpcConnection.object(o, key);
    }

    static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    static JsonObject params(String key, String value) {
        JsonObject p = new JsonObject();
        if (value != null) p.addProperty(key, value);
        return p;
    }

    static void put(JsonObject o, String key, String value) {
        if (value != null) o.addProperty(key, value);
    }

    static Value value(JsonElement v) {
        if (v == null || v.isJsonNull()) return Value.empty();
        if (v.isJsonObject()) {
            Map<String, Value> fields = new LinkedHashMap<String, Value>();
            for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet())
                fields.put(e.getKey(), value(e.getValue()));
            return Value.object(fields);
        }
        if (v.isJsonArray()) {
            List<Value> values = new ArrayList<Value>();
            for (JsonElement e : v.getAsJsonArray()) values.add(value(e));
            return Value.array(values);
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        return p.isBoolean()
                ? Value.bool(p.getAsBoolean())
                : p.isNumber() ? Value.number(p.getAsDouble()) : Value.text(p.getAsString());
    }

    static JsonElement json(Value v) {
        switch (v.type) {
            case EMPTY:
                return JsonNull.INSTANCE;
            case BOOLEAN:
                return new JsonPrimitive(v.bool());
            case NUMBER:
                return new JsonPrimitive(v.number());
            case TEXT:
                return new JsonPrimitive(v.text());
            case RESOURCE:
                return new JsonPrimitive(v.resource().location);
            case LIST:
                JsonArray a = new JsonArray();
                for (Value e : v.array()) a.add(json(e));
                return a;
            case OBJECT:
                JsonObject o = new JsonObject();
                for (Map.Entry<String, Value> e : v.object().entrySet())
                    o.add(e.getKey(), json(e.getValue()));
                return o;
            default:
                throw new IllegalArgumentException("Unknown value type");
        }
    }

    static State state(String s, State fallback) {
        if (s.equals("completed") || s.equals("success")) return State.COMPLETED;
        if (s.equals("failed") || s.equals("error") || s.equals("declined")) return State.FAILED;
        if (s.equals("interrupted") || s.equals("cancelled")) return State.INTERRUPTED;
        if (s.equals("inProgress") || s.equals("running")) return State.RUNNING;
        return fallback;
    }

    static Item item(JsonObject o, String turnId, State fallback) {
        String type = text(o, "type"), id = text(o, "id"), body = text(o, "text"), title = type;
        Kind kind = Kind.GENERIC;
        if (type.equals("userMessage")) {
            kind = Kind.USER;
            title = "You";
            body = content(array(o, "content"));
        } else if (type.equals("agentMessage")) {
            kind = Kind.ASSISTANT;
            title = "Agent";
        } else if (type.equals("plan")) {
            kind = Kind.PLAN;
            title = "Plan";
        } else if (type.equals("reasoning")) {
            kind = Kind.SUMMARY;
            title = "Summary";
            body = content(array(o, "summary"));
        } else if (type.equals("webSearch")) {
            kind = Kind.WEB;
            JsonObject action = object(o, "action");
            String verb = text(action, "type");
            title =
                    "Web · "
                            + (verb.equals("openPage")
                                    ? "Open page"
                                    : verb.equals("findInPage") ? "Find in page" : "Search");
            body = text(action, "query");
            if (body.isEmpty()) body = text(action, "url");
            if (body.isEmpty()) body = text(o, "query");
            if (body.isEmpty()) body = array(action, "queries").toString();
            if (!text(action, "pattern").isEmpty()) body += "\nFind: " + text(action, "pattern");
        } else if (type.equals("mcpToolCall") || type.equals("dynamicToolCall")) {
            kind = Kind.TOOL;
            title = text(o, "server") + " · " + text(o, "tool");
            body = content(array(object(o, "result"), "content"));
            if (!text(object(o, "error"), "message").isEmpty())
                body = text(object(o, "error"), "message");
        } else if (type.equals("commandExecution")) {
            kind = Kind.EXECUTION;
            title = text(o, "command");
            body = text(o, "aggregatedOutput");
        } else if (type.equals("fileChange")) {
            kind = Kind.FILE_CHANGE;
            title = "File changes";
            StringBuilder changes = new StringBuilder();
            for (JsonElement entry : array(o, "changes")) {
                JsonObject change = entry.getAsJsonObject();
                changes.append(text(change, "path"))
                        .append('\n')
                        .append(text(change, "diff"))
                        .append('\n');
            }
            body = changes.toString();
        } else if (type.startsWith("collab")) {
            kind = Kind.CHILD_TASK;
            title = "Child task · " + text(o, "tool");
        } else if (type.contains("ReviewMode")) {
            kind = Kind.REVIEW;
            title = "Review";
            body = text(o, "review");
        } else if (type.equals("contextCompaction")) {
            kind = Kind.COMPACTION;
            title = "Context compacted";
        }
        Map<String, Value> details = new LinkedHashMap<String, Value>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            // Do not expose hidden reasoning content; only the supplied summary is public.
            if (kind == Kind.SUMMARY && !(k.equals("summary") || k.equals("status"))) continue;
            if (!Arrays.asList("id", "type", "text", "aggregatedOutput").contains(k))
                details.put(k, value(e.getValue()));
        }
        List<Resource> resources = new ArrayList<Resource>();
        links(o, resources);
        if (kind == Kind.CHILD_TASK) {
            for (JsonElement child : array(o, "receiverThreadIds"))
                resources.add(
                        new Resource(
                                Resource.Type.CONVERSATION,
                                "Child conversation",
                                child.getAsString()));
            String receiver = text(o, "receiverThreadId");
            if (!receiver.isEmpty())
                resources.add(
                        new Resource(Resource.Type.CONVERSATION, "Child conversation", receiver));
        }
        Long elapsed =
                o.has("durationMs") && !o.get("durationMs").isJsonNull()
                        ? o.get("durationMs").getAsLong()
                        : null;
        State status = state(text(o, "status"), fallback);
        if (o.has("error") && !o.get("error").isJsonNull()
                || object(o, "result").has("isError")
                        && object(o, "result").get("isError").getAsBoolean()) status = State.FAILED;
        return new Item(id, turnId, kind, status, title, body, details, resources, elapsed);
    }

    static String content(JsonArray entries) {
        StringBuilder text = new StringBuilder();
        for (JsonElement e : entries) {
            String part =
                    e.isJsonPrimitive()
                            ? e.getAsString()
                            : e.isJsonObject() ? text(e.getAsJsonObject(), "text") : "";
            if (!part.isEmpty()) {
                if (text.length() > 0) text.append('\n');
                text.append(part);
            }
        }
        return text.toString();
    }

    static void links(JsonElement value, List<Resource> out) {
        if (value.isJsonObject())
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                if ((e.getKey().toLowerCase(Locale.ROOT).endsWith("url")
                                || e.getKey().equals("uri"))
                        && e.getValue().isJsonPrimitive()) {
                    String uri = e.getValue().getAsString();
                    if (uri.startsWith("https://") || uri.startsWith("http://"))
                        out.add(new Resource(Resource.Type.LINK, uri, uri));
                } else if (!e.getKey().equals("content")) links(e.getValue(), out);
            }
        else if (value.isJsonArray()) for (JsonElement e : value.getAsJsonArray()) links(e, out);
    }

    static JsonArray input(Message message) {
        JsonArray a = new JsonArray();
        if (!message.text.isEmpty()) {
            JsonObject text = params("type", "text");
            text.addProperty("text", message.text);
            a.add(text);
        }
        for (Resource r : message.resources) {
            JsonObject v = new JsonObject();
            switch (r.type) {
                case IMAGE:
                    v.addProperty("type", "localImage");
                    v.addProperty("path", r.location);
                    break;
                case AUDIO:
                    v.addProperty("type", "localAudio");
                    v.addProperty("path", r.location);
                    break;
                case SKILL:
                    v.addProperty("type", "skill");
                    v.addProperty("name", r.name);
                    v.addProperty("path", r.location);
                    break;
                case APP:
                    v.addProperty("type", "mention");
                    v.addProperty("name", r.name);
                    v.addProperty("path", r.location);
                    break;
                case FILE:
                case LINK:
                    v.addProperty("type", "text");
                    v.addProperty("text", "Attached resource: " + r.location);
                    break;
                default:
                    throw new Failure(
                            Failure.Code.UNAVAILABLE,
                            "Audio input requires the realtime transport");
            }
            a.add(v);
        }
        return a;
    }
}
