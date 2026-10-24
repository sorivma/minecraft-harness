package dev.minecraftharness.chat.widget;

import static org.junit.Assert.*;

import dev.minecraftharness.chat.*;
import java.lang.reflect.Proxy;
import org.junit.Test;

public class WidgetDrawingTest {
    @Test
    public void drawingKeepsOriginalCoordinatesButClipsNativeRegionToCanvas() {
        int[][] region = {null}, drawing = {null};
        Surface surface =
                (Surface)
                        Proxy.newProxyInstance(
                                Surface.class.getClassLoader(),
                                new Class<?>[] {Surface.class},
                                (proxy, method, args) -> {
                                    assertEquals("custom", method.getName());
                                    region[0] =
                                            new int[] {
                                                (Integer) args[0],
                                                (Integer) args[1],
                                                (Integer) args[2],
                                                (Integer) args[3]
                                            };
                                    ((Runnable) args[4]).run();
                                    return true;
                                });
        WidgetCanvas canvas = new WidgetCanvas(surface, 10, 20, 100, 40);
        assertTrue(
                canvas.custom(-5, 5, 30, 50, (l, t, w, h) -> drawing[0] = new int[] {l, t, w, h}));
        assertArrayEquals(new int[] {10, 25, 25, 35}, region[0]);
        assertArrayEquals(new int[] {5, 25, 30, 50}, drawing[0]);
        assertFalse(canvas.custom(0, 50, 20, 20, (l, t, w, h) -> fail("Off-canvas drawing")));
    }

    @Test
    public void portableHostDoesNotExecuteNativeGraphics() {
        WidgetCanvas canvas = new WidgetCanvas(new RecordingSurface(), 0, 0, 100, 100);
        assertFalse(
                canvas.custom(
                        0, 0, 50, 50, (l, t, w, h) -> fail("Native graphics on portable host")));
    }
}
