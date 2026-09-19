package i8086.asm;

import i8086.testing.Assert;
import i8086.testing.Suite;

import java.util.List;

/**
 * Tests for the scanner. Every rule here is one the IR and the assembly text
 * both depend on, and each error case checks the position as well as the
 * message, because an error without a position is not a finished error.
 */
public final class TokenizerTest {

    private TokenizerTest() {
    }

    public static void register(Suite suite) {
        suite.add("Tokenizer ends with EOF", TokenizerTest::endsWithEof);
        suite.add("Tokenizer reads a plain statement", TokenizerTest::readsPlainStatement);
        suite.add("Tokenizer keeps newlines as tokens", TokenizerTest::keepsNewlines);
        suite.add("Tokenizer drops comments", TokenizerTest::dropsComments);
        suite.add("Tokenizer reads decimal and hexadecimal numbers", TokenizerTest::readsNumbers);
        suite.add("Tokenizer reads names case-insensitively", TokenizerTest::readsNamesCaseInsensitively);
        suite.add("Tokenizer reads dotted words", TokenizerTest::readsDottedWords);
        suite.add("Tokenizer reads two-character punctuation", TokenizerTest::readsTwoCharacterPunctuation);
        suite.add("Tokenizer reads every punctuation character", TokenizerTest::readsEveryPunctuation);
        suite.add("Tokenizer reads strings without their quotes", TokenizerTest::readsStrings);
        suite.add("Tokenizer reads an empty string", TokenizerTest::readsEmptyString);
        suite.add("Tokenizer keeps a semicolon inside a string", TokenizerTest::keepsSemicolonInsideString);
        suite.add("Tokenizer ignores a quote inside a comment", TokenizerTest::ignoresQuoteInsideComment);
        suite.add("Tokenizer reads a memory operand", TokenizerTest::readsMemoryOperand);
        suite.add("Tokenizer reads names with digits and underscores", TokenizerTest::readsNamesWithDigits);
        suite.add("Tokenizer tracks line and column", TokenizerTest::tracksLineAndColumn);
        suite.add("Tokenizer reads CRLF lines", TokenizerTest::readsCrlfLines);
        suite.add("Tokenizer refuses a lone carriage return",
                TokenizerTest::refusesLoneCarriageReturn);
        suite.add("Tokenizer reads blank lines", TokenizerTest::readsBlankLines);
        suite.add("Tokenizer skips whitespace-only input", TokenizerTest::skipsWhitespaceOnlyInput);
        suite.add("Tokenizer reads input that is only a comment", TokenizerTest::endsWithCommentOnly);
        suite.add("Tokenizer reads punctuation at the end of input", TokenizerTest::readsPunctuationAtEnd);
        suite.add("Tokenizer does not mistake '=' for a two-character token",
                TokenizerTest::doesNotMistakeEqualsForTwoCharacters);
        suite.add("Tokenizer describes tokens for messages", TokenizerTest::describesTokens);
        suite.add("Tokenizer refuses an unclosed string", TokenizerTest::refusesUnclosedString);
        suite.add("Tokenizer refuses a non-ASCII string", TokenizerTest::refusesNonAsciiString);
        suite.add("Tokenizer refuses a dangling dot", TokenizerTest::refusesDanglingDot);
        suite.add("Tokenizer refuses a dot before a digit", TokenizerTest::refusesDotBeforeDigit);
        suite.add("Tokenizer refuses an empty hexadecimal number", TokenizerTest::refusesEmptyHex);
        suite.add("Tokenizer refuses hexadecimal without hex digits", TokenizerTest::refusesHexWithoutDigits);
        suite.add("Tokenizer refuses a number glued to a name", TokenizerTest::refusesGluedNumber);
        suite.add("Tokenizer refuses an unexpected character", TokenizerTest::refusesUnexpectedCharacter);
        suite.add("Tokenizer refuses an oversized number", TokenizerTest::refusesOversizedNumber);
    }

    private static List<Token> scan(String source) {
        return Tokenizer.tokenize("test.ir", source);
    }

    private static void endsWithEof() {
        List<Token> tokens = scan("");
        Assert.assertEquals(1L, tokens.size());
        Assert.assertTrue(tokens.get(0).isEof(), "the only token is EOF");
    }

    private static void readsPlainStatement() {
        List<Token> tokens = scan("s = eval(a + b)");
        Assert.assertEquals(9L, tokens.size());
        Assert.assertTrue(tokens.get(0).isName("s"), "an identifier");
        Assert.assertTrue(tokens.get(1).is("="), "'='");
        Assert.assertTrue(tokens.get(2).isName("eval"), "'eval'");
        Assert.assertTrue(tokens.get(3).is("("), "'('");
        Assert.assertTrue(tokens.get(4).isName("a"), "'a'");
        Assert.assertTrue(tokens.get(5).is("+"), "'+'");
        Assert.assertTrue(tokens.get(6).isName("b"), "'b'");
        Assert.assertTrue(tokens.get(7).is(")"), "')'");
        Assert.assertTrue(tokens.get(8).isEof(), "EOF");
    }

    private static void keepsNewlines() {
        List<Token> tokens = scan("a\nb\n");
        Assert.assertEquals(5L, tokens.size());
        Assert.assertTrue(tokens.get(1).is(TokenKind.NEWLINE), "second token is a newline");
        Assert.assertTrue(tokens.get(3).is(TokenKind.NEWLINE), "fourth token is a newline");
        Assert.assertTrue(tokens.get(4).isEof(), "EOF follows the last newline");
    }

    private static void dropsComments() {
        List<Token> tokens = scan("mov ax, 1 ; set it\n; a whole line\nret");
        Assert.assertEquals(8L, tokens.size());
        Assert.assertTrue(tokens.get(4).is(TokenKind.NEWLINE), "the comment ends at the newline");
        Assert.assertTrue(tokens.get(5).is(TokenKind.NEWLINE), "a comment-only line is still a line");
        Assert.assertTrue(tokens.get(6).isName("ret"), "'ret' follows");
    }

    private static void readsNumbers() {
        List<Token> tokens = scan("26 0x1A 0x1a 0 0Xff 0x0");
        Assert.assertEquals(7L, tokens.size());
        Assert.assertEquals(26L, tokens.get(0).value());
        Assert.assertEquals(26L, tokens.get(1).value());
        Assert.assertEquals(26L, tokens.get(2).value());
        Assert.assertEquals(0L, tokens.get(3).value());
        Assert.assertEquals(255L, tokens.get(4).value());
        Assert.assertEquals(0L, tokens.get(5).value());
    }

    private static void readsNamesCaseInsensitively() {
        Assert.assertEquals("mov", scan("MOV").get(0).name());
        Assert.assertEquals("MOV", scan("MOV").get(0).text());
        Assert.assertTrue(scan("MoV").get(0).isName("mov"), "the spelling does not matter");
    }

    private static void readsDottedWords() {
        List<Token> tokens = scan(".if .endif");
        Assert.assertTrue(tokens.get(0).isName(".if"), "'.if' is one word");
        Assert.assertTrue(tokens.get(1).isName(".endif"), "'.endif' is one word");
    }

    private static void readsTwoCharacterPunctuation() {
        List<Token> tokens = scan("< <= > >= == != !");
        Assert.assertEquals(8L, tokens.size());
        Assert.assertTrue(tokens.get(0).is("<"), "'<'");
        Assert.assertTrue(tokens.get(1).is("<="), "'<=' is one token");
        Assert.assertTrue(tokens.get(2).is(">"), "'>'");
        Assert.assertTrue(tokens.get(3).is(">="), "'>=' is one token");
        Assert.assertTrue(tokens.get(4).is("=="), "'==' is one token");
        Assert.assertTrue(tokens.get(5).is("!="), "'!=' is one token");
        Assert.assertTrue(tokens.get(6).is("!"), "'!' alone");
    }

    private static void readsEveryPunctuation() {
        String input = "()[]{},:=+-*/%&|^~<>!";
        List<Token> tokens = scan(input);
        Assert.assertEquals(22L, tokens.size());
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            Assert.assertTrue(tokens.get(i).is(TokenKind.PUNCT), "token " + i + " is punctuation");
            joined.append(tokens.get(i).text());
        }
        Assert.assertEquals(input, joined.toString());
    }

    private static void readsStrings() {
        List<Token> tokens = scan("db \"Hello, world!$\"");
        Assert.assertEquals(3L, tokens.size());
        Assert.assertTrue(tokens.get(1).is(TokenKind.STRING), "a string");
        Assert.assertEquals("Hello, world!$", tokens.get(1).text());
        Assert.assertEquals(4L, tokens.get(1).position().column());
    }

    private static void readsEmptyString() {
        List<Token> tokens = scan("db \"\"");
        Assert.assertEquals(3L, tokens.size());
        Assert.assertTrue(tokens.get(1).is(TokenKind.STRING), "an empty string is a string");
        Assert.assertEquals("", tokens.get(1).text());
    }

    private static void keepsSemicolonInsideString() {
        List<Token> tokens = scan("db \"a;b\"");
        Assert.assertEquals("a;b", tokens.get(1).text());
    }

    private static void ignoresQuoteInsideComment() {
        List<Token> tokens = scan("; \"unclosed\nret");
        Assert.assertEquals(3L, tokens.size());
        Assert.assertTrue(tokens.get(0).is(TokenKind.NEWLINE), "the comment ends at the newline");
        Assert.assertTrue(tokens.get(1).isName("ret"), "the quote was not a string");
    }

    private static void readsMemoryOperand() {
        List<Token> tokens = scan("mov ax, [bx+si+0x16]");
        Assert.assertEquals(11L, tokens.size());
        Assert.assertTrue(tokens.get(3).is("["), "'['");
        Assert.assertTrue(tokens.get(4).isName("bx"), "'bx'");
        Assert.assertTrue(tokens.get(5).is("+"), "'+'");
        Assert.assertTrue(tokens.get(6).isName("si"), "'si'");
        Assert.assertEquals(22L, tokens.get(8).value());
        Assert.assertTrue(tokens.get(9).is("]"), "']'");
        Assert.assertTrue(tokens.get(10).isEof(), "EOF");
    }

    private static void readsNamesWithDigits() {
        List<Token> tokens = scan("_a1 bx2 .endif");
        Assert.assertEquals(4L, tokens.size());
        Assert.assertTrue(tokens.get(0).isName("_a1"), "'_a1'");
        Assert.assertTrue(tokens.get(1).isName("bx2"), "'bx2'");
        Assert.assertTrue(tokens.get(2).isName(".endif"), "'.endif'");
    }

    private static void tracksLineAndColumn() {
        List<Token> tokens = scan("mov ax\n  add bx, 1");
        Assert.assertEquals("test.ir:1:1", tokens.get(0).position().toString());
        Assert.assertEquals("test.ir:1:5", tokens.get(1).position().toString());
        Assert.assertEquals("test.ir:1:7", tokens.get(2).position().toString());
        Assert.assertEquals("test.ir:2:3", tokens.get(3).position().toString());
        Assert.assertEquals("test.ir:2:7", tokens.get(4).position().toString());
        Assert.assertEquals("test.ir:2:9", tokens.get(5).position().toString());
        Assert.assertEquals("test.ir:2:11", tokens.get(6).position().toString());
    }

    private static void readsCrlfLines() {
        List<Token> tokens = scan("a\r\nb");
        Assert.assertEquals(4L, tokens.size());
        Assert.assertTrue(tokens.get(0).isName("a"), "'a'");
        Assert.assertEquals("test.ir:1:2", tokens.get(1).position().toString());
        Assert.assertEquals("test.ir:2:1", tokens.get(2).position().toString());
    }

    private static void refusesLoneCarriageReturn() {
        Assert.assertRefused("test.ir:1:2", () -> scan("a\rb"));
    }

    private static void readsBlankLines() {
        List<Token> tokens = scan("\n\n");
        Assert.assertEquals(3L, tokens.size());
        Assert.assertTrue(tokens.get(0).is(TokenKind.NEWLINE), "first blank line");
        Assert.assertTrue(tokens.get(1).is(TokenKind.NEWLINE), "second blank line");
        Assert.assertEquals("test.ir:3:1", tokens.get(2).position().toString());
    }

    private static void skipsWhitespaceOnlyInput() {
        List<Token> tokens = scan("  \t ");
        Assert.assertEquals(1L, tokens.size());
        Assert.assertEquals("test.ir:1:5", tokens.get(0).position().toString());
    }

    private static void endsWithCommentOnly() {
        List<Token> tokens = scan("; nothing else, no newline");
        Assert.assertEquals(1L, tokens.size());
        Assert.assertTrue(tokens.get(0).isEof(), "a trailing comment does not need a newline");
    }

    // Regression: a window taken from the input used to be searched inside a
    // packed list of two-character punctuation, and an empty window matches
    // every string, so a final ')' swallowed the end of the input.
    private static void readsPunctuationAtEnd() {
        List<Token> tokens = scan("a)");
        Assert.assertEquals(3L, tokens.size());
        Assert.assertEquals(")", tokens.get(1).text());
        Assert.assertTrue(tokens.get(2).isEof(), "EOF still follows");
    }

    // Regression: '=' followed by a space used to match the "= " inside "<= ".
    private static void doesNotMistakeEqualsForTwoCharacters() {
        List<Token> tokens = scan("a = b");
        Assert.assertEquals(4L, tokens.size());
        Assert.assertEquals("=", tokens.get(1).text());
        Assert.assertTrue(tokens.get(1).is("="), "exactly one '=' token");
    }

    private static void describesTokens() {
        List<Token> tokens = scan("mov 26 \"x\" (\n");
        Assert.assertEquals("word 'mov'", tokens.get(0).describe());
        Assert.assertEquals("number '26'", tokens.get(1).describe());
        Assert.assertEquals("string", tokens.get(2).describe());
        Assert.assertEquals("'('", tokens.get(3).describe());
        Assert.assertEquals("end of line", tokens.get(4).describe());
        Assert.assertEquals("end of input", tokens.get(5).describe());
    }

    private static void refusesUnclosedString() {
        Assert.assertRefused("test.ir:1:4", () -> scan("db \"oops\nret"));
    }

    private static void refusesNonAsciiString() {
        Assert.assertRefused("test.ir:1:5", () -> scan("db \"\u00e9\""));
    }

    private static void refusesDanglingDot() {
        Assert.assertRefused("test.ir:2:5", () -> scan("mov ax\na = ."));
    }

    private static void refusesDotBeforeDigit() {
        Assert.assertRefused("test.ir:1:1", () -> scan(".2"));
    }

    private static void refusesEmptyHex() {
        Assert.assertRefused("test.ir:1:1", () -> scan("0x"));
    }

    private static void refusesHexWithoutDigits() {
        Assert.assertRefused("test.ir:1:1", () -> scan("0xzz"));
    }

    private static void refusesGluedNumber() {
        Assert.assertRefused("test.ir:1:1", () -> scan("1f"));
    }

    private static void refusesUnexpectedCharacter() {
        Assert.assertRefused("test.ir:1:5", () -> scan("mov #ax"));
    }

    private static void refusesOversizedNumber() {
        Assert.assertRefused("test.ir:1:1", () -> scan("0xFFFFFFFFFFFFFFFFFF"));
    }
}
