package i8086.asm;

import i8086.CompileError;
import i8086.SourcePos;

import java.util.ArrayList;
import java.util.List;

/**
 * The scanner for the surface language.
 *
 * <p>There is one scanner for the whole surface: the IR and the assembly text an
 * inline block contains are lexed by the same rules, because they are written by
 * the same people in the same style. What differs between them is which words
 * mean what, and that is the parser's business, not this class's.
 *
 * <p>The rules are those of {@code docs/asm.md} §3: names are
 * {@code [A-Za-z_][A-Za-z0-9_]*} with an optional leading dot, numbers are
 * decimal or {@code 0x}-prefixed hexadecimal, strings are {@code "..."} with no
 * escape sequences, comments run from {@code ;} to the end of the line, and a
 * newline is a token of its own.
 */
public final class Tokenizer {

    private final String file;
    private final String source;
    private int index;
    private int line;
    private int lineStart;

    private Tokenizer(String file, String source) {
        this.file = file;
        this.source = source;
        this.line = 1;
        this.lineStart = 0;
    }

    /** Scans the whole source, ending with an {@link TokenKind#EOF} token. */
    public static List<Token> tokenize(String file, String source) {
        return new Tokenizer(file, source).scan();
    }

    private List<Token> scan() {
        List<Token> tokens = new ArrayList<Token>();
        while (true) {
            skipBlanksAndComments();
            if (atEnd()) {
                break;
            }
            char c = peek();
            if (c == '\n') {
                tokens.add(newline(1));
            } else if (c == '\r') {
                if (index + 1 >= source.length() || source.charAt(index + 1) != '\n') {
                    throw new CompileError(here(),
                            "a carriage return must be followed by a newline");
                }
                tokens.add(newline(2));
            } else if (isNameStart(c) || c == '.' || c == '$') {
                tokens.add(scanName());
            } else if (isDigit(c)) {
                tokens.add(scanNumber());
            } else if (c == '"') {
                tokens.add(scanString());
            } else if (isPunctuation(c)) {
                tokens.add(scanPunctuation());
            } else {
                throw error("unexpected character '" + c + "'");
            }
        }
        tokens.add(token(TokenKind.EOF, "", 0, here()));
        return tokens;
    }

    /**
     * Consumes a line terminator of the given length and returns its token. A
     * line end is LF or CRLF and nothing else: a lone carriage return is refused
     * rather than treated as blank, because treating it as blank would silently
     * join the lines it separates into one. The token's position is the first
     * character of the terminator, so a column never depends on which of the two
     * line endings the file uses.
     */
    private Token newline(int length) {
        SourcePos position = here();
        index += length;
        line++;
        lineStart = index;
        return token(TokenKind.NEWLINE, "\n", 0, position);
    }

    private Token scanName() {
        SourcePos start = here();
        // The marker is not part of the name: '$ax' is the author saying 'this is my
        // ax, not whatever word ax is' (docs/ir.md §3.1), and the token keeps the
        // fact rather than the character so that every reader of a name — there are
        // nine of them — does not have to strip it.
        boolean forced = false;
        if (peek() == '$') {
            index++;
            forced = true;
        }
        int from = index;
        if (peek() == '.') {
            index++;
            if (!atEnd() && peek() == '.') {
                // A name the compiler generated: '..@lbl0' (docs/ir.md §7.2). One dot
                // is the author's, two are ours, and a two-dot name without the '@'
                // is refused rather than accepted as an ordinary name, because in an
                // assembler that reads this dialect text a ragged name like '..lbl0'
                // means something else again.
                index++;
                if (atEnd() || peek() != '@') {
                    throw new CompileError(start,
                            "a name beginning with '..' is one the compiler generated, as in "
                                    + "'..@lbl0', and it is not something to write");
                }
                index++;
            }
            if (atEnd() || !isNameStart(peek())) {
                throw new CompileError(start, "a dot must begin a word, as in '.if'");
            }
        } else if (atEnd() || !isNameStart(peek())) {
            throw new CompileError(start, forced
                    ? "a '$' marks a name as the author's, so a name has to follow it"
                    : "expected a name");
        }
        while (!atEnd() && isNamePart(peek())) {
            index++;
        }
        return token(TokenKind.IDENT, source.substring(from, index), 0, start, forced);
    }

    private Token scanNumber() {
        SourcePos start = here();
        int from = index;
        int base = 10;
        if (peek() == '0' && index + 1 < source.length()
                && (source.charAt(index + 1) == 'x' || source.charAt(index + 1) == 'X')) {
            base = 16;
            index += 2;
            if (atEnd() || !isDigitInBase(peek(), base)) {
                throw new CompileError(start, "expected hexadecimal digits after '0x'");
            }
        }
        while (!atEnd() && isDigitInBase(peek(), base)) {
            index++;
        }
        String spelling = source.substring(from, index);
        String digits = base == 16 ? spelling.substring(2) : spelling;
        long value;
        try {
            value = Long.parseLong(digits, base);
        } catch (NumberFormatException tooLarge) {
            throw new CompileError(start, "number '" + spelling + "' is too large");
        }
        if (!atEnd() && isNamePart(peek())) {
            throw new CompileError(start,
                    "number '" + spelling + "' is followed by '" + peek() + "'");
        }
        return token(TokenKind.NUMBER, spelling, value, start);
    }

    private Token scanString() {
        SourcePos start = here();
        index++;
        StringBuilder content = new StringBuilder();
        while (true) {
            if (atEnd() || peek() == '\n') {
                throw new CompileError(start, "string is not closed on this line");
            }
            char c = peek();
            if (c == '"') {
                index++;
                return token(TokenKind.STRING, content.toString(), 0, start);
            }
            if (c > 0x7E || c < 0x20) {
                throw error("a string may only contain printable ASCII");
            }
            content.append(c);
            index++;
        }
    }

    private Token scanPunctuation() {
        SourcePos start = here();
        if (index + 1 < source.length()
                && isTwoCharacterPunctuation(source.substring(index, index + 2))) {
            String two = source.substring(index, index + 2);
            index += 2;
            return token(TokenKind.PUNCT, two, 0, start);
        }
        String one = String.valueOf(peek());
        index++;
        return token(TokenKind.PUNCT, one, 0, start);
    }

    // An explicit comparison per pair, rather than a search in a packed string:
    // a window taken from the source can span the separators of such a string
    // and match by accident, and every string contains the empty one.
    private static boolean isTwoCharacterPunctuation(String spelling) {
        return spelling.equals("<=")
                || spelling.equals(">=")
                || spelling.equals("==")
                || spelling.equals("!=");
    }

    private void skipBlanksAndComments() {
        while (!atEnd()) {
            char c = peek();
            if (c == ' ' || c == '\t') {
                index++;
            } else if (c == ';') {
                while (!atEnd() && peek() != '\n') {
                    index++;
                }
            } else {
                return;
            }
        }
    }

    private Token token(TokenKind kind, String text, long value, SourcePos position) {
        return new Token(kind, text, value, position);
    }

    private Token token(TokenKind kind, String text, long value, SourcePos position,
                        boolean forced) {
        return new Token(kind, text, value, position, forced);
    }

    private SourcePos here() {
        return new SourcePos(file, line, index - lineStart + 1);
    }

    private CompileError error(String message) {
        return new CompileError(here(), message);
    }

    private boolean atEnd() {
        return index >= source.length();
    }

    private char peek() {
        return source.charAt(index);
    }

    // An explicit switch, not a packed string searched with indexOf: a window
    // into that string can match across its separators, the empty window always
    // matches, and a duplicated element would be invisible. A duplicated case
    // here is a compile error. A dot is deliberately absent: it may only open a
    // word, as in '.if', and anything else with a dot in it is refused below.
    private static boolean isPunctuation(char c) {
        switch (c) {
            case '(':
            case ')':
            case '[':
            case ']':
            case '{':
            case '}':
            case ',':
            case ':':
            case '=':
            case '+':
            case '-':
            case '*':
            case '/':
            case '%':
            case '&':
            case '|':
            case '^':
            case '~':
            case '<':
            case '>':
            case '!':
                return true;
            default:
                return false;
        }
    }

    private static boolean isNameStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isNamePart(char c) {
        return isNameStart(c) || isDigit(c);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isDigitInBase(char c, int base) {
        if (base == 16) {
            return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }
        return isDigit(c);
    }
}
