package dev.minecraftharness.chat;

import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.core.Timeline;
import java.util.*;

/** Incremental presentation projection. Original tool events remain independently inspectable. */
final class TimelineRows {
    static final class Row {
        final List<Item> items = new ArrayList<Item>();
        final int start;
        int running, failed, completed, stopped;

        Row(int start) {
            this.start = start;
        }

        Item first() {
            return items.get(0);
        }

        boolean group() {
            return first().kind == Kind.TOOL && items.size() > 1;
        }

        void count(Item item, int delta) {
            switch (item.state) {
                case RUNNING:
                case QUEUED:
                case WAITING_FOR_USER:
                    running += delta;
                    break;
                case FAILED:
                    failed += delta;
                    break;
                case COMPLETED:
                    completed += delta;
                    break;
                default:
                    stopped += delta;
            }
        }

        String summary() {
            return completed
                    + " done"
                    + (running > 0 ? " · " + running + " running" : "")
                    + (failed > 0 ? " · " + failed + " failed" : "")
                    + (stopped > 0 ? " · " + stopped + " stopped" : "");
        }
    }

    final List<Row> rows = new ArrayList<Row>();
    private final List<Integer> byItem = new ArrayList<Integer>();
    private Timeline owner;
    private long revision = -1;

    int index(Timeline timeline, String id) {
        int i = timeline.index(id);
        return i < 0 || i >= byItem.size() ? -1 : byItem.get(i);
    }

    void update(Timeline timeline) {
        List<Timeline.Change> changes = owner == timeline ? timeline.changesSince(revision) : null;
        if (changes == null) rebuild(timeline);
        else
            for (Timeline.Change change : changes) {
                int index = change.index;
                if (index >= byItem.size()) {
                    while (byItem.size() < timeline.size()) append(timeline.get(byItem.size()));
                } else {
                    Row row = rows.get(byItem.get(index));
                    Item old = row.items.get(index - row.start), item = timeline.get(index);
                    if (old.kind != item.kind || !Objects.equals(old.turnId, item.turnId)) {
                        rebuild(timeline);
                        break;
                    }
                    row.count(old, -1);
                    row.items.set(index - row.start, item);
                    row.count(item, 1);
                }
            }
        owner = timeline;
        revision = timeline.revision();
    }

    private void rebuild(Timeline timeline) {
        rows.clear();
        byItem.clear();
        for (int i = 0; i < timeline.size(); i++) append(timeline.get(i));
    }

    private void append(Item item) {
        Row row = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        if (row == null
                || item.kind != Kind.TOOL
                || row.first().kind != Kind.TOOL
                || !Objects.equals(row.first().turnId, item.turnId)) {
            row = new Row(byItem.size());
            rows.add(row);
        }
        row.items.add(item);
        row.count(item, 1);
        byItem.add(rows.size() - 1);
    }
}
