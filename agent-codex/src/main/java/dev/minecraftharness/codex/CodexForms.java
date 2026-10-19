package dev.minecraftharness.codex;

import static dev.minecraftharness.codex.Wire.*;

import com.google.gson.*;
import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.util.*;

final class CodexForms {
    private CodexForms() {}

    static Form request(String method, JsonObject p) {
        if (method.equals("item/commandExecution/requestApproval")
                || method.equals("item/fileChange/requestApproval"))
            return new Form(
                    Arrays.asList(Form.Field.choice("decision", "Decision", "accept", "decline")));
        if (method.equals("item/tool/requestUserInput")) {
            List<Form.Field> fields = new ArrayList<Form.Field>();
            for (JsonElement e : array(p, "questions")) {
                JsonObject q = e.getAsJsonObject();
                boolean secret = q.has("isSecret") && q.get("isSecret").getAsBoolean();
                List<String> choices = new ArrayList<String>();
                StringBuilder help = new StringBuilder();
                for (JsonElement v : array(q, "options")) {
                    JsonObject option = v.getAsJsonObject();
                    choices.add(text(option, "label"));
                    help.append(text(option, "label"))
                            .append(": ")
                            .append(text(option, "description"))
                            .append('\n');
                }
                boolean other = q.has("isOther") && q.get("isOther").getAsBoolean();
                fields.add(
                        new Form.Field(
                                text(q, "id"),
                                text(q, "question"),
                                help.toString(),
                                secret
                                        ? Form.Type.SECRET
                                        : choices.isEmpty() || other
                                                ? Form.Type.TEXT
                                                : Form.Type.CHOICE,
                                true,
                                choices,
                                null,
                                null,
                                null,
                                null,
                                null));
            }
            if (fields.isEmpty()) throw unavailable("Question has no supported fields");
            return new Form(fields);
        }
        if (method.equals("mcpServer/elicitation/request")) {
            String mode = text(p, "mode");
            if (!mode.equals("form"))
                throw unavailable("MCP form mode " + mode + " needs an external or richer client");
            Form content = decode(object(p, "requestedSchema"));
            return new Form(
                    Arrays.asList(
                            Form.Field.choice("action", "Decision", "accept", "decline", "cancel"),
                            new Form.Field(
                                    "content",
                                    "Response",
                                    "Required when accepting",
                                    Form.Type.GROUP,
                                    false,
                                    Collections.<String>emptyList(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    content)));
        }
        throw unavailable("Unsupported required request: " + method);
    }

    static Form decode(JsonObject schema) {
        if (!text(schema, "type").equals("object")) throw unavailable("Expected an object form");
        Set<String> required = new HashSet<String>();
        for (JsonElement e : array(schema, "required")) required.add(e.getAsString());
        List<Form.Field> fields = new ArrayList<Form.Field>();
        for (Map.Entry<String, JsonElement> e : object(schema, "properties").entrySet()) {
            if (!e.getValue().isJsonObject()) throw unavailable("Unsupported field schema");
            JsonObject s = e.getValue().getAsJsonObject();
            String type = text(s, "type");
            Form.Type neutral;
            List<String> choices = new ArrayList<String>();
            for (JsonElement choice : array(s, "enum")) choices.add(choice.getAsString());
            // Reject constraints we cannot faithfully validate, instead of silently dropping them.
            for (String keyword :
                    Arrays.asList(
                            "oneOf",
                            "anyOf",
                            "allOf",
                            "$ref",
                            "pattern",
                            "format",
                            "multipleOf",
                            "exclusiveMinimum",
                            "exclusiveMaximum"))
                if (s.has(keyword)) throw unavailable("Unsupported form constraint: " + keyword);
            if (type.equals("string"))
                neutral = choices.isEmpty() ? Form.Type.TEXT : Form.Type.CHOICE;
            else if (type.equals("number")) neutral = Form.Type.NUMBER;
            else if (type.equals("integer")) neutral = Form.Type.INTEGER;
            else if (type.equals("array")
                    && text(object(s, "items"), "type").equals("string")
                    && !array(object(s, "items"), "enum").equals(new JsonArray())) {
                neutral = Form.Type.MULTICHOICE;
                for (JsonElement choice : array(object(s, "items"), "enum"))
                    choices.add(choice.getAsString());
            } else if (type.equals("boolean")) neutral = Form.Type.BOOLEAN;
            else if (type.equals("object")) neutral = Form.Type.GROUP;
            else throw unavailable("Unsupported form field type: " + type);
            fields.add(
                    new Form.Field(
                            e.getKey(),
                            s.has("title") ? text(s, "title") : e.getKey(),
                            text(s, "description"),
                            neutral,
                            required.contains(e.getKey()),
                            choices,
                            number(s, "minimum"),
                            number(s, "maximum"),
                            integer(s, neutral == Form.Type.MULTICHOICE ? "minItems" : "minLength"),
                            integer(s, neutral == Form.Type.MULTICHOICE ? "maxItems" : "maxLength"),
                            neutral == Form.Type.GROUP ? decode(s) : null));
        }
        return new Form(fields);
    }

    private static Double number(JsonObject s, String key) {
        return s.has(key) && !s.get(key).isJsonNull() ? s.get(key).getAsDouble() : null;
    }

    private static Integer integer(JsonObject s, String key) {
        return s.has(key) && !s.get(key).isJsonNull() ? s.get(key).getAsInt() : null;
    }

    static JsonObject answer(String method, Map<String, Value> fields) {
        if (method.equals("item/tool/requestUserInput")) {
            JsonObject out = new JsonObject(), answers = new JsonObject();
            for (Map.Entry<String, Value> e : fields.entrySet()) {
                JsonArray a = new JsonArray();
                a.add(new JsonPrimitive(e.getValue().text()));
                JsonObject answer = new JsonObject();
                answer.add("answers", a);
                answers.add(e.getKey(), answer);
            }
            out.add("answers", answers);
            return out;
        }
        if (method.equals("mcpServer/elicitation/request")
                && fields.get("action").text().equals("accept")
                && !fields.containsKey("content"))
            throw new Failure(
                    Failure.Code.INVALID_INPUT, "Response fields are required when accepting");
        JsonObject out = json(Value.object(fields)).getAsJsonObject();
        if (method.equals("mcpServer/elicitation/request")
                && !fields.get("action").text().equals("accept")) out.remove("content");
        return out;
    }

    static String title(String method) {
        return method.contains("commandExecution")
                ? "Run command?"
                : method.contains("fileChange")
                        ? "Change files?"
                        : method.contains("elicitation") ? "Tool needs input" : "Agent question";
    }

    static String description(JsonObject p) {
        StringBuilder s = new StringBuilder();
        for (String k : Arrays.asList("serverName", "command", "cwd", "reason", "message")) {
            String v = text(p, k);
            if (!v.isEmpty()) s.append(k).append(": ").append(v).append('\n');
        }
        return s.toString();
    }

    private static Failure unavailable(String text) {
        return new Failure(Failure.Code.UNAVAILABLE, text);
    }
}
