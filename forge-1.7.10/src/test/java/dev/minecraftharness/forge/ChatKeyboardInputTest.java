package dev.minecraftharness.forge;

import static org.junit.Assert.*;

import dev.minecraftharness.chat.Editor;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

public class ChatKeyboardInputTest {
    private void type(Editor editor, char value, int key, boolean separateTextEvents) {
        char character = ChatKeyboardInput.character(value, key, separateTextEvents);
        if (character >= 32 && character != 127) editor.insert(Character.toString(character));
    }

    @Test
    public void sdlPhysicalKeyAndCommittedCyrillicTextInsertOnlyOnce() {
        Editor editor = new Editor("", 0);
        type(editor, '\u0440', Keyboard.KEY_H, true);
        type(editor, '\u0440', Keyboard.KEY_NONE, true);
        assertEquals("\u0440", editor.text);
    }

    @Test
    public void repeatedSdlPressesAndTextKeepIntentionalRepeatedLetters() {
        Editor editor = new Editor("", 0);
        for (int i = 0; i < 3; i++) {
            type(editor, 'a', Keyboard.KEY_A, true);
            type(editor, 'a', Keyboard.KEY_NONE, true);
        }
        assertEquals("aaa", editor.text);
    }

    @Test
    public void committedTextSuppliesShiftedCharactersAndUnicode() {
        Editor editor = new Editor("", 0);
        type(editor, 'a', Keyboard.KEY_A, true);
        type(editor, 'A', Keyboard.KEY_NONE, true);
        for (char c : "\ud83d\ude00".toCharArray()) type(editor, c, Keyboard.KEY_NONE, true);
        assertEquals("A\ud83d\ude00", editor.text);
    }

    @Test
    public void lwjgl2CombinedKeyAndCharacterStillWorks() {
        Editor editor = new Editor("", 0);
        type(editor, '\u0440', Keyboard.KEY_H, false);
        assertEquals("\u0440", editor.text);
    }

    @Test
    public void controlCharactersAndNonTextKeysRemainAvailable() {
        assertEquals('\b', ChatKeyboardInput.character('\b', Keyboard.KEY_BACK, true));
        assertEquals('\r', ChatKeyboardInput.character('\r', Keyboard.KEY_RETURN, true));
        assertEquals('\0', ChatKeyboardInput.character('\0', Keyboard.KEY_LEFT, true));
        assertEquals('\0', ChatKeyboardInput.character('a', Keyboard.KEY_A, true));
    }
}
