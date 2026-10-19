package dev.minecraftharness.agent;

import java.util.*;

/** Closed value algebra for extension parameters, independent of JSON libraries. */
public final class Value {
    public enum Type {
        TEXT,
        NUMBER,
        BOOLEAN,
        RESOURCE,
        OBJECT,
        LIST,
        EMPTY
    }

    public final Type type;
    private final Object value;

    private Value(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    public static Value text(String v) {
        return new Value(Type.TEXT, Objects.requireNonNull(v));
    }

    public static Value number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v))
            throw new IllegalArgumentException("Number must be finite");
        return new Value(Type.NUMBER, v);
    }

    public static Value bool(boolean v) {
        return new Value(Type.BOOLEAN, v);
    }

    public static Value resource(AgentData.Resource v) {
        return new Value(Type.RESOURCE, Objects.requireNonNull(v));
    }

    public static Value object(Map<String, Value> v) {
        return new Value(Type.OBJECT, AgentData.map(v));
    }

    public static Value array(Collection<Value> v) {
        return new Value(Type.LIST, AgentData.list(v));
    }

    public static Value empty() {
        return new Value(Type.EMPTY, null);
    }

    private void require(Type expected) {
        if (type != expected)
            throw new IllegalStateException("Expected " + expected + ", got " + type);
    }

    public String text() {
        require(Type.TEXT);
        return (String) value;
    }

    public double number() {
        require(Type.NUMBER);
        return (Double) value;
    }

    public boolean bool() {
        require(Type.BOOLEAN);
        return (Boolean) value;
    }

    public AgentData.Resource resource() {
        require(Type.RESOURCE);
        return (AgentData.Resource) value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Value> object() {
        require(Type.OBJECT);
        return (Map<String, Value>) value;
    }

    @SuppressWarnings("unchecked")
    public List<Value> array() {
        require(Type.LIST);
        return (List<Value>) value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Value
                && type == ((Value) o).type
                && Objects.equals(value, ((Value) o).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value);
    }

    @Override
    public String toString() {
        return value == null ? "" : value.toString();
    }
}
