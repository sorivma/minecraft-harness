package dev.minecraftharness.chat.widget;

import java.util.*;

/** Immutable, bounded planning snapshots. Payload parsing never accesses the world. */
final class PlanningWidgets {
    private static final int WHITE = 0xffeeeeee,
            BLUE = 0xff8fc9ff,
            GREEN = 0xff85cf91,
            RED = 0xffffab85;
    private static final String COMMON =
            "First line: title (1-120 characters). Second line: source|measured, source|user-provided, or source|estimated. "
                    + "Remaining lines use the schema below, separated by |; no escaping or embedded pipes. Maximum 32 records, each field 1-120 characters. "
                    + "Item IDs must be namespaced; metadata 0-32767. Counts are integers 0-1000000 (positive where required). "
                    + "Snapshots only: never invent inventory, recipes, locations, rates or completion states. Use estimated for plans, and ? for unknown supported fields. ";

    static void register(WidgetRegistry r) {
        add(
                r,
                "materials",
                "Rows: itemId|label|metadata|required|have-or-?|stackSize (1-64). Required is positive. Example:\nStarter supplies\nsource|estimated\nminecraft:planks|Planks|0|80|?|64");
        add(
                r,
                "machine",
                "First record: machine|name|duration|energy. Then input, output, fluid-in, fluid-out or byproduct records: kind|itemId|label|metadata|positiveCount; fluids instead use kind|label|amount-and-unit; byproducts add |chance-percent (0-100). At least one input/fluid-in and output/fluid-out/byproduct. Example:\nSmelting\nsource|user-provided\nmachine|Furnace|10 s|Unknown\ninput|minecraft:iron_ore|Iron ore|0|1\noutput|minecraft:iron_ingot|Iron ingot|0|1");
        add(
                r,
                "chain",
                "Ordered steps: step|action|itemId|outputLabel|metadata|positiveOutputCount|dependencies. Dependencies are comma-separated earlier step numbers (1-based), or -. Include input quantities in action. Example:\nPlanks to sticks\nsource|estimated\nstep|Craft 1 log|minecraft:planks|Planks|0|4|-\nstep|Craft 2 planks|minecraft:stick|Sticks|0|4|1");
        add(
                r,
                "layer",
                "First record: layer|integerY (-2048..2048)|north-edge (top/right/bottom/left). Then legend records: legend|single ASCII letter/digit|itemId|label|metadata. Then row|cells records: rectangular grid, maximum 16 by 16. Dot means air; every other cell must have a legend. Example:\nFoundation\nsource|estimated\nlayer|64|top\nlegend|S|minecraft:stone|Stone|0\nrow|SSS\nrow|S.S\nrow|SSS");
        add(
                r,
                "comparison",
                "First 2-3 records: item|itemId|label|metadata. Then 1-16 records: stat|name|value-for-item-1|value-for-item-2[|value-for-item-3]. Include units and tradeoffs. Example:\nBuilding blocks\nsource|estimated\nitem|minecraft:stone|Stone|0\nitem|minecraft:planks|Planks|0\nstat|Appearance|Grey|Wood grain");
        add(
                r,
                "production",
                "First record: unit|shared rate unit. Rows: resource|name|supplyRate|demandRate|machineCount-or-?. Rates are finite nonnegative decimals <=1000000; machine count positive. Supply below demand is a bottleneck. Example:\nSmelting balance\nsource|estimated\nunit|items/min\nresource|Ore|30|60|2");
        add(
                r,
                "waypoint",
                "Exactly two records: location|dimension|x|y|z (integer coordinates, x/z +/-30000000, y +/-2048), then note|text. Example:\nHome\nsource|user-provided\nlocation|Overworld|120|64|-80\nnote|Main entrance");
        add(
                r,
                "progression",
                "Ordered rows: status|milestone|dependencies. Status is todo, doing, done, or unknown. Dependencies are earlier row numbers comma-separated, or -. Example:\nStarter base\nsource|estimated\ntodo|Gather wood|-\nunknown|Build shelter|1");
        add(
                r,
                "shapeless",
                "First record: output|itemId|label|metadata|positiveCount. Then 1-9 records: input|itemId|label|metadata|positiveCount. Input order has no significance. Example:\nPlanks\nsource|estimated\noutput|minecraft:planks|Planks|0|4\ninput|minecraft:log|Oak log|0|1");
    }

    private static void add(WidgetRegistry r, String kind, String schema) {
        r.register("minecraftharness:" + kind + "-v1", COMMON + schema, p -> parse(kind, p));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid widget payload");
    }

    private static int integer(String s, int min, int max) {
        require(s.matches("-?[0-9]{1,9}"));
        int n = Integer.parseInt(s);
        require(n >= min && n <= max);
        return n;
    }

    private static double rate(String s) {
        require(s.matches("[0-9]{1,7}(\\.[0-9]{1,6})?"));
        double n = Double.parseDouble(s);
        require(n <= 1000000);
        return n;
    }

    private static void size(String[] f, int n) {
        require(f.length == n);
    }

    private static void id(String s) {
        require(s.matches("[a-z0-9_.-]+:[a-zA-Z0-9_./-]+"));
    }

    private static void dependencies(String s, int current) {
        if (s.equals("-")) return;
        Set<Integer> seen = new HashSet<Integer>();
        for (String d : s.split(",", -1)) require(seen.add(integer(d, 1, current - 1)));
    }

    private static Icon icon(String[] f, int start) {
        id(f[start]);
        return new Icon(f[start], integer(f[start + 2], 0, 32767));
    }

    private static String quantity(int count, int stack) {
        return count
                + " ("
                + (count / stack)
                + " stacks + "
                + (count % stack)
                + ", stack size "
                + stack
                + ")";
    }

    private static Widget parse(String kind, String payload) {
        try {
            return decode(kind, payload);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Widget decode(String kind, String payload) {
        require(payload.length() <= 16000);
        String[] lines = payload.split("\n", -1);
        require(lines.length >= 3 && lines.length <= 34);
        require(
                !lines[0].trim().isEmpty()
                        && lines[0].length() <= 120
                        && !lines[0].contains("|")
                        && !lines[0].matches(".*[\\p{Cntrl}].*"));
        require(lines[1].matches("source\\|(measured|user-provided|estimated)"));
        List<String[]> records = new ArrayList<String[]>();
        for (int i = 2; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            for (String v : f)
                require(!v.trim().isEmpty() && v.length() <= 120 && !v.matches(".*[\\p{Cntrl}].*"));
            records.add(f);
        }
        Card c = new Card(lines[0], lines[1].substring(7));
        if (kind.equals("materials")) {
            for (String[] f : records) {
                size(f, 6);
                Icon item = icon(f, 0);
                int need = integer(f[3], 1, 1000000), stack = integer(f[5], 1, 64);
                String have = "unknown", missing = "unknown";
                double fraction = -1;
                if (!f[4].equals("?")) {
                    int n = integer(f[4], 0, 1000000);
                    have = quantity(n, stack);
                    missing = quantity(Math.max(0, need - n), stack);
                    fraction = Math.min(1, (double) n / need);
                }
                c.row(f[1] + ": need " + quantity(need, stack), item, WHITE, -1);
                c.row("Have " + have + "; missing " + missing, null, BLUE, fraction);
            }
        } else if (kind.equals("machine") || kind.equals("shapeless")) {
            boolean machine = kind.equals("machine"), input = false, output = false;
            int start = 0;
            if (machine) {
                String[] f = records.get(0);
                size(f, 4);
                require(f[0].equals("machine"));
                c.row(f[1] + " | Time: " + f[2] + " | Energy: " + f[3]);
                start = 1;
            } else {
                require(records.size() >= 2 && records.size() <= 10);
                c.row("Shapeless: ingredient positions do not matter");
            }
            for (int i = start; i < records.size(); i++) {
                String[] f = records.get(i);
                String role = f[0];
                if (!machine) require(role.equals(i == 0 ? "output" : "input"));
                require(
                        Arrays.asList("input", "output", "fluid-in", "fluid-out", "byproduct")
                                .contains(role));
                input |= role.equals("input") || role.equals("fluid-in");
                output |=
                        role.equals("output")
                                || role.equals("fluid-out")
                                || role.equals("byproduct");
                if (role.startsWith("fluid-")) {
                    size(f, 3);
                    c.row(role + ": " + f[1] + " " + f[2]);
                } else {
                    size(f, role.equals("byproduct") ? 6 : 5);
                    Icon item = icon(f, 1);
                    int n = integer(f[4], 1, 1000000);
                    String chance =
                            role.equals("byproduct")
                                    ? " (" + integer(f[5], 0, 100) + "% chance)"
                                    : "";
                    c.row(
                            role + ": " + n + " x " + f[2] + chance,
                            item,
                            role.equals("input") ? WHITE : GREEN,
                            -1);
                }
            }
            require(input && output);
        } else if (kind.equals("chain")) {
            int step = 0;
            for (String[] f : records) {
                size(f, 7);
                require(f[0].equals("step"));
                Icon item = icon(f, 2);
                int n = integer(f[5], 1, 1000000);
                dependencies(f[6], ++step);
                c.row(step + ". " + f[1] + " -> " + n + " x " + f[3], item, WHITE, -1);
                c.row("Requires steps: " + f[6]);
            }
        } else if (kind.equals("layer")) {
            String[] header = records.get(0);
            size(header, 3);
            require(header[0].equals("layer"));
            integer(header[1], -2048, 2048);
            require(Arrays.asList("top", "right", "bottom", "left").contains(header[2]));
            c.row("Layer Y=" + header[1] + "; north: " + header[2] + "; . = air");
            Map<Character, Icon> legend = new LinkedHashMap<Character, Icon>();
            List<String> grid = new ArrayList<String>();
            for (int i = 1; i < records.size(); i++) {
                String[] f = records.get(i);
                if (f[0].equals("legend")) {
                    size(f, 5);
                    require(grid.isEmpty() && f[1].matches("[A-Za-z0-9]"));
                    Icon item = icon(f, 2);
                    require(!legend.containsKey(f[1].charAt(0)));
                    legend.put(f[1].charAt(0), item);
                    c.row(f[1] + " = " + f[3], item, WHITE, -1);
                } else {
                    size(f, 2);
                    require(f[0].equals("row") && f[1].length() <= 16);
                    if (!grid.isEmpty()) require(f[1].length() == grid.get(0).length());
                    for (char cell : f[1].toCharArray())
                        require(cell == '.' || legend.containsKey(cell));
                    grid.add(f[1]);
                }
            }
            require(!grid.isEmpty() && grid.size() <= 16);
            c.grid = grid;
            c.legend = legend;
        } else if (kind.equals("comparison")) {
            List<String> names = new ArrayList<String>();
            boolean stats = false;
            int count = 0;
            for (String[] f : records) {
                if (f[0].equals("item")) {
                    size(f, 4);
                    require(!stats && names.size() < 3);
                    Icon item = icon(f, 1);
                    names.add(f[2]);
                    c.row((names.size()) + ". " + f[2], item, BLUE, -1);
                } else {
                    require(names.size() >= 2 && f[0].equals("stat") && ++count <= 16);
                    size(f, names.size() + 2);
                    stats = true;
                    if (count == 1) {
                        List<String> header = new ArrayList<String>();
                        header.add("Attribute");
                        header.addAll(names);
                        c.tableRow(header.toArray(new String[0]), BLUE);
                    }
                    c.tableRow(Arrays.copyOfRange(f, 1, f.length), WHITE);
                }
            }
            require(stats);
        } else if (kind.equals("production")) {
            String[] unit = records.get(0);
            size(unit, 2);
            require(unit[0].equals("unit") && records.size() > 1);
            c.row("Rates: " + unit[1]);
            for (int i = 1; i < records.size(); i++) {
                String[] f = records.get(i);
                size(f, 5);
                require(f[0].equals("resource"));
                double supply = rate(f[2]), demand = rate(f[3]);
                if (!f[4].equals("?")) integer(f[4], 1, 1000000);
                c.row(
                        f[1]
                                + ": supply "
                                + f[2]
                                + ", demand "
                                + f[3]
                                + " "
                                + unit[1]
                                + "; machines: "
                                + (f[4].equals("?") ? "unknown" : f[4]),
                        null,
                        WHITE,
                        -1);
                c.row(
                        supply < demand
                                ? "BOTTLENECK: deficit " + decimal(demand - supply) + " " + unit[1]
                                : "Surplus: " + decimal(supply - demand) + " " + unit[1],
                        null,
                        supply < demand ? RED : GREEN,
                        demand == 0 ? 1 : Math.min(1, supply / demand));
            }
        } else if (kind.equals("waypoint")) {
            require(records.size() == 2);
            String[] f = records.get(0);
            size(f, 5);
            require(f[0].equals("location"));
            integer(f[2], -30000000, 30000000);
            integer(f[3], -2048, 2048);
            integer(f[4], -30000000, 30000000);
            c.row(f[1], null, BLUE, -1);
            c.row("X: " + f[2] + "   Y: " + f[3] + "   Z: " + f[4]);
            String[] note = records.get(1);
            size(note, 2);
            require(note[0].equals("note"));
            c.row(note[1]);
        } else if (kind.equals("progression")) {
            int step = 0;
            for (String[] f : records) {
                size(f, 3);
                require(Arrays.asList("todo", "doing", "done", "unknown").contains(f[0]));
                dependencies(f[2], ++step);
                c.row(
                        step + ". [" + f[0] + "] " + f[1],
                        null,
                        f[0].equals("done") ? GREEN : WHITE,
                        -1);
                c.row("Prerequisites: " + f[2]);
            }
        } else throw new IllegalArgumentException("Unknown widget kind");
        return c;
    }

    private static String decimal(double value) {
        return java.math.BigDecimal.valueOf(value)
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static final class Icon {
        final String id;
        final int metadata;

        Icon(String id, int metadata) {
            this.id = id;
            this.metadata = metadata;
        }
    }

    private static final class Row {
        final String text;
        final Icon icon;
        final int color;
        final double fraction;
        final String[] columns;

        Row(String text, Icon icon, int color, double fraction) {
            this(text, icon, color, fraction, null);
        }

        Row(String text, Icon icon, int color, double fraction, String[] columns) {
            this.text = text;
            this.icon = icon;
            this.color = color;
            this.fraction = fraction;
            this.columns = columns;
        }
    }

    private static final class Card implements Widget {
        final String title;
        final List<Row> rows = new ArrayList<Row>();
        List<String> grid = Collections.emptyList();
        Map<Character, Icon> legend = Collections.emptyMap();

        Card(String title, String source) {
            this.title = title;
            row("Source: " + source + " (snapshot)");
        }

        void row(String text) {
            row(text, null, WHITE, -1);
        }

        void row(String text, Icon icon, int color, double fraction) {
            rows.add(new Row(text, icon, color, fraction));
        }

        void tableRow(String[] columns, int color) {
            rows.add(new Row(String.join(" | ", columns), null, color, -1, columns));
        }

        public String title() {
            return title;
        }

        public String plainText() {
            StringBuilder out = new StringBuilder();
            for (Row row : rows) out.append(row.text).append('\n');
            for (String line : grid) out.append(line).append('\n');
            return out.toString().trim();
        }

        public int height(int width) {
            return height(width, 9, s -> s.codePointCount(0, s.length()) * 6);
        }

        public boolean includesText() {
            return true;
        }

        public int height(
                int width, int lineHeight, java.util.function.ToIntFunction<String> measure) {
            if (width < 120) return 0;
            if (!grid.isEmpty() && cellSize(width) < lineHeight + 2) return 0;
            int height = grid.size() * cellSize(width);
            for (Row row : rows) height += layout(row, width, lineHeight, measure).height;
            return height > 4096 ? 0 : height;
        }

        private int cellSize(int width) {
            return grid.isEmpty() ? 20 : Math.min(20, width / grid.get(0).length());
        }

        public void render(WidgetCanvas canvas) {
            int y = 0;
            for (Row row : rows) {
                RowLayout layout =
                        layout(row, canvas.width(), canvas.lineHeight(), canvas::textWidth);
                if (row.columns != null) {
                    int cellWidth = canvas.width() / row.columns.length;
                    for (int i = 0; i < row.columns.length; i++) {
                        canvas.rectangle(
                                i * cellWidth, y, cellWidth - 2, layout.height - 2, 0xff30363e);
                        drawLines(canvas, layout.cells.get(i), i * cellWidth + 3, y + 3, row.color);
                    }
                    y += layout.height;
                    continue;
                }
                int x = 0;
                if (row.icon != null) {
                    canvas.rectangle(0, y, 18, 18, 0xff555555);
                    if (!canvas.item(row.icon.id, row.icon.metadata, 1, 1, y + 1))
                        canvas.text("?", 5, y + 4, BLUE);
                    x = 22;
                }
                drawLines(canvas, layout.cells.get(0), x, y + 3, row.color);
                if (row.fraction >= 0) {
                    canvas.rectangle(x, y + layout.height - 5, canvas.width() - x, 3, 0xff454545);
                    canvas.rectangle(
                            x,
                            y + layout.height - 5,
                            (int) ((canvas.width() - x) * row.fraction),
                            3,
                            row.color);
                }
                y += layout.height;
            }
            int cellSize = cellSize(canvas.width());
            for (String line : grid) {
                int x = 0;
                for (char cell : line.toCharArray()) {
                    canvas.rectangle(x, y, cellSize - 2, cellSize - 2, 0xff454545);
                    Icon icon = legend.get(cell);
                    if (icon == null
                            || cellSize < 18
                            || !canvas.item(icon.id, icon.metadata, 1, x + 1, y + 1))
                        canvas.text(String.valueOf(cell), x + 1, y + 1, BLUE);
                    x += cellSize;
                }
                y += cellSize;
            }
        }

        private void drawLines(WidgetCanvas canvas, List<String> lines, int x, int y, int color) {
            for (String text : lines) {
                canvas.text(text, x, y, color);
                y += canvas.lineHeight() + 2;
            }
        }

        private RowLayout layout(
                Row row,
                int width,
                int lineHeight,
                java.util.function.ToIntFunction<String> measure) {
            List<List<String>> cells = new ArrayList<List<String>>();
            int lines = 0;
            if (row.columns != null)
                for (String cell : row.columns) {
                    List<String> wrapped = wrap(cell, width / row.columns.length - 8, measure);
                    cells.add(wrapped);
                    lines = Math.max(lines, wrapped.size());
                }
            else {
                List<String> wrapped = wrap(row.text, width - (row.icon == null ? 0 : 22), measure);
                cells.add(wrapped);
                lines = wrapped.size();
            }
            return new RowLayout(
                    cells,
                    Math.max(row.icon == null ? 0 : 18, lines * (lineHeight + 2))
                            + 6
                            + (row.fraction >= 0 ? 5 : 0));
        }

        private List<String> wrap(
                String text, int width, java.util.function.ToIntFunction<String> measure) {
            List<String> result = new ArrayList<String>();
            int start = 0;
            while (start < text.length()) {
                int end = start, lastSpace = -1;
                while (end < text.length()) {
                    int next = end + Character.charCount(text.codePointAt(end));
                    if (end > start
                            && measure.applyAsInt(text.substring(start, next)) > Math.max(1, width))
                        break;
                    if (Character.isWhitespace(text.codePointAt(end))) lastSpace = next;
                    end = next;
                }
                if (end < text.length() && lastSpace > start) end = lastSpace;
                result.add(text.substring(start, end));
                start = end;
            }
            return result;
        }
    }

    private static final class RowLayout {
        final List<List<String>> cells;
        final int height;

        RowLayout(List<List<String>> cells, int height) {
            this.cells = cells;
            this.height = height;
        }
    }
}
