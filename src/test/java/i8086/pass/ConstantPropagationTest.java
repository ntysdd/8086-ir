package i8086.pass;

import i8086.ir.IrParser;
import i8086.ir.Module;
import i8086.ssa.SsaBuilder;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaPrinter;
import i8086.ssa.SsaVerifier;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for constant propagation: what it folds, and — the half that matters as
 * much — what it refuses to.
 *
 * <p>Each test compares the SSA form before and after, because that is the unit a
 * pass works in: the versions are what say whether a definition is still there and
 * whether a use still names it.
 *
 * <p>A value the compiler cannot know is the interesting input here, and the way to
 * write one is to read a variable nothing writes ({@code docs/ssa.md} §5). It is
 * also how a test keeps arithmetic around to be tested at all: fold everything and
 * a program whose result nobody reads disappears.
 */
public final class ConstantPropagationTest {

    private ConstantPropagationTest() {
    }

    public static void register(Suite suite) {
        suite.add("Constants are written where they are read",
                ConstantPropagationTest::substitutes);
        suite.add("A definition that is a constant is folded away",
                ConstantPropagationTest::foldsTheDefinition);
        suite.add("An operation on two known values is worked out",
                ConstantPropagationTest::foldsAnOperation);
        suite.add("A value is not folded when its flags are read",
                ConstantPropagationTest::keepsFlagsThatAreRead);
        suite.add("A comparison always keeps one side to take its width from",
                ConstantPropagationTest::keepsAComparisonWide);
        suite.add("A store with no width of its own keeps the value stating it",
                ConstantPropagationTest::keepsAnUnsizedStoreWide);
        suite.add("A store that says its width folds its value",
                ConstantPropagationTest::foldsIntoASizedStore);
        suite.add("Division is not folded, so a trap stays where the program put it",
                ConstantPropagationTest::doesNotFoldDivision);
        suite.add("A shift by the width is not folded", ConstantPropagationTest::doesNotFoldWideShift);
        suite.add("A variable nothing writes is not a constant",
                ConstantPropagationTest::doesNotInventConstants);
        suite.add("Constant propagation is deterministic", ConstantPropagationTest::deterministic);
    }

    static SsaForm build(String body) {
        Module module = IrParser.parse("test.ir", "target 8086\norg 0x100\nentry $main\n\n$main:\n"
                + body);
        return SsaBuilder.build(module);
    }

    /** The form after this pass, printed, and verified because it is a transformation. */
    static String after(String body) {
        SsaForm form = new ConstantPropagation().run(build(body));
        SsaVerifier.verify(form);
        return SsaPrinter.print(form);
    }

    /** What the pass was given, printed, for a test that compares the two. */
    static String before(String body) {
        return SsaPrinter.print(build(body));
    }

    private static void substitutes() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var n: u16\n"
                        + "    var i: u16\n"
                        + "    n#1 = 3\n"
                        + "    flags#2, carry#3 = cmp i#undef, 3\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var n: u16\n    var i: u16\n"
                        + "    n = 3\n"
                        + "    cmp i, n\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void foldsTheDefinition() {
        // The value is written where it was wanted. The definitions themselves are
        // still here: removing what nothing reads is the next pass's job, and a pass
        // that both folded and deleted would be two passes wearing one name.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    x#1 = 5\n"
                        + "    y#2 = 0xa\n"
                        + "    flags#5, carry#6 = cmp y#2, 0\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n"
                        + "    x = 5\n"
                        + "    y = eval(x * 2)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void foldsAnOperation() {
        // Sixteen bits wide, so the sum is 0x1FF rather than the byte that would be
        // left if the operation were done in a narrower one.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var y: u16\n"
                        + "    y#1 = 0x1ff\n"
                        + "    flags#2, carry#3 = cmp y#1, 0\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var y: u16\n"
                        + "    y = expr(0xFF + 0x100)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void keepsFlagsThatAreRead() {
        // The addition is of two known values, and it still is not replaced: what
        // it leaves in the flags is what the branch reads, and a value is not the
        // same thing as the flags it came with (docs/ir.md §5.1).
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    x#1 = 4\n"
                        + "    y#2 = eval(4 + 1)\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n"
                        + "    x = 4\n"
                        + "    y = eval(x + 1)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void keepsAComparisonWide() {
        // Both sides are known, and substituting both would leave a comparison of
        // two literals — which has no width to take (docs/ir.md §3.2). The left
        // side stays, which is also the side that lets the machine compare against
        // an immediate.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var n: u16\n"
                        + "    var i: u16\n"
                        + "    n#1 = 3\n"
                        + "    i#2 = 3\n"
                        + "    flags#3, carry#4 = cmp i#2, 3\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var n: u16\n    var i: u16\n"
                        + "    n = 3\n"
                        + "    i = n\n"
                        + "    cmp i, n\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void keepsAnUnsizedStoreWide() {
        // '[0x32] = x' is sixteen bits because x is (docs/ir.md §3.4). Writing 20 in
        // place of x would leave the statement with no width at all, which is a
        // program the verifier refuses — so the pass does not write it. The sized
        // spelling below is where the fold happens instead.
        String body = "    var x: i16\n"
                + "    x = 20\n"
                + "    [0x32] = x\n"
                + "    ret\n";
        Assert.assertEquals(before(body), after(body));
    }

    private static void foldsIntoASizedStore() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: i16\n"
                        + "    x#1 = 0x14\n"
                        + "    word [0x32] = 0x14\n"
                        + "    ret\n",
                after("    var x: i16\n"
                        + "    x = 20\n"
                        + "    word [0x32] = x\n"
                        + "    ret\n"));
    }

    private static void doesNotFoldDivision() {
        // Sixteen divided by nothing is a fault the program asked for, and the
        // folder may not take it away by working the answer out itself
        // (docs/ir.md §2.2, §5.5).
        Assert.assertEquals(before("    var y: u16\n"
                        + "    y = eval(0x10 / 0)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"),
                after("    var y: u16\n"
                        + "    y = eval(0x10 / 0)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void doesNotFoldWideShift() {
        // What a shift by the width of the value does is not something the surface
        // says (docs/ir.md §12), so the folder does not say either: the operation
        // stays and the machine answers it.
        Assert.assertEquals(before("    var y: u16\n"
                        + "    y = eval(1 shl 16)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"),
                after("    var y: u16\n"
                        + "    y = eval(1 shl 16)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void doesNotInventConstants() {
        // 'u' is read and never written, which is a value the compiler does not
        // know, so nothing that depends on it is known either.
        Assert.assertEquals(before("    var x: u16\n    var y: u16\n    var u: u16\n"
                        + "    x = eval(u + 1)\n"
                        + "    y = eval(x + 1)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"),
                after("    var x: u16\n    var y: u16\n    var u: u16\n"
                        + "    x = eval(u + 1)\n"
                        + "    y = eval(x + 1)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void deterministic() {
        String body = "    var x: u16\n    var y: u16\n"
                + "    x = 5\n    y = eval(x * 2)\n    cmp y, 0\n    jc l0\nl0:\n    ret\n";
        Assert.assertEquals(after(body), after(body));
    }
}
