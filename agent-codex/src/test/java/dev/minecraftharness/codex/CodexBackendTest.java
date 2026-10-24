package dev.minecraftharness.codex;

import static org.junit.Assert.*;

import com.google.gson.*;
import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.agent.testing.BackendScenarios;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class CodexBackendTest {
    public static class ReplayServer {
        static void output(JsonObject message) {
            System.out.println(message);
            System.out.flush();
        }

        static void event(String method, JsonObject params) {
            JsonObject e = Wire.params("method", method);
            e.add("params", params);
            output(e);
        }

        public static void main(String[] args) throws Exception {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            int sequence = 0, sends = 0;
            boolean questionsRegistered = false;
            String line;
            while ((line = in.readLine()) != null) {
                JsonObject message = new JsonParser().parse(line).getAsJsonObject();
                if (!message.has("method")) {
                    JsonObject p = new JsonObject();
                    p.add("response", message);
                    p.addProperty("threadId", "thread-1");
                    event("test/answer", p);
                    continue;
                }
                String method = Wire.text(message, "method");
                JsonObject p = Wire.object(message, "params"), r = new JsonObject();
                if (!message.has("id")) continue;
                if (method.equals("initialize"))
                    r.addProperty("userAgent", "codex_cli_rs/0.163.0 (Windows)");
                else if (method.equals("thread/start")
                        || method.equals("thread/resume")
                        || method.equals("thread/fork")) {
                    if (method.equals("thread/start"))
                        questionsRegistered =
                                Wire.array(p, "dynamicTools").toString().contains("ask_questions");
                    JsonObject thread =
                            Wire.params(
                                    "id",
                                    method.endsWith("resume")
                                            ? Wire.text(p, "threadId")
                                            : "thread-" + (++sequence));
                    thread.addProperty("cwd", new File(".").getCanonicalPath());
                    thread.addProperty("preview", "Fixture conversation");
                    r.add("thread", thread);
                    r.addProperty("model", "fixture");
                } else if (method.equals("thread/items/list")
                        || method.equals("thread/list")
                        || method.equals("model/list")) r.add("data", new JsonArray());
                else if (method.equals("turn/start")) {
                    JsonObject turn = Wire.params("id", "turn-" + (++sends));
                    turn.addProperty("status", "inProgress");
                    r.add("turn", turn);
                }
                r.add("sentParams", message.get("params"));
                r.addProperty("sentMethod", method);
                if (method.equals("externalAgentConfig/detect"))
                    r.add(
                            "items",
                            new JsonParser()
                                    .parse(
                                            "[{\"description\":\"Fixture\",\"itemType\":\"CONFIG\"}]"));
                if (method.equals("permissionProfile/list"))
                    r.add(
                            "data",
                            new JsonParser()
                                    .parse(
                                            "[{\"id\":\"locked\",\"allowed\":false,\"description\":\"Policy forbids\"},{\"id\":\"workspace\",\"allowed\":true}]"));
                if (method.equals("collaborationMode/list"))
                    r.add(
                            "data",
                            new JsonParser()
                                    .parse(
                                            "[{\"name\":\"Plan\",\"mode\":\"plan\",\"model\":\"fixture\"}]"));
                JsonObject response = new JsonObject();
                response.add("id", message.get("id"));
                response.add("result", r);
                output(response);
                if (method.equals("process/spawn")) {
                    JsonObject exit = Wire.params("processHandle", Wire.text(p, "processHandle"));
                    exit.addProperty("exitCode", 0);
                    exit.addProperty("stdout", "fixture diff");
                    exit.addProperty("stderr", "");
                    event("process/exited", exit);
                }
                if (method.equals("turn/start")) {
                    String thread = Wire.text(p, "threadId"), turn = "turn-" + sends;
                    JsonObject item = Wire.params("type", "agentMessage");
                    item.addProperty("id", "answer-" + sends);
                    item.addProperty("text", "");
                    JsonObject scope = Wire.params("threadId", thread);
                    scope.addProperty("turnId", turn);
                    scope.add("item", item);
                    event("item/started", scope);
                    JsonObject delta = Wire.params("threadId", thread);
                    delta.addProperty("turnId", turn);
                    delta.addProperty("itemId", "answer-" + sends);
                    delta.addProperty("delta", "partial");
                    event("item/agentMessage/delta", delta);
                    item.addProperty("text", "Final snapshot");
                    event("item/completed", scope);
                    event("account/updated", new JsonObject());
                    event("account/rateLimits/updated", new JsonObject());
                    event("thread/status/changed", scope);
                    event("thread/readState/changed", scope);
                    event("mcpServer/startupStatus/updated", scope);
                    event("remoteControl/status/changed", new JsonObject());
                    event("turn/moderationMetadata", scope);
                    if (p.toString().contains("ask-test")) {
                        if (!questionsRegistered)
                            throw new IllegalStateException("ask_questions was not registered");
                        JsonObject question = Wire.params("method", "item/tool/call");
                        question.addProperty("id", "question-call");
                        JsonObject params = Wire.params("threadId", thread);
                        params.addProperty("turnId", turn);
                        params.addProperty("callId", "question-call");
                        params.addProperty("tool", "ask_questions");
                        params.add(
                                "arguments",
                                new JsonParser()
                                        .parse(
                                                "{\"questions\":[{\"id\":\"material\",\"question\":\"Which material?\"}]}"));
                        question.add("params", params);
                        output(question);
                        continue;
                    }
                    JsonObject request =
                            Wire.params("method", "item/commandExecution/requestApproval");
                    request.add("id", sends == 1 ? new JsonPrimitive(7) : new JsonPrimitive("7"));
                    JsonObject approval = Wire.params("threadId", thread);
                    approval.addProperty("turnId", turn);
                    approval.addProperty("itemId", "command-" + sends);
                    approval.addProperty("command", "echo fixture");
                    approval.addProperty("startedAtMs", 0);
                    request.add("params", approval);
                    output(request);
                    if (p.toString().contains("unsupported-test")) {
                        JsonObject unknown = Wire.params("method", "future/mandatoryRequest");
                        unknown.addProperty("id", "unknown-request");
                        unknown.add("params", approval);
                        output(unknown);
                    }
                }
            }
        }
    }

    @Test
    public void dynamicQuestionsRoundTripThroughServerRequestAndExplicitAnswer() throws Exception {
        CodexBackend b = backend();
        List<Event> events = new ArrayList<Event>();
        try {
            BackendScenarios.await(b, b.connect(new File("."), events::add));
            Conversation chat = BackendScenarios.await(b, b.create(Settings.defaults()));
            BackendScenarios.await(
                    b, b.send(chat.key, new Message("ask-test"), Settings.defaults()));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (events.stream().noneMatch(e -> e.request != null)
                    && System.nanoTime() < deadline) {
                b.pump(32);
                Thread.sleep(1);
            }
            Request question =
                    events.stream().filter(e -> e.request != null).findFirst().get().request;
            assertEquals("material", question.form.fields.get(0).id);
            BackendScenarios.await(
                    b,
                    b.answer(
                            question.key,
                            Collections.singletonMap("material", Value.text("Stone"))));
            deadline = System.nanoTime() + 5_000_000_000L;
            while (events.stream()
                            .noneMatch(e -> e.item != null && e.item.title.equals("test/answer"))
                    && System.nanoTime() < deadline) {
                b.pump(32);
                Thread.sleep(1);
            }
            Item reply =
                    events.stream()
                            .filter(e -> e.item != null && e.item.title.equals("test/answer"))
                            .findFirst()
                            .get()
                            .item;
            Map<String, Value> result =
                    reply.details.get("response").object().get("result").object();
            assertTrue(result.get("success").bool());
            assertTrue(
                    result.get("contentItems")
                            .array()
                            .get(0)
                            .object()
                            .get("text")
                            .text()
                            .contains("Stone"));
            assertTrue(events.stream().anyMatch(e -> e.type == EventType.REQUEST_RESOLVED));
            assertFalse(events.stream().anyMatch(e -> e.type == EventType.WARNING));
        } finally {
            b.close();
        }
    }

    CodexBackend backend() throws Exception {
        String path =
                location(CodexBackendTest.class)
                        + File.pathSeparator
                        + location(CodexBackend.class)
                        + File.pathSeparator
                        + location(JsonObject.class)
                        + File.pathSeparator
                        + location(AgentBackend.class);
        return new CodexBackend(
                Arrays.asList(
                        new File(System.getProperty("java.home"), "bin/java").getPath(),
                        "-cp",
                        path,
                        ReplayServer.class.getName()));
    }

    private static String location(Class<?> type) throws Exception {
        return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
    }

    @Test
    public void sharedLifecycleContract() throws Exception {
        BackendScenarios.lifecycle(backend(), new File("."));
    }

    @Test
    public void preservesNumericAndStringRequestIdsAndRoutesInterleavedThreads() throws Exception {
        CodexBackend b = backend();
        List<Event> events = new ArrayList<Event>();
        try {
            BackendScenarios.await(b, b.connect(new File("."), events::add));
            Conversation a = BackendScenarios.await(b, b.create(Settings.defaults())),
                    c = BackendScenarios.await(b, b.create(Settings.defaults()));
            BackendScenarios.await(b, b.send(a.key, new Message("A"), Settings.defaults()));
            BackendScenarios.await(b, b.send(c.key, new Message("B"), Settings.defaults()));
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (events.stream().filter(e -> e.request != null).count() < 2
                    && System.nanoTime() < deadline) {
                b.pump(1);
                Thread.sleep(1);
            }
            List<Request> requests = new ArrayList<Request>();
            for (Event e : events) if (e.request != null) requests.add(e.request);
            assertEquals(2, requests.size());
            assertNotEquals(requests.get(0).key, requests.get(1).key);
            Map<String, Value> denied = Collections.singletonMap("decision", Value.text("decline"));
            for (Request request : requests)
                BackendScenarios.await(b, b.answer(request.key, denied));
            assertTrue(b.answer(requests.get(0).key, denied).isCompletedExceptionally());
            deadline = System.nanoTime() + 5_000_000_000L;
            while (events.stream()
                                    .filter(
                                            e ->
                                                    e.item != null
                                                            && e.item.title.equals("test/answer"))
                                    .count()
                            < 2
                    && System.nanoTime() < deadline) {
                b.pump(16);
                Thread.sleep(1);
            }
            Set<Value.Type> types = new HashSet<Value.Type>();
            for (Event e : events)
                if (e.item != null && e.item.title.equals("test/answer"))
                    types.add(e.item.details.get("response").object().get("id").type);
            assertEquals(
                    new HashSet<Value.Type>(Arrays.asList(Value.Type.TEXT, Value.Type.NUMBER)),
                    types);
            assertEquals(
                    2,
                    events.stream()
                            .filter(e -> e.item != null && e.item.text.equals("Final snapshot"))
                            .count());
            assertFalse(
                    "Account notifications are not errors",
                    events.stream().anyMatch(e -> e.type == EventType.WARNING));
            assertFalse(
                    "Transport bookkeeping must not displace the answer",
                    events.stream()
                            .anyMatch(
                                    e ->
                                            e.item != null
                                                    && (e.item.title.contains("status/changed")
                                                            || e.item.title.contains("readState")
                                                            || e.item.title.contains(
                                                                    "startupStatus")
                                                            || e.item.title.contains(
                                                                    "moderationMetadata"))));
        } finally {
            b.close();
        }
    }

    @Test
    public void formAnswersHaveProtocolShapeAndUnsupportedConstraintsAreRejected() {
        JsonObject question =
                new JsonParser()
                        .parse(
                                "{\"questions\":[{\"id\":\"q\",\"header\":\"Q\",\"question\":\"Choose\",\"options\":[{\"label\":\"Yes\",\"description\":\"Continue\"}]}]}")
                        .getAsJsonObject();
        Form f = CodexForms.request("item/tool/requestUserInput", question);
        Map<String, Value> fields = Collections.singletonMap("q", Value.text("Yes"));
        assertTrue(f.validate(fields).isEmpty());
        JsonObject answer = CodexForms.answer("item/tool/requestUserInput", fields);
        assertEquals(
                "Yes",
                answer.getAsJsonObject("answers")
                        .getAsJsonObject("q")
                        .getAsJsonArray("answers")
                        .get(0)
                        .getAsString());
        try {
            CodexForms.decode(
                    new JsonParser()
                            .parse(
                                    "{\"type\":\"object\",\"properties\":{\"date\":{\"type\":\"string\",\"format\":\"date\"}}}")
                            .getAsJsonObject());
            fail("Constraint silently discarded");
        } catch (Failure expected) {
            assertEquals(Failure.Code.UNAVAILABLE, expected.code);
        }
    }

    @Test
    public void policyProfilesAndGitInspectionUseAdvertisedCapabilities() throws Exception {
        CodexBackend b = backend();
        try {
            BackendScenarios.await(b, b.connect(new File("."), e -> {}));
            BackendScenarios.await(b, b.capabilities());
            BackendScenarios.await(
                    b, b.act(null, "account", Collections.<String, Value>emptyMap()));
            Capabilities caps = BackendScenarios.await(b, b.capabilities());
            Action locked =
                    caps.actions.stream()
                            .filter(a -> a.id.equals("profile:locked"))
                            .findFirst()
                            .get();
            assertFalse(locked.availability.available);
            Conversation c = BackendScenarios.await(b, b.create(Settings.defaults()));
            assertTrue(
                    b.act(c.key, "profile:locked", Collections.<String, Value>emptyMap())
                            .isCompletedExceptionally());
            ActionResult mode =
                    BackendScenarios.await(
                            b, b.act(c.key, "mode:plan", Collections.<String, Value>emptyMap()));
            assertEquals("plan", mode.settings.mode);
            assertEquals(
                    "fixture diff",
                    BackendScenarios.await(
                                    b, b.act(c.key, "diff", Collections.<String, Value>emptyMap()))
                            .text);
            for (Action action : ExternalActions.catalog()) {
                ActionResult route =
                        BackendScenarios.await(
                                b, b.act(c.key, action.id, Collections.<String, Value>emptyMap()));
                assertTrue(route.text.contains("Run externally:"));
                assertFalse(route.text.contains("not implemented"));
            }
        } finally {
            b.close();
        }
    }

    @Test
    public void unknownRequiredRequestGetsErrorInsteadOfApprovalOrHang() throws Exception {
        CodexBackend b = backend();
        List<Event> events = new ArrayList<Event>();
        try {
            BackendScenarios.await(b, b.connect(new File("."), events::add));
            Conversation c = BackendScenarios.await(b, b.create(Settings.defaults()));
            BackendScenarios.await(
                    b, b.send(c.key, new Message("unsupported-test"), Settings.defaults()));
            long end = System.nanoTime() + 5_000_000_000L;
            while (events.stream()
                            .noneMatch(e -> e.item != null && e.item.title.equals("test/answer"))
                    && System.nanoTime() < end) {
                b.pump(32);
                Thread.sleep(1);
            }
            Item response =
                    events.stream()
                            .filter(e -> e.item != null && e.item.title.equals("test/answer"))
                            .findFirst()
                            .get()
                            .item;
            Map<String, Value> envelope = response.details.get("response").object();
            assertEquals("unknown-request", envelope.get("id").text());
            assertTrue(envelope.containsKey("error"));
            assertFalse(envelope.containsKey("result"));
        } finally {
            b.close();
        }
    }
}
