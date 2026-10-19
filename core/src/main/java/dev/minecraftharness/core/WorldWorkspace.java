package dev.minecraftharness.core;

import java.io.*;
import java.nio.file.*;

/** Resolves against the real save directory, never a display name or a dimension directory. */
public final class WorldWorkspace {
    public static final String DIRECTORY = "codex-workspace";

    private WorldWorkspace() {}

    public static File open(File saveRoot) throws IOException {
        if (saveRoot == null) throw new IOException("No local world save is available");
        Path root = saveRoot.toPath().toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("World save is not a directory");
        Path candidate = root.resolve(DIRECTORY);
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(candidate) || !candidate.toRealPath().startsWith(root)) {
                throw new IOException("Workspace must be a directory inside the world save");
            }
        } else Files.createDirectory(candidate);
        Path actual = candidate.toRealPath();
        if (actual.equals(root) || !actual.startsWith(root))
            throw new IOException("Workspace escapes world save");
        return actual.toFile();
    }
}
