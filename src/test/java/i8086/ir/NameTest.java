package i8086.ir;

import i8086.CompileError;
import i8086.testing.Assert;
import i8086.testing.Suite;
import i8086.target.Target;
import i8086.target.Targets;

import java.util.ArrayList;
import java.util.List;

/**
 * The audit of the naming rule: every word the surface knows, used as an author's
 * name in every position a name can stand in.
 *
 * <p>The rule is that there are no reserved words — a word means what its position
 * says it means, and values never stand next to each other, so no position is
 * ambiguous ({@code docs/ir.md} §3.1). This class is that claim under test rather
 * than in prose: it walks the whole vocabulary and, for each word, writes a module
 * that declares it, assigns it, reads it, addresses memory with it, labels a place
 * with it and branches to it.
 *
 * <p>What it cannot test is the other half, which is that the surface keeps
 * understanding its own words: those are the tests that were already here, and they
 * are the reason a word used as a name in a position where it is <em>not</em> a
 * name fails loudly rather than quietly doing something else.
 */
public final class NameTest {

    private static final Target TARGET = Targets.byName("8086");

    private NameTest() {
    }

    public static void register(Suite suite) {
        suite.add("Every word can be an author's name", NameTest::everyWordIsAName);
        suite.add("Every word printed back is marked", NameTest::everyWordComesBackMarked);
        suite.add("A word keeps its meaning where it is a word", NameTest::wordsStillMeanThemselves);
        suite.add("A $ name is the author's, whatever it looks like", NameTest::theDollarEscapes);
        suite.add("A $ name works everywhere a name does", NameTest::theDollarWorksEverywhere);
        suite.add("A name the compiler generated cannot be declared",
                NameTest::refusesGeneratedNames);
        suite.add("The sugar still reads its own words", NameTest::sugarStillWorks);
        suite.add("A dot word can be a name where the sugar is not reading",
                NameTest::dotWordsAreNames);
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", header() + body);
    }

    private static String header() {
        return "target 8086\norg 0x100\nentry main\n\nmain:\n";
    }

    private static String printed(String body) {
        return IrPrinter.print(parse(body));
    }

    private static String became(String body) {
        String text = printed(body);
        return text.substring(text.indexOf("\nmain:\n") + "\nmain:\n".length());
    }

    /**
     * What the audit cannot check, written down so that it is not mistaken for a
     * naming question: a data item cannot hold a label's address, so {@code dw msg}
     * — a pointer table, a vector table, a boot sector's jump — is not writable yet.
     * That is {@code docs/ir.md} §10.2 and it has nothing to do with names.
     */

    /**
     * The audit. Each word is used as a variable, a memory base, a label and a
     * branch target, and the whole thing has to be a program.
     *
     * <p>If this ever fails for a word, the surface has grown an ambiguity — some
     * position where the word can be read two ways — and the failure names it. That
     * is the moment to decide whether the word must be marked, or the position must
     * say more, and not before.
     */
    private static void everyWordIsAName() {
        List<String> failed = new ArrayList<String>();
        for (String word : Vocabulary.words(TARGET)) {
            for (String body : namePositions(word)) {
                try {
                    parse(body);
                } catch (RuntimeException refused) {
                    failed.add(word + ": " + refused.getMessage());
                }
            }
        }
        Assert.assertEquals("[]", failed.toString());
    }

    /**
     * Every position a name can stand in, one module each.
     *
     * <p>Written out rather than generated from the grammar, because this list is the
     * thing under test: a position missing from it is a position nobody has checked,
     * and the next reader should be able to see that at a glance.
     */
    private static List<String> namePositions(String word) {
        List<String> positions = new ArrayList<String>();
        // Declared, assigned, read as a value of both forms, used as an address, and
        // named by a label, a data label and a branch.
        positions.add("    var " + word + ": i16\n"
                + "    " + word + " = 1\n"
                + "    " + word + " = eval(" + word + " + 1)\n"
                + "    " + word + " = expr(" + word + " * 2)\n"
                + "    word [0x40] = " + word + "\n"
                + "    ret\n");
        positions.add("    var " + word + ": i16\n"
                + "    var p: i16\n"
                + "    " + word + " = 0x1000\n"
                + "    p = " + word + "\n"
                + "    word [" + word + "] = 1\n"       // as an address, inside brackets
                + "    word [" + word + " + 2] = 1\n"
                + "    p = [" + word + "]\n"
                + "    ret\n");
        positions.add(word + ":\n"
                + "    cmp " + word + ", 0\n"
                + "    jz " + word + "Target\n"
                + "    jmp " + word + "Target\n"
                + word + "Target:\n"
                + "    ret\n");
        positions.add(word + ": db 1\n"
                + "    ret\n");
        positions.add("    var " + word + ": i16\n"
                + "    asm clobbers(ax) {\n"
                + "        mov ax, offset " + word + "\n"        // named from a block
                + "    }\n"
                + "    ret\n"
                + "\n"
                + word + ": db 1\n");
        positions.add("    var p: i16\n"
                + "    var " + word + ": i16\n"
                + "    p = 0x1000\n"
                + "    " + word + " = movzx byte [p]\n"      // a conversion, and the
                + "    " + word + " = movzx byte " + word + "\n"   // same word as its operand
                + "    word [0x40] = " + word + "\n"
                + "    ret\n");
        positions.add("    var " + word + ": i16\n"
                + "    add " + word + ", 1\n"              // an instruction-shaped statement
                + "    neg " + word + "\n"
                + "    mov " + word + ", 2\n"
                + "    word [0x40] = " + word + "\n"
                + "    ret\n");
        return positions;
    }

    private static void everyWordComesBackMarked() {
        for (String word : Vocabulary.words(TARGET)) {
            String body = "    var " + word + ": i16\n"
                    + "    " + word + " = 1\n"
                    + "    word [0x40] = " + word + "\n";
            String expected = "    var $" + word + ": i16\n"
                    + "    $" + word + " = 1\n"
                    + "    word [0x40] = $" + word + "\n";
            Assert.assertEquals(expected, became(body));
        }
    }

    /**
     * The other half of the rule: a word in the position where it <em>is</em> a word
     * still means itself, even when a variable of that name is in scope.
     */
    private static void wordsStillMeanThemselves() {
        // 'add' is an instruction-shaped statement and also a variable, in one module.
        Assert.assertEquals("    var $add: i16\n"
                        + "    var s: i16\n"
                        + "    $add = 1\n"
                        + "    s = 2\n"
                        + "    s = eval(s + 1)\n"
                        + "    $add = eval($add + 1)\n",
                became("    var add: i16\n"
                        + "    var s: i16\n"
                        + "    add = 1\n"
                        + "    s = 2\n"
                        + "    add s, 1\n"
                        + "    add add, 1\n"));
        // A size word in front of a bracket is the machine's; the same word as a name
        // is the author's.
        Assert.assertEquals("    var $word: i16\n"
                        + "    var p: i16\n"
                        + "    p = 0x1000\n"
                        + "    word [p] = 1\n"
                        + "    $word = [p]\n"
                        + "    p = $word\n",
                became("    var word: i16\n"
                        + "    var p: i16\n"
                        + "    p = 0x1000\n"
                        + "    word [p] = 1\n"
                        + "    word = [p]\n"
                        + "    p = word\n"));
    }

    private static void theDollarEscapes() {
        Assert.assertEquals("    var $shl: i16\n"
                        + "    $shl = 1\n"
                        + "    word [0x40] = $shl\n",
                became("    var $shl: i16\n"
                        + "    $shl = 1\n"
                        + "    word [0x40] = $shl\n"));
    }

    private static void theDollarWorksEverywhere() {
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

    private static void refusesGeneratedNames() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    var ..@lbl0: i16\n"));
        Assert.assertTrue(refused.getMessage().contains("generated"), refused.getMessage());
    }

    private static void sugarStillWorks() {
        Assert.assertEquals("    var n: u16\n"
                        + "    jmp ..@lbl1\n"
                        + "\n"
                        + "..@lbl0:\n"
                        + "    n = eval(n - 1)\n"
                        + "\n"
                        + "..@lbl1:\n"
                        + "    cmp n, 0\n"
                        + "    ja ..@lbl0\n",
                became("    var n: u16\n"
                        + "    .while n > 0\n"
                        + "        n = eval(n - 1)\n"
                        + "    .endw\n"));
    }

    private static void dotWordsAreNames() {
        // A dot word is a name wherever the sugar is not looking for a shape. Outside
        // a sugar block, '.if = 1' is an assignment; inside one, the marker is what
        // says the same thing, because there the closing word is what is being read.
        Assert.assertEquals("    var $.if: i16\n"
                        + "    $.if = 1\n",
                became("    var .if: i16\n"
                        + "    .if = 1\n"));
        Assert.assertEquals("    var n: u16\n"
                        + "    var $.endif: i16\n"
                        + "    jmp ..@lbl1\n"
                        + "\n"
                        + "..@lbl0:\n"
                        + "    $.endif = 1\n"
                        + "    n = eval(n - 1)\n"
                        + "\n"
                        + "..@lbl1:\n"
                        + "    cmp n, 0\n"
                        + "    ja ..@lbl0\n",
                became("    var n: u16\n"
                        + "    var .endif: i16\n"
                        + "    .while n > 0\n"
                        + "        $.endif = 1\n"
                        + "        n = eval(n - 1)\n"
                        + "    .endw\n"));
    }
}
