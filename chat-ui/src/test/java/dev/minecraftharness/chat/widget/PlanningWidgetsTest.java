package dev.minecraftharness.chat.widget;

import static org.junit.Assert.*;

import dev.minecraftharness.chat.RecordingSurface;
import org.junit.Test;

public class PlanningWidgetsTest {
    private final WidgetRegistry registry = WidgetRegistry.defaults().snapshot();

    private String block(String kind, String body) {
        return "```minecraftharness:" + kind + "-v1\nExample\nsource|estimated\n" + body + "\n```";
    }

    private WidgetRegistry.Entry entry(String kind, String body) {
        WidgetRegistry.Message message = registry.parse(block(kind, body));
        assertEquals(kind, 1, message.widgets.size());
        return message.widgets.get(0);
    }

    private void invalid(String kind, String body) {
        String text = block(kind, body);
        WidgetRegistry.Message result = registry.parse(text);
        assertTrue(kind + ": " + body, result.widgets.isEmpty());
        assertEquals(text, result.prose);
    }

    @Test
    public void narrowCardsWrapAllValuesInsteadOfEllipsizingThem() {
        WidgetRegistry.Entry e =
                entry(
                        "comparison",
                        "item|minecraft:planks|Oak planks|0\nitem|minecraft:cobblestone|Cobblestone|0\nstat|Suitable mining tool|Axe|Pickaxe");
        RecordingSurface s = new RecordingSurface();
        e.render(new WidgetCanvas(s, 0, 0, 300, e.height(300)));
        assertFalse(
                "Comparison labels must not be cut off",
                s.drawn.stream().anyMatch(v -> v.contains("...")));
        assertTrue(
                "Full label should survive wrapping",
                String.join("", s.drawn).contains("Suitable mining tool"));
    }

    @Test
    public void everyAdvertisedExampleParsesRendersAndSurvivesHistory() {
        String instructions = registry.instructions();
        int count = 0;
        for (String section : instructions.split("\nWidget ")) {
            int example = section.indexOf("Example:\n");
            if (example < 0) continue;
            String id = section.substring(0, section.indexOf(":\n"));
            String payload = section.substring(example + 9).trim();
            String original = "```" + id + "\n" + payload + "\n```";
            WidgetRegistry.Message message = registry.parse(original);
            assertEquals(id, 1, message.widgets.size());
            WidgetRegistry.Entry e = message.widgets.get(0);
            assertTrue(e.text.contains("Source:"));
            assertEquals(0, e.height(80));
            assertTrue(e.height(400) > 0);
            assertTrue(e.height(400) <= 4096);
            RecordingSurface surface = new RecordingSurface();
            e.render(new WidgetCanvas(surface, 0, 0, 400, e.height(400)));
            assertFalse(surface.drawn.isEmpty());
            assertEquals(0, surface.clips);
            assertEquals(
                    message.plainText(),
                    WidgetRegistry.defaults().parse(original.replace("\n", "\r\n")).plainText());
            count++;
        }
        assertEquals(9, count);
    }

    @Test
    public void materialsComputeDeficitsAndPreserveUnknownCountsAndStackSizes() {
        WidgetRegistry.Entry e =
                entry(
                        "materials",
                        "minecraft:planks|Planks|0|80|17|64\nminecraft:egg|Egg|0|20|?|16");
        assertTrue(e.text.contains("missing 63 (0 stacks + 63, stack size 64)"));
        assertTrue(e.text.contains("Have unknown; missing unknown"));
        assertTrue(e.text.contains("20 (1 stacks + 4, stack size 16)"));
        assertTrue(entry("materials", "minecraft:stone|Stone|0|1|9|64").text.contains("missing 0"));
        invalid("materials", "minecraft:stone|Stone|0|0|?|64");
        invalid("materials", "minecraft:stone|Stone|0|1|-1|64");
        invalid("materials", "minecraft:stone|Stone|0|1|?|0");
    }

    @Test
    public void machineSupportsFluidsAndChanceByproductsAndRequiresBothSides() {
        WidgetRegistry.Entry e =
                entry(
                        "machine",
                        "machine|Washer|5 s|20 RF/t\nfluid-in|Water|1000 mB\ninput|test:ore|Ore|0|1\noutput|test:clean|Clean ore|0|2\nbyproduct|test:dust|Dust|0|1|25\nfluid-out|Slurry|500 mB");
        assertTrue(e.text.contains("25% chance"));
        assertTrue(e.text.contains("1000 mB"));
        invalid("machine", "machine|Washer|5 s|20 RF/t\ninput|test:ore|Ore|0|1");
        invalid(
                "machine",
                "machine|Washer|5 s|20 RF/t\ninput|test:ore|Ore|0|1\nbyproduct|test:dust|Dust|0|1|101");
    }

    @Test
    public void chainsAndProgressionRejectCyclesForwardAndDuplicateDependencies() {
        entry(
                "chain",
                "step|Craft 1 log|minecraft:planks|Planks|0|4|-\nstep|Craft 2 planks|minecraft:stick|Sticks|0|4|1");
        invalid("chain", "step|Craft|minecraft:planks|Planks|0|4|1");
        invalid("progression", "todo|First|2\ndone|Second|-");
        invalid("progression", "todo|First|-\ndoing|Second|1,1");
        invalid("progression", "complete|First|-");
        assertTrue(
                entry("progression", "unknown|Gather wood|-\ntodo|Build shelter|1")
                        .text
                        .contains("[unknown]"));
    }

    @Test
    public void layersValidateLegendsShapeOrientationAndFallbackAtSmallWidths() {
        WidgetRegistry.Entry e =
                entry("layer", "layer|64|top\nlegend|S|minecraft:stone|Stone|0\nrow|SSS\nrow|S.S");
        assertTrue(e.text.contains("SSS\nS.S"));
        RecordingSurface surface = new RecordingSurface();
        e.render(new WidgetCanvas(surface, 0, 0, 300, e.height(300)));
        assertTrue(surface.drawn.contains("S"));
        assertTrue(surface.drawn.contains("."));
        invalid("layer", "layer|64|top\nrow|X");
        invalid("layer", "layer|64|top\nrow|..\nrow|.");
        invalid("layer", "layer|64|up\nrow|.");
        invalid(
                "layer",
                "layer|64|top\nlegend|S|minecraft:stone|Stone|0\nlegend|S|minecraft:dirt|Dirt|0\nrow|S");
        invalid("layer", "layer|64|top\nrow|.................");
        assertTrue(
                "Wide layers should use a compact grid",
                entry("layer", "layer|64|left\nrow|................").height(300) > 0);
    }

    @Test
    public void comparisonsRequireMatchingColumnsAndRenderAlignedValues() {
        String items = "item|minecraft:stone|Stone|0\nitem|minecraft:planks|Planks|0\n";
        WidgetRegistry.Entry e = entry("comparison", items + "stat|Cost|Low|High");
        assertTrue(e.text.contains("Attribute | Stone | Planks"));
        assertTrue(e.text.contains("Cost | Low | High"));
        RecordingSurface surface = new RecordingSurface();
        e.render(new WidgetCanvas(surface, 0, 0, 400, e.height(400)));
        assertTrue(surface.drawn.contains("Low"));
        assertTrue(surface.drawn.contains("High"));
        invalid("comparison", items + "stat|Cost|Low");
        invalid("comparison", items.trim());
    }

    @Test
    public void productionComputesBottlenecksWithoutFloatingPointNoise() {
        WidgetRegistry.Entry e =
                entry("production", "unit|items/s\nresource|Ore|0.1|0.3|2\nresource|Dust|1|0|?");
        assertTrue(e.text.contains("BOTTLENECK: deficit 0.2 items/s"));
        assertTrue(e.text.contains("Surplus: 1 items/s"));
        assertTrue(e.text.contains("machines: unknown"));
        for (String rate : new String[] {"NaN", "Infinity", "-1", "1000001", "1e9"})
            invalid("production", "unit|items/s\nresource|Ore|" + rate + "|1|1");
    }

    @Test
    public void waypointsPreserveDimensionAndValidateCoordinates() {
        assertTrue(
                entry("waypoint", "location|Nether|-30000000|64|30000000\nnote|Portal")
                        .text
                        .contains("Nether"));
        invalid("waypoint", "location|Overworld|30000001|64|0\nnote|Home");
        invalid("waypoint", "location|Overworld|0|64.5|0\nnote|Home");
    }

    @Test
    public void shapelessRejectsMissingOutputTooManyInputsAndUnknownFields() {
        entry("shapeless", "output|minecraft:planks|Planks|0|4\ninput|minecraft:log|Oak log|0|1");
        invalid("shapeless", "input|minecraft:log|Oak log|0|1");
        invalid(
                "shapeless",
                "output|minecraft:planks|Planks|0|4\ninput|minecraft:log|Oak log|0|1|extra");
        String body = "output|minecraft:planks|Planks|0|4";
        for (int i = 0; i < 10; i++) body += "\ninput|minecraft:log|Oak log|0|1";
        invalid("shapeless", body);
    }

    @Test
    public void malformedAndOversizedPayloadsStayReadable() {
        String valid = block("materials", "minecraft:stone|Stone|0|1|?|64");
        for (String text :
                new String[] {
                    valid.replace("source|estimated", "source|live"),
                    valid.replace("Example", "Bad\ttitle"),
                    valid.replace("Stone", "Stone\t"),
                    valid.substring(0, valid.length() - 3)
                }) {
            assertTrue(registry.parse(text).widgets.isEmpty());
            assertEquals(text.trim(), registry.parse(text).prose);
        }
        String body = "";
        for (int i = 0; i < 33; i++) body += (i == 0 ? "" : "\n") + "todo|Task|-";
        invalid("progression", body);
        invalid("materials", "minecraft:stone|Stone|32768|1|?|64");
        invalid("materials", "stone|Stone|0|1|?|64");
    }

    @Test
    public void buildGuideComposesSnapshotsInOrderWithFullCopyableText() {
        String guide =
                "Gather supplies\n"
                        + block("materials", "minecraft:stone|Stone|0|8|?|64")
                        + "\nBuild layer\n"
                        + block(
                                "layer",
                                "layer|64|top\nlegend|S|minecraft:stone|Stone|0\nrow|SSS\nrow|S.S\nrow|SSS")
                        + "\nFinish\n"
                        + block("progression", "todo|Gather stone|-\ntodo|Place foundation|1");
        WidgetRegistry.Message message = registry.parse(guide);
        assertEquals(3, message.widgets.size());
        assertTrue(message.widgets.get(0).text.contains("missing unknown"));
        assertTrue(message.widgets.get(1).text.contains("Layer Y=64"));
        assertTrue(message.widgets.get(2).text.contains("Place foundation"));
        assertTrue(message.plainText().contains("SSS\nS.S\nSSS"));
        assertTrue(registry.instructions().contains("For a build guide"));
    }
}
