package dev.minecraftharness.chat;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.chat.widget.*;
import dev.minecraftharness.core.*;
import dev.minecraftharness.core.WorkspaceController.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Portable interaction and layout. The Minecraft adapter contains no menu/chat logic. */
public final class ChatPanel {
    public enum Key {
        ENTER,
        ESCAPE,
        TAB,
        LEFT,
        RIGHT,
        UP,
        DOWN,
        HOME,
        END,
        PAGEUP,
        PAGEDOWN,
        BACKSPACE,
        DELETE,
        A,
        C,
        V,
        X,
        OTHER
    }

    private static final int BG = 0xd0202428,
            FIELD = 0xe030353a,
            FG = 0xffeeeeee,
            MUTED = 0xffb2bac2,
            ACCENT = 0xff8fc9ff,
            ERROR = 0xffffac92;

    private static final class Control {
        final String id, label;
        final int x, y, w, h;
        final Runnable action;

        Control(String id, String label, int x, int y, int w, int h, Runnable action) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }
    }

    private final WorkspaceController core;
    private final List<Control> controls = new ArrayList<Control>();
    private final TextCache textCache = new TextCache();
    private Surface surface;
    private String backendId, focus = "composer", overlay = "", error = "";
    private Chat editing;
    private Editor composer = new Editor("", 0), search = new Editor("", 0);
    private int slashIndex;
    private boolean slashDismissed;
    private String actionFilter = "";
    private int historyIndex = -1, menuOffset, detailOffset, formOffset;
    private String historyDraft = "";
    private boolean expanded, archived, steer, starting;
    private boolean vim, vimInsert = true, highContrast;
    private String panelTitle = "";
    private int width, height, top, bottom, line, buttonHeight;
    private String detailTitle = "", detailText = "";
    private String detailCopyText = "";
    private int detailWidgetOffset, detailWidgetMaximum, detailWidgetViewport;
    private final WidgetRegistry widgets;
    private int timelineOffset, firstVisible, timelineViewport, latestStart;
    private final Map<String, InlineLayout> inlineCache =
            new LinkedHashMap<String, InlineLayout>(32, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, InlineLayout> entry) {
                    return size() > 32;
                }
            };

    private static final class InlinePart {
        final String text;
        final WidgetRegistry.Entry widget;
        final int y, height;

        InlinePart(String text, WidgetRegistry.Entry widget, int y, int height) {
            this.text = text;
            this.widget = widget;
            this.y = y;
            this.height = height;
        }
    }

    private static final class InlineLayout {
        final List<InlinePart> parts = new ArrayList<InlinePart>();
        int height;
    }

    private WidgetRegistry.Message detailWidgets;
    private int widgetIndex;
    private final Map<String, WidgetRegistry.Message> widgetCache =
            new LinkedHashMap<String, WidgetRegistry.Message>(32, 0.75f, true) {
                protected boolean removeEldestEntry(
                        Map.Entry<String, WidgetRegistry.Message> entry) {
                    return size() > 32;
                }
            };
    private Editor detailSelection = new Editor("", 0);
    private final Deque<Integer> detailPages = new ArrayDeque<Integer>();
    private int detailEnd;
    private List<Resource> detailResources = Collections.emptyList();
    private Form activeForm;
    private String formTitle = "", formDescription = "";
    private final Map<String, Value> formValues = new LinkedHashMap<String, Value>();
    private Map<String, String> formErrors = Collections.emptyMap();
    private final Map<String, Editor> formEditors = new HashMap<String, Editor>();
    private Function<Map<String, Value>, CompletableFuture<?>> formSubmit;
    private boolean formSending;
    private Runnable confirmed;
    private String confirmation = "";
    private long readRevision;
    public int renderedItems, measuredCharacters;

    public ChatPanel(WorkspaceController core) {
        this(core, WidgetRegistry.defaults());
    }

    public ChatPanel(WorkspaceController core, WidgetRegistry widgets) {
        this.widgets = widgets.snapshot();
        this.core = core;
        if (!core.backends().isEmpty()) backendId = core.backends().iterator().next().backend.id();
    }

    public WorkspaceController controller() {
        return core;
    }

    public String focusedControl() {
        return focus;
    }

    public String overlay() {
        return overlay;
    }

    public void opacity(float opacity) {
        this.opacity = Math.max(0.1f, Math.min(1, opacity));
    }

    private float opacity = 1;

    private int color(int color) {
        if (highContrast && (color == BG || color == FIELD)) color = 0xff000000;
        return ((int) (((color >>> 24) & 255) * opacity) << 24) | (color & 0xffffff);
    }

    private void rect(int x, int y, int w, int h, int color) {
        if (w > 0 && h > 0) surface.rectangle(x, y, x + w, y + h, color(color));
    }

    private String safe(String text) {
        return text.replace('\u00a7', '?').replace('\t', ' ');
    }

    private void text(String text, int x, int y, int width, int color) {
        surface.clip(x, y, x + Math.max(1, width), y + line);
        surface.text(safe(text), x, y, color(color));
        surface.unclip();
    }

    private void button(String id, String label, int x, int y, int w, Runnable action) {
        if (w < 1) return;
        Control control = new Control(id, label, x, y, w, buttonHeight, action);
        controls.add(control);
        rect(x, y, w, buttonHeight, focus.equals(id) ? 0xff45617a : FIELD);
        text(label, x + 4, y + 4, w - 8, focus.equals(id) ? ACCENT : FG);
    }

    private BackendState backend() {
        return core.backend(backendId);
    }

    private void bind() {
        Chat chat = core.selected();
        if (chat != editing) {
            sync();
            editing = chat;
            timelineOffset = 0;
            inlineCache.clear();
            widgetCache.clear();
            composer =
                    new Editor(
                            chat == null ? "" : chat.ui.draft, chat == null ? 0 : chat.ui.cursor);
            historyIndex = -1;
            focus = "composer";
            overlay = "";
            readRevision = chat == null ? 0 : chat.timeline.revision();
            if (chat != null) {
                backendId = chat.conversation.key.connection.backend;
                expanded = chat.ui.expanded;
            }
        } else if (chat != null && !chat.ui.draft.equals(composer.text))
            composer = new Editor(chat.ui.draft, chat.ui.cursor);
    }

    private void sync() {
        if (editing != null) core.draft(editing, composer.text, composer.cursor);
    }

    public void activated() {
        focus = "composer";
    }

    public void closed() {
        sync();
        if (editing != null) {
            editing.ui.expanded = expanded;
            core.save(editing);
        }
    }

    public void render(Surface surface, int width, int height) {
        this.surface = surface;
        this.width = Math.max(120, width);
        this.height = height;
        line = surface.lineHeight();
        buttonHeight = line + 8;
        bind();
        controls.clear();
        renderedItems = 0;
        textCache.measuredCharacters = 0;
        int panelHeight = expanded ? height - 8 : Math.min(height - 8, 180);
        top = Math.max(4, height - panelHeight - 4);
        bottom = height - 4;
        rect(4, top, this.width - 8, panelHeight, BG);
        int y = top + 4;
        button(
                "chat",
                "Chat",
                8,
                y,
                42,
                () -> {
                    closed();
                    surface.ordinaryChat();
                });
        button(
                "expand",
                expanded ? "Compact" : "Expand",
                this.width - 76,
                y,
                64,
                () -> expanded = !expanded);
        y += buttonHeight + 4;
        String status =
                editing == null
                        ? (starting ? "Starting conversation..." : "Send a message to start")
                        : (panelTitle.isEmpty() ? editing.conversation.title : panelTitle)
                                + " · "
                                + status(editing);
        text(status, 8, y, this.width - 16, ACCENT);
        y += line + 3;
        text(
                "Workspace: "
                        + core.workspace().getName()
                        + (editing != null && !editing.ui.resources.isEmpty()
                                ? " · attached " + editing.ui.resources.size()
                                : ""),
                8,
                y,
                this.width - 16,
                MUTED);
        y += line + 3;
        if (expanded
                && editing != null
                && (editing.ui.settings.effort != null || editing.ui.settings.tier != null)) {
            text(
                    "Effort: "
                            + editing.ui.settings.effort
                            + " · tier: "
                            + editing.ui.settings.tier,
                    8,
                    y,
                    this.width - 16,
                    MUTED);
            y += line + 2;
        }
        if (expanded && editing != null && editing.busy()) {
            text(
                    "Model / effort / tier changes apply to the next message",
                    8,
                    y,
                    this.width - 16,
                    MUTED);
            y += line + 2;
        }
        String issue =
                !error.isEmpty()
                        ? error
                        : editing != null && !editing.error.isEmpty()
                                ? editing.error
                                : backend() != null ? backend().error : "No backend";
        if (issue.isEmpty()) issue = core.notice;
        if (!issue.isEmpty()) {
            text(issue, 8, y, this.width - 16, ERROR);
            y += line + 2;
        }
        int composerY = bottom - 3 * line - buttonHeight - 16;
        int timelineBottom = composerY - 4;
        if (editing != null) {
            if (!editing.queue.isEmpty()) {
                timelineBottom -= buttonHeight + 4;
                button(
                        "queue",
                        "Queued: " + editing.queue.size(),
                        8,
                        timelineBottom,
                        100,
                        () -> menu("queue"));
            }
            Pending pending = firstRequest(editing);
            if (pending != null) {
                timelineBottom -= buttonHeight + 4;
                button(
                        "request",
                        "! " + pending.request.title,
                        8,
                        timelineBottom,
                        this.width - 16,
                        () -> request(pending));
            }
            timeline(y, Math.max(y, timelineBottom));
        }
        composer(composerY);
        if (!overlay.isEmpty()) drawOverlay();
        else if (slashVisible()) drawSlash(composerY);
        measuredCharacters = textCache.measuredCharacters;
    }

    private String status(Chat c) {
        return c.needsAnswer()
                ? "Needs answer"
                : c.busy()
                        ? "Working"
                        : !c.loaded ? "Resume required" : !c.error.isEmpty() ? "Error" : "Ready";
    }

    private Pending firstRequest(Chat chat) {
        for (Pending p : chat.requests.values())
            if (p.status == Pending.Status.OPEN || p.status == Pending.Status.SENDING) return p;
        return null;
    }

    private boolean readable(Item item) {
        return item.kind == Kind.USER
                || item.kind == Kind.ASSISTANT
                || item.kind == Kind.SUMMARY
                || item.kind == Kind.PLAN
                || item.kind == Kind.ERROR;
    }

    private void inlineText(InlineLayout layout, String value) {
        TextWindow wrapped =
                new TextWindow(surface, safe(value), 0, this.width - 24, Integer.MAX_VALUE);
        for (String row : wrapped.lines) {
            layout.parts.add(new InlinePart(row, null, layout.height, line));
            layout.height += line;
        }
    }

    private InlineLayout inlineLayout(Item item) {
        String key = item.kind + ":" + width + ":" + line + ":" + item.text;
        InlineLayout layout = inlineCache.get(key);
        if (layout != null) return layout;
        layout = new InlineLayout();
        layout.height = line + 4;
        WidgetRegistry.Message message =
                item.kind == Kind.ASSISTANT ? widgetMessage(item.text) : null;
        if (message == null) inlineText(layout, item.text);
        else
            for (WidgetRegistry.Part part : message.parts) {
                if (part.widget == null) inlineText(layout, part.text);
                else {
                    WidgetRegistry.Entry widget = part.widget;
                    layout.height += 4;
                    inlineText(layout, widget.title);
                    int h =
                            widget.height(
                                    this.width - 24, surface.lineHeight(), surface::textWidth);
                    if (h > 0) {
                        layout.parts.add(new InlinePart(null, widget, layout.height, h));
                        layout.height += h + 4;
                    }
                    if (h == 0 || !widget.includesText()) inlineText(layout, widget.text);
                }
            }
        layout.height = Math.max(3 * line + 8, layout.height + 7);
        inlineCache.put(key, layout);
        return layout;
    }

    private int itemHeight(Item item) {
        return readable(item) ? inlineLayout(item).height : 3 * line + 8;
    }

    private void timeline(int y, int end) {
        Timeline timeline = editing.timeline;
        int total = timeline.size();
        timelineViewport = Math.max(0, end - y);
        if (total == 0 || timelineViewport == 0) return;
        latestStart = total - 1;
        int used = itemHeight(timeline.get(latestStart));
        while (latestStart > 0 && used < timelineViewport) {
            int previous = itemHeight(timeline.get(latestStart - 1));
            if (used + previous > timelineViewport) break;
            latestStart--;
            used += previous;
        }
        int start = editing.ui.anchor.isEmpty() ? latestStart : timeline.index(editing.ui.anchor);
        if (start < 0) {
            start = latestStart;
            timelineOffset = 0;
        }
        if (editing.ui.anchor.isEmpty()) {
            timelineOffset = 0;
            readRevision = timeline.revision();
        }
        firstVisible = start;
        timelineOffset =
                Math.min(timelineOffset, Math.max(0, itemHeight(timeline.get(start)) - line));
        int viewportTop = y;
        y -= timelineOffset;
        surface.clip(8, viewportTop, this.width - 8, end);
        for (int i = start; i < total && y < end; i++) {
            final Item item = timeline.get(i);
            int cardHeight = itemHeight(item);
            renderedItems++;
            rect(8, y, this.width - 16, cardHeight - 3, FIELD);
            int hitTop = Math.max(viewportTop, y), hitBottom = Math.min(end, y + cardHeight - 3);
            // Opening details remains optional for copying and metadata, never required to read
            // prose.
            if (hitBottom > hitTop)
                controls.add(
                        new Control(
                                "item:" + item.id,
                                item.title,
                                8,
                                hitTop,
                                this.width - 16,
                                hitBottom - hitTop,
                                () -> inspect(item)));
            String title =
                    readable(item)
                            ? item.title
                            : item.title
                                    + " · "
                                    + item.state.name().toLowerCase(Locale.ROOT)
                                    + (item.elapsedMillis == null
                                            ? ""
                                            : " · " + item.elapsedMillis + " ms");
            if (y + line + 3 >= viewportTop)
                text(
                        title,
                        12,
                        y + 3,
                        this.width - 24,
                        item.state == State.FAILED ? ERROR : ACCENT);
            if (readable(item)) {
                List<InlinePart> parts = inlineLayout(item).parts;
                int low = 0, high = parts.size();
                while (low < high) {
                    int middle = (low + high) / 2;
                    InlinePart part = parts.get(middle);
                    if (y + part.y + part.height <= viewportTop) low = middle + 1;
                    else high = middle;
                }
                for (int partIndex = low; partIndex < parts.size(); partIndex++) {
                    InlinePart part = parts.get(partIndex);
                    int yy = y + part.y;
                    if (yy + part.height <= viewportTop) continue;
                    if (yy >= end) break;
                    if (part.widget == null) text(part.text, 12, yy, this.width - 24, FG);
                    else {
                        final int selected = widgetMessage(item.text).widgets.indexOf(part.widget);
                        int widgetTop = Math.max(viewportTop, yy),
                                widgetBottom = Math.min(end, yy + part.height);
                        if (widgetBottom > widgetTop)
                            controls.add(
                                    new Control(
                                            "widget:" + item.id + ":" + selected,
                                            part.widget.title,
                                            12,
                                            widgetTop,
                                            this.width - 24,
                                            widgetBottom - widgetTop,
                                            () -> {
                                                inspect(item);
                                                selectWidget(selected);
                                            }));
                        surface.clip(12, yy, this.width - 12, yy + part.height);
                        try {
                            part.widget.render(
                                    new WidgetCanvas(
                                            surface, 12, yy, this.width - 24, part.height));
                        } finally {
                            surface.unclip();
                        }
                        if (part.widget.height(
                                        this.width - 24, surface.lineHeight(), surface::textWidth)
                                == 0) inlineCache.clear();
                    }
                }
            } else {
                List<String> rows =
                        textCache.preview(
                                surface,
                                safe(item.text.substring(0, Math.min(512, item.text.length()))),
                                this.width - 24,
                                2);
                int yy = y + line + 4;
                for (String row : rows) {
                    text(row, 12, yy, this.width - 24, FG);
                    yy += line;
                }
            }
            y += cardHeight;
        }
        surface.unclip();
        if (!editing.ui.anchor.isEmpty() && timeline.revision() != readRevision)
            button(
                    "latest",
                    "New events ↓",
                    Math.max(8, this.width - 112),
                    Math.max(top + 4, end - buttonHeight),
                    100,
                    () -> {
                        editing.ui.anchor = "";
                        timelineOffset = 0;
                        readRevision = timeline.revision();
                    });
    }

    private void composer(int y) {
        rect(8, y, this.width - 16, 3 * line + 6, focus.equals("composer") ? 0xff343f49 : FIELD);
        controls.add(
                new Control(
                        "composer",
                        "Composer",
                        8,
                        y,
                        this.width - 16,
                        3 * line + 6,
                        () -> focus = "composer"));
        int from = Math.max(0, composer.cursor - 160);
        if (from > 0 && Character.isLowSurrogate(composer.text.charAt(from))) from--;
        TextWindow inputWindow = new TextWindow(surface, composer.text, from, this.width - 24, 3);
        while (inputWindow.end < composer.cursor && from < composer.cursor) {
            from = composer.text.offsetByCodePoints(from, 1);
            inputWindow = new TextWindow(surface, composer.text, from, this.width - 24, 3);
        }
        List<String> lines = inputWindow.lines;
        int yy = y + 3;
        for (String value : lines) {
            text(value, 12, yy, this.width - 24, FG);
            yy += line;
        }
        if (composer.text.isEmpty()) text("Message or /command", 12, y + 3, this.width - 24, MUTED);
        if (focus.equals("composer")) {
            int row = 0, start = from;
            for (int i = 0; i < inputWindow.starts.size(); i++)
                if (inputWindow.starts.get(i) <= composer.cursor) {
                    row = i;
                    start = inputWindow.starts.get(i);
                }
            rect(
                    Math.min(
                            this.width - 14,
                            12
                                    + surface.textWidth(
                                            safe(composer.text.substring(start, composer.cursor)))),
                    y + 3 + row * line,
                    vim && !vimInsert ? 5 : 1,
                    line,
                    ACCENT);
        }
        y += 3 * line + 10;
        text("/ commands  ·  Enter send  ·  Shift+Enter newline", 8, y, this.width - 16, MUTED);
    }

    private void menu(String type) {
        actionFilter = "";
        sync();
        overlay = type;
        menuOffset = 0;
        search = new Editor("", 0);
        focus = "search";
    }

    private void send() {
        if (starting) return;
        if (composer.text.startsWith("/")) {
            executeSlash(composer.text);
            return;
        }
        if (composer.text.trim().isEmpty() && (editing == null || editing.ui.resources.isEmpty()))
            return;
        if (editing == null) {
            if (backend() == null) {
                error = "No backend available";
                return;
            }
            if (backend().connecting) {
                error = "Connecting; send again when ready";
                return;
            }
            final String target = backendId;
            final Message first = new Message(composer.text);
            starting = true;
            CompletableFuture<?> connected =
                    backend().ready
                            ? CompletableFuture.completedFuture(null)
                            : core.connect(target);
            watch(
                    connected
                            .thenCompose(ignored -> core.create(target))
                            .thenCompose(
                                    chat -> {
                                        // Keep edits made while connection/start was in flight in
                                        // the new draft.
                                        core.draft(chat, composer.text, composer.cursor);
                                        bind();
                                        return core.submit(chat, first, false);
                                    })
                            .whenComplete((ignored, problem) -> starting = false));
            return;
        }
        sync();
        watch(core.submit(editing, new Message(composer.text, editing.ui.resources), steer));
    }

    private Map<String, String> commandRoutes() {
        Map<String, String> routes = new LinkedHashMap<String, String>(SlashCommands.ROUTES);
        if (backend() != null && backend().capabilities != null)
            for (Action a : backend().capabilities.actions)
                if (!a.id.contains(":")) routes.putIfAbsent(a.id, "action:" + a.id);
        return routes;
    }

    private boolean slashVisible() {
        return !slashDismissed
                && focus.equals("composer")
                && composer.text.startsWith("/")
                && composer.text.indexOf(' ') == -1
                && composer.text.indexOf('\n') == -1;
    }

    private List<String> slashMatches() {
        List<String> out = new ArrayList<String>();
        String prefix = composer.text.substring(1).toLowerCase(Locale.ROOT);
        for (String name : commandRoutes().keySet())
            if (name.startsWith(prefix)) {
                if (name.equals(prefix)) out.add(0, name);
                else out.add(name);
            }
        return out;
    }

    private void drawSlash(int composerY) {
        List<String> matches = slashMatches();
        if (matches.isEmpty()) {
            text("Unknown command · /help", 12, composerY - line - 4, this.width - 24, ERROR);
            return;
        }
        slashIndex = Math.max(0, Math.min(slashIndex, matches.size() - 1));
        int rows = Math.max(1, Math.min(5, (composerY - top - 8) / (line + 4))),
                start = Math.max(0, slashIndex - rows + 1);
        int y = composerY - rows * (line + 4) - 4;
        rect(8, y, this.width - 16, rows * (line + 4), BG);
        for (int i = start; i < Math.min(matches.size(), start + rows); i++) {
            final String name = matches.get(i);
            int yy = y + (i - start) * (line + 4);
            if (i == slashIndex) rect(8, yy, this.width - 16, line + 4, FIELD);
            text(
                    (i == slashIndex ? "> " : "  ") + "/" + name + "  " + commandDescription(name),
                    12,
                    yy + 2,
                    this.width - 24,
                    i == slashIndex ? ACCENT : FG);
            controls.add(
                    new Control(
                            "slash:" + name,
                            name,
                            8,
                            yy,
                            this.width - 16,
                            line + 4,
                            () -> executeSlash("/" + name)));
        }
    }

    private String commandDescription(String name) {
        String route = commandRoutes().get(name);
        if (route.startsWith("action:") && backend() != null && backend().capabilities != null)
            for (Action a : backend().capabilities.actions)
                if (route.equals("action:" + a.id))
                    return a.title + (a.availability.available ? "" : " (unavailable)");
        if (route.equals("new")) return "Start a new conversation";
        if (route.equals("models")) return "Choose model and parameters";
        if (route.equals("conversations")) return "Find and resume a conversation";
        if (route.equals("stop")) return "Interrupt the current task";
        if (route.equals("help")) return "Commands and keyboard help";
        if (route.startsWith("filter:"))
            return "Choose " + route.substring(7).replace(":", "").replace(".", "") + " operation";
        return "Open " + route.replace("action:", "").replace("external.", "").replace('.', ' ');
    }

    private void executeSlash(String input) {
        String[] parts = input.trim().substring(1).split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT),
                argument = parts.length > 1 ? parts[1] : "";
        String route = commandRoutes().get(name);
        if (route == null) {
            error = "Unknown command /" + name + ". Use /help.";
            return;
        }
        if (!argument.isEmpty()
                && !name.equals("rename")
                && !name.equals("model")
                && !name.equals("resume")
                && !name.equals("agents")) {
            error = "/" + name + " opens an interactive choice; run it without arguments.";
            return;
        }
        composer = new Editor("", 0);
        focus = "composer";
        sync();
        slashDismissed = false;
        slashIndex = 0;
        error = "";
        if (route.equals("chat")) {
            closed();
            surface.ordinaryChat();
            return;
        }
        if (route.equals("expand")) {
            expanded = !expanded;
            return;
        }
        if (route.equals("help")) {
            inspect(
                    "Commands",
                    "Type / to find commands. Up/Down selects; Tab completes; Enter runs.\nEscape hides suggestions or returns to input.\n/new /clear: new conversation\n/model /resume /mcp /skills /permissions\n/stop: interrupt turn; /queue: queued messages; /steer: toggle steering\n/commands: all operations\nShift+Enter: newline; Ctrl+Up/Down: input history\nUnknown commands are never sent to the model.",
                    Collections.<Resource>emptyList());
            return;
        }
        if (backend() == null) {
            error = "No backend available";
            composer = new Editor(input, input.length());
            sync();
            return;
        }
        starting = true;
        CompletableFuture<?> ready =
                backend().ready && !route.equals("connect")
                        ? CompletableFuture.completedFuture(null)
                        : core.connect(backendId);
        watch(
                ready.thenCompose(
                                ignored ->
                                        backend().capabilities != null
                                                ? CompletableFuture.completedFuture(null)
                                                : backend()
                                                        .backend
                                                        .capabilities()
                                                        .thenAccept(
                                                                c -> backend().capabilities = c))
                        .thenCompose(
                                ignored ->
                                        (route.equals("new") || core.selected() == null)
                                                ? core.create(backendId)
                                                : CompletableFuture.completedFuture(
                                                        core.selected()))
                        .thenAccept(
                                chat -> {
                                    bind();
                                    if (route.equals("new")) {
                                        focus = "composer";
                                        return;
                                    }
                                    if (name.equals("rename") && !argument.isEmpty()) {
                                        watch(core.rename(chat, argument));
                                        return;
                                    }
                                    if (route.equals("connect")) return;
                                    if (route.equals("stop")) {
                                        watch(core.interrupt(chat));
                                        return;
                                    }
                                    if (route.equals("steer")) {
                                        steer = !steer;
                                        core.notice =
                                                steer
                                                        ? "Busy messages steer the current turn"
                                                        : "Busy messages join the queue";
                                        return;
                                    }
                                    if (Arrays.asList(
                                                    "models",
                                                    "conversations",
                                                    "parameters",
                                                    "attachments",
                                                    "queue",
                                                    "backends",
                                                    "actions")
                                            .contains(route)) {
                                        menu(route);
                                        if (!argument.isEmpty())
                                            search = new Editor(argument, argument.length());
                                        return;
                                    }
                                    if (route.startsWith("action:")
                                            && backend().capabilities != null)
                                        for (Action action : backend().capabilities.actions) {
                                            boolean required = false;
                                            for (Form.Field field : action.form.fields)
                                                if (field.required) required = true;
                                            if (route.equals("action:" + action.id)
                                                    && !required
                                                    && !action.confirmationRequired
                                                    && action.availability.available) {
                                                watch(
                                                        core.act(
                                                                        backendId,
                                                                        chat,
                                                                        action.id,
                                                                        Collections
                                                                                .<String, Value>
                                                                                        emptyMap())
                                                                .thenAccept(
                                                                        result ->
                                                                                inspect(
                                                                                        result.title,
                                                                                        describe(
                                                                                                        result.fields)
                                                                                                + result.text,
                                                                                        result.resources)));
                                                return;
                                            }
                                        }
                                    menu("actions");
                                    if (route.startsWith("filter:")) {
                                        actionFilter = route.substring(7);
                                        return;
                                    }
                                    for (MenuEntry entry : entries())
                                        if (entry.id.equals(route)) {
                                            entry.action.run();
                                            return;
                                        }
                                    overlay = "";
                                    focus = "composer";
                                    throw new IllegalArgumentException(
                                            "/" + name + " is unavailable on this backend");
                                })
                        .whenComplete(
                                (ignored, problem) -> {
                                    starting = false;
                                    if (problem != null && composer.text.isEmpty()) {
                                        composer = new Editor(input, input.length());
                                        sync();
                                    }
                                }));
    }

    private void watch(CompletableFuture<?> f) {
        f.whenComplete(
                (r, e) -> {
                    if (e != null) error = message(e);
                    else error = "";
                });
    }

    private String message(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private void drawOverlay() {
        controls.clear();
        int x = 12,
                y =
                        overlay.equals("details")
                                        && detailWidgets != null
                                        && !detailWidgets.widgets.isEmpty()
                                ? 12
                                : top + 8,
                w = this.width - 24,
                h = bottom - y - 4;
        rect(x, y, w, h, 0xf0202428);
        button(
                "back",
                "Back",
                x + 4,
                y + 4,
                52,
                () -> {
                    overlay = "";
                    focus = "composer";
                });
        text(overlay, x + 64, y + 8, w - 72, ACCENT);
        y += buttonHeight + 12;
        if (overlay.equals("form")) {
            drawForm(x + 4, y, w - 8, bottom - 8);
            return;
        }
        if (overlay.equals("confirm")) {
            for (String part : textCache.preview(surface, confirmation, w - 8, 6)) {
                text(part, x + 4, y, w - 8, FG);
                y += line;
            }
            button(
                    "confirm",
                    "Continue",
                    x + 4,
                    Math.min(bottom - buttonHeight - 8, y + 8),
                    80,
                    () -> {
                        Runnable action = confirmed;
                        overlay = "";
                        action.run();
                    });
            return;
        }
        if (overlay.equals("details")) {
            details(x + 4, y, w - 8, bottom - 8);
            return;
        }
        rect(x + 4, y, w - 8, buttonHeight, FIELD);
        text(search.text.isEmpty() ? "Search…" : search.text, x + 8, y + 4, w - 16, FG);
        controls.add(
                new Control(
                        "search", "Search", x + 4, y, w - 8, buttonHeight, () -> focus = "search"));
        y += buttonHeight + 4;
        List<MenuEntry> entries = entries();
        int visible = Math.max(1, (bottom - y - 8) / (buttonHeight + 3));
        menuOffset = Math.min(menuOffset, Math.max(0, entries.size() - visible));
        for (int i = menuOffset; i < Math.min(entries.size(), menuOffset + visible); i++) {
            MenuEntry e = entries.get(i);
            button(e.id, e.label, x + 4, y, w - 8, e.action);
            y += buttonHeight + 3;
        }
    }

    private static final class MenuEntry {
        final String id, label;
        final Runnable action;

        MenuEntry(String id, String label, Runnable action) {
            this.id = id;
            this.label = label;
            this.action = action;
        }
    }

    private List<MenuEntry> entries() {
        List<MenuEntry> entries = new ArrayList<MenuEntry>();
        if (overlay.equals("backends")) {
            for (BackendState b : core.backends())
                entries.add(
                        new MenuEntry(
                                "backend:" + b.backend.id(),
                                "New · " + b.backend.id(),
                                () -> {
                                    backendId = b.backend.id();
                                    CompletableFuture<?> f =
                                            b.ready
                                                    ? core.create(backendId)
                                                    : core.connect(backendId)
                                                            .thenCompose(
                                                                    c -> core.create(backendId));
                                    watch(f);
                                    overlay = "";
                                }));
        } else if (overlay.equals("conversations")) {
            entries.add(
                    new MenuEntry(
                            "archived",
                            archived ? "Show active" : "Show archived",
                            () -> {
                                archived = !archived;
                                watch(core.loadCatalog(backendId, search.text, archived, null));
                            }));
            entries.add(
                    new MenuEntry(
                            "searchServer",
                            "Search all pages",
                            () -> watch(core.loadCatalog(backendId, search.text, archived, null))));
            for (Chat c : core.chats())
                if (c.conversation.archived == archived)
                    entries.add(
                            new MenuEntry(
                                    "thread:"
                                            + c.conversation.key.connection.backend
                                            + ":"
                                            + c.conversation.key.id,
                                    c.conversation.title
                                            + " · "
                                            + status(c)
                                            + (c.unread > 0 ? " · unread " + c.unread : ""),
                                    () -> {
                                        watch(core.open(c));
                                        overlay = "";
                                    }));
            if (backend() != null && backend().catalogCursor != null)
                entries.add(
                        new MenuEntry(
                                "catalogMore",
                                "Load more conversations",
                                () ->
                                        watch(
                                                core.loadCatalog(
                                                        backendId,
                                                        search.text,
                                                        archived,
                                                        backend().catalogCursor))));
        } else if (overlay.equals("models")) {
            if (backend() != null)
                for (Model m : backend().models)
                    entries.add(
                            new MenuEntry(
                                    "model:" + m.id,
                                    m.name,
                                    () -> {
                                        if (!m.availability.available) {
                                            inspect(
                                                    m.name,
                                                    m.availability.reason,
                                                    Collections.<Resource>emptyList());
                                            return;
                                        }
                                        if (editing != null) {
                                            Settings s = editing.ui.settings;
                                            editing.ui.settings =
                                                    new Settings(
                                                            m.id,
                                                            m.defaultEffort,
                                                            m.defaultTier,
                                                            s.mode,
                                                            s.permissionProfile);
                                            core.save(editing);
                                        }
                                        overlay = "parameters";
                                        search = new Editor("", 0);
                                        menuOffset = 0;
                                    }));
            if (backend() != null && backend().modelCursor != null)
                entries.add(
                        new MenuEntry(
                                "modelsMore",
                                "Load more models",
                                () -> watch(core.loadModels(backendId, backend().modelCursor))));
        } else if (overlay.equals("parameters") && editing != null) {
            Settings s = editing.ui.settings;
            entries.add(
                    new MenuEntry(
                            "currentSettings",
                            "Current / next parameters",
                            () ->
                                    inspect(
                                            "Parameters",
                                            settingsText(editing),
                                            Collections.<Resource>emptyList())));
            if (backend() != null)
                for (Model m : backend().models)
                    if (m.id.equals(s.model)) {
                        for (String effort : m.efforts)
                            entries.add(
                                    new MenuEntry(
                                            "effort:" + effort,
                                            "Effort: " + effort,
                                            () -> {
                                                Settings old = editing.ui.settings;
                                                editing.ui.settings =
                                                        new Settings(
                                                                old.model,
                                                                effort,
                                                                old.tier,
                                                                old.mode,
                                                                old.permissionProfile,
                                                                old.accessProgram);
                                                core.save(editing);
                                            }));
                        for (String tier : m.tiers)
                            entries.add(
                                    new MenuEntry(
                                            "tier:" + tier,
                                            "Tier: " + tier,
                                            () -> {
                                                Settings old = editing.ui.settings;
                                                editing.ui.settings =
                                                        new Settings(
                                                                old.model,
                                                                old.effort,
                                                                tier,
                                                                old.mode,
                                                                old.permissionProfile,
                                                                old.accessProgram);
                                                core.save(editing);
                                            }));
                        for (String program : m.accessPrograms)
                            entries.add(
                                    new MenuEntry(
                                            "access:" + program,
                                            "Access program: " + program,
                                            () -> {
                                                Settings old = editing.ui.settings;
                                                editing.ui.settings =
                                                        new Settings(
                                                                old.model,
                                                                old.effort,
                                                                old.tier,
                                                                old.mode,
                                                                old.permissionProfile,
                                                                program);
                                                core.save(editing);
                                            }));
                        if (m.accessPrograms.isEmpty())
                            entries.add(
                                    new MenuEntry(
                                            "access:none",
                                            "Access programs · none advertised",
                                            () ->
                                                    inspect(
                                                            "Access programs",
                                                            "The backend has not advertised an eligible access program for this model. Selecting a model never grants access.",
                                                            Collections.<Resource>emptyList())));
                    }
        } else if (overlay.equals("queue") && editing != null) {
            if (editing.queuePaused)
                entries.add(
                        new MenuEntry(
                                "queueRetry",
                                "Retry queued messages",
                                () -> core.retryQueue(editing)));
            for (Queued q : editing.queue)
                entries.add(
                        new MenuEntry(
                                "queued:" + q.id,
                                "Cancel: " + q.message.text,
                                () -> core.cancelQueued(editing, q.id)));
        } else if (overlay.equals("resources")) {
            for (Resource resource : detailResources)
                entries.add(
                        new MenuEntry(
                                "resource:" + resource.location,
                                "Open " + resource.name,
                                () -> {
                                    if (resource.type == Resource.Type.CONVERSATION)
                                        watch(core.openRelated(backendId, resource.location));
                                    else
                                        confirm(
                                                "Open externally: " + resource.location,
                                                () -> surface.openResource(resource.location));
                                }));
        } else if (overlay.equals("attachments") && editing != null) {
            if (backend() == null
                    || backend().capabilities == null
                    || !backend().capabilities.feature(Feature.ATTACHMENTS).available) {
                entries.add(
                        new MenuEntry(
                                "attachments:unavailable",
                                "Attachments unavailable",
                                () ->
                                        inspect(
                                                "Attachments",
                                                backend() != null && backend().capabilities != null
                                                        ? backend()
                                                                .capabilities
                                                                .feature(Feature.ATTACHMENTS)
                                                                .reason
                                                        : "Connect first",
                                                Collections.<Resource>emptyList())));
                if (!actionFilter.isEmpty())
                    entries.removeIf(entry -> !entry.id.startsWith("action:" + actionFilter));
                return entries;
            }
            for (Resource.Type type :
                    Arrays.asList(
                            Resource.Type.FILE,
                            Resource.Type.IMAGE,
                            Resource.Type.AUDIO,
                            Resource.Type.SKILL,
                            Resource.Type.APP,
                            Resource.Type.LINK))
                entries.add(
                        new MenuEntry(
                                "attach:" + type,
                                "Attach " + type,
                                () ->
                                        form(
                                                "Attach " + type,
                                                "File and image paths are relative to this world's workspace.",
                                                new Form(
                                                        Arrays.asList(
                                                                Form.Field.text(
                                                                        "path",
                                                                        "Path or URL",
                                                                        true),
                                                                Form.Field.text(
                                                                        "name",
                                                                        "Name (skill or app)",
                                                                        false))),
                                                values -> {
                                                    try {
                                                        String path = values.get("path").text(),
                                                                name =
                                                                        values.containsKey("name")
                                                                                ? values.get("name")
                                                                                        .text()
                                                                                : path;
                                                        Resource r =
                                                                type == Resource.Type.FILE
                                                                                || type
                                                                                        == Resource
                                                                                                .Type
                                                                                                .IMAGE
                                                                                || type
                                                                                        == Resource
                                                                                                .Type
                                                                                                .AUDIO
                                                                        ? core.workspaceResource(
                                                                                type, path)
                                                                        : new Resource(
                                                                                type, name, path);
                                                        editing.ui.resources.add(r);
                                                        core.save(editing);
                                                        return CompletableFuture.completedFuture(
                                                                null);
                                                    } catch (Exception e) {
                                                        CompletableFuture<Void> failed =
                                                                new CompletableFuture<Void>();
                                                        failed.completeExceptionally(e);
                                                        return failed;
                                                    }
                                                })));
            for (Resource resource : new ArrayList<Resource>(editing.ui.resources))
                entries.add(
                        new MenuEntry(
                                "detach:" + resource.location,
                                "Remove " + resource.name,
                                () -> {
                                    editing.ui.resources.remove(resource);
                                    core.save(editing);
                                }));
        } else if (overlay.equals("actions")) {
            if (editing != null) {
                entries.add(
                        new MenuEntry(
                                "attachments",
                                "Context · Attachments (" + editing.ui.resources.size() + ")",
                                () -> menu("attachments")));
                entries.add(
                        new MenuEntry(
                                "export",
                                "Context · Export full conversation to clipboard",
                                () -> watch(core.export(editing).thenAccept(surface::clipboard))));
                entries.add(
                        new MenuEntry(
                                "raw",
                                "Context · Read loaded conversation as text",
                                () ->
                                        inspect(
                                                "Conversation",
                                                core.transcript(editing),
                                                Collections.<Resource>emptyList())));
                entries.add(
                        new MenuEntry(
                                "usage",
                                "Context · Token usage",
                                () ->
                                        inspect(
                                                "Usage",
                                                editing.usage == null
                                                        ? "No usage reported"
                                                        : describe(editing.usage.details),
                                                Collections.<Resource>emptyList())));
                entries.add(
                        new MenuEntry(
                                "status",
                                "Status · Current / next model and permissions",
                                () ->
                                        inspect(
                                                "Status",
                                                settingsText(editing),
                                                Collections.<Resource>emptyList())));
                entries.add(
                        new MenuEntry(
                                "rename",
                                "Conversations · Rename",
                                () ->
                                        form(
                                                "Rename",
                                                "",
                                                new Form(
                                                        Arrays.asList(
                                                                Form.Field.text(
                                                                        "title", "Title", true))),
                                                values ->
                                                        core.rename(
                                                                editing,
                                                                values.get("title").text()))));
                entries.add(
                        new MenuEntry(
                                "archive",
                                "Conversations · "
                                        + (editing.conversation.archived ? "Restore" : "Archive"),
                                () ->
                                        watch(
                                                core.archive(
                                                        editing, !editing.conversation.archived))));
                entries.add(
                        new MenuEntry(
                                "historyMore",
                                "Context · Load older history",
                                () -> watch(core.loadHistory(editing))));
                entries.add(
                        new MenuEntry("copy", "Context · Copy latest answer", this::copyLatest));
                entries.add(
                        new MenuEntry(
                                "init",
                                "Context · Draft a request to create AGENTS.md",
                                () ->
                                        preparePrompt(
                                                "Inspect this workspace and create an AGENTS.md with accurate project instructions. Explain proposed changes before writing.")));
                entries.add(
                        new MenuEntry(
                                "recap",
                                "Context · Draft a recap request",
                                () ->
                                        preparePrompt(
                                                "Summarize the current conversation, decisions, remaining work and relevant files.")));
                entries.add(
                        new MenuEntry(
                                "children",
                                "Conversations · Child tasks",
                                () -> {
                                    StringBuilder children = new StringBuilder();
                                    for (int i = 0; i < editing.timeline.size(); i++)
                                        if (editing.timeline.get(i).kind == Kind.CHILD_TASK)
                                            children.append(
                                                            describe(
                                                                    editing.timeline.get(i)
                                                                            .details))
                                                    .append('\n');
                                    inspect(
                                            "Child tasks",
                                            children.length() == 0
                                                    ? "No child tasks reported"
                                                    : children.toString(),
                                            Collections.<Resource>emptyList());
                                }));
            }
            if (backend() != null && backend().capabilities != null)
                for (Action a : backend().capabilities.actions)
                    entries.add(
                            new MenuEntry(
                                    "action:" + a.id,
                                    a.category
                                            + " · "
                                            + a.title
                                            + (a.availability.available ? "" : " · unavailable"),
                                    () -> {
                                        if (!a.availability.available) {
                                            inspect(
                                                    a.title,
                                                    a.availability.reason,
                                                    Collections.<Resource>emptyList());
                                            return;
                                        }
                                        Runnable run =
                                                () -> {
                                                    if (a.form.fields.isEmpty()) {
                                                        overlay = "";
                                                        focus = "composer";
                                                        watch(
                                                                core.act(
                                                                                backendId,
                                                                                editing,
                                                                                a.id,
                                                                                Collections
                                                                                        .<String,
                                                                                                Value>
                                                                                                emptyMap())
                                                                        .thenAccept(
                                                                                result ->
                                                                                        inspect(
                                                                                                result.title,
                                                                                                describe(
                                                                                                                result.fields)
                                                                                                        + result.text,
                                                                                                result.resources)));
                                                    } else
                                                        form(
                                                                a.title,
                                                                a.description,
                                                                a.form,
                                                                values ->
                                                                        core.act(
                                                                                        backendId,
                                                                                        editing,
                                                                                        a.id,
                                                                                        values)
                                                                                .thenAccept(
                                                                                        result ->
                                                                                                inspect(
                                                                                                        result.title,
                                                                                                        describe(
                                                                                                                        result.fields)
                                                                                                                + result.text,
                                                                                                        result.resources)));
                                                };
                                        if (a.confirmationRequired)
                                            confirm(
                                                    a.description
                                                            + ". This action changes backend state.",
                                                    run);
                                        else run.run();
                                    }));
            entries.add(
                    new MenuEntry(
                            "workspace",
                            "Workspace · Directory",
                            () ->
                                    inspect(
                                            "Workspace",
                                            core.workspace().getPath()
                                                    + "\nAll conversations share this world workspace.",
                                            Collections.<Resource>emptyList())));
            entries.add(
                    new MenuEntry(
                            "cd",
                            "Workspace · Change directory / worktree",
                            () ->
                                    inspect(
                                            "World-bound workspace",
                                            "Directory changes and worktrees are explicitly outside parity. Continue using the external Codex CLI for these operations.",
                                            Collections.<Resource>emptyList())));
            entries.add(
                    new MenuEntry(
                            "help",
                            "Help · Keyboard",
                            () ->
                                    inspect(
                                            "Help",
                                            "Enter: send\nShift+Enter: newline\nTab: next control\nEscape: close menu, then panel\nCtrl+Up/Down: input history\nScroll: history or menu\nAgent input is private to the agent backend.",
                                            Collections.<Resource>emptyList())));
            entries.add(
                    new MenuEntry(
                            "warnings",
                            "Help · Warnings",
                            () ->
                                    inspect(
                                            "Warnings",
                                            String.join("\n", core.warnings),
                                            Collections.<Resource>emptyList())));
            entries.add(
                    new MenuEntry(
                            "disconnect",
                            "Connection · Disconnect",
                            () ->
                                    confirm(
                                            "Disconnect this backend and stop its running work?",
                                            () -> backend().backend.close())));
            entries.add(
                    new MenuEntry(
                            "vim",
                            "Settings · Vim editing: " + (vim ? "on" : "off"),
                            () -> {
                                vim = !vim;
                                vimInsert = true;
                                overlay = "";
                                focus = "composer";
                            }));
            entries.add(
                    new MenuEntry(
                            "presentation",
                            "Settings · Theme / title / status",
                            () ->
                                    form(
                                            "Presentation",
                                            "Conversation status and active parameters remain visible.",
                                            new Form(
                                                    Arrays.asList(
                                                            Form.Field.choice(
                                                                    "theme",
                                                                    "Theme",
                                                                    "minecraft",
                                                                    "high-contrast"),
                                                            Form.Field.text(
                                                                    "title",
                                                                    "Local panel title",
                                                                    false))),
                                            values -> {
                                                highContrast =
                                                        values.get("theme")
                                                                .text()
                                                                .equals("high-contrast");
                                                panelTitle =
                                                        values.containsKey("title")
                                                                ? values.get("title").text()
                                                                : "";
                                                return CompletableFuture.completedFuture(null);
                                            })));
        }
        if (!search.text.isEmpty())
            entries.removeIf(
                    e ->
                            !e.label
                                            .toLowerCase(Locale.ROOT)
                                            .contains(search.text.toLowerCase(Locale.ROOT))
                                    && !e.id.equals("searchServer")
                                    && !e.id.endsWith("More"));
        if (!actionFilter.isEmpty())
            entries.removeIf(entry -> !entry.id.startsWith("action:" + actionFilter));
        return entries;
    }

    private void copyLatest() {
        for (int i = editing.timeline.size() - 1; i >= 0; i--) {
            Item item = editing.timeline.get(i);
            if (item.kind == Kind.ASSISTANT && item.state == State.COMPLETED) {
                surface.clipboard(item.text);
                return;
            }
        }
        error = "No completed answer loaded";
    }

    private void preparePrompt(String value) {
        confirm(
                "Place this request in the composer? It will run only when you press Send.\n"
                        + value,
                () -> {
                    composer.insert(value);
                    sync();
                    overlay = "";
                    focus = "composer";
                });
    }

    private String settingsText(Chat chat) {
        Settings s = chat.ui.settings, r = chat.runningSettings;
        String next =
                "Model: "
                        + s.model
                        + "\nEffort: "
                        + s.effort
                        + "\nTier: "
                        + s.tier
                        + "\nMode: "
                        + s.mode
                        + "\nPermissions: "
                        + s.permissionProfile
                        + "\nAccess program: "
                        + s.accessProgram;
        return "Shared workspace: "
                + core.workspace().getPath()
                + "\n"
                + status(chat)
                + "\n\nNext message\n"
                + next
                + (chat.busy() && r != null
                        ? "\n\nRunning task (unchanged)\nModel: "
                                + r.model
                                + "\nEffort: "
                                + r.effort
                                + "\nTier: "
                                + r.tier
                        : "");
    }

    private void inspect(Item item) {
        if (item.kind == Kind.REQUEST && editing != null)
            for (Pending pending : editing.requests.values())
                if (item.id.equals("request:" + pending.request.key.token)
                        && pending.status == Pending.Status.OPEN) {
                    request(pending);
                    return;
                }
        inspect(item.title, item.text + "\n" + describe(item.details), item.resources);
        detailWidgets = item.kind == Kind.ASSISTANT ? widgetMessage(item.text) : null;
        widgetIndex = 0;
        if (detailWidgets != null && !detailWidgets.widgets.isEmpty()) {
            detailCopyText = detailWidgets.plainText() + "\n" + describe(item.details);
            selectWidget(0);
        }
    }

    private void selectWidget(int index) {
        widgetIndex = index;
        WidgetRegistry.Entry widget = detailWidgets.widgets.get(index);
        detailTitle = widget.title;
        detailText = widget.text;
        detailSelection = new Editor(detailText, 0);
        detailOffset = 0;
        detailWidgetOffset = 0;
        detailPages.clear();
        focus = "back";
    }

    private WidgetRegistry.Message widgetMessage(String text) {
        WidgetRegistry.Message message = widgetCache.get(text);
        if (message == null) {
            message = widgets.parse(text);
            if (text.length() <= 32768) widgetCache.put(text, message);
        }
        return message;
    }

    private String describe(Map<String, Value> values) {
        return ReadableValues.format(values);
    }

    private void inspect(String title, String text, List<Resource> resources) {
        detailWidgets = null;
        detailTitle = title;
        detailText = text;
        detailCopyText = text;
        detailWidgetOffset = 0;
        detailSelection = new Editor(text, 0);
        detailPages.clear();
        detailResources = resources;
        detailOffset = 0;
        overlay = "details";
        focus = "back";
    }

    private void details(int x, int y, int w, int end) {
        text(detailTitle, x, y, w, ACCENT);
        y += line + 4;
        WidgetRegistry.Entry selectedWidget = null;
        if (detailWidgets != null && !detailWidgets.widgets.isEmpty()) {
            if (detailWidgets.widgets.size() > 1) {
                button(
                        "nextWidget",
                        "Widget "
                                + (widgetIndex + 1)
                                + " / "
                                + detailWidgets.widgets.size()
                                + " - Next",
                        x,
                        y,
                        w,
                        () -> {
                            selectWidget((widgetIndex + 1) % detailWidgets.widgets.size());
                        });
                y += buttonHeight + 4;
            }
            selectedWidget = detailWidgets.widgets.get(widgetIndex);
        }
        button("copyDetails", "Copy all", x, y, 72, () -> surface.clipboard(detailCopyText));
        button(
                "selectDetails",
                "Select text",
                x + 76,
                y,
                Math.max(40, w - 76),
                () -> focus = "detailText");
        y += buttonHeight + 4;
        if (!detailResources.isEmpty()) {
            button(
                    "resources",
                    "Links / conversations (" + detailResources.size() + ")",
                    x,
                    y,
                    w,
                    () -> menu("resources"));
            y += buttonHeight + 4;
        }
        if (selectedWidget != null && !focus.equals("detailText")) {
            int widgetHeight = selectedWidget.height(w, surface.lineHeight(), surface::textWidth);
            List<String> extra =
                    widgetHeight == 0 || !selectedWidget.includesText()
                            ? new TextWindow(surface, selectedWidget.text, 0, w, Integer.MAX_VALUE)
                                    .lines
                            : Collections.<String>emptyList();
            int extraTop = widgetHeight > 0 ? widgetHeight + 4 : 0;
            detailWidgetViewport = Math.max(0, end - y);
            detailWidgetMaximum =
                    Math.max(0, extraTop + extra.size() * line - detailWidgetViewport);
            detailWidgetOffset = Math.min(detailWidgetOffset, detailWidgetMaximum);
            if (detailWidgetViewport > 0) {
                surface.clip(x, y, x + w, end);
                try {
                    if (widgetHeight > 0)
                        selectedWidget.render(
                                new WidgetCanvas(
                                        surface, x, y - detailWidgetOffset, w, widgetHeight));
                    int yy = y - detailWidgetOffset + extraTop;
                    for (String value : extra) {
                        if (yy + line > y && yy < end) text(value, x, yy, w, FG);
                        yy += line;
                    }
                } finally {
                    surface.unclip();
                }
            }
            return;
        }
        int available = Math.max(0, (end - y) / line);
        detailOffset = Math.max(0, Math.min(detailOffset, Math.max(0, detailText.length() - 1)));
        TextWindow page = new TextWindow(surface, detailText, detailOffset, w, available);
        detailEnd = page.end;
        for (int i = 0; i < page.lines.size(); i++) {
            String part = page.lines.get(i);
            int begin = page.starts.get(i);
            int a = Math.max(begin, Math.min(detailSelection.mark, detailSelection.cursor));
            int b =
                    Math.min(
                            begin + part.length(),
                            Math.max(detailSelection.mark, detailSelection.cursor));
            if (b > a)
                rect(
                        x + surface.textWidth(part.substring(0, a - begin)),
                        y,
                        surface.textWidth(part.substring(a - begin, b - begin)),
                        line,
                        0xff45617a);
            text(
                    part,
                    x,
                    y,
                    w,
                    part.startsWith("+") ? 0xff9cddaa : part.startsWith("-") ? ERROR : FG);
            y += line;
        }
    }

    private void request(Pending pending) {
        form(
                pending.request.title,
                pending.request.description,
                pending.request.form,
                values -> core.answer(editing, pending, values));
    }

    private void form(
            String title,
            String description,
            Form form,
            Function<Map<String, Value>, CompletableFuture<?>> submit) {
        activeForm = form;
        formTitle = title;
        formDescription = description;
        formSubmit = submit;
        formValues.clear();
        formErrors = Collections.emptyMap();
        formEditors.clear();
        formOffset = 0;
        formSending = false;
        overlay = "form";
        focus = form.fields.isEmpty() ? "submitForm" : "field:" + form.fields.get(0).id;
    }

    private void drawForm(int x, int y, int w, int end) {
        text(formTitle + (formSending ? " · Sending…" : ""), x, y, w, ACCENT);
        y += line + 4;
        for (String part : textCache.preview(surface, formDescription, w, 2)) {
            text(part, x, y, w, MUTED);
            y += line;
        }
        y += 4;
        List<Form.Field> fields = activeForm.fields;
        for (int i = formOffset; i < fields.size(); i++) {
            Form.Field field = fields.get(i);
            if (y + 2 * buttonHeight + 2 * line > end) break;
            text(field.label + (field.required ? " *" : ""), x, y, w, FG);
            y += line + 2;
            if (!field.description.isEmpty()) {
                text(field.description, x, y, w, MUTED);
                y += line;
            }
            Value value = formValues.get(field.id);
            if (field.type == Form.Type.CHOICE || field.type == Form.Type.BOOLEAN) {
                String label = value == null ? "Choose…" : value.toString();
                button(
                        "field:" + field.id,
                        label,
                        x,
                        y,
                        w,
                        () -> {
                            if (formSending) return;
                            if (field.type == Form.Type.BOOLEAN)
                                formValues.put(
                                        field.id, Value.bool(value == null || !value.bool()));
                            else {
                                int index =
                                        value == null ? -1 : field.choices.indexOf(value.text());
                                formValues.put(
                                        field.id,
                                        Value.text(
                                                field.choices.get(
                                                        (index + 1) % field.choices.size())));
                            }
                        });
            } else if (field.type == Form.Type.MULTICHOICE) {
                button(
                        "field:" + field.id,
                        value == null ? "Choose options" : value.toString(),
                        x,
                        y,
                        w,
                        () -> editChoices(field));
            } else if (field.type == Form.Type.GROUP) {
                button("field:" + field.id, "Edit fields", x, y, w, () -> editGroup(field));
            } else {
                Editor input =
                        formEditors.computeIfAbsent(
                                field.id,
                                k ->
                                        new Editor(
                                                value == null ? "" : value.toString(),
                                                value == null ? 0 : value.toString().length()));
                rect(x, y, w, buttonHeight, focus.equals("field:" + field.id) ? 0xff45617a : FIELD);
                text(
                        field.type == Form.Type.SECRET
                                ? input.text.replaceAll(".", "*")
                                : input.text,
                        x + 4,
                        y + 4,
                        w - 8,
                        FG);
                controls.add(
                        new Control(
                                "field:" + field.id,
                                field.label,
                                x,
                                y,
                                w,
                                buttonHeight,
                                () -> focus = "field:" + field.id));
            }
            y += buttonHeight + 3;
            String problem = formErrors.get(field.id);
            if (problem != null) {
                text(problem, x, y, w, ERROR);
                y += line;
            }
        }
        if (formErrors.containsKey("submit"))
            text(formErrors.get("submit"), x, end - buttonHeight - line - 2, w, ERROR);
        button(
                "submitForm",
                formSending ? "Sending…" : "Submit",
                x,
                end - buttonHeight,
                w,
                this::submitForm);
    }

    private void editGroup(Form.Field field) {
        collectEditors();
        Form parent = activeForm;
        String title = formTitle, description = formDescription;
        Function<Map<String, Value>, CompletableFuture<?>> submit = formSubmit;
        Map<String, Value> values = new LinkedHashMap<String, Value>(formValues);
        form(
                field.label,
                field.description,
                field.children,
                child -> {
                    form(title, description, parent, submit);
                    formValues.putAll(values);
                    formValues.put(field.id, Value.object(child));
                    return CompletableFuture.completedFuture(null);
                });
        if (values.containsKey(field.id)) formValues.putAll(values.get(field.id).object());
    }

    private void editChoices(Form.Field field) {
        List<Form.Field> children = new ArrayList<Form.Field>();
        for (String choice : field.choices)
            children.add(
                    new Form.Field(
                            choice,
                            choice,
                            "",
                            Form.Type.BOOLEAN,
                            false,
                            Collections.<String>emptyList(),
                            null,
                            null,
                            null,
                            null,
                            null));
        collectEditors();
        Form parent = activeForm;
        String title = formTitle, description = formDescription;
        Function<Map<String, Value>, CompletableFuture<?>> submit = formSubmit;
        Map<String, Value> values = new LinkedHashMap<String, Value>(formValues);
        form(
                field.label,
                field.description,
                new Form(children),
                selected -> {
                    List<Value> chosen = new ArrayList<Value>();
                    for (String choice : field.choices)
                        if (selected.containsKey(choice) && selected.get(choice).bool())
                            chosen.add(Value.text(choice));
                    form(title, description, parent, submit);
                    formValues.putAll(values);
                    formValues.put(field.id, Value.array(chosen));
                    return CompletableFuture.completedFuture(null);
                });
        if (values.containsKey(field.id))
            for (Value choice : values.get(field.id).array())
                formValues.put(choice.text(), Value.bool(true));
    }

    private Map<String, String> collectEditors() {
        Map<String, String> errors = new LinkedHashMap<String, String>();
        for (Form.Field field : activeForm.fields)
            if (formEditors.containsKey(field.id)) {
                String input = formEditors.get(field.id).text;
                if (input.isEmpty() && !field.required) {
                    formValues.remove(field.id);
                    continue;
                }
                try {
                    formValues.put(
                            field.id,
                            field.type == Form.Type.NUMBER || field.type == Form.Type.INTEGER
                                    ? Value.number(Double.parseDouble(input))
                                    : field.type == Form.Type.RESOURCE
                                            ? Value.resource(
                                                    new Resource(Resource.Type.FILE, input, input))
                                            : Value.text(input));
                } catch (IllegalArgumentException e) {
                    errors.put(field.id, "Enter a valid number");
                }
            }
        return errors;
    }

    private void submitForm() {
        if (formSending) return;
        Map<String, String> errors = collectEditors();
        errors.putAll(activeForm.validate(formValues));
        formErrors = errors;
        if (!errors.isEmpty()) return;
        formSending = true;
        String oldTitle = formTitle;
        Form submittedForm = activeForm;
        CompletableFuture<?> operation =
                formSubmit.apply(new LinkedHashMap<String, Value>(formValues));
        operation.whenComplete(
                (r, e) -> {
                    formSending = false;
                    if (e != null) {
                        error = message(e);
                        formErrors = Collections.singletonMap("submit", error);
                    } else if (overlay.equals("form")
                            && activeForm == submittedForm
                            && formTitle.equals(oldTitle)) {
                        overlay = "";
                        focus = "composer";
                    }
                });
    }

    private void confirm(String message, Runnable action) {
        confirmation = message;
        confirmed = action;
        overlay = "confirm";
        focus = "back";
    }

    public void click(int x, int y, int button) {
        if (button != 0) return;
        for (int i = controls.size() - 1; i >= 0; i--) {
            Control c = controls.get(i);
            if (x >= c.x && x < c.x + c.w && y >= c.y && y < c.y + c.h) {
                focus = c.id;
                c.action.run();
                return;
            }
        }
    }

    public void scroll(int direction) {
        if (!overlay.isEmpty()) {
            if (overlay.equals("details")) {
                if (detailWidgets != null
                        && !detailWidgets.widgets.isEmpty()
                        && !focus.equals("detailText")) {
                    detailWidgetOffset =
                            Math.max(
                                    0,
                                    Math.min(
                                            detailWidgetMaximum,
                                            detailWidgetOffset
                                                    + (direction > 0 ? -1 : 1)
                                                            * Math.max(
                                                                    line,
                                                                    detailWidgetViewport / 2)));
                    return;
                }
                if (direction > 0) {
                    if (!detailPages.isEmpty()) detailOffset = detailPages.pop();
                    else detailOffset = 0;
                } else if (detailEnd > detailOffset && detailEnd < detailText.length()) {
                    detailPages.push(detailOffset);
                    detailOffset = detailEnd;
                }
            } else if (overlay.equals("form"))
                formOffset =
                        Math.max(
                                0,
                                Math.min(
                                        activeForm.fields.size() - 1,
                                        formOffset + (direction > 0 ? -1 : 1)));
            else menuOffset = Math.max(0, menuOffset + (direction > 0 ? -3 : 3));
            return;
        }
        if (editing == null || editing.timeline.size() == 0) return;
        int index = Math.max(0, Math.min(firstVisible, editing.timeline.size() - 1));
        int step = Math.max(line, timelineViewport / 2);
        if (direction > 0) {
            if (timelineOffset > 0) timelineOffset = Math.max(0, timelineOffset - step);
            else if (index > 0) {
                index--;
                timelineOffset =
                        Math.max(0, itemHeight(editing.timeline.get(index)) - timelineViewport);
            }
        } else {
            int remaining = itemHeight(editing.timeline.get(index)) - timelineViewport;
            if (timelineOffset < remaining)
                timelineOffset = Math.min(remaining, timelineOffset + step);
            else if (index < editing.timeline.size() - 1) {
                index++;
                timelineOffset = 0;
            }
        }
        editing.ui.anchor =
                index == latestStart && timelineOffset == 0 ? "" : editing.timeline.get(index).id;
        firstVisible = index;
    }

    public void key(Key key, char character, boolean shift, boolean control) {
        if (overlay.isEmpty() && slashVisible() && !control) {
            List<String> matches = slashMatches();
            if (key == Key.ESCAPE) {
                slashDismissed = true;
                return;
            }
            if (!matches.isEmpty()) {
                slashIndex = Math.max(0, Math.min(slashIndex, matches.size() - 1));
                if (key == Key.UP || key == Key.DOWN) {
                    slashIndex =
                            (slashIndex + (key == Key.UP ? -1 : 1) + matches.size())
                                    % matches.size();
                    return;
                }
                if (key == Key.TAB) {
                    String value = "/" + matches.get(slashIndex);
                    composer = new Editor(value, value.length());
                    slashIndex = 0;
                    sync();
                    return;
                }
                if (key == Key.ENTER && !shift) {
                    executeSlash("/" + matches.get(slashIndex));
                    return;
                }
            }
        }
        if (key == Key.PAGEUP || key == Key.PAGEDOWN) {
            scroll(key == Key.PAGEUP ? 1 : -1);
            return;
        }
        if (vim && overlay.isEmpty() && focus.equals("composer")) {
            if (key == Key.ESCAPE && vimInsert) {
                vimInsert = false;
                return;
            }
            if (!vimInsert && !control && key != Key.ESCAPE && key != Key.TAB && key != Key.ENTER) {
                switch (character) {
                    case 'i':
                        vimInsert = true;
                        break;
                    case 'a':
                        composer.move(1, false);
                        vimInsert = true;
                        break;
                    case 'h':
                        composer.move(-1, shift);
                        break;
                    case 'l':
                        composer.move(1, shift);
                        break;
                    case 'j':
                        composer.vertical(1, shift);
                        break;
                    case 'k':
                        composer.vertical(-1, shift);
                        break;
                    case '0':
                        composer.home(shift);
                        break;
                    case '$':
                        composer.end(shift);
                        break;
                    case 'x':
                        composer.delete();
                        break;
                    case 'o':
                        composer.end(false);
                        composer.insert("\n");
                        vimInsert = true;
                        break;
                    default:
                        break;
                }
                sync();
                return;
            }
        }
        if (overlay.equals("details")
                && focus.equals("detailText")
                && key != Key.ESCAPE
                && key != Key.TAB) {
            if (control && key == Key.A) detailSelection.selectAll();
            else if (control && key == Key.C) surface.clipboard(detailSelection.selection());
            else if (key == Key.LEFT) detailSelection.move(-1, shift);
            else if (key == Key.RIGHT) detailSelection.move(1, shift);
            else if (key == Key.UP) detailSelection.vertical(-1, shift);
            else if (key == Key.DOWN) detailSelection.vertical(1, shift);
            else if (key == Key.HOME) detailSelection.home(shift);
            else if (key == Key.END) detailSelection.end(shift);
            if (detailSelection.cursor < detailOffset || detailSelection.cursor > detailEnd)
                detailOffset = detailSelection.cursor;
            return;
        }
        if (key == Key.ESCAPE) {
            if (!overlay.isEmpty()) {
                overlay = "";
                focus = "composer";
            } else {
                closed();
                surface.closePanel();
            }
            return;
        }
        if (key == Key.TAB) {
            navigate(shift ? -1 : 1);
            return;
        }
        if ((key == Key.UP || key == Key.DOWN)
                && !overlay.isEmpty()
                && !focus.startsWith("field:")) {
            navigate(key == Key.UP ? -1 : 1);
            return;
        }
        Editor editor = null;
        if (overlay.isEmpty() && focus.equals("composer")) editor = composer;
        else if (!overlay.isEmpty() && focus.equals("search")) editor = search;
        else if (focus.startsWith("field:")) editor = formEditors.get(focus.substring(6));
        else if (!overlay.isEmpty()
                && !Arrays.asList("form", "confirm", "details").contains(overlay)
                && (key == Key.BACKSPACE
                        || key == Key.DELETE
                        || key == Key.OTHER && character >= 32)) {
            editor = search;
            focus = "search";
        }
        if (key == Key.ENTER) {
            if (editor == composer) {
                if (shift) composer.insert("\n");
                else send();
                sync();
                return;
            }
            if (editor == search && !shift) {
                List<MenuEntry> choices = entries();
                if (!choices.isEmpty()) choices.get(0).action.run();
                return;
            }
            if (editor != null) {
                if (shift) editor.insert("\n");
                return;
            }
            for (Control c : new ArrayList<Control>(controls))
                if (c.id.equals(focus)) {
                    c.action.run();
                    return;
                }
        }
        if (editor == null || formSending && overlay.equals("form")) return;
        if (control && key == Key.A) editor.selectAll();
        else if (control && key == Key.C) surface.clipboard(editor.selection());
        else if (control && key == Key.X) {
            surface.clipboard(editor.selection());
            editor.insert("");
        } else if (control && key == Key.V) editor.insert(surface.clipboard());
        else if (control && (key == Key.UP || key == Key.DOWN) && editor == composer)
            history(key == Key.UP ? -1 : 1);
        else if (key == Key.LEFT) editor.move(-1, shift);
        else if (key == Key.RIGHT) editor.move(1, shift);
        else if (key == Key.HOME) editor.home(shift);
        else if (key == Key.END) editor.end(shift);
        else if (key == Key.UP) editor.vertical(-1, shift);
        else if (key == Key.DOWN) editor.vertical(1, shift);
        else if (key == Key.BACKSPACE) editor.backspace();
        else if (key == Key.DELETE) editor.delete();
        else if (!control && character >= 32 && character != 127)
            editor.insert(Character.toString(character));
        if (editor == composer) {
            slashDismissed = false;
            slashIndex = 0;
            sync();
        }
    }

    private void history(int direction) {
        if (editing == null) return;
        if (historyIndex < 0) {
            historyIndex = editing.inputHistory.size();
            historyDraft = composer.text;
        }
        historyIndex = Math.max(0, Math.min(editing.inputHistory.size(), historyIndex + direction));
        String text =
                historyIndex == editing.inputHistory.size()
                        ? historyDraft
                        : editing.inputHistory.get(historyIndex);
        composer = new Editor(text, text.length());
        sync();
    }

    private void navigate(int direction) {
        if (controls.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < controls.size(); i++) if (controls.get(i).id.equals(focus)) index = i;
        focus = controls.get((index + direction + controls.size()) % controls.size()).id;
    }

    /** Accessible control geometry is also used by headless interaction tests. */
    public int[] controlBounds(String id) {
        for (Control c : controls) if (c.id.equals(id)) return new int[] {c.x, c.y, c.w, c.h};
        return null;
    }
}
