package dev.minecraftharness.chat;

import dev.minecraftharness.agent.*;
import dev.minecraftharness.agent.AgentData.*;
import dev.minecraftharness.core.WorkspaceController.Pending;
import java.util.*;

/** Answers stay with the pending request, independently of the ordinary chat draft. */
final class InlineQuestion {
    final Pending pending;
    final Map<String, Editor> editors = new LinkedHashMap<String, Editor>();
    final Map<String, Value> values = new LinkedHashMap<String, Value>();
    int index, offset, maximum, top, bottom;
    int focusOption = -1;
    boolean collapsed, custom;
    String error = "";

    InlineQuestion(Pending pending) {
        this.pending = pending;
    }

    static boolean supports(Pending pending) {
        for (Form.Field field : pending.request.form.fields)
            if (field.type == Form.Type.GROUP || field.type == Form.Type.RESOURCE) return false;
        return !pending.request.form.fields.isEmpty();
    }

    Form.Field field() {
        return pending.request.form.fields.get(index);
    }

    Editor editor(Form.Field field) {
        return editors.computeIfAbsent(field.id, k -> new Editor("", 0));
    }

    void select(String option) {
        if (pending.status != Pending.Status.OPEN) return;
        Form.Field field = field();
        if (field.type == Form.Type.MULTICHOICE) {
            List<Value> chosen = new ArrayList<Value>();
            Value old = values.get(field.id);
            if (old != null) chosen.addAll(old.array());
            Value next = Value.text(option);
            if (!chosen.remove(next)) chosen.add(next);
            values.put(field.id, Value.array(chosen));
        } else if (field.type == Form.Type.BOOLEAN)
            values.put(field.id, Value.bool(option.equals("Yes")));
        else if (field.type == Form.Type.CHOICE) values.put(field.id, Value.text(option));
        else {
            Editor editor = editor(field);
            editor.selectAll();
            editor.insert(option);
        }
        error = "";
        custom = false;
    }

    boolean selected(String option) {
        Form.Field field = field();
        Value value = values.get(field.id);
        if (field.type == Form.Type.MULTICHOICE)
            return value != null && value.array().contains(Value.text(option));
        if (field.type == Form.Type.BOOLEAN)
            return value != null && value.bool() == option.equals("Yes");
        if (field.type == Form.Type.CHOICE) return value != null && value.text().equals(option);
        return editor(field).text.equals(option);
    }

    boolean collect() {
        error = "";
        for (Form.Field field : pending.request.form.fields) {
            Editor editor = editors.get(field.id);
            if (editor == null) continue;
            String input = editor.text;
            if (input.trim().isEmpty()) {
                values.remove(field.id);
                continue;
            }
            try {
                values.put(
                        field.id,
                        field.type == Form.Type.NUMBER || field.type == Form.Type.INTEGER
                                ? Value.number(Double.parseDouble(input))
                                : Value.text(input));
            } catch (IllegalArgumentException e) {
                error = "Enter a valid number";
                index = pending.request.form.fields.indexOf(field);
                return false;
            }
        }
        Map<String, String> errors = pending.request.form.validate(values);
        if (!errors.isEmpty())
            for (int i = 0; i < pending.request.form.fields.size(); i++) {
                String id = pending.request.form.fields.get(i).id;
                if (errors.containsKey(id)) {
                    index = i;
                    offset = 0;
                    error = errors.get(id);
                    return false;
                }
            }
        return errors.isEmpty();
    }
}
