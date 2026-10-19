package dev.minecraftharness.chat;

import static org.junit.Assert.*;

import org.junit.Test;

public class TextWindowTest {
    @Test
    public void narrowPagesNeverSkipOrSplitText() {
        RecordingSurface surface = new RecordingSurface();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3000; i++) text.append("Строка😀 ").append(i).append('\n');
        String input = text.toString();
        int start = 0;
        StringBuilder seen = new StringBuilder();
        while (start < input.length()) {
            TextWindow page = new TextWindow(surface, input, start, 42, 3);
            assertTrue(page.end > start);
            for (String row : page.lines) {
                if (!row.isEmpty()) {
                    assertFalse(Character.isLowSurrogate(row.charAt(0)));
                    assertFalse(Character.isHighSurrogate(row.charAt(row.length() - 1)));
                }
                seen.append(row);
            }
            start = page.end;
        }
        assertEquals(input.replace("\n", ""), seen.toString());
    }
}
