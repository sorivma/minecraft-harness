package dev.minecraftharness.chat;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.Value;
import java.util.*;
import org.junit.Test;

public class JsonTextTest {
    private static final int PLAIN = 0xffeeeeee;

    @Test
    public void formatsNestedJsonWithoutChangingNumbersEscapesOrDuplicateKeys() {
        String source =
                " {\"position\":[-12,6.25e+2],\"name\":\"a\\\"b\\n\\u0410\","
                        + "\"large\":9007199254740993,\"large\":-0,\"empty\":{},\"ready\":true,\"value\":null} ";
        JsonText json = JsonText.format(source);
        assertEquals(
                "{\n  \"position\": [\n    -12,\n    6.25e+2\n  ],\n"
                        + "  \"name\": \"a\\\"b\\n\\u0410\",\n  \"large\": 9007199254740993,\n"
                        + "  \"large\": -0,\n  \"empty\": {},\n  \"ready\": true,\n  \"value\": null\n}",
                json.text);
        assertColor(json, "\"position\"", JsonText.KEY);
        assertColor(json, "a\\\"b", JsonText.STRING);
        assertColor(json, "6.25e+2", JsonText.NUMBER);
        assertColor(json, "true", JsonText.LITERAL);
        assertColor(json, "null", JsonText.LITERAL);
        assertColor(json, "{", PLAIN);
    }

    @Test
    public void invalidOrPartialJsonAndOrdinaryOutputAreUnchangedAndUncolored() {
        for (String source :
                Arrays.asList(
                        "",
                        "Working...",
                        "{\"a\":",
                        "{\"a\":1,}",
                        "[01]",
                        "[-]",
                        "[1.]",
                        "[1e+]",
                        "[true false]",
                        "[false,]",
                        "NaN",
                        "{a:1}",
                        "{'a':1}",
                        "\"\\x\"",
                        "\"\\u0zz0\"",
                        "\"line\nbreak\"",
                        "\"unfinished",
                        "{} trailing text",
                        "/* comment */ {}",
                        "{}\u0000")) {
            JsonText json = JsonText.format(source);
            assertEquals(source, json.text);
            for (JsonText.Span span : json.spans(0, json.text.length(), PLAIN))
                assertEquals(source, PLAIN, span.color);
        }
    }

    @Test
    public void wrappedStringsRetainTheirColorAndOffsetsOnEveryPage() {
        JsonText json =
                JsonText.format("{\"label\":\"first 😀 second third fourth\",\"ok\":false}");
        RecordingSurface surface = new RecordingSurface();
        StringBuilder copied = new StringBuilder();
        int at = 0;
        while (at < json.text.length()) {
            TextWindow page = new TextWindow(surface, json.text, at, 48, 2);
            assertTrue(page.end > at);
            for (int i = 0; i < page.lines.size(); i++) {
                int begin = page.starts.get(i), end = begin + page.lines.get(i).length();
                int position = begin;
                for (JsonText.Span span : json.spans(begin, end, PLAIN)) {
                    assertEquals(position, span.start);
                    assertFalse(Character.isLowSurrogate(json.text.charAt(span.start)));
                    position = span.end;
                }
                assertEquals(end, position);
            }
            copied.append(json.text, at, page.end);
            at = page.end;
        }
        assertEquals(json.text, copied.toString());
        assertColor(json, "second", JsonText.STRING);
        assertColor(json, "false", JsonText.LITERAL);
    }

    @Test
    public void structuredArgumentsAreJsonAndKeepExactFieldNamesAndEscapedStrings() {
        Map<String, Value> arguments = new LinkedHashMap<String, Value>();
        arguments.put("sessionId", Value.text("world\n\"one\"\\"));
        arguments.put("enabled", Value.bool(false));
        arguments.put("optional", Value.empty());
        arguments.put(
                "positions",
                Value.array(
                        Arrays.asList(
                                Value.number(3),
                                Value.object(Collections.<String, Value>emptyMap()))));
        Item item =
                new Item(
                        "m",
                        "t",
                        Kind.TOOL,
                        State.COMPLETED,
                        "MCP",
                        "{\"ok\":true}",
                        Collections.singletonMap("arguments", Value.object(arguments)),
                        Collections.<Resource>emptyList(),
                        null);
        JsonText json = JsonText.tool(item);
        assertTrue(json.text.startsWith("{\n  \"ok\": true\n}\n\nDetails\n{\n  \"arguments\": {"));
        assertTrue(json.text.contains("\"sessionId\": \"world\\u000a\\\"one\\\"\\\\\""));
        assertTrue(json.text.contains("\"optional\": null"));
        assertColor(json, "\"arguments\"", JsonText.KEY);
        assertColor(json, "false", JsonText.LITERAL);
        assertColor(json, "3.0", JsonText.NUMBER);
        assertColor(json, "Details", PLAIN);
    }

    @Test
    public void deepAndOversizedOutputFallsBackWithoutTruncating() {
        String nested =
                String.join("", Collections.nCopies(2000, "["))
                        + "0"
                        + String.join("", Collections.nCopies(2000, "]"));
        assertEquals(nested, JsonText.format(nested).text);
        String large = "[\"" + String.join("", Collections.nCopies(1024 * 1024, "x")) + "\"]";
        assertEquals(large, JsonText.format(large).text);
    }

    private void assertColor(JsonText json, String value, int color) {
        int begin = json.text.indexOf(value);
        assertTrue(value, begin >= 0);
        List<JsonText.Span> spans = json.spans(begin, begin + value.length(), PLAIN);
        assertEquals(value, 1, spans.size());
        assertEquals(begin, spans.get(0).start);
        assertEquals(begin + value.length(), spans.get(0).end);
        assertEquals(value, color, spans.get(0).color);
    }
}
