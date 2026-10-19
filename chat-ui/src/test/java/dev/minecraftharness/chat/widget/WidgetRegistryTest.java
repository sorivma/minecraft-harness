package dev.minecraftharness.chat.widget;

import static org.junit.Assert.*;

import dev.minecraftharness.chat.RecordingSurface;
import org.junit.Test;

public class WidgetRegistryTest {
    private Widget gauge(String body) {
        final int value = Integer.parseInt(body);
        if (value < 0 || value > 100) return null;
        return new Widget() {
            public String title() {
                return "Energy";
            }

            public String plainText() {
                return value + "% energy";
            }

            public int height(int width) {
                return 24;
            }

            public void render(WidgetCanvas c) {
                c.rectangle(0, 0, c.width() * value / 100, 8, 0xff00ff00);
                c.text(plainText(), 0, 12, 0xffffffff);
            }
        };
    }

    @Test
    public void thirdPartyWidgetsUseNamespacedFormatsAndMultipleBlocks() {
        WidgetRegistry r = WidgetRegistry.defaults();
        r.register("example:energy-v1", "One integer from 0 to 100.", this::gauge);
        WidgetRegistry.Message message =
                r.parse(
                        "Before\n```example:energy-v1\n25\n```\nBetween\n```example:energy-v1\n75\n```\nAfter");
        assertEquals(2, message.widgets.size());
        assertEquals("Before\nBetween\nAfter", message.prose);
        assertTrue(message.plainText().contains("75% energy"));
        assertTrue(r.instructions().contains("example:energy-v1"));
        RecordingSurface surface = new RecordingSurface();
        message.widgets.get(0).render(new WidgetCanvas(surface, 0, 0, 100, 24));
        assertEquals(1, surface.rectangles);
        assertTrue(surface.drawn.contains("25% energy"));
    }

    @Test
    public void invalidUnknownIncompleteAndQuotedBlocksStayText() {
        WidgetRegistry r = new WidgetRegistry();
        r.register("example:energy-v1", "Integer", this::gauge);
        String[] texts = {
            "```missing:widget\nx\n```",
            "```example:energy-v1\nno\n```",
            "```example:energy-v1\n101\n```",
            "```example:energy-v1\n25",
            "````text\n```example:energy-v1\n25\n```\n````"
        };
        for (String text : texts) {
            assertEquals(text, r.parse(text).prose);
            assertTrue(r.parse(text).widgets.isEmpty());
        }
    }

    @Test
    public void snapshotsAndDuplicatesHaveExplicitBehavior() {
        WidgetRegistry r = new WidgetRegistry();
        r.register("example:energy-v1", "Integer", this::gauge);
        WidgetRegistry snapshot = r.snapshot();
        r.register("example:other-v1", "Integer", this::gauge);
        assertFalse(snapshot.instructions().contains("example:other-v1"));
        try {
            r.register("example:energy-v1", "Integer", this::gauge);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            r.register("unnamespaced", "Integer", this::gauge);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            snapshot.register("example:new", "Integer", this::gauge);
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void rendererFailureKeepsFallbackAndDisablesRetry() {
        WidgetRegistry r = new WidgetRegistry();
        r.register(
                "example:broken",
                "Empty",
                body ->
                        new Widget() {
                            public String title() {
                                return "Broken";
                            }

                            public String plainText() {
                                return "Fallback";
                            }

                            public int height(int w) {
                                return 20;
                            }

                            public void render(WidgetCanvas c) {
                                throw new IllegalStateException();
                            }
                        });
        WidgetRegistry.Message message = r.parse("```example:broken\nx\n```");
        WidgetRegistry.Entry entry = message.widgets.get(0);
        entry.render(new WidgetCanvas(new RecordingSurface(), 0, 0, 100, 20));
        assertEquals(0, entry.height(100));
        assertTrue(message.plainText().contains("Fallback"));
    }

    @Test
    public void boundsWidgetCountAndMessageSize() {
        WidgetRegistry r = new WidgetRegistry();
        r.register("example:energy-v1", "Integer", this::gauge);
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 20; i++) input.append("```example:energy-v1\n25\n```\n");
        WidgetRegistry.Message message = r.parse(input.toString());
        assertEquals(16, message.widgets.size());
        assertTrue(message.prose.contains("```example:energy-v1"));
        while (input.length() < 33000) input.append('x');
        assertTrue(r.parse(input.toString()).widgets.isEmpty());
    }
}
