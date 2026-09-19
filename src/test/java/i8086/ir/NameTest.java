package i8086.ir;

import i8086.CompileError;
import i8086.testing.Assert;
import i8086.testing.Suite;
import i8086.target.Target;
import i8086.target.Targets;

/**
 * Tests for names: what may be one, what may not, and the two ways out.
 *
 * <p>A word the surface gives a meaning to cannot be a plain name, because a reader
 * could not tell the language from the program. Two things make that liveable, and
 * they are what this class is about:
 *
 * <ul>
 *   <li>{@code $name} is the author's name whatever it looks like — NASM's rule,
 *       and it is NASM's because the problem is the same one ({@code docs/ir.md}
 *       §3.1). The marker is not part of the name: {@code $ax} names what
 *       {@code ax} names.
 *   <li>{@code ..@name} is the compiler's own, and an author declaring one is
 *       refused ({@code docs/ir.md} §7.2).
 * </ul>
 *
 * <p>The names that can be written <em>without</em> the marker are tested too, so
 * that the narrow list is a fact with a test rather than a habit.
 */
public final class NameTest {

    private static final String HEAD = "target 8086\norg 0x100\nentry main\n\nmain:\n";

    private static final Target TARGET = Targets.byName("8086");

    private NameTest() {
    }

    public static void register(Suite suite) {
        suite.add("A name with a $ is the author's, whatever it looks like",
                NameTest::theDollarEscapes);
        suite.add("A $ name works everywhere a name does", NameTest::theDollarWorksEverywhere);
        suite.add("The printer writes the $ a reader would need",
                NameTest::printsTheMarker);
        suite.add("Every word of the surface can be escaped", NameTest::everyWordEscapes);
        suite.add("A word of the surface written plain is a name where it can be one",
                NameTest::plainWordsAreNamesWhereTheyCanBe);
        suite.add("A name the compiler generated cannot be declared",
                NameTest::refusesGeneratedNames);
        suite.add("Words the surface does not know are ordinary names",
                NameTest::plainNamesStayNames);
        suite.add("A word of the surface is refused with the way out",
                NameTest::refusesWithTheWayOut);
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", HEAD + body);
    }

    private static String printed(String body) {
        return IrPrinter.print(parse(body));
    }

    /** What the statements became, which is what the rest of the pipeline sees. */
    private static String became(String body) {
        String text = printed(body);
        return text.substring(text.indexOf("\nmain:\n") + "\nmain:\n".length());
    }

    private static void theDollarEscapes() {
        // 'shl' is a word of the surface — an operator spelled as a word — so a plain
        // name cannot be it, and the marker is how the author says otherwise. What
        // comes back keeps the marker, because that is the canonical spelling.
        Assert.assertEquals("    var $shl: i16\n"
                        + "    $shl = 1\n"
                        + "    word [0x40] = $shl\n",
                became("    var $shl: i16\n"
                        + "    $shl = 1\n"
                        + "    word [0x40] = $shl\n"));
    }

    private static void theDollarWorksEverywhere() {
        // Declarations, uses, labels, branch targets, memory bases and the operands of
        // an instruction-shaped statement: the marker means the same thing in all of
        // them, because it is the token that carries it. Note 'word' twice: the size
        // word in front of a bracket is the machine's, the name inside it is the
        // author's, and only one of them is marked.
        Assert.assertEquals("    var $shl: i16\n"
                        + "    var $word: i16\n"
                        + "    $shl = 0\n"
                        + "    $word = 0x1000\n"
                        + "    $shl = [$word]\n"
                        + "    $shl = eval($shl + 1)\n"
                        + "    word [0x40] = $shl\n",
                became("    var $shl: i16\n"
                        + "    var $word: i16\n"
                        + "    $shl = 0\n"
                        + "    $word = 0x1000\n"
                        + "    $shl = [$word]\n"
                        + "    add $shl, 1\n"
                        + "    word [0x40] = $shl\n"));
    }

    private static void printsTheMarker() {
        // Input is liberal, output is canonical, the same split as the thirty
        // spellings of a condition (§4.4): 'eval' and 'ax' are accepted as plain names
        // and written back marked, and a name the surface does not know stays plain.
        Assert.assertEquals("    var $eval: i16\n"
                        + "    var $ax: i16\n"
                        + "    var plain: i16\n"
                        + "    $eval = 1\n"
                        + "    $ax = 2\n"
                        + "    plain = 3\n",
                became("    var eval: i16\n"
                        + "    var ax: i16\n"
                        + "    var plain: i16\n"
                        + "    eval = 1\n"
                        + "    ax = 2\n"
                        + "    plain = 3\n"));
    }

    private static void everyWordEscapes() {
        // The list is the surface's whole vocabulary plus the target's, so a word added
        // anywhere is covered by this test the day it is added: it must be usable with
        // the marker, and it must come back marked.
        for (String word : Vocabulary.words(TARGET)) {
            String marked = "$" + word;
            // Written, read back and printed: a word of the surface has to be a name in
            // every position a name is read in, or the escape is only half an escape.
            String body = "    var " + marked + ": i16\n"
                    + "    " + marked + " = 1\n"
                    + "    " + marked + " = eval(" + marked + " + 1)\n"
                    + "    " + marked + " = expr(" + marked + " * 2)\n"
                    + "    word [0x40] = " + marked + "\n";
            Assert.assertEquals(body, became(body));
            Assert.assertTrue(Vocabulary.markedWhenPrinted(word, TARGET),
                    "'" + word + "' is a word of the surface, so the printer marks it");
        }
    }

    private static void plainWordsAreNamesWhereTheyCanBe() {
        // The narrow list is what cannot be written plain, and it is exactly the words
        // whose meaning the surface cannot tell from a name where they stand. Everything
        // else the surface knows — a statement mnemonic, a word like 'eval' or 'pad', a
        // type name, a register name — is a name, in every position a name is read in.
        for (String word : Vocabulary.words(TARGET)) {
            if (Vocabulary.reserved(word, TARGET)) {
                continue;
            }
            String body = "    var " + word + ": i16\n"
                    + "    " + word + " = 1\n"
                    + "    word [0x40] = " + word + "\n";
            String marked = "    var $" + word + ": i16\n"
                    + "    $" + word + " = 1\n"
                    + "    word [0x40] = $" + word + "\n";
            Assert.assertEquals(marked, became(body));
        }
    }

    private static void refusesGeneratedNames() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    var ..@lbl0: i16\n"));
        Assert.assertTrue(refused.getMessage().contains("generated"), refused.getMessage());
    }

    private static void plainNamesStayNames() {
        // The marker is an escape, not a requirement: a name the surface does not know
        // stays plain, and so does one it knows only in a position this is not.
        Assert.assertEquals("    var $add: i16\n"
                        + "    var $eval: i16\n"
                        + "    var loop: i16\n"
                        + "    $add = 1\n"
                        + "    $eval = 2\n"
                        + "    loop = 3\n",
                became("    var add: i16\n"
                        + "    var eval: i16\n"
                        + "    var loop: i16\n"
                        + "    add = 1\n"
                        + "    eval = 2\n"
                        + "    loop = 3\n"));
    }

    private static void refusesWithTheWayOut() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    var shl: i16\n"));
        Assert.assertTrue(refused.getMessage().contains("$"), refused.getMessage());
        Assert.assertTrue(refused.getMessage().contains("docs/ir.md"), refused.getMessage());
    }
}
