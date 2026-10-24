package dev.minecraftharness.chat;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.text.*;
import java.util.*;

/** Presentation models for command screens. Raw diagnostics remain an explicit secondary view. */
final class InfoPage {
    static final class Card {
        final String title;
        final Map<String, String> fields = new LinkedHashMap<String, String>();
        double percent = -1;
        boolean warning;

        Card(String title) {
            this.title = title;
        }

        Card field(String label, String value) {
            fields.put(label, value == null || value.isEmpty() ? "Not reported" : value);
            return this;
        }
    }

    final String title, subtitle;
    final List<Card> cards = new ArrayList<Card>();

    InfoPage(String title, String subtitle) {
        this.title = title;
        this.subtitle = subtitle;
    }

    Card card(String title) {
        Card card = new Card(title);
        cards.add(card);
        return card;
    }

    static String label(String value) {
        if (value == null || value.isEmpty()) return "Details";
        String result =
                value.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ');
        result =
                result.substring(0, 1).toUpperCase(Locale.ROOT)
                        + result.substring(1).toLowerCase(Locale.ROOT);
        return result.replace("Mcp", "MCP").replace("Oauth", "OAuth").replace("Api ", "API ");
    }

    static String scalar(Value value) {
        if (value == null || value.type == Value.Type.EMPTY) return "Not reported";
        if (value.type == Value.Type.BOOLEAN) return value.bool() ? "Yes" : "No";
        if (value.type == Value.Type.NUMBER)
            return NumberFormat.getNumberInstance(Locale.US).format(value.number());
        if (value.type == Value.Type.LIST) return value.array().size() + " entries";
        if (value.type == Value.Type.OBJECT) return value.object().size() + " entries";
        if (value.type == Value.Type.RESOURCE) return value.resource().name;
        return value.text();
    }

    static InfoPage result(String action, ActionResult result) {
        InfoPage page =
                new InfoPage(
                        result.title,
                        "Overview · select Technical details for the complete response");
        if (result.text != null && !result.text.trim().isEmpty())
            page.card("Result").field("Summary", result.text);
        if (action.equals("mcp")) {
            Value data = result.fields.get("data");
            if (data != null && data.type == Value.Type.LIST) {
                for (Value entry : data.array())
                    if (entry.type == Value.Type.OBJECT) {
                        Map<String, Value> server = entry.object();
                        Card card = page.card(scalar(server.get("name")));
                        card.field("Authentication", scalar(server.get("authStatus")));
                        card.field("Tools", count(server.get("tools")));
                        card.field("Resources", count(server.get("resources")));
                        Value error = server.get("error");
                        if (error != null && error.type != Value.Type.EMPTY) {
                            card.warning = true;
                            card.field("Issue", scalar(error));
                        }
                    }
            }
        }
        if (page.cards.isEmpty()) append(page, result.fields, "Overview", 0);
        if (page.cards.isEmpty())
            page.card("No entries").field("Status", "No information was returned");
        if (result.fields.containsKey("nextCursor")
                && result.fields.get("nextCursor").type != Value.Type.EMPTY)
            page.card("More available").field("Catalog", "Use Next page to browse more entries.");
        return page;
    }

    private static String count(Value value) {
        if (value == null || value.type == Value.Type.EMPTY) return "Not reported";
        if (value.type == Value.Type.OBJECT) return Integer.toString(value.object().size());
        if (value.type == Value.Type.LIST) return Integer.toString(value.array().size());
        return scalar(value);
    }

    private static void append(InfoPage page, Map<String, Value> values, String title, int depth) {
        if (page.cards.size() >= 100 || depth > 5) return;
        String name = null;
        for (String key : Arrays.asList("name", "displayName", "title", "email")) {
            Value v = values.get(key);
            if (v != null && v.type == Value.Type.TEXT) {
                name = v.text();
                break;
            }
        }
        Card card = new Card(name == null ? title : name);
        Value percent = values.get("usedPercent");
        if (percent != null && percent.type == Value.Type.NUMBER)
            card.percent = Math.max(0, Math.min(100, percent.number()));
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            String key = entry.getKey();
            Value v = entry.getValue();
            if (v.type == Value.Type.OBJECT
                    || v.type == Value.Type.LIST
                    || v.type == Value.Type.EMPTY
                    || Arrays.asList("nextCursor", "id", "threadId", "turnId", "type")
                            .contains(key)) continue;
            String rendered = scalar(v);
            if (key.equals("resetsAt") && v.type == Value.Type.NUMBER)
                rendered =
                        new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                                .format(new Date((long) (v.number() * 1000)));
            if (key.equals("usedPercent")) rendered = scalar(v) + "% used";
            if (key.equals("windowDurationMins")) rendered = scalar(v) + " min";
            card.field(label(key), rendered);
            if ((key.equals("error") || key.equals("failureReason")) && !rendered.isEmpty())
                card.warning = true;
        }
        if (!card.fields.isEmpty()) page.cards.add(card);
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            Value v = entry.getValue();
            if (v.type == Value.Type.OBJECT)
                append(page, v.object(), label(entry.getKey()), depth + 1);
            else if (v.type == Value.Type.LIST) {
                int i = 0;
                List<String> scalars = new ArrayList<String>();
                for (Value child : v.array()) {
                    if (child.type == Value.Type.OBJECT)
                        append(
                                page,
                                child.object(),
                                label(entry.getKey()) + " · " + (++i),
                                depth + 1);
                    else scalars.add(scalar(child));
                }
                if (!scalars.isEmpty())
                    page.card(label(entry.getKey())).field("Entries", String.join(", ", scalars));
            }
        }
        if (page.cards.size() >= 100 && depth == 0)
            page.card("Additional entries")
                    .field("Details", "Open Technical details to read all entries.");
    }
}
