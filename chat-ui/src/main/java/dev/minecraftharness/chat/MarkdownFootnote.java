package dev.minecraftharness.chat;

import java.util.*;
import java.util.regex.*;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.commonmark.parser.block.*;

/** Footnotes for the Java 8 parser, which predates the upstream footnote extension. */
final class MarkdownFootnote extends CustomBlock {
    final String label;

    MarkdownFootnote(String label) {
        this.label = label;
    }

    static final class Factory extends AbstractBlockParserFactory {
        private static final Pattern DEFINITION = Pattern.compile("^\\[\\^([^\\]\\s]+)\\]:[ \\t]*");

        public BlockStart tryStart(ParserState state, MatchedBlockParser matched) {
            if (state.getIndent() >= 4) return BlockStart.none();
            int index = state.getNextNonSpaceIndex();
            Matcher match =
                    DEFINITION.matcher(
                            state.getLine()
                                    .getContent()
                                    .subSequence(index, state.getLine().getContent().length()));
            if (!match.find()) return BlockStart.none();
            return BlockStart.of(new DefinitionParser(match.group(1))).atIndex(index + match.end());
        }
    }

    private static final class DefinitionParser extends AbstractBlockParser {
        private final MarkdownFootnote block;
        private final List<SourceLine> lines = new ArrayList<SourceLine>();

        DefinitionParser(String label) {
            block = new MarkdownFootnote(label);
        }

        public Block getBlock() {
            return block;
        }

        public BlockContinue tryContinue(ParserState state) {
            if (state.isBlank())
                return BlockContinue.atIndex(state.getLine().getContent().length());
            return state.getIndent() >= 4
                    ? BlockContinue.atColumn(state.getColumn() + 4)
                    : BlockContinue.none();
        }

        public void addLine(SourceLine line) {
            lines.add(line);
        }

        public void parseInlines(InlineParser parser) {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).getContent().length() == 0)
                lines.remove(lines.size() - 1);
            Paragraph paragraph = new Paragraph();
            block.appendChild(paragraph);
            StrongEmphasis label = new StrongEmphasis("**");
            label.appendChild(new Text("[" + block.label + "] "));
            paragraph.appendChild(label);
            parser.parse(SourceLines.of(lines), paragraph);
        }
    }
}
