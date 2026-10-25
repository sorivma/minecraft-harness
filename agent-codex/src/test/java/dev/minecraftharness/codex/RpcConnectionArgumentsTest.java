package dev.minecraftharness.codex;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.io.*;
import java.nio.file.Files;
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
        List<String> command = echoCommand();
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

    private List<String> echoCommand() throws Exception {
        if (File.separatorChar == '\\') {
            File source = temporary.newFile("NativeArguments.cs");
            try (InputStream input = getClass().getResourceAsStream("/NativeArguments.cs")) {
                Files.copy(
                        input, source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            File executable = new File(temporary.getRoot(), "NativeArguments.exe");
            File compiler =
                    new File(
                            System.getenv("WINDIR"),
                            "Microsoft.NET/Framework64/v4.0.30319/csc.exe");
            if (!compiler.isFile())
                compiler =
                        new File(
                                System.getenv("WINDIR"),
                                "Microsoft.NET/Framework/v4.0.30319/csc.exe");
            assertTrue(
                    "Windows .NET Framework compiler is required for native argv regression",
                    compiler.isFile());
            Process compile =
                    new ProcessBuilder(
                                    compiler.getPath(),
                                    "/nologo",
                                    "/out:" + executable.getPath(),
                                    source.getPath())
                            .redirectErrorStream(true)
                            .start();
            assertTrue(
                    "Native fixture compilation timed out", compile.waitFor(30, TimeUnit.SECONDS));
            assertEquals("Native fixture compilation failed", 0, compile.exitValue());
            return new ArrayList<String>(Collections.singletonList(executable.getPath()));
        }
        String cp = location(EchoArguments.class) + File.pathSeparator + location(JsonObject.class);
        return new ArrayList<String>(
                Arrays.asList(
                        new File(System.getProperty("java.home"), "bin/java").getPath(),
                        "-cp",
                        cp,
                        EchoArguments.class.getName()));
    }

    private static String location(Class<?> type) throws Exception {
        return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
    }
}
