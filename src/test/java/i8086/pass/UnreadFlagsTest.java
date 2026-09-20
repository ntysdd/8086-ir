package i8086.pass;

import i8086.ssa.SsaForm;
import i8086.ssa.SsaPrinter;
import i8086.ssa.SsaVerifier;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for giving up flags nobody reads, without which the target may never use a
 * form that disturbs them.
 *
 * <p>What the pass changes is one word: {@code eval} becomes {@code expr}. What
 * that word means is that the operation no longer claims the flags it leaves — and
 * the claim is only droppable when nothing reads them, which is the case the SSA
 * form can answer and the module cannot.
 */
public final class UnreadFlagsTest {

    private UnreadFlagsTest() {
    }

    public static void register(Suite suite) {
        suite.add("An operation nobody reads the flags of gives them up",
                UnreadFlagsTest::givesUpUnreadFlags);
        suite.add("An operation whose flags are read keeps them",
                UnreadFlagsTest::keepsFlagsThatAreRead);
        suite.add("An operation with a load in it is left alone",
                UnreadFlagsTest::leavesLoadsAlone);
        suite.add("An operation that reads the carry is left alone",
                UnreadFlagsTest::leavesCarryReadersAlone);
        suite.add("Giving up the flags is deterministic", UnreadFlagsTest::deterministic);
    }

    private static String after(String body) {
        SsaForm form = new UnreadFlags().run(ConstantPropagationTest.build(body));
        SsaVerifier.verify(form);
        return SsaPrinter.print(form);
    }

    private static void givesUpUnreadFlags() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    x#1 = expr(x#undef + 1)\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n"
                        + "    x = eval(x + 1)\n"
                        + "    ret\n"));
    }

    private static void keepsFlagsThatAreRead() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    x#1 = eval(x#undef + 1)\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var x: u16\n"
                        + "    x = eval(x + 1)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void leavesLoadsAlone() {
        // expr works on values already in registers (docs/ir.md §5.2), so an
        // operation with a load in it keeps the form it was written with.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var p: u16\n"
                        + "    x#1 = eval(x#undef + [p#undef])\n"
                        + "    ret\n",
                after("    var x: u16\n    var p: u16\n"
                        + "    x = eval(x + [p])\n"
                        + "    ret\n"));
    }

    private static void leavesCarryReadersAlone() {
        // 'adc' is not an operation expr can hold: it reads a flag, and expr reads
        // none at all (docs/ir.md §5.5). So although nobody reads the flags it
        // leaves, the promise stays — the operation itself needs one, and the form
        // that could give them up cannot express that.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    flags#1, carry#2 = cmp x#undef, 0\n"
                        + "    x#3 = eval(x#undef adc 1)\n"
                        + "    ret\n",
                after("    var x: u16\n"
                        + "    cmp x, 0\n"
                        + "    x = eval(x adc 1)\n"
                        + "    ret\n"));
    }

    private static void deterministic() {
        String body = "    var x: u16\n    x = eval(x + 1)\n    ret\n";
        Assert.assertEquals(after(body), after(body));
    }
}
