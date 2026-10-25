package dev.minecraftharness.codex;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns a local app-server. Callbacks run ONLY when the UI calls pump(). */
public final class RpcConnection implements Closeable {
    public interface Reply {
        void accept(JsonObject result, JsonObject error);
    }

    public interface Events {
        void accept(JsonObject message);
    }

    private final Process process;
    private final BufferedWriter writer;
    private final BlockingQueue<JsonObject> inbox = new ArrayBlockingQueue<JsonObject>(4096);
    private final Map<Integer, Reply> pending = new HashMap<Integer, Reply>();
    private int sequence;
    private volatile boolean closed;
    private volatile String failureMessage;
    private final List<Thread> readers = new ArrayList<Thread>();

    public RpcConnection(List<String> command, File cwd) throws IOException {
        process = new ProcessBuilder(nativeCommand(command)).directory(cwd).start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        read(process.getInputStream(), false);
        read(process.getErrorStream(), true);
    }

    private static List<String> nativeCommand(List<String> command) {
        if (File.separatorChar != '\\') return command;
        List<String> escaped = new ArrayList<String>(command);
        // ProcessBuilder preserves embedded quotes on Unix, but on Windows its
        // command-line serializer treats them as syntax. Encode arguments that
        // contain literal quotes for the native Windows argv parser ourselves.
        // The executable remains separate; no shell or command wrapper is used.
        for (int i = 1; i < escaped.size(); i++) {
            String value = escaped.get(i);
            if (value.indexOf('"') < 0) continue;
            StringBuilder encoded = new StringBuilder("\"");
            int slashes = 0;
            for (int j = 0; j < value.length(); j++) {
                char c = value.charAt(j);
                if (c == '\\') {
                    slashes++;
                    continue;
                }
                int count = c == '"' ? slashes * 2 + 1 : slashes;
                for (int k = 0; k < count; k++) encoded.append('\\');
                encoded.append(c);
                slashes = 0;
            }
            for (int k = 0; k < slashes * 2; k++) encoded.append('\\');
            escaped.set(i, encoded.append('"').toString());
        }
        return escaped;
    }

    private void read(final InputStream stream, final boolean stderr) {
        Thread reader =
                new Thread(
                        new Runnable() {
                            public void run() {
                                try {
                                    BufferedReader input =
                                            new BufferedReader(
                                                    new InputStreamReader(stream, "UTF-8"));
                                    String line;
                                    while (!closed && (line = input.readLine()) != null) {
                                        if (stderr) {
                                            // Drain stderr without allowing verbose diagnostics to
                                            // starve protocol events.
                                            continue;
                                        }
                                        JsonElement parsed = new JsonParser().parse(line);
                                        if (!parsed.isJsonObject())
                                            throw new IOException("Non-object app-server message");
                                        inbox.put(parsed.getAsJsonObject());
                                    }
                                    if (!stderr && !closed) failure("Codex process disconnected");
                                } catch (Exception e) {
                                    if (!closed) failure(e.toString());
                                }
                            }
                        },
                        stderr ? "harness-stderr" : "harness-rpc");
        reader.setDaemon(true);
        readers.add(reader);
        reader.start();
    }

    private void failure(String message) {
        failureMessage = message;
    }

    public int request(String method, JsonObject params, Reply callback) throws IOException {
        int id = ++sequence;
        JsonObject message = envelope(method, params);
        message.addProperty("id", id);
        pending.put(id, callback);
        try {
            send(message);
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        }
        return id;
    }

    public void notify(String method, JsonObject params) throws IOException {
        send(envelope(method, params));
    }

    public void respond(JsonElement id, JsonObject result) throws IOException {
        JsonObject message = new JsonObject();
        message.add("id", id);
        message.add("result", result);
        send(message);
    }

    public void unsupported(JsonElement id) throws IOException {
        JsonObject message = new JsonObject();
        message.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", -32601);
        error.addProperty("message", "This Minecraft client does not implement this request");
        message.add("error", error);
        send(message);
    }

    private JsonObject envelope(String method, JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("method", method);
        message.add("params", params);
        return message;
    }

    private synchronized void send(JsonObject message) throws IOException {
        if (closed) throw new IOException("Connection closed");
        writer.write(message.toString());
        writer.newLine();
        writer.flush();
    }

    public void pump(Events events) {
        pump(events, 200);
    }

    public int pump(Events events, int budget) {
        int count = 0;
        while (!closed && count < Math.max(0, budget)) {
            JsonObject message = inbox.poll();
            if (message == null) break;
            count++;
            if (!message.has("method") && message.has("id")) {
                Reply reply = pending.remove(message.get("id").getAsInt());
                if (reply != null)
                    reply.accept(object(message, "result"), object(message, "error"));
            } else events.accept(message);
        }
        if (!closed && failureMessage != null && inbox.isEmpty() && count < budget) {
            JsonObject params = new JsonObject();
            params.addProperty("message", failureMessage);
            JsonObject event = envelope("harness/disconnected", params);
            close();
            events.accept(event);
            count++;
        }
        return count;
    }

    public static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key)
                : new JsonObject();
    }

    public void close() {
        if (closed) return;
        closed = true;
        List<Reply> abandoned = new ArrayList<Reply>(pending.values());
        pending.clear();
        inbox.clear();
        process.destroy();
        for (Thread reader : readers) reader.interrupt();
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        JsonObject error = new JsonObject();
        error.addProperty("code", -32000);
        error.addProperty("message", failureMessage == null ? "Connection closed" : failureMessage);
        for (Reply reply : abandoned) reply.accept(new JsonObject(), error);
    }
}
