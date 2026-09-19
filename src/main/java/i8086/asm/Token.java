package i8086.asm;

import i8086.SourcePos;

import java.util.Locale;

/**
 * One token, with the position it started at.
 *
 * <p>{@link #text()} is the spelling exactly as written, which is what error
 * messages should quote. Names are matched case-insensitively, and
 * {@link #name()} is the lower-cased form to match on and to store; the emitter
 * writes lowercase, so the output never depends on how the input was typed.
 */
public final class Token {

    private final TokenKind kind;
    private final String text;
    private final long value;
    private final SourcePos position;
    private final boolean forced;

    Token(TokenKind kind, String text, long value, SourcePos position) {
        this(kind, text, value, position, false);
    }

    Token(TokenKind kind, String text, long value, SourcePos position, boolean forced) {
        this.kind = kind;
        this.text = text;
        this.value = value;
        this.position = position;
        this.forced = forced;
    }

    public TokenKind kind() {
        return kind;
    }

    /**
     * The spelling as written, without the {@code $} that marked it as a name. For a
     * string literal, its contents without quotes.
     *
     * <p>The marker is not part of the name — {@code $ax} names the same thing
     * {@code ax} does — which is what makes {@code $} an escape from the surface's
     * own words rather than a second namespace. {@link #written()} is what to quote
     * in a diagnostic.
     */
    public String text() {
        return text;
    }

    /**
     * Whether the author wrote {@code $} in front of the name to say it is theirs
     * rather than one of the surface's words ({@code docs/ir.md} §3.1).
     */
    public boolean forced() {
        return forced;
    }

    /** The spelling as it appears in the source, marker and all. */
    public String written() {
        return forced ? "$" + text : text;
    }

    /** The numeric value. Only meaningful for {@link TokenKind#NUMBER}. */
    public long value() {
        return value;
    }

    public SourcePos position() {
        return position;
    }

    /** The lower-cased spelling, for comparing names case-insensitively. */
    public String name() {
        return text.toLowerCase(Locale.ROOT);
    }

    public boolean is(TokenKind other) {
        return kind == other;
    }

    /** True for the given punctuation, for example {@code is("(")}. */
    public boolean is(String punctuation) {
        return kind == TokenKind.PUNCT && text.equals(punctuation);
    }

    /** True for the given name, matched case-insensitively. */
    public boolean isName(String name) {
        return kind == TokenKind.IDENT && name().equals(name);
    }

    public boolean isEof() {
        return kind == TokenKind.EOF;
    }

    /**
     * How this token is named in an error message: {@code "("}, {@code mnemonic
     * 'mov'}, {@code number '0x10'}, {@code end of line}.
     */
    public String describe() {
        switch (kind) {
            case IDENT:
                return "word '" + written() + "'";
            case NUMBER:
                return "number '" + text + "'";
            case STRING:
                return "string";
            case PUNCT:
                return "'" + text + "'";
            case NEWLINE:
                return "end of line";
            default:
                return "end of input";
        }
    }

    @Override
    public String toString() {
        return describe() + " at " + position;
    }
}
