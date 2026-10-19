package dev.minecraftharness.agent.testing;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Reusable contract checks. Supply a deterministic backend or protocol replay adapter. */
public final class BackendScenarios {
    private BackendScenarios() {}

    public static <T> T await(AgentBackend backend, CompletableFuture<T> future) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!future.isDone() && System.nanoTime() < deadline) {
            backend.pump(16);
            if (!future.isDone())
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
        }
        require(future.isDone(), "Operation did not settle");
        return future.join();
    }

    public static void lifecycle(AgentBackend backend, File workspace) {
        List<Event> events = new ArrayList<Event>();
        Connection first = await(backend, backend.connect(workspace, events::add));
        require(first.backend.equals(backend.id()), "Backend identity mismatch");
        require(await(backend, backend.capabilities()).concurrentTurns >= 1, "Invalid concurrency");
        Conversation thread = await(backend, backend.create(Settings.defaults()));
        require(thread.key.connection.equals(first), "Wrong conversation owner");
        require(
                await(backend, backend.resume(thread.key)).key.equals(thread.key),
                "Resume changed identity");
        await(backend, backend.history(thread.key, null));
        backend.close();
        Connection second = await(backend, backend.connect(workspace, events::add));
        require(!first.equals(second), "Reconnect reused generation");
        CompletableFuture<Conversation> stale = backend.resume(thread.key);
        try {
            await(backend, stale);
            throw new AssertionError("Stale handle accepted");
        } catch (java.util.concurrent.CompletionException expected) {
            require(expected.getCause() instanceof Failure, "Unstructured stale-handle failure");
        }
        backend.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
