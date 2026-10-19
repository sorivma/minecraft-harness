package dev.minecraftharness.agent;

import dev.minecraftharness.agent.AgentData.*;
import java.util.*;

/** Neutral form descriptor. Invalid replies never reach an adapter's transport. */
public final class Form {
    public enum Type {
        TEXT,
        SECRET,
        NUMBER,
        INTEGER,
        BOOLEAN,
        CHOICE,
        MULTICHOICE,
        RESOURCE,
        GROUP
    }

    public static final class Field {
        public final String id, label, description;
        public final Type type;
        public final boolean required;
        public final List<String> choices;
        public final Double minimum, maximum;
        public final Integer minLength, maxLength;
        public final Form children;

        public Field(
                String id,
                String label,
                String description,
                Type type,
                boolean required,
                Collection<String> choices,
                Double minimum,
                Double maximum,
                Integer minLength,
                Integer maxLength,
                Form children) {
            this.id = AgentData.required(id, "field id");
            this.label = label;
            this.description = description;
            this.type = Objects.requireNonNull(type);
            this.required = required;
            this.choices = AgentData.list(choices);
            this.minimum = minimum;
            this.maximum = maximum;
            this.minLength = minLength;
            this.maxLength = maxLength;
            this.children = children;
            if (type == Type.GROUP && children == null)
                throw new IllegalArgumentException("Group needs child fields");
            if (type == Type.CHOICE && choices.isEmpty())
                throw new IllegalArgumentException("Choice needs options");
        }

        public static Field text(String id, String label, boolean required) {
            return new Field(
                    id,
                    label,
                    "",
                    Type.TEXT,
                    required,
                    Collections.<String>emptyList(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static Field choice(String id, String label, String... choices) {
            return new Field(
                    id,
                    label,
                    "",
                    Type.CHOICE,
                    true,
                    Arrays.asList(choices),
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    public final List<Field> fields;

    public Form(Collection<Field> fields) {
        this.fields = AgentData.list(fields);
        Set<String> ids = new HashSet<String>();
        for (Field f : fields)
            if (!ids.add(f.id)) throw new IllegalArgumentException("Duplicate field: " + f.id);
    }

    public static Form empty() {
        return new Form(Collections.<Field>emptyList());
    }

    public Map<String, String> validate(Map<String, Value> input) {
        Map<String, String> errors = new LinkedHashMap<String, String>();
        Set<String> known = new HashSet<String>();
        for (Field f : fields) {
            known.add(f.id);
            Value v = input.get(f.id);
            if (v == null || v.type == Value.Type.EMPTY) {
                if (f.required) errors.put(f.id, "Required");
                continue;
            }
            Value.Type expected =
                    f.type == Type.CHOICE || f.type == Type.SECRET
                            ? Value.Type.TEXT
                            : f.type == Type.INTEGER
                                    ? Value.Type.NUMBER
                                    : f.type == Type.MULTICHOICE
                                            ? Value.Type.LIST
                                            : f.type == Type.GROUP
                                                    ? Value.Type.OBJECT
                                                    : Value.Type.valueOf(f.type.name());
            if (v.type != expected) {
                errors.put(f.id, "Expected " + f.type);
                continue;
            }
            if (v.type == Value.Type.TEXT) {
                if (f.required && v.text().trim().isEmpty()) errors.put(f.id, "Required");
                if (f.minLength != null && v.text().length() < f.minLength)
                    errors.put(f.id, "Too short");
                if (f.maxLength != null && v.text().length() > f.maxLength)
                    errors.put(f.id, "Too long");
                if (f.type == Type.CHOICE && !f.choices.contains(v.text()))
                    errors.put(f.id, "Choose a listed option");
            }
            if (v.type == Value.Type.NUMBER
                    && ((f.minimum != null && v.number() < f.minimum)
                            || (f.maximum != null && v.number() > f.maximum)))
                errors.put(f.id, "Outside allowed range");
            if (v.type == Value.Type.NUMBER
                    && (!Double.isFinite(v.number())
                            || f.type == Type.INTEGER && v.number() != Math.rint(v.number())))
                errors.put(f.id, "Enter a valid integer/number");
            if (f.type == Type.MULTICHOICE) {
                Set<String> selected = new HashSet<String>();
                for (Value entry : v.array())
                    if (entry.type != Value.Type.TEXT
                            || !f.choices.contains(entry.text())
                            || !selected.add(entry.text()))
                        errors.put(f.id, "Choose distinct listed options");
                if (f.minLength != null && selected.size() < f.minLength
                        || f.maxLength != null && selected.size() > f.maxLength)
                    errors.put(f.id, "Wrong number of selected options");
            }
            if (f.type == Type.GROUP)
                for (Map.Entry<String, String> e : f.children.validate(v.object()).entrySet())
                    errors.put(f.id + "." + e.getKey(), e.getValue());
        }
        for (String id : input.keySet()) if (!known.contains(id)) errors.put(id, "Unknown field");
        return Collections.unmodifiableMap(errors);
    }
}
