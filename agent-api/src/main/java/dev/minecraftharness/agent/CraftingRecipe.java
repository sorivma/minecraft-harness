package dev.minecraftharness.agent;

import java.util.*;

/** Version-neutral, display-only recipe carried in assistant text and saved history. */
public final class CraftingRecipe {
    public static final String INSTRUCTIONS =
            "When explaining a shaped crafting recipe, you may include a visual widget using a fenced minecraft-recipe block. "
                    + "The block has exactly 11 lines: recipe title, output stack, then nine input slots in row-major order (top-left to bottom-right). "
                    + "A stack is registry_id|display name|count|metadata, e.g. minecraft:planks|Oak planks|4|0. An empty input slot is -. "
                    + "Counts are 1..64 and metadata 0..32767. Include explanatory prose outside the fence. Use one block per response. "
                    + "This is display only, not a crafting action or recipe lookup. Match the world's Minecraft version and modpack; "
                    + "verify uncertain recipes with available tools or say you are unsure instead of inventing them. Do not use this format for shapeless or machine recipes.";

    public static final class Stack {
        public final String id, label;
        public final int count, metadata;

        private Stack(String id, String label, int count, int metadata) {
            this.id = id;
            this.label = label;
            this.count = count;
            this.metadata = metadata;
        }
    }

    public final String title, prose;
    public final Stack output;
    public final List<Stack> slots;

    private CraftingRecipe(String title, String prose, Stack output, List<Stack> slots) {
        this.title = title;
        this.prose = prose;
        this.output = output;
        this.slots = Collections.unmodifiableList(slots);
    }

    /** Bounded parsing; incomplete or invalid blocks remain ordinary text. */
    public static CraftingRecipe parse(String text) {
        if (text.length() > 32768) return null;
        String normalized = text.replace("\r\n", "\n");
        String marker = "```minecraft-recipe\n";
        int start = normalized.indexOf(marker);
        if (start < 0 || (start > 0 && normalized.charAt(start - 1) != '\n')) return null;
        int end = normalized.indexOf("\n```", start + marker.length());
        if (end < 0 || (end + 4 < normalized.length() && normalized.charAt(end + 4) != '\n'))
            return null;
        String[] rows = normalized.substring(start + marker.length(), end).split("\n", -1);
        if (rows.length != 11 || rows[0].trim().isEmpty() || rows[0].length() > 120) return null;
        try {
            Stack output = stack(rows[1]);
            List<Stack> slots = new ArrayList<Stack>();
            boolean occupied = false;
            for (int i = 2; i < 11; i++) {
                Stack s = rows[i].trim().equals("-") ? null : stack(rows[i]);
                slots.add(s);
                occupied |= s != null;
            }
            if (!occupied) return null;
            return new CraftingRecipe(
                    rows[0].trim(),
                    (normalized.substring(0, start) + normalized.substring(end + 4)).trim(),
                    output,
                    slots);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Stack stack(String row) {
        String[] parts = row.split("\\|", -1);
        if (parts.length != 4
                || !parts[0].matches("[a-z0-9_.-]+:[a-zA-Z0-9_./-]+")
                || parts[1].trim().isEmpty()
                || parts[1].length() > 120) throw new IllegalArgumentException();
        int count = Integer.parseInt(parts[2]), metadata = Integer.parseInt(parts[3]);
        if (count < 1 || count > 64 || metadata < 0 || metadata > 32767)
            throw new IllegalArgumentException();
        return new Stack(parts[0], parts[1].trim(), count, metadata);
    }

    public String explanation() {
        StringBuilder result =
                new StringBuilder("Output: ")
                        .append(output.count)
                        .append(" x ")
                        .append(output.label)
                        .append('\n');
        for (int i = 0; i < 9; i++)
            if (slots.get(i) != null) {
                Stack s = slots.get(i);
                result.append("Row ")
                        .append(i / 3 + 1)
                        .append(", column ")
                        .append(i % 3 + 1)
                        .append(": ")
                        .append(s.count)
                        .append(" x ")
                        .append(s.label)
                        .append('\n');
            }
        return result.append('\n').append(prose).toString();
    }
}
