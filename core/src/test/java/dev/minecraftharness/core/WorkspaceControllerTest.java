package dev.minecraftharness.core;

import static org.junit.Assert.*;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.FakeBackend;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

public class WorkspaceControllerTest {
    @Test
    public void followupDuringStartReachesRunningTurnWithoutWaitingForCompletion()
            throws Exception {
        FakeBackend b = new FakeBackend();
        WorkspaceController c = new WorkspaceController(temp.newFolder(), Arrays.asList(b));
        await(c, c.connect("fake"));
        WorkspaceController.Chat chat = await(c, c.create("fake"));
        CompletableFuture<Void> start = c.submit(chat, new Message("build"), true);
        CompletableFuture<Void> followup = c.submit(chat, new Message("use stone"), true);
        assertFalse(
                "Followup must be retained while turn/start is pending",
                followup.isCompletedExceptionally());
        await(c, start);
        // Finish the fake question to model a normal running step, not a user-input wait.
        for (WorkspaceController.Pending p : chat.requests.values())
            b.emit(Event.resolved(p.request.key));
        drain(c);
        assertEquals(Arrays.asList("use stone"), b.steered);
        assertEquals(1, b.sends);
        assertTrue(chat.queue.isEmpty());
        c.close();
    }

    @Test
    public void streamingEventsCannotStarveQueuedFollowupsAndOrderIsPreserved() throws Exception {
        FakeBackend b = new FakeBackend();
        AgentBackend streaming =
                (AgentBackend)
                        java.lang.reflect.Proxy.newProxyInstance(
                                AgentBackend.class.getClassLoader(),
                                new Class<?>[] {AgentBackend.class},
                                (proxy, method, args) -> {
                                    Object result = method.invoke(b, args);
                                    // Simulate an event source that always consumes its entire pump
                                    // allowance.
                                    return method.getName().equals("pump") ? args[0] : result;
                                });
        WorkspaceController c = new WorkspaceController(temp.newFolder(), Arrays.asList(streaming));
        await(c, c.connect("fake"));
        WorkspaceController.Chat chat = await(c, c.create("fake"));
        await(c, c.submit(chat, new Message("build"), true));
        c.submit(chat, new Message("first"), true);
        c.submit(chat, new Message("second"), true);
        drain(c);
        assertTrue("Questions must be answered before steering", b.steered.isEmpty());
        for (WorkspaceController.Pending p : chat.requests.values())
            b.emit(Event.resolved(p.request.key));
        drain(c);
        assertEquals(Arrays.asList("first", "second"), b.steered);
        assertTrue(chat.queue.isEmpty());
        assertEquals(1, b.sends);
        c.close();
    }

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private <T> T await(WorkspaceController c, CompletableFuture<T> f) {
        for (int i = 0; i < 200 && !f.isDone(); i++) c.tick(32);
        assertTrue(f.isDone());
        return f.join();
    }

    private void drain(WorkspaceController c) {
        for (int i = 0; i < 20; i++) assertTrue(c.tick(32) <= 32);
    }

    @Test
    public void backgroundRequestsIndependentDraftsAndModelRejection() throws Exception {
        FakeBackend b = new FakeBackend();
        WorkspaceController c = new WorkspaceController(temp.newFolder(), Arrays.asList(b));
        await(c, c.connect("fake"));
        WorkspaceController.Chat a = await(c, c.create("fake")), d = await(c, c.create("fake"));
        c.draft(a, "Первый", 6);
        c.draft(d, "Second", 3);
        await(c, c.submit(a, new Message(a.ui.draft), false));
        await(c, c.submit(d, new Message(d.ui.draft), false));
        drain(c);
        assertEquals("", a.ui.draft);
        assertTrue(a.needsAnswer());
        assertTrue(a.unread > 0);
        assertTrue(d.needsAnswer());
        WorkspaceController.Pending p = a.requests.values().iterator().next();
        CompletableFuture<Void> first =
                c.answer(a, p, Collections.singletonMap("material", Value.text("wood")));
        assertTrue(
                c.answer(a, p, Collections.singletonMap("material", Value.text("wood")))
                        .isCompletedExceptionally());
        await(c, first);
        drain(c);
        assertFalse(a.busy());
        assertTrue(d.busy());
        assertEquals(1, b.answers);
        c.draft(a, "Keep this", 4);
        a.ui.settings = new Settings("unavailable", null, null, null, null);
        CompletableFuture<Void> rejected = c.submit(a, new Message(a.ui.draft), false);
        drain(c);
        assertTrue(rejected.isCompletedExceptionally());
        assertEquals("Keep this", a.ui.draft);
        assertEquals("unavailable", a.ui.settings.model);
        c.close();
    }

    @Test
    public void serialBackendQueuesAcrossChatsAndCancellationIsExplicit() throws Exception {
        FakeBackend b = new FakeBackend("serial", 1);
        WorkspaceController c = new WorkspaceController(temp.newFolder(), Arrays.asList(b));
        await(c, c.connect("serial"));
        WorkspaceController.Chat a = await(c, c.create("serial")), d = await(c, c.create("serial"));
        await(c, c.submit(a, new Message("one"), false));
        await(c, c.submit(d, new Message("two"), false));
        drain(c);
        assertEquals(1, b.sends);
        assertEquals(1, d.queue.size());
        c.cancelQueued(d, d.queue.get(0).id);
        assertTrue(d.queue.isEmpty());
        await(c, c.submit(d, new Message("three"), false));
        await(c, c.interrupt(a));
        drain(c);
        assertEquals(2, b.sends);
        assertTrue(d.busy());
        c.close();
    }

    @Test
    public void recoveryDoesNotResendAndWorldNamespacesDoNotLeak() throws Exception {
        File world = temp.newFolder();
        FakeBackend b = new FakeBackend();
        WorkspaceController c = new WorkspaceController(world, Arrays.asList(b));
        await(c, c.connect("fake"));
        WorkspaceController.Chat chat = await(c, c.create("fake"));
        c.draft(chat, "Сохранить", 3);
        c.save(chat);
        Connection old = b.connection();
        await(c, c.submit(chat, new Message("working"), false));
        drain(c);
        b.close();
        assertFalse(chat.loaded);
        await(c, c.connect("fake"));
        drain(c);
        assertEquals(1, b.sends);
        assertEquals("Сохранить", chat.ui.draft);
        b.emit(Event.delta(old.thread(chat.conversation.key.id), "late", "wrong world"));
        drain(c);
        assertEquals(-1, chat.timeline.index("late"));
        c.close();
        UiStateStore.State restored =
                new UiStateStore(world).read("fake", chat.conversation.key.id);
        assertEquals("Сохранить", restored.draft);
        assertEquals(3, restored.cursor);
        assertEquals(
                "",
                new UiStateStore(temp.newFolder()).read("fake", chat.conversation.key.id).draft);
    }

    @Test
    public void finalSnapshotReplacesDeltasAndHistoryCannotOverwriteNewLiveOutput() {
        Timeline t = new Timeline();
        Item item =
                new Item(
                        "i",
                        "t",
                        Kind.ASSISTANT,
                        State.RUNNING,
                        "Agent",
                        "",
                        Collections.<String, Value>emptyMap(),
                        Collections.<Resource>emptyList(),
                        null);
        t.snapshot(item);
        t.delta("i", "partial");
        long before = t.revision();
        t.delta("i", " new");
        t.prependHistory(Arrays.asList(item.withText("old")), before);
        assertEquals("partial new", t.get(0).text);
        t.snapshot(item.withText("final"));
        assertEquals(1, t.size());
        assertEquals("final", t.get(0).text);
        t.prependHistory(Arrays.asList(item.withText("persisted final")));
        assertEquals(1, t.size());
        assertEquals("persisted final", t.get(0).text);
    }

    @Test
    public void catalogAndExportTraverseEveryPageAndRelatedConversationOpens() throws Exception {
        FakeBackend b = new FakeBackend();
        File world = temp.newFolder();
        WorkspaceController first = new WorkspaceController(world, Arrays.asList(b));
        await(first, first.connect("fake"));
        WorkspaceController.Chat last = null;
        for (int i = 0; i < 27; i++) last = await(first, first.create("fake"));
        String id = last.conversation.key.id;
        for (int i = 0; i < 9; i++) {
            await(first, first.submit(last, new Message("page-message-" + i), false));
            drain(first);
            WorkspaceController.Pending pending = null;
            for (WorkspaceController.Pending candidate : last.requests.values())
                if (candidate.status == WorkspaceController.Pending.Status.OPEN)
                    pending = candidate;
            await(
                    first,
                    first.answer(
                            last,
                            pending,
                            Collections.singletonMap("material", Value.text("stone"))));
            drain(first);
        }
        await(first, first.rename(last, "Find me"));
        first.close();
        WorkspaceController restored = new WorkspaceController(world, Arrays.asList(b));
        await(restored, restored.connect("fake"));
        drain(restored);
        assertEquals(10, restored.chats().size());
        await(restored, restored.openRelated("fake", id));
        assertEquals(27, restored.chats().size());
        assertEquals("Find me", restored.selected().conversation.title);
        String transcript = await(restored, restored.export(restored.selected()));
        for (int i = 0; i < 9; i++) assertTrue(transcript.contains("page-message-" + i));
        assertEquals(27, restored.selected().timeline.size());
        assertEquals(9, b.sends);
        await(restored, restored.archive(restored.selected(), true));
        Page<Conversation> archived =
                await(restored, restored.loadCatalog("fake", "Find", true, null));
        assertEquals(1, archived.entries.size());
        await(restored, restored.archive(restored.selected(), false));
        assertFalse(restored.selected().conversation.archived);
        restored.close();
    }

    @Test
    public void persistedAttachmentsAndAccessProgramStayInsideWorldNamespace() throws Exception {
        File world = temp.newFolder();
        File attachment = new File(world, "notes.txt");
        assertTrue(attachment.createNewFile());
        FakeBackend b = new FakeBackend();
        WorkspaceController c = new WorkspaceController(world, Arrays.asList(b));
        await(c, c.connect("fake"));
        WorkspaceController.Chat chat = await(c, c.create("fake"));
        chat.ui.resources.add(c.workspaceResource(Resource.Type.FILE, "notes.txt"));
        chat.ui.settings =
                new Settings("test-model", "high", "standard", "plan", "workspace-write", "cyber");
        c.save(chat);
        UiStateStore.State state = new UiStateStore(world).read("fake", chat.conversation.key.id);
        assertEquals(attachment.getCanonicalPath(), state.resources.get(0).location);
        assertEquals("cyber", state.settings.accessProgram);
        try {
            c.workspaceResource(Resource.Type.FILE, "../outside.txt");
            fail("Must reject workspace escape");
        } catch (IOException expected) {
        }
        assertTrue(
                new UiStateStore(temp.newFolder())
                        .read("fake", chat.conversation.key.id)
                        .resources
                        .isEmpty());
        c.close();
    }
}
