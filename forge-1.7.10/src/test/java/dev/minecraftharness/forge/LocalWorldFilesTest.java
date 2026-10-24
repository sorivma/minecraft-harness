package dev.minecraftharness.forge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LocalWorldFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void clearsAllWorldsIncludingTheirWorkspacesButPreservesOtherGameFiles()
            throws Exception {
        Path game = temporary.newFolder("run").toPath();
        Path config = Files.createDirectories(game.resolve("config")).resolve("mod.cfg");
        Files.write(config, new byte[] {1});
        Files.createDirectories(game.resolve("saves/first/region"));
        Files.write(game.resolve("saves/first/region/r.0.0.mca"), new byte[] {2});
        Files.createDirectories(game.resolve("saves/second/codex-workspace"));
        Files.write(game.resolve("saves/second/level.dat"), new byte[] {3});

        LocalWorldFiles.resetSaves(game);

        assertTrue(Files.isDirectory(game.resolve("saves")));
        try (Stream<Path> children = Files.list(game.resolve("saves"))) {
            assertEquals(0L, children.count());
        }
        assertEquals(1, Files.readAllBytes(config)[0]);
    }

    @Test
    public void supportsFirstLaunchAndRepeatedResets() throws Exception {
        Path game = temporary.newFolder("run").toPath();
        LocalWorldFiles.resetSaves(game);
        Files.createDirectories(game.resolve("saves/harness-test"));
        LocalWorldFiles.resetSaves(game);
        try (Stream<Path> children = Files.list(game.resolve("saves"))) {
            assertEquals(0L, children.count());
        }
    }
}
