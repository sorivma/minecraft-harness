package dev.minecraftharness.core;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class WorldWorkspaceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void createsSeparateWorkspaceInsideEachSave() throws Exception {
        File first = temp.newFolder("Мир один");
        File second = temp.newFolder("World two");
        File a = WorldWorkspace.open(first);
        File b = WorldWorkspace.open(second);
        assertEquals(first.getCanonicalFile(), a.getParentFile());
        assertEquals(WorldWorkspace.DIRECTORY, a.getName());
        assertTrue(a.isDirectory());
        assertNotEquals(a, b);
        assertEquals(a, WorldWorkspace.open(first));
        assertFalse(new File(first, "DIM-1").exists());
    }

    @Test(expected = IOException.class)
    public void rejectsMissingSave() throws Exception {
        WorldWorkspace.open(null);
    }

    @Test(expected = IOException.class)
    public void rejectsFileInsteadOfWorkspace() throws Exception {
        File root = temp.newFolder();
        assertTrue(new File(root, WorldWorkspace.DIRECTORY).createNewFile());
        WorldWorkspace.open(root);
    }

    @Test
    public void rejectsSymlinkOutsideWorldWhenSupported() throws Exception {
        File root = temp.newFolder("save");
        File outside = temp.newFolder("outside");
        try {
            Files.createSymbolicLink(
                    root.toPath().resolve(WorldWorkspace.DIRECTORY), outside.toPath());
        } catch (IOException e) {
            Assume.assumeNoException(e);
        } catch (UnsupportedOperationException e) {
            Assume.assumeNoException(e);
        }
        try {
            WorldWorkspace.open(root);
            fail("Escaping workspace was accepted");
        } catch (IOException expected) {
        }
    }
}
