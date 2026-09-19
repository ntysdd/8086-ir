package i8086.pass;

import i8086.ssa.SsaForm;
import i8086.ssa.SsaPrinter;
import i8086.ssa.SsaVerifier;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for dead value elimination: what goes, and what stays however dead it looks.
 *
 * <p>The pass is small and the tests are mostly about the exceptions, because the
 * exceptions are the whole of the difficulty: a store, a block of assembly, a
 * branch, and the flags when somebody reads them. Getting any of those wrong
 * deletes a program's effects, which is the one thing an optimiser may never do.
 *
 * <p>The flags version is worth its own case: an addition nobody reads the flags of
 * goes, and an addition whose flags a branch reads stays — even though its *value*
 * is dead and may well be too.
 */
public final class DeadValueEliminationTest {

    private DeadValueEliminationTest() {
    }

    public static void register(Suite suite) {
        suite.add("A value nothing reads is removed", DeadValueEliminationTest::removesDeadValues);
        suite.add("Removing one value is what makes the next one dead",
                DeadValueEliminationTest::removesInAChain);
        suite.add("A comparison nobody reads the flags of is removed",
                DeadValueEliminationTest::removesDeadFlags);
        suite.add("A store is kept, however dead the value in it is",
                DeadValueEliminationTest::keepsStores);
        suite.add("An inline block is kept, and so is everything it might read",
                DeadValueEliminationTest::keepsBlocksAndTheirScope);
        suite.add("A comparison whose flags are read is kept",
                DeadValueEliminationTest::keepsFlagsThatAreRead);
        suite.add("A φ nothing reads is removed", DeadValueEliminationTest::removesDeadPhis);
        suite.add("Dead value elimination is deterministic",
                DeadValueEliminationTest::deterministic);
    }

    private static String after(String body) {
        SsaForm form = new DeadValueElimination().run(ConstantPropagationTest.build(body));
        SsaVerifier.verify(form);
        return SsaPrinter.print(form);
    }

    private static void removesDeadValues() {
        // The first addition is of no use to anyone: nobody reads its value and
        // nobody reads its flags. The second one stays, because the branch reads
        // what it left — a value is one thing and the flags it came with are
        // another, and only one of the two is dead here.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    var z: u16\n"
                        + "    z#3 = eval(x#undef + 2)\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n    var z: u16\n"
                        + "    y = eval(x + 1)\n"
                        + "    z = eval(x + 2)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void removesInAChain() {
        // Each value is read by the next and by nothing else, and the last is read
        // by nobody, so removing one is what makes the next one removable. The
        // result of one round of the pass is an invitation to run it again, and
        // that is what the loop in the pass is.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    var z: u16\n"
                        + "    var u: u16\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n    var z: u16\n    var u: u16\n"
                        + "    x = eval(u + 1)\n"
                        + "    y = eval(u + 2)\n"
                        + "    z = eval(u + 3)\n"
                        + "    ret\n"));
    }

    private static void removesDeadFlags() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    y#2 = eval(x#undef + 1)\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n"
                        + "    cmp x, 0\n"
                        + "    y = eval(x + 1)\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    /**
     * A store is an effect and stays; a load whose value nobody reads is not, and
     * goes.
     *
     * <p>What keeps the load's address alive is the store that uses it: the value
     * loaded into {@code p} is dead, and the version of {@code q} is not, because
     * the store is written through it. A {@code volatile} load would be a different
     * question, and is not one this compiler can be asked yet
     * ({@code docs/ir.md} §3.4).
     */
    private static void keepsStores() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var p: u16\n"
                        + "    var q: u16\n"
                        + "    q#2 = 1\n"
                        + "    [q#2] = x#undef\n"
                        + "    ret\n",
                after("    var x: u16\n    var p: u16\n    var q: u16\n"
                        + "    p = [p]\n"
                        + "    q = 1\n"
                        + "    [q] = x\n"
                        + "    ret\n"));
    }

    private static void keepsBlocksAndTheirScope() {
        // A block cannot say what it reads (docs/ir.md §9), so nothing in its
        // scope may be removed — not even a value that looks as dead as this one.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    x#1 = 1\n"
                        + "    flags#2 = asm clobbers(ax) {\n"
                        + "        int 0x21\n"
                        + "    }\n"
                        + "    y#3 = eval(x#1 + 1)\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n"
                        + "    x = 1\n"
                        + "    asm clobbers(ax) {\n        int 0x21\n    }\n"
                        + "    y = eval(x + 1)\n"
                        + "    ret\n"));
    }

    private static void keepsFlagsThatAreRead() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    y#1 = eval(x#undef + 1)\n"
                        + "    flags#3 = cmp y#1, 0\n"
                        + "    jc l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                after("    var x: u16\n    var y: u16\n"
                        + "    y = eval(x + 1)\n"
                        + "    cmp y, 0\n"
                        + "    jc l0\n"
                        + "l0:\n"
                        + "    ret\n"));
    }

    private static void removesDeadPhis() {
        // Both arms write x and nothing reads it, so the assignments go. The φ was
        // never there to go: it is placed only where a value is live on the way in,
        // and this one never was (docs/ssa.md §3). The branches stay, because they
        // are the shape of the program and not a value.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var c: u16\n"
                        + "    var x: u16\n"
                        + "    flags#1 = cmp c#undef, 0\n"
                        + "    jnz $lbl0\n"
                        + "\n"
                        + "block1 <- block0:\n"
                        + "    jmp $lbl1\n"
                        + "\n"
                        + "block2 ($lbl0) <- block0:\n"
                        + "\n"
                        + "block3 ($lbl1) <- block1 block2:\n"
                        + "    ret\n",
                after("    var c: u16\n    var x: u16\n"
                        + "    .if c == 0\n        x = 1\n    .else\n        x = 2\n    .endif\n"
                        + "    ret\n"));
    }

    private static void deterministic() {
        String body = "    var x: u16\n    var y: u16\n"
                + "    x = 1\n    y = eval(x + 2)\n    cmp y, 0\n    jc l0\nl0:\n    ret\n";
        Assert.assertEquals(after(body), after(body));
    }
}
