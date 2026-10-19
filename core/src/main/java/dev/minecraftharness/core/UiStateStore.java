package dev.minecraftharness.core;

import dev.minecraftharness.agent.AgentData.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Only local presentation state; backend history and runnable queues are never persisted. */
public final class UiStateStore {
    public static final class State {
        public String draft = "", anchor = "";
        public int cursor, anchorOffset;
        public boolean expanded;
        public Settings settings = Settings.defaults();
        public final List<Resource> resources = new ArrayList<Resource>();
    }

    private final File directory;

    public UiStateStore(File workspace) {
        directory = new File(workspace, ".harness-ui-v1");
    }

    private File file(String backend, String thread) throws IOException {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest((backend + "\u0000" + thread).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return new File(directory, hex + ".properties");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public State read(String backend, String thread) throws IOException {
        State s = new State();
        File file = file(backend, thread);
        if (!file.isFile()) return s;
        Properties p = new Properties();
        try (Reader in = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            p.load(in);
        }
        if (!"1".equals(p.getProperty("version"))) return s;
        s.draft = p.getProperty("draft", "");
        s.anchor = p.getProperty("anchor", "");
        s.cursor = Math.max(0, Math.min(s.draft.length(), number(p, "cursor")));
        s.anchorOffset = Math.max(0, number(p, "anchorOffset"));
        s.expanded = Boolean.parseBoolean(p.getProperty("expanded"));
        s.settings =
                new Settings(
                        p.getProperty("model"),
                        p.getProperty("effort"),
                        p.getProperty("tier"),
                        p.getProperty("mode"),
                        p.getProperty("permissionProfile"),
                        p.getProperty("accessProgram"));
        for (int i = 0; i < Math.min(100, number(p, "resources")); i++)
            try {
                s.resources.add(
                        new Resource(
                                Resource.Type.valueOf(p.getProperty("resource." + i + ".type")),
                                p.getProperty("resource." + i + ".name"),
                                p.getProperty("resource." + i + ".path")));
            } catch (IllegalArgumentException | NullPointerException ignored) {
            }
        return s;
    }

    private static int number(Properties p, String key) {
        try {
            return Integer.parseInt(p.getProperty(key, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void write(String backend, String thread, State s) throws IOException {
        Files.createDirectories(directory.toPath());
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("draft", s.draft);
        p.setProperty("cursor", Integer.toString(s.cursor));
        p.setProperty("anchor", s.anchor);
        p.setProperty("anchorOffset", Integer.toString(s.anchorOffset));
        p.setProperty("expanded", Boolean.toString(s.expanded));
        put(p, "model", s.settings.model);
        put(p, "effort", s.settings.effort);
        put(p, "tier", s.settings.tier);
        put(p, "mode", s.settings.mode);
        put(p, "permissionProfile", s.settings.permissionProfile);
        put(p, "accessProgram", s.settings.accessProgram);
        p.setProperty("resources", Integer.toString(s.resources.size()));
        for (int i = 0; i < s.resources.size(); i++) {
            Resource r = s.resources.get(i);
            String key = "resource." + i;
            p.setProperty(key + ".type", r.type.name());
            p.setProperty(key + ".name", r.name);
            p.setProperty(key + ".path", r.location);
        }
        Path target = file(backend, thread).toPath(),
                temp = Files.createTempFile(directory.toPath(), "state-", ".tmp");
        try {
            try (Writer out = new OutputStreamWriter(Files.newOutputStream(temp), "UTF-8")) {
                p.store(out, "Minecraft Harness UI state");
            }
            try {
                Files.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void put(Properties p, String key, String value) {
        if (value != null) p.setProperty(key, value);
    }
}
