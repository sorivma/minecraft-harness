package dev.minecraftharness.chat;

import dev.minecraftharness.agent.Value;
import java.util.*;

/** Human-readable sections, not a transport/JSON dump. */
final class ReadableValues {
    static String format(Map<String, Value> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Value> entry : values.entrySet())
            append(out, entry.getKey(), entry.getValue(), 0);
        return out.toString();
    }

    private static void append(StringBuilder out, String label, Value value, int depth) {
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(label.replaceAll("([a-z])([A-Z])", "$1 $2")).append(':');
        if (value.type == Value.Type.OBJECT) {
            out.append('\n');
            for (Map.Entry<String, Value> entry : value.object().entrySet())
                append(out, entry.getKey(), entry.getValue(), depth + 1);
        } else if (value.type == Value.Type.LIST) {
            out.append('\n');
            int i = 0;
            for (Value entry : value.array()) append(out, Integer.toString(i++), entry, depth + 1);
        } else
            out.append(' ')
                    .append(value.type == Value.Type.TEXT ? value.text() : value.toString())
                    .append('\n');
    }
}
