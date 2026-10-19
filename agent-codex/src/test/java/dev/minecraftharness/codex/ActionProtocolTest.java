package dev.minecraftharness.codex;

import static org.junit.Assert.*;

import com.google.gson.*;
import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.BackendScenarios;
import java.io.*;
import java.util.*;
import org.junit.Test;

public class ActionProtocolTest {
    @Test
    public void everyActionEmitsSchemaValidRequestAndEffectsAreTyped() throws Exception {
        JsonObject schemas =
                new JsonParser()
                        .parse(
                                new InputStreamReader(
                                        getClass()
                                                .getResourceAsStream(
                                                        "/protocol/0.163.0/action-request-schemas.json"),
                                        "UTF-8"))
                        .getAsJsonObject();
        CodexBackend b = new CodexBackendTest().backend();
        try {
            BackendScenarios.await(b, b.connect(new File("."), e -> {}));
            Conversation thread = BackendScenarios.await(b, b.create(Settings.defaults()));
            // Import is intentionally limited to a previously detected item, not arbitrary input.
            BackendScenarios.await(
                    b,
                    b.act(
                            null,
                            "import.detect",
                            Collections.singletonMap("includeHome", Value.bool(false))));
            for (CodexActions.Definition action : CodexActions.DEFINITIONS) {
                Map<String, Value> input = new LinkedHashMap<String, Value>();
                for (Form.Field field : action.form.fields)
                    if (field.required)
                        input.put(
                                field.id,
                                field.type == Form.Type.BOOLEAN
                                        ? Value.bool(false)
                                        : field.type == Form.Type.NUMBER
                                                ? Value.number(100)
                                                : Value.text(
                                                        field.type == Form.Type.CHOICE
                                                                ? field.choices.get(0)
                                                                : "fixture"));
                if (action.id.equals("import.apply")) input.put("selection", Value.text("0"));
                ActionResult result =
                        BackendScenarios.await(b, b.act(thread.key, action.id, input));
                assertEquals(action.method, result.fields.get("sentMethod").text());
                JsonElement params = Wire.json(result.fields.get("sentParams"));
                validate(
                        params,
                        schemas.getAsJsonObject(action.method),
                        schemas.getAsJsonObject(action.method),
                        action.id);
                if (action.id.equals("fork") || action.id.equals("side"))
                    assertNotNull(result.conversation);
                if (action.id.equals("delete")) assertTrue(result.conversationRemoved);
                if (action.id.equals("review"))
                    assertEquals(
                            "uncommittedChanges",
                            params.getAsJsonObject()
                                    .getAsJsonObject("target")
                                    .get("type")
                                    .getAsString());
                if (action.id.equals("config.write"))
                    assertTrue(
                            params.getAsJsonObject()
                                    .get("filePath")
                                    .getAsString()
                                    .endsWith(".codex" + File.separator + "config.toml"));
                if (action.id.equals("side"))
                    assertTrue(params.getAsJsonObject().get("ephemeral").getAsBoolean());
            }
        } finally {
            b.close();
        }
    }

    static void validate(JsonElement v, JsonObject schema, JsonObject root, String path) {
        if (schema.has("$ref")) {
            validate(
                    v,
                    root.getAsJsonObject("definitions")
                            .getAsJsonObject(
                                    schema.get("$ref")
                                            .getAsString()
                                            .substring("#/definitions/".length())),
                    root,
                    path);
            return;
        }
        for (String union : Arrays.asList("oneOf", "anyOf"))
            if (schema.has(union)) {
                int valid = 0;
                for (JsonElement option : schema.getAsJsonArray(union))
                    try {
                        validate(v, option.getAsJsonObject(), root, path);
                        valid++;
                    } catch (AssertionError rejected) {
                    }
                assertTrue(path + " has no matching " + union + ": " + v, valid > 0);
                if (union.equals("oneOf")) assertEquals(path, 1, valid);
                return;
            }
        if (schema.has("enum"))
            assertTrue(path + " enum " + v, contains(schema.getAsJsonArray("enum"), v));
        if (schema.has("type")) {
            JsonElement types = schema.get("type");
            List<String> allowed = new ArrayList<String>();
            if (types.isJsonArray())
                for (JsonElement t : types.getAsJsonArray()) allowed.add(t.getAsString());
            else allowed.add(types.getAsString());
            String actual =
                    v.isJsonNull()
                            ? "null"
                            : v.isJsonObject()
                                    ? "object"
                                    : v.isJsonArray()
                                            ? "array"
                                            : v.getAsJsonPrimitive().isBoolean()
                                                    ? "boolean"
                                                    : v.getAsJsonPrimitive().isNumber()
                                                            ? "number"
                                                            : "string";
            assertTrue(
                    path + " type " + actual + " expected " + allowed,
                    allowed.contains(actual)
                            || actual.equals("number")
                                    && allowed.contains("integer")
                                    && v.getAsDouble() == Math.rint(v.getAsDouble()));
        }
        if (v.isJsonObject()) {
            JsonObject props = Wire.object(schema, "properties");
            for (JsonElement name : Wire.array(schema, "required"))
                assertTrue(path + " missing " + name, v.getAsJsonObject().has(name.getAsString()));
            for (Map.Entry<String, JsonElement> entry : v.getAsJsonObject().entrySet()) {
                if (props.has(entry.getKey()) && props.get(entry.getKey()).isJsonObject())
                    validate(
                            entry.getValue(),
                            props.getAsJsonObject(entry.getKey()),
                            root,
                            path + "." + entry.getKey());
                else if (schema.has("additionalProperties")
                        && schema.get("additionalProperties").isJsonObject())
                    validate(
                            entry.getValue(),
                            schema.getAsJsonObject("additionalProperties"),
                            root,
                            path);
                else
                    assertTrue(
                            path + " unexpected field " + entry.getKey(),
                            props.has(entry.getKey())
                                    || !schema.has("properties")
                                    || schema.has("additionalProperties")
                                            && schema.get("additionalProperties").getAsBoolean());
            }
        }
        if (v.isJsonArray() && schema.has("items"))
            for (JsonElement item : v.getAsJsonArray())
                validate(item, schema.getAsJsonObject("items"), root, path + "[]");
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && schema.has("minimum"))
            assertTrue(path, v.getAsDouble() >= schema.get("minimum").getAsDouble());
    }

    private static boolean contains(JsonArray array, JsonElement value) {
        for (JsonElement e : array) if (e.equals(value)) return true;
        return false;
    }
}
