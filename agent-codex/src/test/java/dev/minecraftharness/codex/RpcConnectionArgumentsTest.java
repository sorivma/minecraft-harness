package dev.minecraftharness.codex;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class RpcConnectionArgumentsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    public static class EchoArguments {
        public static void main(String[] args) throws Exception {
            new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine();
            JsonArray values = new JsonArray();
            for (String arg : args) values.add(new JsonPrimitive(arg));
            JsonObject result = new JsonObject();
            result.add("arguments", values);
            JsonObject reply = new JsonObject();
            reply.addProperty("id", 1);
            reply.add("result", result);
            Writer output = new OutputStreamWriter(System.out, "UTF-8");
            output.write(reply.toString() + "\n");
            output.flush();
        }
    }

    @Test
    public void preservesConfigQuotesAcrossActualNativeProcessBoundary() throws Exception {
        File workspace = temporary.newFolder("GTNH 2.9.0 - player's мир");
        List<String> expected =
                new ArrayList<String>(
                        CodexWorkspaceConfig.command("codex", workspace).subList(1, 6));
        expected.add("quoted=\"C:\\folder with spaces\\\"");
        expected.add("literal=\\\"embedded\\\"");
        String cp = location(EchoArguments.class) + File.pathSeparator + location(JsonObject.class);
        List<String> command =
                new ArrayList<String>(
                        Arrays.asList(
                                System.getProperty("harness.test.nativeJava"),
                                "-cp",
                                cp,
                                EchoArguments.class.getName()));
        command.addAll(expected);
        CompletableFuture<JsonObject> reply = new CompletableFuture<JsonObject>();
        try (RpcConnection rpc = new RpcConnection(command, workspace)) {
            rpc.request(
                    "initialize",
                    new JsonObject(),
                    (result, error) -> {
                        if (error.entrySet().isEmpty()) reply.complete(result);
                        else reply.completeExceptionally(new IOException(error.toString()));
                    });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!reply.isDone() && System.nanoTime() < deadline) {
                rpc.pump(event -> {});
                Thread.sleep(5);
            }
            JsonArray actual = reply.get(1, TimeUnit.SECONDS).getAsJsonArray("arguments");
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++)
                assertEquals(expected.get(i), actual.get(i).getAsString());
        }
    }

    private static String location(Class<?> type) throws Exception {
        return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
    }
}
