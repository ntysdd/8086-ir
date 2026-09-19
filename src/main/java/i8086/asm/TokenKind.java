package i8086.asm;

/**
 * What a {@link Token} is.
 *
 * <p>The set is deliberately small. Keywords, mnemonics and register names are
 * all {@link #IDENT}: which of them a word is depends on where it appears, and
 * deciding that is the parser's job. Punctuation is likewise a single kind
 * carrying its own spelling, which keeps the token set from growing a constant
 * per operator.
 */
public enum TokenKind {

    /** A word: a keyword, a mnemonic, a register name, a label, a variable. */
    IDENT,

    /** A numeric literal; see {@link Token#value()}. */
    NUMBER,

    /** A string literal; {@link Token#text()} is its contents, without quotes. */
    STRING,

    /** Punctuation; {@link Token#text()} is the exact spelling. */
    PUNCT,

    /** End of a line. The surface language is line-oriented, so this matters. */
    NEWLINE,

    /** End of input. Always the last token. */
    EOF
}
