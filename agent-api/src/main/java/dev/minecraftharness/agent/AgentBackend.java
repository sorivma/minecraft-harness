package dev.minecraftharness.agent;

import dev.minecraftharness.agent.AgentData.*;
import java.io.Closeable;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Provider-independent v1 contract. All calls and callbacks use the owner thread. Implementations
 * enqueue IO; pump dispatches at most budget events/callbacks. Closing completes pending futures
 * exceptionally and invalidates request handles. Reconnect assigns a new generation. No operation
 * implicitly resubmits a turn.
 */
public interface AgentBackend extends Closeable {
    String id();

    CompletableFuture<Connection> connect(File workspace, Consumer<Event> events);

    Connection connection();

    int pump(int budget);

    CompletableFuture<Capabilities> capabilities();

    CompletableFuture<Page<Model>> models(String cursor);

    CompletableFuture<Page<Conversation>> conversations(
            String query, boolean archived, String cursor);

    CompletableFuture<Conversation> create(Settings settings);

    CompletableFuture<Conversation> resume(ThreadKey thread);

    CompletableFuture<Page<Item>> history(ThreadKey thread, String cursor);

    CompletableFuture<Void> rename(ThreadKey thread, String title);

    CompletableFuture<Void> archive(ThreadKey thread, boolean archived);

    CompletableFuture<Turn> send(ThreadKey thread, Message message, Settings settings);

    CompletableFuture<Void> steer(ThreadKey thread, String turnId, Message message);

    CompletableFuture<Void> interrupt(ThreadKey thread, String turnId);

    CompletableFuture<Void> answer(RequestKey request, Map<String, Value> fields);

    CompletableFuture<ActionResult> act(
            ThreadKey thread, String actionId, Map<String, Value> fields);

    void close();
}
