package i8086.ssa;

import i8086.ir.IrParser;
import i8086.ir.Module;
import i8086.testing.Assert;
import i8086.testing.Suite;

/**
 * Tests for reading a module as a control flow graph.
 *
 * <p>The graph is a reading of the item list and not a rewriting of it, so what
 * is checked is where blocks begin and end and which ones lead to which. The two
 * rules that are easy to get wrong are both here: the item after something that
 * leaves a block starts a new one, and a branch to the label right after it is
 * one edge and not two.
 *
 * <p>Labels written in these tests are ordinary names. The names the sugar invents
 * begin with a character no name may contain, so that a person cannot write one
 * ({@code docs/ir.md} §7.2); the tests that use the sugar therefore expect to see
 * those names, and the ones that do not never mention them.
 */
public final class CfgTest {

    private CfgTest() {
    }

    public static void register(Suite suite) {
        suite.add("Cfg keeps a straight line in one block", CfgTest::oneBlock);
        suite.add("Cfg starts a block at a label", CfgTest::labelsStartBlocks);
        suite.add("Cfg starts a block after something that leaves", CfgTest::retEndsABlock);
        suite.add("Cfg lets a far jump leave the image", CfgTest::farJumpLeaves);
        suite.add("Cfg falls through to the next block", CfgTest::fallsThrough);
        suite.add("Cfg gives a branch two edges", CfgTest::branchHasTwoEdges);
        suite.add("Cfg counts a branch to the next label once", CfgTest::branchToTheNextLabel);
        suite.add("Cfg starts a block at a named data definition", CfgTest::dataStartsABlock);
        suite.add("Cfg leaves a block nothing reaches", CfgTest::unreachableBlock);
        suite.add("Cfg puts the entry first and reads it forwards", CfgTest::entryFirst);
        suite.add("Cfg is deterministic", CfgTest::deterministic);
    }

    static Module parse(String body) {
        return IrParser.parse("test.ir", "target 8086\norg 0x100\nentry main\n\nmain:\n" + body);
    }

    /**
     * One line per block: its name, its label, whether anything reaches it, where
     * it goes, and how it is arrived at. A dash is "none", so that an empty edge
     * list cannot be mistaken for a formatting accident.
     */
    static String describe(Cfg cfg) {
        StringBuilder text = new StringBuilder();
        for (Block block : cfg.blocks()) {
            text.append(block);
            if (block.label() != null) {
                text.append('(').append(block.label()).append(')');
            }
            if (!cfg.isReachable(block)) {
                text.append(" dead");
            }
            text.append(" ->");
            append(text, block.successors());
            text.append("  <-");
            append(text, block.predecessors());
            text.append('\n');
        }
        return text.toString();
    }

    private static void append(StringBuilder text, java.util.List<Block> blocks) {
        if (blocks.isEmpty()) {
            text.append(" -");
            return;
        }
        for (Block block : blocks) {
            text.append(' ').append(block);
        }
    }

    private static String shape(String body) {
        return describe(Cfg.of(parse(body)));
    }

    /**
     * A far jump is the one statement that says "nothing after this runs": it goes out
     * of the image into another segment, and the fact that the compiler knows it is
     * the whole reason it is a statement rather than a line in a block
     * ({@code docs/ir.md} §7.1).
     */
    private static void farJumpLeaves() {
        Assert.assertEquals("block0(main) -> -  <- -\n"
                        + "block1(later) dead -> -  <- -\n",
                shape("    jmp 0x0000:0x7E00\nlater:\n    ret\n"));
    }

    private static void oneBlock() {
        Assert.assertEquals("block0(main) -> -  <- -\n",
                shape("    var x: u16\n    x = 1\n"));
    }

    private static void labelsStartBlocks() {
        // Nothing branches and nothing leaves, so this is blocks falling one into
        // the next: the label is the only thing that ends one.
        Assert.assertEquals("block0(main) -> block1  <- -\n"
                        + "block1(l0) -> block2  <- block0\n"
                        + "block2(l1) -> -  <- block1\n",
                shape("    var x: u16\n    x = 1\nL0:\n    x = 2\nL1:\n"));
    }

    private static void retEndsABlock() {
        // The item after the 'ret' starts a block even though no label names it:
        // the block that ended cannot go on to it, so nothing reaches it.
        Assert.assertEquals("block0(main) -> -  <- -\n"
                        + "block1 dead -> -  <- -\n",
                shape("    ret\n    var x: u16\n"));
    }

    private static void fallsThrough() {
        Assert.assertEquals("block0(main) -> block1  <- -\n"
                        + "block1(l0) -> -  <- block0\n",
                shape("    var x: u16\n    x = 1\nL0:\n    ret\n"));
    }

    private static void branchHasTwoEdges() {
        // 'cmp; jnc l1; x = 1; l1:'. The branch goes to the label, and the body
        // falls past it into the same block. The successors are in the order the
        // terminator mentions them: where it goes, then where it falls.
        Assert.assertEquals("block0(main) -> block2 block1  <- -\n"
                        + "block1 -> block2  <- block0\n"
                        + "block2(l1) -> -  <- block0 block1\n",
                shape("    var x: u16\n    cmp x, 0\n    jnc l1\n    x = 1\nL1:\n"));
    }

    private static void branchToTheNextLabel() {
        // The branch target is the item right after the branch, so the branch and
        // the fall-through arrive at the same block. One edge, because a block
        // either leads to a place or does not, and two edges would be two φ
        // operands for one arrival.
        Assert.assertEquals("block0(main) -> block1  <- -\n"
                        + "block1(l0) -> -  <- block0\n",
                shape("    var x: u16\n    cmp x, 0\n    jnc l0\nL0:\n"));
    }

    private static void dataStartsABlock() {
        Assert.assertEquals("block0(main) -> -  <- -\n"
                        + "block1(msg) dead -> -  <- -\n",
                shape("    var p: u16\n    p = msg\n    ret\n\nmsg: db \"hi\"\n"));
    }

    private static void unreachableBlock() {
        Assert.assertEquals("block0(main) -> -  <- -\n"
                        + "block1(l0) dead -> -  <- -\n",
                shape("    ret\nL0:\n    ret\n"));
    }

    private static void entryFirst() {
        Cfg cfg = Cfg.of(parse("    jmp l0\nL0:\n    ret\n"));
        Assert.assertEquals("block0", cfg.entry().toString());
        // Reverse post-order puts the entry first, which is what the dominator
        // walk and the φ placement both read.
        Assert.assertEquals("[block0, block1]", cfg.reversePostOrder().toString());
    }

    private static void deterministic() {
        String body = "    var x: u16\n    .if x < 0\n        x = 1\n    .else\n"
                + "        x = 2\n    .endif\n    ret\n";
        Assert.assertEquals(shape(body), shape(body));
    }
}
