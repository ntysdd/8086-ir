package i8086.ir;

import i8086.CompileError;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for the control-flow sugar, which is normalised away as it is read.
 *
 * <p>What is checked is therefore not that the sugar survives but exactly what it
 * became: a comparison, a branch on the opposite of what was written, and a
 * label. The labels it generates are named so that no name a person can write
 * looks like one, and they are numbered in the order they are created, so the
 * output is the same every run.
 */
public final class IrSugarTest {

    private IrSugarTest() {
    }

    public static void register(Suite suite) {
        suite.add("Sugar turns .if into a test and a jump", IrSugarTest::expandsIf);
        suite.add("Sugar turns .else into a jump over the body", IrSugarTest::expandsElse);
        suite.add("Sugar chains .elseif", IrSugarTest::expandsElseif);
        suite.add("Sugar turns .while into a test and a jump back", IrSugarTest::expandsWhile);
        suite.add("Sugar reads the signedness from the variables", IrSugarTest::readsSignedness);
        suite.add("Sugar nests, numbering its labels in creation order",
                IrSugarTest::numbersLabelsDeterministically);
        suite.add("Sugar prints what it became, not what was written",
                IrSugarTest::printsTheExpansion);
        suite.add("Sugar refuses an unclosed .if", IrSugarTest::refusesUnclosedIf);
        suite.add("Sugar refuses an unclosed .while", IrSugarTest::refusesUnclosedWhile);
        suite.add("Sugar refuses a stray .endif", IrSugarTest::refusesStrayEndif);
        suite.add("Sugar refuses a second .else", IrSugarTest::refusesSecondElse);
        suite.add("Sugar refuses a condition that is not a comparison",
                IrSugarTest::refusesNonComparison);
        suite.add("Sugar refuses a comparison across signedness",
                IrSugarTest::refusesMixedSignedness);
    }

    private static Module parse(String body) {
        return IrParser.parse("test.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n" + body);
    }

    private static String printed(String body) {
        return IrPrinter.print(parse(body));
    }

    private static void expandsIf() {
        Assert.assertEquals("target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var $x: u16\n"
                + "    var $y: u16\n"
                + "    var $z: u16\n"
                + "    cmp $x, $y\n"
                + "    jnc ..@lbl0\n"
                + "    $z = 1\n"
                + "\n"
                + "..@lbl0:\n", printed("    var x: u16\n    var y: u16\n    var z: u16\n"
                + "    .if x < y\n"
                + "        z = 1\n"
                + "    .endif\n"));
    }

    private static void expandsElse() {
        Assert.assertEquals("target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var $x: u16\n"
                + "    var $y: u16\n"
                + "    var $z: u16\n"
                + "    cmp $x, $y\n"
                + "    jnz ..@lbl0\n"
                + "    $z = 1\n"
                + "    jmp ..@lbl1\n"
                + "\n"
                + "..@lbl0:\n"
                + "    $z = 2\n"
                + "\n"
                + "..@lbl1:\n", printed("    var x: u16\n    var y: u16\n    var z: u16\n"
                + "    .if x == y\n"
                + "        z = 1\n"
                + "    .else\n"
                + "        z = 2\n"
                + "    .endif\n"));
    }

    private static void expandsElseif() {
        Assert.assertEquals("target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var $x: u16\n"
                + "    var $y: u16\n"
                + "    var $z: u16\n"
                + "    cmp $x, $y\n"
                + "    jnc ..@lbl0\n"
                + "    $z = 1\n"
                + "    jmp ..@lbl1\n"
                + "\n"
                + "..@lbl0:\n"
                + "    cmp $x, $y\n"
                + "    jbe ..@lbl2\n"
                + "    $z = 2\n"
                + "    jmp ..@lbl1\n"
                + "\n"
                + "..@lbl2:\n"
                + "    $z = 3\n"
                + "\n"
                + "..@lbl1:\n", printed("    var x: u16\n    var y: u16\n    var z: u16\n"
                + "    .if x < y\n"
                + "        z = 1\n"
                + "    .elseif x > y\n"
                + "        z = 2\n"
                + "    .else\n"
                + "        z = 3\n"
                + "    .endif\n"));
    }

    private static void expandsWhile() {
        Assert.assertEquals("target 8086\n"
                + "org 0x100\n"
                + "entry $main\n"
                + "\n"
                + "$main:\n"
                + "    var $x: u16\n"
                + "    var $y: u16\n"
                + "    var $z: u16\n"
                + "    jmp ..@lbl1\n"
                + "\n"
                + "..@lbl0:\n"
                + "    $z = 1\n"
                + "\n"
                + "..@lbl1:\n"
                + "    cmp $x, $y\n"
                + "    ja ..@lbl0\n", printed("    var x: u16\n    var y: u16\n    var z: u16\n"
                + "    .while x > y\n"
                + "        z = 1\n"
                + "    .endw\n"));
    }

    /** A signed variable makes it a signed comparison, so the test is {@code jl}. */
    private static void readsSignedness() {
        Assert.assertTrue(printed("    var i: i16\n    var j: i16\n    var z: u16\n"
                        + "    .if i < j\n        z = 1\n    .endif\n")
                        .contains("    jge ..@lbl0\n"),
                "i16 < i16 is a signed test, and its opposite is jge");
        Assert.assertTrue(printed("    var i: u16\n    var j: u16\n    var z: u16\n"
                        + "    .if i < j\n        z = 1\n    .endif\n")
                        .contains("    jnc ..@lbl0\n"),
                "u16 < u16 is an unsigned test, and its opposite is jnc");
    }

    private static void numbersLabelsDeterministically() {
        String body = "    var i: u16\n    var n: u16\n"
                + "    .while i < n\n"
                + "        .if i == n\n"
                + "            i = 0\n"
                + "        .endif\n"
                + "    .endw\n";
        String once = printed(body);
        Assert.assertEquals(once, printed(body));
        Assert.assertTrue(once.contains("..@lbl0") && once.contains("..@lbl1")
                        && once.contains("..@lbl2"),
                "the labels are numbered as they are created: " + once);
    }

    private static void printsTheExpansion() {
        String printed = printed("    var x: u16\n    var y: u16\n    var z: u16\n"
                + "    .if x < y\n        z = 1\n    .endif\n");
        Assert.assertFalse(printed.contains(".if"), "the sugar is gone by the time it is printed");
        Assert.assertTrue(printed.contains("    cmp $x, $y\n"), "what it became is there instead");
    }

    private static void refusesUnclosedIf() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    var x: u16\n    .if x == x\n        x = 1\n"));
        Assert.assertTrue(refused.getMessage().contains(".endif"), refused.getMessage());
    }

    private static void refusesUnclosedWhile() {
        Assert.assertThrows(CompileError.class,
                () -> parse("    var x: u16\n    .while x == x\n        x = 1\n"));
    }

    private static void refusesStrayEndif() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    var x: u16\n    x = 1\n    .endif\n"));
        Assert.assertTrue(refused.getMessage().contains("closes nothing"),
                refused.getMessage());
    }

    private static void refusesSecondElse() {
        Assert.assertThrows(CompileError.class,
                () -> parse("    var x: u16\n    .if x == x\n        x = 1\n    .else\n"
                        + "        x = 2\n    .else\n        x = 3\n    .endif\n"));
    }

    private static void refusesNonComparison() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    var x: u16\n    .if x\n        x = 1\n    .endif\n"));
        Assert.assertTrue(refused.getMessage().contains("needs a comparison"),
                refused.getMessage());
    }

    private static void refusesMixedSignedness() {
        CompileError refused = Assert.assertThrows(CompileError.class,
                () -> parse("    var i: i16\n    var u: u16\n    .if i < u\n        i = 0\n"
                        + "    .endif\n"));
        Assert.assertTrue(refused.getMessage().contains("signed and one unsigned"),
                refused.getMessage());
    }
}
