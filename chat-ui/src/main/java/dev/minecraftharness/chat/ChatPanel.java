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
        Z,
        Y,
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
    private final TimelineRows timelineRows = new TimelineRows();
    private final Map<Editor, EditorView> editorViews = new WeakHashMap<Editor, EditorView>();
    private final Map<String, EditorView> editorHits = new HashMap<String, EditorView>();
    private Editor dragging;
    private final Map<RequestKey, InlineQuestion> questions =
            new HashMap<RequestKey, InlineQuestion>();
    private InlineQuestion question;
    private int mouseX = -1, mouseY = -1;

    public void pointer(int x, int y) {
        mouseX = x;
        mouseY = y;
    }

    private TimelineRows.Row activity;
    private InfoPage information;
    private int pageOffset, pageMaximum;
    private String actionId = "", resultRaw = "", backTarget = "";
    private Form.Field choiceField;
    private String nextPage;
    private boolean informationLoading;
    private long informationGeneration;
    private Editor choiceEditor;
    private final Map<String, Value> actionFields = new LinkedHashMap<String, Value>();
    private final Map<String, MarkdownLayout> proseCache =
            new LinkedHashMap<String, MarkdownLayout>(32, .75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, MarkdownLayout> entry) {
                    return size() > 64;
                }
            };
    private Surface surface;
    private String backendId, focus = "composer", overlay = "", error = "";
    private Chat editing;
    private Editor composer = new Editor("", 0), search = new Editor("", 0);
    private int slashIndex;
    private boolean slashDismissed;
    private String actionFilter = "";
    private int historyIndex = -1, menuOffset, detailOffset, formOffset;
    private String historyDraft = "";
    private boolean expanded, archived, starting;
    private boolean steer = true;
    private boolean vim, vimInsert = true, highContrast;
    private String panelTitle = "";
    private int width, height, top, bottom, line, buttonHeight;
    private String detailTitle = "", detailText = "";
    private String detailCopyText = "";
    private JsonText detailJson;
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
        MarkdownLayout.Row markdown;

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
        String source;
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
        rect(
                x,
                y,
                w,
                buttonHeight,
                focus.equals(id)
                        ? 0xff45617a
                        : mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + buttonHeight
                                ? 0xff3b4854
                                : FIELD);
        text(
                textCache.preview(surface, safe(label), w - 8, 1).get(0),
                x + 4,
                y + 4,
                w - 8,
                focus.equals(id) ? ACCENT : FG);
    }

    private WidgetCanvas.Actions widgetActions(
            WidgetRegistry.Entry entry,
            String prefix,
            int clipLeft,
            int clipTop,
            int clipRight,
            int clipBottom) {
        Set<String> ids = new HashSet<String>();
        return (id, label, x, y, w, h, callback) -> {
            if (prefix.startsWith("widget:") && !overlay.isEmpty()) return;
            if (ids.size() >= 64 || !ids.add(id))
                throw new IllegalArgumentException("Too many or duplicate widget actions");
            int left = Math.max(x, clipLeft), top = Math.max(y, clipTop);
            int right = (int) Math.min((long) x + w, clipRight);
            int bottom = (int) Math.min((long) y + h, clipBottom);
            if (right > left && bottom > top)
                controls.add(
                        new Control(
                                prefix + ":action:" + id,
                                label,
                                left,
                                top,
                                right - left,
                                bottom - top,
                                () -> entry.invoke(callback)));
        };
    }

    private BackendState backend() {
        return core.backend(backendId);
    }

    private void bind() {
        Chat chat = core.selected();
        if (chat != editing) {
            sync();
            editing = chat;
            informationGeneration++;
            timelineOffset = chat == null ? 0 : chat.ui.anchorOffset;
            inlineCache.clear();
            proseCache.clear();
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
        if (editing != null) {
            core.draft(editing, composer.text, composer.cursor);
            editing.ui.anchorOffset = timelineOffset;
            editing.ui.expanded = expanded;
        }
    }

    public void activated() {
        focus = "composer";
    }

    public void closed() {
        dragging = null;
        sync();
        if (editing != null) {
            editing.ui.expanded = expanded;
            core.save(editing);
        }
    }

    public void render(Surface target, int width, int height) {
        MeasuredSurface measured = new MeasuredSurface(target);
        this.surface = measured;
        this.width = Math.max(120, width);
        this.height = height;
        line = surface.lineHeight();
        buttonHeight = line + 8;
        bind();
        controls.clear();
        editorHits.clear();
        renderedItems = 0;
        textCache.measuredCharacters = 0;
        questions
                .entrySet()
                .removeIf(
                        e ->
                                e.getValue().pending.status == Pending.Status.RESOLVED
                                        || e.getValue().pending.status
                                                == Pending.Status.DISCONNECTED);
        Pending pending = editing == null ? null : firstRequest(editing);
        question =
                pending != null && InlineQuestion.supports(pending)
                        ? questions.computeIfAbsent(
                                pending.request.key, k -> new InlineQuestion(pending))
                        : null;
        int panelHeight =
                expanded
                        ? height - 8
                        : Math.min(
                                height - 8,
                                question != null && !question.collapsed
                                        ? 180 + questionHeight(this.width - 16) - buttonHeight
                                        : 180);
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
        int waiting = 0, unread = 0;
        for (Chat chat : core.chats())
            if (chat != editing) {
                if (chat.needsAnswer()) waiting++;
                unread += chat.unread;
            }
        String conversations =
                waiting > 0
                        ? waiting + " needs answer"
                        : unread > 0
                                ? "Chats · " + unread + " new"
                                : "Chats · " + core.chats().size();
        button(
                "conversations",
                conversations,
                54,
                y,
                this.width - 134,
                () -> menu("conversations"));
        button(
                "expand",
                expanded ? "Compact" : "Expand",
                this.width - 76,
                y,
                64,
                () -> expanded = !expanded);
        y += buttonHeight + 4;
        String title =
                editing == null
                        ? (starting ? "Starting conversation..." : "Ready for your first message")
                        : (panelTitle.isEmpty() ? editing.conversation.title : panelTitle)
                                + " · "
                                + status(editing);
        text(title, 10, y, this.width - 20, ACCENT);
        y += line + 3;
        String model = editing == null ? "Automatic model" : setting(editing.ui.settings.model);
        String params =
                editing == null
                        ? ""
                        : " · "
                                + setting(editing.ui.settings.effort)
                                + (editing.ui.resources.isEmpty()
                                        ? ""
                                        : " · " + editing.ui.resources.size() + " attached");
        text(model + params, 10, y, this.width - 20, MUTED);
        y += line + 4;
        EditorView input = editorView(composer);
        input.layout(surface, 12, 0, this.width - 24, 4, false);
        int inputRows = Math.max(1, Math.min(4, input.layout.lines.size()));
        int composerY = bottom - inputRows * line - line - 16;
        int timelineBottom = composerY - 5;
        String issue =
                !error.isEmpty()
                        ? error
                        : editing != null && !editing.error.isEmpty()
                                ? editing.error
                                : backend() != null ? backend().error : "No backend";
        if (issue.isEmpty()) issue = core.notice;
        int questionHeight =
                question != null && !question.collapsed
                        ? Math.max(
                                80, Math.min(questionHeight(this.width - 16), composerY - y - 24))
                        : 0;
        if (questionHeight > 0) timelineBottom -= questionHeight + 5;
        boolean queued = editing != null && !editing.queue.isEmpty();
        boolean requestButton = pending != null && (question == null || question.collapsed);
        if (requestButton || queued || !issue.isEmpty()) {
            timelineBottom -= buttonHeight + 3;
            int slots = (requestButton ? 1 : 0) + (queued ? 1 : 0) + (issue.isEmpty() ? 0 : 1);
            int slotWidth = (this.width - 16 - (slots - 1) * 4) / slots, x = 8;
            if (requestButton) {
                button(
                        "request",
                        "! " + pending.request.title,
                        x,
                        timelineBottom,
                        slotWidth,
                        () -> request(pending));
                x += slotWidth + 4;
            }
            if (queued) {
                button(
                        "queue",
                        "Queued: " + editing.queue.size(),
                        x,
                        timelineBottom,
                        slotWidth,
                        () -> menu("queue"));
                x += slotWidth + 4;
            }
            if (!issue.isEmpty()) {
                final String message = issue;
                button(
                        "notice",
                        "! " + issue,
                        x,
                        timelineBottom,
                        slotWidth,
                        () -> inspect("Notice", message, Collections.<Resource>emptyList()));
            }
        }
        if (editing != null) timeline(y, Math.max(y, timelineBottom));
        if (editing == null || editing.timeline.size() == 0) {
            text("What would you like to build?", 12, y + 8, this.width - 24, FG);
            if (timelineBottom - y > 3 * line + 12)
                text(
                        "Describe a task, or type / to explore commands.",
                        12,
                        y + line + 12,
                        this.width - 24,
                        MUTED);
        }
        if (questionHeight > 0)
            drawQuestion(8, composerY - questionHeight - 5, this.width - 16, questionHeight);
        composer(composerY, inputRows);
        if (!overlay.isEmpty()) drawOverlay();
        else if (slashVisible()) drawSlash(composerY);
        measuredCharacters = measured.characters;
    }

    private String setting(String value) {
        return value == null || value.isEmpty() ? "Default" : value;
    }

    private String status(Chat c) {
        return c.needsAnswer()
                ? "Needs answer"
                : c.busy()
                        ? "Working (" + c.elapsedSeconds() + " s)"
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

    private void inlineText(InlineLayout layout, String value, String key) {
        MarkdownLayout wrapped = proseCache.computeIfAbsent(key, k -> new MarkdownLayout());
        wrapped.update(surface, value, this.width - 28);
        for (MarkdownLayout.Row row : wrapped.rows) {
            InlinePart part = new InlinePart(null, null, layout.height, row.height);
            part.markdown = row;
            layout.parts.add(part);
            layout.height += row.height;
        }
    }

    private InlineLayout inlineLayout(Item item) {
        String key = item.id + ":" + width + ":" + line;
        InlineLayout layout = inlineCache.get(key);
        if (layout != null && item.text.equals(layout.source)) return layout;
        layout = new InlineLayout();
        layout.source = item.text;
        layout.height = line + 5;
        WidgetRegistry.Message message =
                item.kind == Kind.ASSISTANT && item.text.contains("```")
                        ? widgetMessage(item.text)
                        : null;
        if (message == null) inlineText(layout, item.text, key);
        else {
            int index = 0;
            for (WidgetRegistry.Part part : message.parts) {
                String partKey = key + ":" + index++;
                if (part.widget == null) inlineText(layout, part.text, partKey);
                else {
                    WidgetRegistry.Entry widget = part.widget;
                    layout.height += 4;
                    inlineText(layout, widget.title, partKey + ":title");
                    int h = widget.height(this.width - 24, line, surface::textWidth);
                    if (h > 0) {
                        layout.parts.add(new InlinePart(null, widget, layout.height, h));
                        layout.height += h + 4;
                    }
                    if (h == 0 || !widget.includesText())
                        inlineText(layout, widget.text, partKey + ":text");
                }
            }
        }
        layout.height = Math.max(2 * line + 12, layout.height + 8);
        inlineCache.put(key, layout);
        return layout;
    }

    private int itemHeight(Item item) {
        return readable(item) ? inlineLayout(item).height : 3 * line + 8;
    }

    private int rowHeight(TimelineRows.Row row) {
        if (question != null
                && !question.collapsed
                && row.first().id.equals("request:" + question.pending.request.key.token)) return 0;
        return row.group() ? 3 * line + 12 : itemHeight(row.first());
    }

    private void timeline(int y, int end) {
        Timeline timeline = editing.timeline;
        timelineRows.update(timeline);
        int total = timelineRows.rows.size();
        timelineViewport = Math.max(0, end - y);
        if (total == 0 || timelineViewport == 0) return;
        latestStart = total - 1;
        int used = rowHeight(timelineRows.rows.get(latestStart));
        while (latestStart > 0 && used < timelineViewport) {
            int previous = rowHeight(timelineRows.rows.get(latestStart - 1));
            latestStart--;
            used += previous;
        }
        int start =
                editing.ui.anchor.isEmpty()
                        ? latestStart
                        : timelineRows.index(timeline, editing.ui.anchor);
        if (start < 0) {
            start = latestStart;
            timelineOffset = 0;
        }
        if (editing.ui.anchor.isEmpty()) {
            timelineOffset = Math.max(0, used - timelineViewport);
            readRevision = timeline.revision();
        }
        firstVisible = start;
        timelineOffset =
                Math.min(
                        timelineOffset,
                        Math.max(0, rowHeight(timelineRows.rows.get(start)) - line));
        int viewportTop = y;
        y -= timelineOffset;
        surface.clip(8, viewportTop, this.width - 8, end);
        for (int i = start; i < total && y < end; i++) {
            final TimelineRows.Row row = timelineRows.rows.get(i);
            final Item item = row.first();
            int cardHeight = rowHeight(row);
            if (cardHeight == 0) continue;
            renderedItems++;
            int background =
                    item.kind == Kind.USER ? 0xe02b3c4a : readable(item) ? 0xc0262b30 : FIELD;
            rect(8, y, this.width - 16, cardHeight - 3, background);
            rect(
                    8,
                    y,
                    2,
                    cardHeight - 3,
                    item.state == State.FAILED || row.failed > 0
                            ? ERROR
                            : item.kind == Kind.USER ? ACCENT : 0xff58636d);
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
                                () -> {
                                    if (row.group()) {
                                        activity = row;
                                        menu("activity");
                                    } else inspect(item);
                                }));
            if (row.group()) {
                text(
                        "MCP activity · " + row.items.size() + " calls",
                        14,
                        y + 3,
                        this.width - 28,
                        ACCENT);
                text(
                        row.summary(),
                        14,
                        y + line + 5,
                        this.width - 28,
                        row.failed > 0 ? ERROR : MUTED);
                text(
                        "> " + row.items.get(row.items.size() - 1).title,
                        14,
                        y + 2 * line + 6,
                        this.width - 28,
                        FG);
                y += cardHeight;
                continue;
            }
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
                    if (part.widget == null) {
                        markdown(
                                part.markdown,
                                14,
                                yy,
                                viewportTop,
                                end,
                                "link:" + item.id + ":" + partIndex);
                    } else {
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
                                            surface,
                                            12,
                                            yy,
                                            this.width - 24,
                                            part.height,
                                            widgetActions(
                                                    part.widget,
                                                    "widget:" + item.id + ":" + selected,
                                                    12,
                                                    viewportTop,
                                                    this.width - 12,
                                                    end)));
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
                for (String preview : rows) {
                    text(preview, 12, yy, this.width - 24, FG);
                    yy += line;
                }
            }
            y += cardHeight;
        }
        surface.unclip();
        if (!editing.ui.anchor.isEmpty())
            button(
                    "latest",
                    timeline.revision() != readRevision ? "New events ↓" : "Latest ↓",
                    Math.max(8, this.width - 112),
                    Math.max(top + 4, end - buttonHeight),
                    100,
                    () -> {
                        editing.ui.anchor = "";
                        timelineOffset = 0;
                        readRevision = timeline.revision();
                    });
    }

    private EditorView editorView(Editor editor) {
        return editorViews.computeIfAbsent(editor, EditorView::new);
    }

    private void drawEditor(
            String id, Editor editor, int x, int y, int w, int rows, boolean secret) {
        EditorView view = editorView(editor);
        view.layout(surface, x + 4, y + 3, w - 8, rows, secret);
        editorHits.put(id, view);
        rect(x, y, w, rows * line + 6, focus.equals(id) ? 0xff343f49 : FIELD);
        controls.add(new Control(id, id, x, y, w, rows * line + 6, () -> focus = id));
        for (int r = view.first; r < Math.min(view.layout.lines.size(), view.first + rows); r++) {
            String row = view.layout.lines.get(r);
            int yy = y + 3 + (r - view.first) * line, start = view.layout.starts.get(r);
            int a = Math.max(start, Math.min(editor.mark, editor.cursor)),
                    b = Math.min(start + row.length(), Math.max(editor.mark, editor.cursor));
            if (a < b)
                rect(
                        x + 4 + surface.textWidth(row.substring(0, a - start)),
                        yy,
                        surface.textWidth(row.substring(a - start, b - start)),
                        line,
                        0xff45617a);
            text(row, x + 4, yy, w - 8, FG);
        }
        if (focus.equals(id)) {
            int row = view.layout.row(editor.cursor), start = view.layout.starts.get(row);
            int cx = x + 4 + surface.textWidth(view.layout.source.substring(start, editor.cursor));
            rect(
                    Math.min(x + w - 5, cx),
                    y + 3 + (row - view.first) * line,
                    vim && !vimInsert ? 4 : 1,
                    line,
                    ACCENT);
        }
    }

    private void composer(int y, int rows) {
        drawEditor("composer", composer, 8, y, this.width - 16, rows, false);
        if (composer.text.isEmpty())
            text(
                    editing != null && editing.busy() ? "Add a follow-up…" : "Message or /command",
                    12,
                    y + 3,
                    this.width - 24,
                    MUTED);
        String action =
                editing != null && editing.busy()
                        ? (steer ? "Enter: follow up" : "Enter: queue")
                        : "Enter: send";
        text(
                action
                        + (this.width < 300
                                ? " · / commands"
                                : " · Shift+Enter: newline · / commands"),
                8,
                y + rows * line + 10,
                this.width - 16,
                MUTED);
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
            helpPage();
            return;
        }
        if (route.equals("status")) {
            dashboard();
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
                                                        performAction(
                                                                action.id,
                                                                Collections
                                                                        .<String, Value>
                                                                                emptyMap()));
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
        editorHits.clear();
        rect(4, 4, this.width - 8, height - 8, 0xeb182028);
        int x = 12, y = 12, w = this.width - 24, h = bottom - y - 4;
        rect(x, y, w, h, 0xf0202428);
        button("back", "Back", x + 4, y + 4, 52, this::goBack);
        text(InfoPage.label(overlay), x + 64, y + 8, w - 72, ACCENT);
        y += buttonHeight + 12;
        if (overlay.equals("dashboard") || overlay.equals("information")) {
            drawInformation(x + 4, y, w - 8, bottom - 8);
            return;
        }
        if (overlay.equals("choices")) {
            drawChoices(x + 4, y, w - 8, bottom - 8);
            return;
        }
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
        drawEditor("search", search, x + 4, y, w - 8, 1, false);
        if (search.text.isEmpty()) text("Search…", x + 8, y + 3, w - 16, MUTED);
        y += buttonHeight + 4;
        List<MenuEntry> entries = entries();
        int visible = Math.max(1, (bottom - y - line - 12) / (buttonHeight + 3));
        menuOffset = Math.min(menuOffset, Math.max(0, entries.size() - visible));
        if (entries.isEmpty())
            text(
                    search.text.isEmpty() ? "Nothing here yet" : "No matching entries",
                    x + 8,
                    y + 6,
                    w - 16,
                    MUTED);
        for (int i = menuOffset; i < Math.min(entries.size(), menuOffset + visible); i++) {
            MenuEntry e = entries.get(i);
            button(e.id, e.label, x + 4, y, w - 8, e.action);
            y += buttonHeight + 3;
        }
        if (entries.size() > visible)
            text(
                    (menuOffset + 1)
                            + "–"
                            + Math.min(entries.size(), menuOffset + visible)
                            + " / "
                            + entries.size()
                            + " · scroll",
                    x + 8,
                    bottom - line - 5,
                    w - 16,
                    MUTED);
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
        if (overlay.equals("activity") && activity != null) {
            for (Item item : activity.items)
                entries.add(
                        new MenuEntry(
                                "call:" + item.id,
                                (item.state == State.FAILED
                                                ? "! "
                                                : item.state == State.RUNNING ? "> " : "· ")
                                        + item.title
                                        + " · "
                                        + InfoPage.label(item.state.name()),
                                () -> inspect(item)));
        } else if (overlay.equals("backends")) {
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
                                    (editing != null && m.id.equals(editing.ui.settings.model)
                                                    ? "● "
                                                    : "")
                                            + m.name,
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
                    new MenuEntry("currentSettings", "Current / next parameters", this::dashboard));
            if (backend() != null)
                for (Model m : backend().models)
                    if (m.id.equals(s.model)) {
                        for (String effort : m.efforts)
                            entries.add(
                                    new MenuEntry(
                                            "effort:" + effort,
                                            (effort.equals(s.effort) ? "● " : "")
                                                    + "Effort: "
                                                    + effort,
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
                                            (tier.equals(s.tier) ? "● " : "") + "Tier: " + tier,
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
                                this::dashboard));
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
                                                    actionFields.clear();
                                                    if (a.form.fields.isEmpty()
                                                            || a.form.fields.stream()
                                                                    .allMatch(
                                                                            f ->
                                                                                    !f.required
                                                                                            && f.id
                                                                                                    .equals(
                                                                                                            "cursor"))) {
                                                        overlay = "";
                                                        focus = "composer";
                                                        watch(
                                                                performAction(
                                                                        a.id,
                                                                        Collections
                                                                                .<String, Value>
                                                                                        emptyMap()));
                                                    } else
                                                        form(
                                                                a.title,
                                                                a.description,
                                                                a.form,
                                                                values ->
                                                                        performAction(
                                                                                a.id, values));
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

    private void goBack() {
        if (overlay.equals("choices")) {
            overlay = "form";
            focus = "field:" + choiceField.id;
            return;
        }
        String next = overlay.equals("details") ? backTarget : "";
        backTarget = "";
        overlay = next;
        focus = next.isEmpty() ? "composer" : "back";
    }

    private void dashboard() {
        overlay = "dashboard";
        focus = "back";
        pageOffset = 0;
    }

    private InfoPage statusPage() {
        InfoPage page =
                new InfoPage("Session status", "Your world, conversation and active settings");
        BackendState backend = backend();
        page.card("Connection")
                .field("Backend", backendId)
                .field(
                        "State",
                        backend == null
                                ? "Unavailable"
                                : backend.connecting
                                        ? "Connecting"
                                        : backend.ready ? "Connected" : "Disconnected");
        if (editing != null) {
            page.card("Conversation")
                    .field("Name", editing.conversation.title)
                    .field("Activity", status(editing))
                    .field(
                            "Follow-ups",
                            editing.queue.size()
                                    + " queued"
                                    + (editing.queuePaused ? " · paused" : ""));
            Settings settings = editing.ui.settings;
            page.card(editing.busy() ? "Next message" : "Model")
                    .field("Model", setting(settings.model))
                    .field("Reasoning", setting(settings.effort))
                    .field("Service", setting(settings.tier));
            if (editing.busy() && editing.runningSettings != null) {
                Settings running = editing.runningSettings;
                page.card("Running task")
                        .field("Model", setting(running.model))
                        .field("Reasoning", setting(running.effort));
            }
            page.card("Access")
                    .field("Permissions", setting(settings.permissionProfile))
                    .field("Work mode", setting(settings.mode));
            if (editing.usage != null) {
                InfoPage usage =
                        InfoPage.result(
                                "usage",
                                new ActionResult(
                                        "Usage",
                                        "",
                                        editing.usage.details,
                                        Collections.<Resource>emptyList()));
                page.cards.addAll(usage.cards);
            }
        }
        page.card("Workspace")
                .field("Directory", core.workspace().getPath())
                .field("Scope", "Shared by this world's conversations");
        return page;
    }

    private void helpPage() {
        information =
                new InfoPage(
                        "Chat guide", "Write naturally. Use / for commands and quick actions.");
        information
                .card("Write & edit")
                .field("Enter / Shift+Enter", "Send / new line")
                .field("Ctrl+Z / Ctrl+Y", "Undo / redo")
                .field("Ctrl+Up / Down", "Previous / next input");
        information
                .card("Conversation")
                .field("/new · /resume", "Start or switch a conversation")
                .field("/model · /status", "Choose a model or view settings");
        information
                .card("While working")
                .field("/stop", "Interrupt the current task")
                .field("/steer · /queue", "Follow-up mode and pending messages");
        information
                .card("Explore")
                .field("/mcp · /skills · /plugins", "Connected tools and extensions")
                .field("/commands", "Browse all available operations");
        resultRaw = "";
        actionId = "";
        detailResources = Collections.emptyList();
        overlay = "information";
        pageOffset = 0;
        focus = "back";
    }

    private CompletableFuture<ActionResult> performAction(String id, Map<String, Value> fields) {
        final long generation = ++informationGeneration;
        final Chat owner = editing;
        actionId = id;
        actionFields.clear();
        actionFields.putAll(fields);
        information = new InfoPage(InfoPage.label(id), "Loading…");
        information.card("Please wait").field("Status", "Fetching the latest information");
        resultRaw = "";
        nextPage = null;
        informationLoading = true;
        detailResources = Collections.emptyList();
        overlay = "information";
        pageOffset = 0;
        focus = "back";
        CompletableFuture<ActionResult> future =
                core.act(backendId, owner, id, new LinkedHashMap<String, Value>(fields));
        future.whenComplete(
                (result, failure) -> {
                    if (generation != informationGeneration
                            || core.selected() != owner
                            || !overlay.equals("information")
                            || !actionId.equals(id)) return;
                    informationLoading = false;
                    if (failure == null) showResult(id, result);
                    else {
                        information = new InfoPage(InfoPage.label(id), "Could not load this view");
                        information
                                .card("Connection or command error")
                                .field("Message", message(failure));
                        resultRaw = message(failure);
                    }
                });
        return future;
    }

    private void showResult(String id, ActionResult result) {
        actionId = id;
        information = InfoPage.result(id, result);
        informationLoading = false;
        Value cursor = result.fields.get("nextCursor");
        nextPage =
                cursor != null && cursor.type == Value.Type.TEXT && !cursor.text().isEmpty()
                        ? cursor.text()
                        : null;
        resultRaw = describe(result.fields) + (result.text == null ? "" : result.text);
        detailResources = result.resources;
        overlay = "information";
        pageOffset = 0;
        focus = "back";
    }

    private void drawInformation(int x, int y, int w, int end) {
        InfoPage page = overlay.equals("dashboard") ? statusPage() : information;
        if (page == null) return;
        text(page.title, x, y, w, FG);
        y += line + 5;
        text(page.subtitle, x, y, w, MUTED);
        y += line + 8;
        int footer = end - buttonHeight, contentEnd = footer - 8;
        int columns = w >= 360 ? 2 : 1, gap = 8, cardWidth = (w - gap * (columns - 1)) / columns;
        List<Integer> heights = new ArrayList<Integer>();
        for (InfoPage.Card card : page.cards) heights.add(cardHeight(card, cardWidth));
        int total = 0;
        for (int i = 0; i < heights.size(); i += columns) {
            int h = heights.get(i);
            if (columns == 2 && i + 1 < heights.size()) h = Math.max(h, heights.get(i + 1));
            total += h + gap;
        }
        pageMaximum = Math.max(0, total - Math.max(0, contentEnd - y));
        pageOffset = Math.min(pageOffset, pageMaximum);
        int yy = y - pageOffset;
        surface.clip(x, y, x + w, Math.max(y, contentEnd));
        try {
            for (int i = 0; i < page.cards.size(); i += columns) {
                int rowHeight = heights.get(i);
                if (columns == 2 && i + 1 < heights.size())
                    rowHeight = Math.max(rowHeight, heights.get(i + 1));
                if (yy + rowHeight > y && yy < contentEnd)
                    for (int col = 0; col < columns && i + col < page.cards.size(); col++)
                        drawCard(
                                page.cards.get(i + col),
                                x + col * (cardWidth + gap),
                                yy,
                                cardWidth,
                                rowHeight);
                yy += rowHeight + gap;
            }
        } finally {
            surface.unclip();
        }
        if (pageMaximum > 0) {
            rect(x + w - 2, y, 2, Math.max(1, contentEnd - y), FIELD);
            int thumb = Math.max(10, (contentEnd - y) * (contentEnd - y) / Math.max(1, total));
            rect(
                    x + w - 2,
                    y + (contentEnd - y - thumb) * pageOffset / Math.max(1, pageMaximum),
                    2,
                    thumb,
                    ACCENT);
        }
        int bw = (w - 8) / 3;
        if (overlay.equals("dashboard")) {
            button("dashboard:models", "Model", x, footer, bw, () -> menu("models"));
            button("dashboard:queue", "Queue", x + bw + 4, footer, bw, () -> menu("queue"));
            button(
                    "dashboard:details",
                    "Details",
                    x + 2 * (bw + 4),
                    footer,
                    bw,
                    () ->
                            inspect(
                                    "Session details",
                                    editing == null
                                            ? core.workspace().getPath()
                                            : settingsText(editing),
                                    Collections.<Resource>emptyList()));
        } else {
            if (actionId.isEmpty())
                button("help:commands", "All commands", x, footer, w, () -> menu("actions"));
            else if (!informationLoading) {
                button(
                        "result:details",
                        "Details",
                        x,
                        footer,
                        bw,
                        () ->
                                inspect(
                                        "Technical details · " + page.title,
                                        resultRaw,
                                        detailResources));
                if (Arrays.asList(
                                "mcp",
                                "skills",
                                "plugins",
                                "plugins.installed",
                                "apps",
                                "limits",
                                "account",
                                "usage",
                                "config",
                                "features",
                                "memory",
                                "requirements")
                        .contains(actionId))
                    button(
                            "result:refresh",
                            "Refresh",
                            x + bw + 4,
                            footer,
                            bw,
                            () ->
                                    watch(
                                            performAction(
                                                    actionId,
                                                    new LinkedHashMap<String, Value>(
                                                            actionFields))));
                if (nextPage != null)
                    button(
                            "result:next",
                            "Next page",
                            x + 2 * (bw + 4),
                            footer,
                            bw,
                            () -> {
                                Map<String, Value> fields =
                                        new LinkedHashMap<String, Value>(actionFields);
                                fields.put("cursor", Value.text(nextPage));
                                watch(performAction(actionId, fields));
                            });
                else if (!detailResources.isEmpty())
                    button(
                            "resources",
                            "Links",
                            x + 2 * (bw + 4),
                            footer,
                            bw,
                            () -> menu("resources"));
            }
        }
    }

    private int cardHeight(InfoPage.Card card, int w) {
        int h = line + 18 + (card.percent < 0 ? 0 : 10);
        for (Map.Entry<String, String> field : card.fields.entrySet())
            h +=
                    line
                            + 2
                            + textCache.preview(surface, safe(field.getValue()), w - 16, 5).size()
                                    * line
                            + 6;
        return h;
    }

    private void drawCard(InfoPage.Card card, int x, int y, int w, int h) {
        rect(x, y, w, h, FIELD);
        rect(x, y, 2, h, card.warning ? ERROR : ACCENT);
        text(card.title, x + 8, y + 7, w - 16, card.warning ? ERROR : ACCENT);
        int yy = y + line + 13;
        if (card.percent >= 0) {
            rect(x + 8, yy, w - 16, 3, 0xff1d242b);
            rect(
                    x + 8,
                    yy,
                    (int) ((w - 16) * card.percent / 100),
                    3,
                    card.percent >= 90 ? ERROR : ACCENT);
            yy += 10;
        }
        for (Map.Entry<String, String> field : card.fields.entrySet()) {
            text(field.getKey(), x + 8, yy, w - 16, MUTED);
            yy += line + 2;
            for (String value : textCache.preview(surface, safe(field.getValue()), w - 16, 5)) {
                text(value, x + 8, yy, w - 16, FG);
                yy += line;
            }
            yy += 6;
        }
    }

    private void markdown(MarkdownLayout.Row row, int x, int y, int top, int bottom, String key) {
        for (MarkdownLayout.Box box : row.boxes)
            rect(x + box.x, y + box.y, box.width, box.height, box.color);
        int index = 0;
        for (MarkdownLayout.Run run : row.runs) {
            int xx = x + run.x, yy = y + row.padding;
            int visibleWidth = Math.min(run.width, this.width - 14 - xx);
            if (visibleWidth <= 0) continue;
            if (run.code) rect(xx, yy, visibleWidth, line, 0xff1d252d);
            surface.clip(xx, yy, xx + visibleWidth, yy + line);
            surface.text(run.text, xx, yy, color(run.color), run.style);
            surface.unclip();
            int hitTop = Math.max(yy, top), hitBottom = Math.min(yy + line, bottom);
            if (run.link != null && hitBottom > hitTop && overlay.isEmpty())
                controls.add(
                        new Control(
                                key + ":" + index,
                                run.text,
                                xx,
                                hitTop,
                                visibleWidth,
                                hitBottom - hitTop,
                                () ->
                                        confirm(
                                                "Open externally: " + run.link,
                                                () -> surface.openResource(run.link))));
            index++;
        }
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
        List<Resource> links = new ArrayList<Resource>(item.resources);
        for (Resource link : MessageMarkup.links(item.text))
            if (links.stream().noneMatch(r -> r.location.equals(link.location))) links.add(link);
        if (item.kind == Kind.TOOL) {
            JsonText json = JsonText.tool(item);
            inspect(item.title, json.text, links);
            detailJson = json;
        } else inspect(item.title, item.text + "\n" + describe(item.details), links);
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
        backTarget =
                Arrays.asList("activity", "form", "dashboard", "information").contains(overlay)
                        ? overlay
                        : "";
        detailWidgets = null;
        detailJson = null;
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
        int copyWidth = Math.min(72, (w - 4) / 2);
        button(
                "copyDetails",
                w < 180 ? "Copy" : "Copy all",
                x,
                y,
                copyWidth,
                () -> surface.clipboard(detailCopyText));
        button(
                "selectDetails",
                w < 180 ? "Select" : "Select text",
                x + copyWidth + 4,
                y,
                w - copyWidth - 4,
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
                                        surface,
                                        x,
                                        y - detailWidgetOffset,
                                        w,
                                        widgetHeight,
                                        widgetActions(
                                                selectedWidget, "detailWidget", x, y, x + w, end)));
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
            if (detailJson != null) {
                int xx = x;
                for (JsonText.Span span : detailJson.spans(begin, begin + part.length(), FG)) {
                    String run = detailText.substring(span.start, span.end);
                    text(run, xx, y, Math.max(1, x + w - xx), span.color);
                    xx += surface.textWidth(safe(run));
                }
            } else
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
        if (InlineQuestion.supports(pending)) {
            question =
                    questions.computeIfAbsent(
                            pending.request.key, k -> new InlineQuestion(pending));
            question.collapsed = false;
            focus =
                    question.field().choices.isEmpty() && question.field().type != Form.Type.BOOLEAN
                            ? "question:" + question.field().id
                            : "question:option:0";
            question.focusOption = 0;
            overlay = "";
            return;
        }
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

    private int questionHeight(int w) {
        Form.Field field = question.field();
        int rows = new TextWindow(surface, safe(field.label), 0, w - 20, 20).lines.size();
        if (!question.pending.request.description.isEmpty())
            rows +=
                    new TextWindow(
                                            surface,
                                            safe(question.pending.request.description),
                                            0,
                                            w - 20,
                                            20)
                                    .lines.size()
                            + 1;
        int h = rows * line + 62;
        for (String option :
                field.type == Form.Type.BOOLEAN ? Arrays.asList("Yes", "No") : field.choices) {
            h += new TextWindow(surface, safe(option), 0, w - 40, 20).lines.size() * line + 12;
            for (String part : field.description.split("\n"))
                if (part.startsWith(option + ": "))
                    h +=
                            new TextWindow(
                                                    surface,
                                                    safe(part.substring(option.length() + 2)),
                                                    0,
                                                    w - 40,
                                                    20)
                                            .lines.size()
                                    * line;
        }
        if (field.choices.isEmpty() || question.custom) h += line + buttonHeight + 12;
        return Math.min(190, h);
    }

    private void drawQuestion(int x, int y, int w, int h) {
        final InlineQuestion state = question;
        Form.Field field = state.field();
        state.top = y;
        state.bottom = y + h;
        rect(x, y, w, h, 0xf02a333d);
        rect(x, y, 2, h, ACCENT);
        text(
                "Question " + (state.index + 1) + " / " + state.pending.request.form.fields.size(),
                x + 8,
                y + 7,
                w - 76,
                ACCENT);
        controls.add(
                new Control(
                        "request",
                        state.pending.request.title,
                        x,
                        y,
                        w - 66,
                        buttonHeight,
                        () -> focus = "question:" + field.id));
        button(
                "question:minimize",
                "Later",
                x + w - 60,
                y + 3,
                56,
                () -> {
                    state.collapsed = true;
                    focus = "composer";
                });
        int footer = y + h - buttonHeight - 6, bodyTop = y + line + 17, bodyEnd = footer - 5;
        boolean editable =
                Arrays.asList(Form.Type.TEXT, Form.Type.SECRET, Form.Type.NUMBER, Form.Type.INTEGER)
                        .contains(field.type);
        boolean input = editable && (field.choices.isEmpty() || state.custom);
        if (!state.error.isEmpty()) {
            text(state.error, x + 8, bodyEnd - line, w - 16, ERROR);
            bodyEnd -= line + 4;
        }
        if (input) {
            int answerY = bodyEnd - line - buttonHeight - 5;
            text(
                    field.choices.isEmpty() ? "Your answer" : "Or write your own",
                    x + 8,
                    answerY,
                    w - 16,
                    MUTED);
            drawEditor(
                    "question:" + field.id,
                    state.editor(field),
                    x + 8,
                    answerY + line + 3,
                    w - 16,
                    1,
                    field.type == Form.Type.SECRET);
            bodyEnd = answerY - 5;
        }
        List<String> title = new ArrayList<String>();
        if (!state.pending.request.description.isEmpty()) {
            title.addAll(
                    new TextWindow(
                                    surface,
                                    safe(state.pending.request.description),
                                    0,
                                    w - 20,
                                    Integer.MAX_VALUE)
                            .lines);
            title.add("");
        }
        title.addAll(
                new TextWindow(surface, safe(field.label), 0, w - 20, Integer.MAX_VALUE).lines);
        List<String> options =
                field.type == Form.Type.BOOLEAN ? Arrays.asList("Yes", "No") : field.choices;
        List<List<String>> labels = new ArrayList<List<String>>();
        List<Integer> optionHeights = new ArrayList<Integer>();
        int contentHeight = title.size() * line + 8;
        for (String option : options) {
            List<String> rows =
                    new TextWindow(surface, safe(option), 0, w - 40, Integer.MAX_VALUE).lines;
            String description = "";
            for (String part : field.description.split("\n"))
                if (part.startsWith(option + ": "))
                    description = part.substring(option.length() + 2);
            if (!description.isEmpty())
                rows.addAll(
                        new TextWindow(surface, safe(description), 0, w - 40, Integer.MAX_VALUE)
                                .lines);
            labels.add(rows);
            optionHeights.add(rows.size() * line + 8);
            contentHeight += rows.size() * line + 12;
        }
        if (options.isEmpty() && !field.description.isEmpty()) {
            title.addAll(
                    new TextWindow(surface, safe(field.description), 0, w - 20, Integer.MAX_VALUE)
                            .lines);
            contentHeight = title.size() * line + 8;
        }
        state.maximum = Math.max(0, contentHeight - Math.max(0, bodyEnd - bodyTop));
        state.offset = Math.min(state.offset, state.maximum);
        if (state.focusOption >= 0 && state.focusOption < options.size()) {
            int optionY = title.size() * line + 8;
            for (int i = 0; i < state.focusOption; i++) optionY += optionHeights.get(i) + 4;
            if (optionY < state.offset) state.offset = optionY;
            if (optionY + optionHeights.get(state.focusOption) > state.offset + bodyEnd - bodyTop)
                state.offset =
                        Math.min(
                                state.maximum,
                                optionY
                                        + optionHeights.get(state.focusOption)
                                        - (bodyEnd - bodyTop));
            state.focusOption = -1;
        }
        int yy = bodyTop - state.offset;
        surface.clip(x + 4, bodyTop, x + w - 4, Math.max(bodyTop, bodyEnd));
        try {
            for (String row : title) {
                text(row, x + 8, yy, w - 20, FG);
                yy += line;
            }
            yy += 8;
            for (int i = 0; i < options.size(); i++) {
                final String option = options.get(i);
                int oh = optionHeights.get(i);
                String id = "question:option:" + i;
                if (yy + oh > bodyTop && yy < bodyEnd) {
                    rect(
                            x + 8,
                            yy,
                            w - 18,
                            oh,
                            state.selected(option)
                                    ? 0xff3d566d
                                    : focus.equals(id) ? 0xff354453 : FIELD);
                    text(
                            state.selected(option) ? "●" : "○",
                            x + 12,
                            yy + 4,
                            10,
                            state.selected(option) ? ACCENT : MUTED);
                    int ty = yy + 4;
                    for (String row : labels.get(i)) {
                        text(row, x + 28, ty, w - 42, FG);
                        ty += line;
                    }
                    int hitTop = Math.max(bodyTop, yy), hitEnd = Math.min(bodyEnd, yy + oh);
                    if (hitEnd > hitTop)
                        controls.add(
                                new Control(
                                        id,
                                        option,
                                        x + 8,
                                        hitTop,
                                        w - 18,
                                        hitEnd - hitTop,
                                        () -> state.select(option)));
                }
                yy += oh + 4;
            }
        } finally {
            surface.unclip();
        }
        if (state.maximum > 0) {
            rect(x + w - 5, bodyTop, 2, Math.max(1, bodyEnd - bodyTop), FIELD);
            int thumb =
                    Math.max(
                            8,
                            (bodyEnd - bodyTop) * (bodyEnd - bodyTop) / Math.max(1, contentHeight));
            rect(
                    x + w - 5,
                    bodyTop
                            + (bodyEnd - bodyTop - thumb)
                                    * state.offset
                                    / Math.max(1, state.maximum),
                    2,
                    thumb,
                    ACCENT);
        }
        int count = state.pending.request.form.fields.size();
        if (count > 1) {
            button(
                    "question:previous",
                    "<",
                    x + 8,
                    footer,
                    22,
                    () -> {
                        state.index = Math.max(0, state.index - 1);
                        state.offset = 0;
                        state.error = "";
                    });
            button(
                    "question:next",
                    ">",
                    x + 34,
                    footer,
                    22,
                    () -> {
                        state.index = Math.min(count - 1, state.index + 1);
                        state.offset = 0;
                        state.error = "";
                    });
        }
        if (editable && !field.choices.isEmpty())
            button(
                    "question:custom",
                    state.custom ? "Options" : "Other",
                    x + (count > 1 ? 60 : 8),
                    footer,
                    48,
                    () -> {
                        state.custom = !state.custom;
                        focus = state.custom ? "question:" + field.id : "question:custom";
                    });
        button(
                "question:submit",
                state.pending.status == Pending.Status.SENDING ? "Sending…" : "Submit answer",
                x + w - 104,
                footer,
                96,
                () -> submitQuestion(state));
    }

    private void submitQuestion(InlineQuestion state) {
        if (state.pending.status != Pending.Status.OPEN || !state.collect()) return;
        final Chat owner = editing;
        core.answer(owner, state.pending, new LinkedHashMap<String, Value>(state.values))
                .whenComplete(
                        (r, e) -> {
                            if (e != null) state.error = message(e);
                            else if (editing == owner && focus.startsWith("question:"))
                                focus = "composer";
                        });
    }

    private void choose(Form.Field field, Editor editor) {
        if (formSending) return;
        choiceField = field;
        choiceEditor = editor;
        overlay = "choices";
        menuOffset = 0;
        focus = "back";
    }

    private void drawChoices(int x, int y, int w, int end) {
        text(choiceField.label, x, y, w, ACCENT);
        y += line + 6;
        if (choiceEditor != null) {
            text("Choose a suggestion, then edit your answer if needed.", x, y, w, MUTED);
            y += line + 6;
        }
        int visible = Math.max(1, (end - y) / (buttonHeight + 3));
        menuOffset = Math.max(0, Math.min(menuOffset, choiceField.choices.size() - visible));
        for (int i = menuOffset;
                i < Math.min(choiceField.choices.size(), menuOffset + visible);
                i++) {
            final String value = choiceField.choices.get(i);
            button(
                    "choice:" + i,
                    value,
                    x,
                    y,
                    w,
                    () -> {
                        if (choiceEditor != null) {
                            choiceEditor.selectAll();
                            choiceEditor.insert(value);
                        }
                        formValues.put(choiceField.id, Value.text(value));
                        overlay = "form";
                        focus = "field:" + choiceField.id;
                    });
            y += buttonHeight + 3;
        }
    }

    private void drawForm(int x, int y, int w, int end) {
        text(formTitle + (formSending ? " · Sending…" : ""), x, y, w, ACCENT);
        y += line + 5;
        if (!formDescription.isEmpty()) {
            for (String part : textCache.preview(surface, formDescription, w - 70, 2)) {
                text(part, x, y, w - 70, MUTED);
                y += line;
            }
            button(
                    "form:description",
                    "Details",
                    x + w - 64,
                    y - line,
                    64,
                    () -> inspect(formTitle, formDescription, Collections.<Resource>emptyList()));
            y += 6;
        }
        int fieldEnd = end - buttonHeight - line - 6;
        List<Form.Field> fields = activeForm.fields;
        int last = formOffset;
        for (int i = formOffset; i < fields.size(); i++) {
            Form.Field field = fields.get(i);
            List<String> label =
                    textCache.preview(surface, field.label + (field.required ? " *" : ""), w, 3);
            List<String> description = textCache.preview(surface, field.description, w, 2);
            int required =
                    label.size() * line
                            + description.size() * line
                            + buttonHeight
                            + 10
                            + (formErrors.containsKey(field.id) ? 2 * line : 0);
            if (y + required > fieldEnd) break;
            for (String row : label) {
                text(row, x, y, w, FG);
                y += line;
            }
            y += 2;
            for (String row : description) {
                text(row, x, y, w, MUTED);
                y += line;
            }
            Value value = formValues.get(field.id);
            if (field.type == Form.Type.CHOICE)
                button(
                        "field:" + field.id,
                        value == null ? "Choose an option…" : InfoPage.scalar(value),
                        x,
                        y,
                        w,
                        () -> choose(field, null));
            else if (field.type == Form.Type.BOOLEAN)
                button(
                        "field:" + field.id,
                        value == null ? "Choose Yes / No" : InfoPage.scalar(value),
                        x,
                        y,
                        w,
                        () -> {
                            if (!formSending)
                                formValues.put(
                                        field.id, Value.bool(value == null || !value.bool()));
                        });
            else if (field.type == Form.Type.MULTICHOICE)
                button(
                        "field:" + field.id,
                        value == null ? "Choose options" : InfoPage.scalar(value),
                        x,
                        y,
                        w,
                        () -> editChoices(field));
            else if (field.type == Form.Type.GROUP)
                button("field:" + field.id, "Edit fields", x, y, w, () -> editGroup(field));
            else {
                Editor input =
                        formEditors.computeIfAbsent(
                                field.id,
                                k ->
                                        new Editor(
                                                value == null ? "" : value.toString(),
                                                value == null ? 0 : value.toString().length()));
                int options = field.choices.isEmpty() ? 0 : 60;
                drawEditor(
                        "field:" + field.id,
                        input,
                        x,
                        y,
                        w - options,
                        1,
                        field.type == Form.Type.SECRET);
                if (options > 0)
                    button(
                            "option:" + field.id,
                            "Choose",
                            x + w - 56,
                            y,
                            56,
                            () -> choose(field, input));
            }
            y += buttonHeight + 4;
            if (formErrors.containsKey(field.id))
                for (String row : textCache.preview(surface, formErrors.get(field.id), w, 2)) {
                    text(row, x, y, w, ERROR);
                    y += line;
                }
            y += 4;
            last = i + 1;
        }
        if (fields.size() > last || formOffset > 0)
            text(
                    "Fields " + (formOffset + 1) + "–" + last + " / " + fields.size() + " · scroll",
                    x,
                    end - buttonHeight - line - 3,
                    w,
                    MUTED);
        if (formErrors.containsKey("submit"))
            button(
                    "form:error",
                    "! " + formErrors.get("submit"),
                    x,
                    end - 2 * buttonHeight - 3,
                    w,
                    () ->
                            inspect(
                                    "Could not submit",
                                    formErrors.get("submit"),
                                    Collections.<Resource>emptyList()));
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
        if (!errors.isEmpty()) {
            for (int i = 0; i < activeForm.fields.size(); i++)
                if (errors.containsKey(activeForm.fields.get(i).id)) {
                    formOffset = i;
                    focus = "field:" + activeForm.fields.get(i).id;
                    break;
                }
            return;
        }
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
        dragging = null;
        for (int i = controls.size() - 1; i >= 0; i--) {
            Control c = controls.get(i);
            if (x >= c.x && x < c.x + c.w && y >= c.y && y < c.y + c.h) {
                focus = c.id;
                EditorView editor = editorHits.get(c.id);
                if (editor != null) {
                    editor.editor.place(editor.position(surface, x, y), false);
                    dragging = editor.editor;
                    sync();
                }
                c.action.run();
                return;
            }
        }
    }

    public void drag(int x, int y) {
        if (dragging == null) return;
        EditorView view = editorViews.get(dragging);
        if (view != null) {
            dragging.place(view.position(surface, x, y), true);
            sync();
        }
    }

    public void release() {
        dragging = null;
    }

    public void scroll(int direction) {
        if (!overlay.isEmpty()) {
            if (overlay.equals("dashboard") || overlay.equals("information")) {
                pageOffset =
                        Math.max(
                                0,
                                Math.min(
                                        pageMaximum,
                                        pageOffset
                                                + (direction > 0 ? -1 : 1)
                                                        * Math.max(line, (height - 100) / 3)));
                return;
            }
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
        if (question != null
                && !question.collapsed
                && ((mouseY >= question.top
                                && mouseY < question.bottom
                                && mouseX >= 8
                                && mouseX < this.width - 8)
                        || focus.startsWith("question:"))) {
            question.offset =
                    Math.max(
                            0,
                            Math.min(
                                    question.maximum,
                                    question.offset + (direction > 0 ? -1 : 1) * 3 * line));
            return;
        }
        if (editing == null || timelineRows.rows.isEmpty()) return;
        int index = Math.max(0, Math.min(firstVisible, timelineRows.rows.size() - 1));
        int step = Math.max(line, timelineViewport / 2);
        if (direction > 0) {
            if (timelineOffset > 0) timelineOffset = Math.max(0, timelineOffset - step);
            else if (index > 0) {
                index--;
                timelineOffset =
                        Math.max(0, rowHeight(timelineRows.rows.get(index)) - timelineViewport);
            }
        } else {
            int remaining = rowHeight(timelineRows.rows.get(index)) - timelineViewport;
            if (timelineOffset < remaining)
                timelineOffset = Math.min(remaining, timelineOffset + step);
            else if (index < timelineRows.rows.size() - 1) {
                index++;
                timelineOffset = 0;
            }
        }
        int remainingHeight = 0;
        for (int i = index;
                i < timelineRows.rows.size()
                        && remainingHeight <= timelineViewport + timelineOffset;
                i++) remainingHeight += rowHeight(timelineRows.rows.get(i));
        boolean atEnd =
                index >= latestStart && remainingHeight - timelineOffset <= timelineViewport;
        editing.ui.anchor = atEnd ? "" : timelineRows.rows.get(index).first().id;
        editing.ui.anchorOffset = timelineOffset;
        firstVisible = index;
    }

    public void key(Key key, char character, boolean shift, boolean control) {
        if (overlay.isEmpty()
                && question != null
                && !question.collapsed
                && focus.startsWith("question:option:")
                && !control) {
            List<String> options =
                    question.field().type == Form.Type.BOOLEAN
                            ? Arrays.asList("Yes", "No")
                            : question.field().choices;
            if (!options.isEmpty()) {
                int current =
                        Math.min(
                                options.size() - 1,
                                Integer.parseInt(focus.substring("question:option:".length())));
                if (key == Key.UP || key == Key.DOWN) {
                    int next =
                            Math.max(
                                    0,
                                    Math.min(
                                            options.size() - 1,
                                            current + (key == Key.UP ? -1 : 1)));
                    focus = "question:option:" + next;
                    question.focusOption = next;
                    return;
                }
                if (key == Key.ENTER || key == Key.OTHER && character == ' ') {
                    question.select(options.get(current));
                    return;
                }
            }
        }
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
                goBack();
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
        else if (overlay.isEmpty()
                && focus.startsWith("question:")
                && editorHits.containsKey(focus)) editor = editorHits.get(focus).editor;
        else if (!overlay.isEmpty() && focus.equals("search")) editor = search;
        else if (focus.startsWith("field:")) editor = formEditors.get(focus.substring(6));
        else if (!overlay.isEmpty()
                && !Arrays.asList(
                                "form", "confirm", "details", "dashboard", "information", "choices")
                        .contains(overlay)
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
        if (editor == null
                || formSending && overlay.equals("form")
                || question != null
                        && focus.startsWith("question:")
                        && question.pending.status != Pending.Status.OPEN) return;
        if (control && (key == Key.Z || key == Key.Y)) editor.undo(key == Key.Y || shift);
        else if (control && key == Key.A) editor.selectAll();
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
        else if (key == Key.UP || key == Key.DOWN) {
            EditorView view = editorViews.get(editor);
            if (view != null)
                editor.place(
                        view.layout.vertical(surface, editor.cursor, key == Key.UP ? -1 : 1),
                        shift);
            else editor.vertical(key == Key.UP ? -1 : 1, shift);
        } else if (key == Key.BACKSPACE) editor.backspace();
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
