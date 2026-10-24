package dev.minecraftharness.chat;

import dev.minecraftharness.agent.AgentData.Item;
import dev.minecraftharness.agent.Value;
import java.util.*;

/** Display-only JSON formatting. Token offsets refer to the selectable, unadorned text. */
final class JsonText {
    static final int KEY = 0xff8fc9ff,
            STRING = 0xff9cddaa,
            NUMBER = 0xffffd38a,
            LITERAL = 0xffc7a6ff;
    private static final int MAX_DEPTH = 64, MAX_LENGTH = 1024 * 1024;

    static final class Span {
        final int start, end, color;

        Span(int start, int end, int color) {
            this.start = start;
            this.end = end;
            this.color = color;
        }
    }

    final String text;
    private final List<Span> tokens;

    private JsonText(String text, List<Span> tokens) {
        this.text = text;
        this.tokens = tokens;
    }

    static JsonText format(String source) {
        if (source.length() <= MAX_LENGTH) {
            try {
                Parser parser = new Parser(source);
                parser.value(0);
                parser.space();
                if (parser.at != source.length()) throw new IllegalArgumentException();
                return new JsonText(parser.out.toString(), parser.tokens);
            } catch (IllegalArgumentException ignored) {
                // Progress messages and incomplete/invalid JSON remain readable verbatim.
            }
        }
        return new JsonText(source, Collections.<Span>emptyList());
    }

    static JsonText tool(Item item) {
        JsonText body = format(item.text);
        if (item.details.isEmpty()) return body;
        StringBuilder raw = new StringBuilder();
        write(raw, Value.object(item.details));
        JsonText details = format(raw.toString());
        String prefix = body.text.isEmpty() ? "" : body.text + "\n\n";
        prefix += "Details\n";
        List<Span> tokens = new ArrayList<Span>(body.tokens);
        for (Span span : details.tokens)
            tokens.add(
                    new Span(prefix.length() + span.start, prefix.length() + span.end, span.color));
        return new JsonText(prefix + details.text, tokens);
    }

    /** Only visits tokens intersecting the visible range, including a wrapped token's tail. */
    List<Span> spans(int start, int end, int plainColor) {
        List<Span> result = new ArrayList<Span>();
        int low = 0, high = tokens.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (tokens.get(mid).end <= start) low = mid + 1;
            else high = mid;
        }
        int at = start;
        for (int i = low; i < tokens.size() && tokens.get(i).start < end; i++) {
            Span token = tokens.get(i);
            if (at < token.start) result.add(new Span(at, token.start, plainColor));
            int until = Math.min(end, token.end);
            result.add(new Span(Math.max(at, token.start), until, token.color));
            at = until;
        }
        if (at < end) result.add(new Span(at, end, plainColor));
        return result;
    }

    private static void write(StringBuilder out, Value value) {
        switch (value.type) {
            case OBJECT:
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Value> entry : value.object().entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    quote(out, entry.getKey());
                    out.append(':');
                    write(out, entry.getValue());
                }
                out.append('}');
                break;
            case LIST:
                out.append('[');
                for (int i = 0; i < value.array().size(); i++) {
                    if (i > 0) out.append(',');
                    write(out, value.array().get(i));
                }
                out.append(']');
                break;
            case TEXT:
                quote(out, value.text());
                break;
            case RESOURCE:
                quote(out, value.resource().location);
                break;
            case EMPTY:
                out.append("null");
                break;
            default:
                out.append(value.toString());
        }
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 0x20) {
                String hex = Integer.toHexString(ch);
                out.append("\\u");
                for (int j = hex.length(); j < 4; j++) out.append('0');
                out.append(hex);
            } else out.append(ch);
        }
        out.append('"');
    }

    /** Validates the full document while preserving string escapes, duplicate keys and numbers. */
    private static final class Parser {
        final String source;
        final StringBuilder out = new StringBuilder();
        final List<Span> tokens = new ArrayList<Span>();
        int at;

        Parser(String source) {
            this.source = source;
        }

        void space() {
            while (at < source.length() && " \t\r\n".indexOf(source.charAt(at)) >= 0) at++;
        }

        char peek() {
            return at < source.length() ? source.charAt(at) : '\0';
        }

        void value(int depth) {
            if (depth > MAX_DEPTH || out.length() > MAX_LENGTH * 4)
                throw new IllegalArgumentException();
            space();
            char ch = peek();
            if (ch == '{' || ch == '[') container(depth, ch == '{');
            else if (ch == '"') string(STRING);
            else if (ch == 't') literal("true");
            else if (ch == 'f') literal("false");
            else if (ch == 'n') literal("null");
            else number();
        }

        void container(int depth, boolean object) {
            out.append(source.charAt(at++));
            char close = object ? '}' : ']';
            space();
            if (peek() != close) {
                while (true) {
                    newline(depth + 1);
                    if (object) {
                        string(KEY);
                        space();
                        expect(':');
                        out.append(": ");
                    }
                    value(depth + 1);
                    space();
                    if (peek() != ',') break;
                    at++;
                    out.append(',');
                    space();
                }
                newline(depth);
            }
            expect(close);
            out.append(close);
        }

        void newline(int depth) {
            out.append('\n');
            for (int i = 0; i < depth; i++) out.append("  ");
        }

        void expect(char ch) {
            if (peek() != ch) throw new IllegalArgumentException();
            at++;
        }

        void string(int color) {
            int begin = at;
            expect('"');
            while (at < source.length()) {
                char ch = source.charAt(at++);
                if (ch == '"') {
                    token(begin, color);
                    return;
                }
                if (ch < 0x20) throw new IllegalArgumentException();
                if (ch == '\\') {
                    if (at == source.length()) throw new IllegalArgumentException();
                    char escape = source.charAt(at++);
                    if (escape == 'u') {
                        for (int i = 0; i < 4; i++) {
                            if (at == source.length()
                                    || "0123456789abcdefABCDEF".indexOf(source.charAt(at++)) < 0)
                                throw new IllegalArgumentException();
                        }
                    } else if ("\"\\/bfnrt".indexOf(escape) < 0)
                        throw new IllegalArgumentException();
                }
            }
            throw new IllegalArgumentException();
        }

        void literal(String literal) {
            int begin = at;
            if (!source.startsWith(literal, at)) throw new IllegalArgumentException();
            at += literal.length();
            token(begin, LITERAL);
        }

        void number() {
            int begin = at;
            if (peek() == '-') at++;
            if (peek() == '0') at++;
            else digits();
            if (peek() == '.') {
                at++;
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                at++;
                if (peek() == '+' || peek() == '-') at++;
                digits();
            }
            token(begin, NUMBER);
        }

        void digits() {
            int begin = at;
            while (peek() >= '0' && peek() <= '9') at++;
            if (at == begin) throw new IllegalArgumentException();
        }

        void token(int begin, int color) {
            int start = out.length();
            out.append(source, begin, at);
            tokens.add(new Span(start, out.length(), color));
        }
    }
}
