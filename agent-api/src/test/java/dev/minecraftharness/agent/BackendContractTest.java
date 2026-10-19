package dev.minecraftharness.agent;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.BackendScenarios;
import dev.minecraftharness.agent.testing.FakeBackend;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class BackendContractTest {
    @Test
    public void commonLifecycleContract() {
        BackendScenarios.lifecycle(new FakeBackend(), new File("."));
    }

    private <T> T await(AgentBackend b, CompletableFuture<T> future) {
        for (int i = 0; i < 100 && !future.isDone(); i++) b.pump(1);
        assertTrue("Operation did not settle", future.isDone());
        return future.join();
    }

    @Test
    public void headlessInterleavingRoutesQuestionsAndRejectsDuplicateAnswers() {
        FakeBackend b = new FakeBackend();
        List<Event> events = new ArrayList<Event>();
        await(b, b.connect(new File("."), events::add));
        Conversation a = await(b, b.create(Settings.defaults())),
                c = await(b, b.create(Settings.defaults()));
        await(b, b.send(a.key, new Message("one"), Settings.defaults()));
        await(b, b.send(c.key, new Message("two"), Settings.defaults()));
        b.pump(100);
        List<Request> requests = new ArrayList<Request>();
        for (Event e : events) if (e.request != null) requests.add(e.request);
        assertEquals(2, requests.size());
        assertNotEquals(requests.get(0).key.thread, requests.get(1).key.thread);
        Map<String, Value> answer = Collections.singletonMap("material", Value.text("stone"));
        CompletableFuture<Void> first = b.answer(requests.get(0).key, answer),
                duplicate = b.answer(requests.get(0).key, answer);
        await(b, first);
        b.pump(100);
        assertTrue(duplicate.isCompletedExceptionally());
        assertEquals(1, b.answers);
        assertEquals(3, await(b, b.history(a.key, null)).entries.size());
        assertEquals(2, await(b, b.history(c.key, null)).entries.size());
        b.close();
    }

    @Test
    public void closeFailsPendingAndReconnectInvalidatesOldHandles() {
        FakeBackend b = new FakeBackend();
        await(b, b.connect(new File("."), e -> {}));
        Conversation c = await(b, b.create(Settings.defaults()));
        CompletableFuture<Page<Model>> pending = b.models(null);
        b.close();
        assertTrue(pending.isCompletedExceptionally());
        await(b, b.connect(new File("."), e -> {}));
        CompletableFuture<Conversation> stale = b.resume(c.key);
        b.pump(1);
        assertTrue(stale.isCompletedExceptionally());
        assertEquals(c.key.id, await(b, b.conversations("", false, null)).entries.get(0).key.id);
        assertEquals(0, b.sends);
        b.close();
    }

    @Test
    public void paginationAndBudgetDoNotDropCatalogEntries() {
        FakeBackend b = new FakeBackend();
        await(b, b.connect(new File("."), e -> {}));
        for (int i = 0; i < 105; i++) await(b, b.create(Settings.defaults()));
        Set<String> ids = new HashSet<String>();
        String cursor = null;
        do {
            Page<Conversation> p = await(b, b.conversations("", false, cursor));
            for (Conversation c : p.entries) assertTrue(ids.add(c.key.id));
            cursor = p.nextCursor;
        } while (cursor != null);
        assertEquals(105, ids.size());
        CompletableFuture<Page<Model>> pending = b.models(null);
        assertEquals(0, b.pump(0));
        assertFalse(pending.isDone());
        assertEquals(1, b.pump(1));
        assertTrue(pending.isDone());
        b.close();
    }
}
