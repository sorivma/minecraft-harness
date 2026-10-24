package dev.minecraftharness.codex;

import static org.junit.Assert.*;

import com.google.gson.*;
import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.util.*;
import org.junit.Test;

public class RichFlowTest {
    @Test
    public void askQuestionsDynamicCallUsesInteractiveFormAndToolResponse() {
        JsonObject call =
                parse(
                        "{\"tool\":\"ask_questions\",\"arguments\":{\"questions\":[{\"id\":\"material\",\"question\":\"Which material?\",\"options\":[{\"label\":\"Stone\",\"description\":\"Durable\"}]}]}}");
        Form form = CodexForms.request("item/tool/call", call);
        Map<String, Value> fields = Collections.singletonMap("material", Value.text("Stone"));
        assertTrue(form.validate(fields).isEmpty());
        JsonObject response = CodexForms.answer("item/tool/call", fields);
        assertTrue(response.get("success").getAsBoolean());
        assertTrue(
                response.getAsJsonArray("contentItems")
                        .get(0)
                        .getAsJsonObject()
                        .get("text")
                        .getAsString()
                        .contains("Stone"));
    }

    private JsonObject parse(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    @Test
    public void webAndToolFailuresRemainHumanReadableAndLinked() {
        Item web =
                Wire.item(
                        parse(
                                "{\"id\":\"w\",\"type\":\"webSearch\",\"action\":{\"type\":\"findInPage\",\"url\":\"https://example.com/page\",\"pattern\":\"needle\"}}"),
                        "turn",
                        State.COMPLETED);
        assertEquals(Kind.WEB, web.kind);
        assertTrue(web.title.contains("Find"));
        assertTrue(web.text.contains("needle"));
        assertEquals(1, web.resources.size());
        Item failed =
                Wire.item(
                        parse(
                                "{\"id\":\"m\",\"type\":\"mcpToolCall\",\"server\":\"world\",\"tool\":\"inspect\",\"error\":{\"message\":\"Disconnected\"}}"),
                        "turn",
                        State.COMPLETED);
        assertEquals(State.FAILED, failed.state);
        assertEquals("Disconnected", failed.text);
    }

    @Test
    public void filesAndCommandsPreserveOutputAndHiddenReasoningIsExcluded() {
        Item diff =
                Wire.item(
                        parse(
                                "{\"id\":\"d\",\"type\":\"fileChange\",\"changes\":[{\"path\":\"one.txt\",\"diff\":\"+one\"},{\"path\":\"two.txt\",\"diff\":\"-two\"}]}"),
                        "turn",
                        State.COMPLETED);
        assertTrue(diff.text.contains("one.txt\n+one"));
        assertTrue(diff.text.contains("two.txt\n-two"));
        Item summary =
                Wire.item(
                        parse(
                                "{\"id\":\"s\",\"type\":\"reasoning\",\"summary\":[\"Public summary\"],\"content\":[\"PRIVATE\"]}"),
                        "turn",
                        State.COMPLETED);
        assertEquals("Public summary", summary.text);
        assertFalse(summary.details.toString().contains("PRIVATE"));
        Item command =
                Wire.item(
                        parse(
                                "{\"id\":\"c\",\"type\":\"commandExecution\",\"command\":\"echo hi\",\"cwd\":\"world\",\"aggregatedOutput\":\"hi\",\"exitCode\":0,\"durationMs\":12}"),
                        "turn",
                        State.COMPLETED);
        assertEquals("hi", command.text);
        assertEquals(Long.valueOf(12), command.elapsedMillis);
        assertTrue(command.details.containsKey("exitCode"));
    }

    @Test
    public void attachmentsAndMcpIntegerChoicesHaveExactWireTypes() {
        JsonArray input =
                Wire.input(
                        new Message(
                                "",
                                Arrays.asList(
                                        new Resource(Resource.Type.AUDIO, "voice", "voice.wav"),
                                        new Resource(Resource.Type.IMAGE, "image", "image.png"),
                                        new Resource(Resource.Type.SKILL, "builder", "SKILL.md"),
                                        new Resource(Resource.Type.APP, "app", "app://example"))));
        assertEquals("localAudio", input.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("mention", input.get(3).getAsJsonObject().get("type").getAsString());
        Form form =
                CodexForms.decode(
                        parse(
                                "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":1},\"choices\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"a\",\"b\"]},\"minItems\":1}},\"required\":[\"count\",\"choices\"]}"));
        Map<String, Value> values = new HashMap<String, Value>();
        values.put("count", Value.number(1.5));
        values.put("choices", Value.array(Arrays.asList(Value.text("a"))));
        assertTrue(form.validate(values).containsKey("count"));
        values.put("count", Value.number(2));
        assertTrue(form.validate(values).isEmpty());
    }
}
