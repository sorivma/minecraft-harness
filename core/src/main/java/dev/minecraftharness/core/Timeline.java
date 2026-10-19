package dev.minecraftharness.core;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import java.util.*;

/** Stable item identity; final snapshots replace streamed values without duplication. */
public final class Timeline {
    private final List<Item> items = new ArrayList<Item>();
    private final Map<String, Integer> positions = new HashMap<String, Integer>();
    private final Map<String, Long> changed = new HashMap<String, Long>();
    private long revision;

    public long revision() {
        return revision;
    }

    public int size() {
        return items.size();
    }

    public Item get(int index) {
        return items.get(index);
    }

    public int index(String id) {
        Integer index = positions.get(id);
        return index == null ? -1 : index;
    }

    public void snapshot(Item item) {
        Integer index = positions.get(item.id);
        if (index == null) {
            positions.put(item.id, items.size());
            items.add(item);
        } else items.set(index, item);
        revision++;
        changed.put(item.id, revision);
    }

    public void update(Item item) {
        int index = index(item.id);
        if (index < 0) {
            snapshot(item);
            return;
        }
        Item old = items.get(index);
        Map<String, Value> details = new LinkedHashMap<String, Value>(old.details);
        details.putAll(item.details);
        snapshot(
                new Item(
                        old.id,
                        old.turnId,
                        old.kind,
                        item.state,
                        old.title,
                        item.text.isEmpty() ? old.text : item.text,
                        details,
                        item.resources.isEmpty() ? old.resources : item.resources,
                        item.elapsedMillis == null ? old.elapsedMillis : item.elapsedMillis));
    }

    public void delta(String id, String delta) {
        int index = index(id);
        if (index < 0)
            snapshot(
                    new Item(
                            id,
                            null,
                            Kind.GENERIC,
                            State.RUNNING,
                            "Streaming",
                            delta,
                            Collections.<String, Value>emptyMap(),
                            Collections.<Resource>emptyList(),
                            null));
        else snapshot(items.get(index).withText(items.get(index).text + delta));
    }

    /** Backend history is newest-first. Older pages never overwrite live items. */
    public void prependHistory(List<Item> page) {
        prependHistory(page, revision);
    }

    public void prependHistory(List<Item> page, long beforeRequest) {
        List<Item> older = new ArrayList<Item>();
        Set<String> seen = new HashSet<String>();
        for (int i = page.size() - 1; i >= 0; i--) {
            Item item = page.get(i);
            if (!positions.containsKey(item.id) && seen.add(item.id)) older.add(item);
            else if (positions.containsKey(item.id) && changed.get(item.id) <= beforeRequest)
                snapshot(item);
        }
        if (older.isEmpty()) return;
        items.addAll(0, older);
        positions.clear();
        for (int i = 0; i < items.size(); i++) positions.put(items.get(i).id, i);
        revision++;
        for (Item item : older) changed.put(item.id, revision);
    }
}
