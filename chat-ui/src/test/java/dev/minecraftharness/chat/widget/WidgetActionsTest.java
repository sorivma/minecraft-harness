package dev.minecraftharness.chat.widget;

import static org.junit.Assert.*;

import dev.minecraftharness.chat.RecordingSurface;
import java.util.*;
import org.junit.Test;

public class WidgetActionsTest {
    @Test
    public void callbacksAreExplicitArbitraryLogicAndClipped() {
        int[] count = {0};
        List<Runnable> actions = new ArrayList<Runnable>();
        List<int[]> bounds = new ArrayList<int[]>();
        WidgetCanvas canvas =
                new WidgetCanvas(
                        new RecordingSurface(),
                        10,
                        20,
                        100,
                        40,
                        (id, label, x, y, w, h, callback) -> {
                            actions.add(callback);
                            bounds.add(new int[] {x, y, w, h});
                        });
        canvas.button("toggle", "Toggle", -5, 5, 30, 20, () -> count[0]++);
        canvas.button("async", "Launch", 40, 5, 30, 20, () -> count[0] += 10);
        canvas.button("disabled", "Disabled", 0, 0, 30, 20, false, () -> count[0] += 100);
        canvas.button("clipped", "Clipped", 0, 50, 30, 20, () -> count[0] += 1000);
        assertEquals(0, count[0]);
        assertEquals(2, actions.size());
        assertArrayEquals(new int[] {10, 25, 25, 20}, bounds.get(0));
        actions.get(1).run();
        assertEquals(10, count[0]);
        actions.get(0).run();
        assertEquals(11, count[0]);
    }
}
