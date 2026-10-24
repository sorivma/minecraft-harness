package dev.minecraftharness.forge;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** File operations confined to the local launch's saves directory. */
final class LocalWorldFiles {
    private LocalWorldFiles() {}

    static void resetSaves(Path gameDirectory) throws IOException {
        final Path root = gameDirectory.toRealPath();
        final Path saves = root.resolve("saves");
        if (Files.isSymbolicLink(saves)
                || (Files.exists(saves) && !saves.toRealPath().equals(saves))) {
            throw new IOException("Refusing to reset a linked saves directory: " + saves);
        }
        if (Files.exists(saves)) {
            Files.walkFileTree(
                    saves,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path directory, BasicFileAttributes attributes) throws IOException {
                            if (!directory.toRealPath().startsWith(saves)) {
                                throw new IOException(
                                        "Directory escapes local saves: " + directory);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                                throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(
                                Path directory, IOException failure) throws IOException {
                            if (failure != null) throw failure;
                            Files.delete(directory);
                            return FileVisitResult.CONTINUE;
                        }
                    });
        }
        Files.createDirectory(saves);
    }
}
