package dev.minecraftharness.forge;

/** Keeps physical key actions separate from committed text on SDL input backends. */
final class ChatKeyboardInput {
    private ChatKeyboardInput() {}

    static boolean hasSeparateTextEvents() {
        try {
            Class.forName(
                    "org.lwjgl.sdl.SDLKeyboard", false, ChatKeyboardInput.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static char character(char character, int key, boolean separateTextEvents) {
        // SDL sends a layout-derived character on physical key events as well as a
        // KEY_NONE event for committed text. Only the latter owns printable text.
        // Keep the key code itself for navigation, editing and Ctrl shortcuts.
        if (separateTextEvents && key != 0 && character >= 32) return '\0';
        return character;
    }
}
