package i8086.ssa;

import i8086.ir.IrPrinter;
import i8086.ir.Module;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for putting a module into SSA form.
 *
 * <p>The dump is compared whole rather than probed, because the thing being
 * tested is a renaming: which version a use names, where a φ lands, and which
 * order the operands of that φ come in. Any of those going wrong produces a form
 * that still looks like SSA and computes the wrong answer, so a test that only
 * counted φ's would not notice.
 *
 * <p>Half of these are the other kind: a program where a φ must <em>not</em>
 * appear, because the value is dead, and one where nothing must be renamed at all,
 * because the name is a label.
 */
public final class SsaBuilderTest {

    private SsaBuilderTest() {
    }

    public static void register(Suite suite) {
        suite.add("Ssa renames a straight line", SsaBuilderTest::straightLine);
        suite.add("Ssa places a φ in the loop", SsaBuilderTest::loop);
        suite.add("Ssa places a φ where two arms define one value", SsaBuilderTest::phiAtJoin);
        suite.add("Ssa places no φ for a value nobody reads", SsaBuilderTest::noPhiWhenDead);
        suite.add("Ssa reads an undefined variable as its undefined value",
                SsaBuilderTest::readBeforeWrite);
        suite.add("Ssa renames variables and not labels", SsaBuilderTest::labelsStay);
        suite.add("Ssa renames the flags like any other variable", SsaBuilderTest::renamesFlags);
        suite.add("Ssa leaves unreachable code to itself", SsaBuilderTest::unreachable);
        suite.add("Ssa keeps a volatile access volatile", SsaBuilderTest::keepsTheVolatileMark);
        suite.add("Ssa does not touch the module", SsaBuilderTest::moduleUntouched);
        suite.add("Ssa is deterministic", SsaBuilderTest::deterministic);
    }

    private static String dump(String body) {
        return SsaPrinter.print(SsaBuilder.build(CfgTest.parse(body)));
    }

    private static void straightLine() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    x#1 = 1\n"
                        + "    x#2 = eval(x#1 + 1)\n"
                        + "    ret\n",
                dump("    var x: u16\n    x = 1\n    x = eval(x + 1)\n    ret\n"));
    }

    /**
     * A volatile access keeps the word through renaming.
     *
     * <p>It is what says the access has to happen however little anyone wants what it read
     * ({@code AGENTS.md} invariant 3), and the passes that read this form ask the mark rather than
     * the syntax: dead value elimination keeps a load that has one, and load folding leaves one
     * where it was written. An operand with a base name is rebuilt when that name is versioned, and
     * one with no base is not, which is how a load based on a variable came to be deletable while
     * {@code volatile [0x1234]} was safe — the documented spelling of the surface is the one that
     * does not go through here ({@code docs/ir.md} §3.4).
     */
    private static void keepsTheVolatileMark() {
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var p: u16\n"
                        + "    var c: u8\n"
                        + "    p#1 = 0x300\n"
                        + "    c#2 = volatile byte [p#1]\n"
                        + "    ret\n",
                dump("    var p: u16\n    var c: u8\n    p = 0x300\n"
                        + "    c = volatile byte [p]\n    ret\n"));
    }

    private static void loop() {
        // The body is inside the test block, so the φ lands in the test block and
        // is what the body reads: the version that reaches the body is the one
        // from either way round, not the one from before the loop.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var i: u16\n"
                        + "    var n: u16\n"
                        + "    i#1 = 0\n"
                        + "    n#2 = 3\n"
                        + "    jmp ..@lbl1\n"
                        + "\n"
                        + "block1 (..@lbl0) <- block2:\n"
                        + "    i#5 = eval(i#3 + 1)\n"
                        + "\n"
                        + "block2 (..@lbl1) <- block0 block1:\n"
                        + "    i#3 = phi(block0: i#1, block1: i#5)\n"
                        + "    flags#4 = cmp i#3, n#2\n"
                        + "    jc ..@lbl0\n"
                        + "\n"
                        + "block3 <- block2:\n"
                        + "    ret\n",
                dump("    var i: u16\n    var n: u16\n    i = 0\n    n = 3\n"
                        + "    .while i < n\n        i = eval(i + 1)\n    .endw\n    ret\n"));
    }

    private static void phiAtJoin() {
        // Both arms define x and the join reads it, so the join needs a φ, with
        // one operand per predecessor in the order the predecessors arrive.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    y#1 = 0\n"
                        + "    flags#2 = cmp y#1, 0\n"
                        + "    jnz ..@lbl0\n"
                        + "\n"
                        + "block1 <- block0:\n"
                        + "    x#3 = 1\n"
                        + "    jmp ..@lbl1\n"
                        + "\n"
                        + "block2 (..@lbl0) <- block0:\n"
                        + "    x#4 = 2\n"
                        + "\n"
                        + "block3 (..@lbl1) <- block1 block2:\n"
                        + "    x#5 = phi(block1: x#3, block2: x#4)\n"
                        + "    y#6 = eval(x#5 + 1)\n"
                        + "    ret\n",
                dump("    var x: u16\n    var y: u16\n    y = 0\n"
                        + "    .if y == 0\n        x = 1\n    .else\n        x = 2\n    .endif\n"
                        + "    y = eval(x + 1)\n    ret\n"));
    }

    private static void noPhiWhenDead() {
        // The same shape, with nothing reading x after the join. A φ there would
        // cost a register and buy nothing, so there is none — and neither version
        // needs to be merged for the two uses of x to be right.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    y#1 = 0\n"
                        + "    flags#2 = cmp y#1, 0\n"
                        + "    jnz ..@lbl0\n"
                        + "\n"
                        + "block1 <- block0:\n"
                        + "    x#3 = 1\n"
                        + "    jmp ..@lbl1\n"
                        + "\n"
                        + "block2 (..@lbl0) <- block0:\n"
                        + "    x#4 = 2\n"
                        + "\n"
                        + "block3 (..@lbl1) <- block1 block2:\n"
                        + "    ret\n",
                dump("    var x: u16\n    var y: u16\n    y = 0\n"
                        + "    .if y == 0\n        x = 1\n    .else\n        x = 2\n    .endif\n"
                        + "    ret\n"));
    }

    private static void readBeforeWrite() {
        // Nothing writes x, and the surface has no rule against reading it
        // (docs/ir.md §3.1), so the read is of x's undefined value: a name for
        // "x, with no value" rather than an invented zero.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    var y: u16\n"
                        + "    y#1 = eval(x#undef + 1)\n"
                        + "    ret\n",
                dump("    var x: u16\n    var y: u16\n    y = eval(x + 1)\n    ret\n"));
    }

    private static void labelsStay() {
        // 'msg' is a label, so it is an address and not a value: the store through
        // it is a load from a fixed address, and only the variable holding the
        // other address gets a version.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var p: u16\n"
                        + "    var x: u16\n"
                        + "    p#1 = msg\n"
                        + "    x#2 = [p#1]\n"
                        + "    x#3 = [msg]\n"
                        + "    ret\n"
                        + "\n"
                        + "block1 (msg)   ; nothing reaches this:\n"
                        + "msg: db \"hi\"\n",
                dump("    var p: u16\n    var x: u16\n    p = msg\n    x = [p]\n    x = [msg]\n"
                        + "    ret\n\nmsg: db \"hi\"\n"));
    }

    private static void renamesFlags() {
        // The flags are a variable like any other (docs/ir.md §4.1), so what
        // defines them is named and numbered with everything else. There is no φ
        // for them here, and there could not be: a flags value that survives a
        // label is what the surface's flags rule already refuses to let anything
        // read (docs/ir.md §4.3), so the only flags value in force at a join is
        // the one the join itself defines.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    var x: u16\n"
                        + "    flags#1 = cmp x#undef, 0\n"
                        + "    jnz l0\n"
                        + "\n"
                        + "block1 (l0) <- block0:\n"
                        + "    ret\n",
                dump("    var x: u16\n    cmp x, 0\n    jnz l0\nl0:\n    ret\n"));
    }

    private static void unreachable() {
        // Nothing reaches the second block, so it is renamed on its own: its reads
        // are undefined and the version it defines stays inside it.
        Assert.assertEquals("; SSA form of target 8086, entry main\n"
                        + "\n"
                        + "block0 (main):\n"
                        + "    ret\n"
                        + "\n"
                        + "block1   ; nothing reaches this:\n"
                        + "    var x: u16\n"
                        + "    x#1 = eval(x#undef + 1)\n",
                dump("    ret\n    var x: u16\n    x = eval(x + 1)\n"));
    }

    private static void moduleUntouched() {
        Module module = CfgTest.parse("    var i: u16\n    var n: u16\n    i = 0\n    n = 3\n"
                + "    .while i < n\n        i = eval(i + 1)\n    .endw\n    ret\n");
        String before = IrPrinter.print(module);
        SsaBuilder.build(module);
        Assert.assertEquals(before, IrPrinter.print(module));
    }

    private static void deterministic() {
        String body = "    var i: u16\n    var n: u16\n    i = 0\n    n = 3\n"
                + "    .while i < n\n        .if i == n\n            i = 0\n        .endif\n"
                + "        i = eval(i + 1)\n    .endw\n    ret\n";
        Assert.assertEquals(dump(body), dump(body));
    }
}
