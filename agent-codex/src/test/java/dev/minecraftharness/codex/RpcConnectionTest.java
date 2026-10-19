package dev.minecraftharness.codex;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.io.*;
import java.util.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RpcConnectionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    public static class SilentServer {
        public static void main(String[] args) throws Exception {
            while (System.in.read() != -1) {}
        }
    }

    @Test
    public void closeFailsPendingExactlyOnceOnOwnerThread() throws Exception {
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        RpcConnection rpc =
                new RpcConnection(
                        Arrays.asList(
                                java,
                                "-cp",
                                location(RpcConnectionTest.class),
                                SilentServer.class.getName()),
                        temp.newFolder());
        final int[] replies = {0};
        final Thread owner = Thread.currentThread();
        rpc.request(
                "never-replies",
                new JsonObject(),
                (result, error) -> {
                    assertSame(owner, Thread.currentThread());
                    assertEquals(-32000, error.get("code").getAsInt());
                    replies[0]++;
                });
        rpc.close();
        rpc.close();
        assertEquals(1, replies[0]);
    }

    public static class FakeServer {
        public static void main(String[] args) throws Exception {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            JsonObject first = new JsonParser().parse(input.readLine()).getAsJsonObject();
            System.err.println("diagnostic, not JSON");
            JsonObject response = new JsonObject();
            response.add("id", first.get("id"));
            JsonObject result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("cwd", new File(".").getCanonicalPath());
            response.add("result", result);
            System.out.println(response.toString());
            System.out.println(
                    "{\"id\":\"approval-1\",\"method\":\"item/commandExecution/requestApproval\",\"params\":{}}");
            System.out.flush();
            JsonObject reply = new JsonParser().parse(input.readLine()).getAsJsonObject();
            System.out.println("{\"method\":\"test/decision\",\"params\":" + reply + "}");
            System.out.flush();
        }
    }

    @Test
    public void correlatesRepliesAndHandlesStringServerIdsOnPumpThread() throws Exception {
        String path =
                location(RpcConnectionTest.class)
                        + File.pathSeparator
                        + location(RpcConnection.class)
                        + File.pathSeparator
                        + location(JsonObject.class);
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        final File workspace = temp.newFolder("Мир с пробелами").getCanonicalFile();
        final RpcConnection rpc =
                new RpcConnection(
                        Arrays.asList(java, "-cp", path, FakeServer.class.getName()), workspace);
        final boolean[] complete = {false, false};
        final Thread caller = Thread.currentThread();
        try {
            rpc.request(
                    "initialize",
                    new JsonObject(),
                    new RpcConnection.Reply() {
                        public void accept(JsonObject result, JsonObject error) {
                            assertSame(caller, Thread.currentThread());
                            assertTrue(result.get("ok").getAsBoolean());
                            assertEquals(workspace.getPath(), result.get("cwd").getAsString());
                            complete[0] = true;
                        }
                    });
            RpcConnection.Events events =
                    new RpcConnection.Events() {
                        public void accept(JsonObject message) {
                            try {
                                if (message.get("method")
                                        .getAsString()
                                        .endsWith("requestApproval")) {
                                    JsonObject decision = new JsonObject();
                                    decision.addProperty("decision", "decline");
                                    rpc.respond(message.get("id"), decision);
                                } else if (message.get("method")
                                        .getAsString()
                                        .equals("test/decision")) {
                                    JsonObject echoed = message.getAsJsonObject("params");
                                    assertEquals("approval-1", echoed.get("id").getAsString());
                                    assertEquals(
                                            "decline",
                                            echoed.getAsJsonObject("result")
                                                    .get("decision")
                                                    .getAsString());
                                    complete[1] = true;
                                }
                            } catch (IOException e) {
                                throw new AssertionError(e);
                            }
                        }
                    };
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (!complete[1] && System.nanoTime() < deadline) {
                rpc.pump(events);
                Thread.sleep(5);
            }
            assertTrue("RPC response not dispatched", complete[0]);
            assertTrue("Approval response not received", complete[1]);
        } finally {
            rpc.close();
        }
    }

    private static String location(Class<?> type) throws Exception {
        return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
    }
}
