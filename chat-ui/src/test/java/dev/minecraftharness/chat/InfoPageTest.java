package dev.minecraftharness.chat;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.util.*;
import org.junit.Test;

public class InfoPageTest {
    @Test
    public void mcpOverviewSummarizesCatalogAndRetainsPagination() {
        Map<String, Value> server = new LinkedHashMap<String, Value>();
        server.put("name", Value.text("World tools"));
        server.put("authStatus", Value.text("authenticated"));
        server.put(
                "tools",
                Value.object(
                        Collections.singletonMap(
                                "inspect", Value.object(Collections.<String, Value>emptyMap()))));
        Map<String, Value> result = new LinkedHashMap<String, Value>();
        result.put("data", Value.array(Arrays.asList(Value.object(server))));
        result.put("nextCursor", Value.text("opaque-page"));
        InfoPage page =
                InfoPage.result(
                        "mcp",
                        new ActionResult(
                                "MCP servers", "", result, Collections.<Resource>emptyList()));
        assertEquals("World tools", page.cards.get(0).title);
        assertEquals("1", page.cards.get(0).fields.get("Tools"));
        assertEquals("Not reported", page.cards.get(0).fields.get("Resources"));
        assertEquals("More available", page.cards.get(1).title);
        assertFalse(
                page.cards.stream()
                        .flatMap(card -> card.fields.values().stream())
                        .anyMatch(value -> value.contains("opaque-page")));
    }

    @Test
    public void limitBarsRequireReportedPercentAndMissingValuesAreNotInvented() {
        Map<String, Value> window = new LinkedHashMap<String, Value>();
        window.put("usedPercent", Value.number(42));
        window.put("windowDurationMins", Value.number(300));
        Map<String, Value> fields = Collections.singletonMap("primary", Value.object(window));
        InfoPage page =
                InfoPage.result(
                        "limits",
                        new ActionResult(
                                "Rate limits", "", fields, Collections.<Resource>emptyList()));
        assertEquals(42, page.cards.get(0).percent, 0);
        assertEquals("42% used", page.cards.get(0).fields.get("Used percent"));
        assertEquals(
                -1,
                InfoPage.result(
                                "limits",
                                new ActionResult(
                                        "Rate limits",
                                        "",
                                        Collections.<String, Value>emptyMap(),
                                        Collections.<Resource>emptyList()))
                        .cards
                        .get(0)
                        .percent,
                0);
    }
}
