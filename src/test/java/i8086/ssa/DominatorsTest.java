package i8086.ssa;

import i8086.testing.Assert;
import i8086.testing.Suite;

import java.util.List;
import java.util.Set;

/**
 * Tests for dominance and the dominance frontier.
 *
 * <p>Two shapes carry most of the weight, and they are the two SSA construction
 * turns on: a diamond, where the join is in the frontier of both arms, and a loop,
 * where the block the loop returns to is in the frontier of the body — and, if the
 * body is inside it, in its own frontier too, which is what makes the φ walk carry
 * on past a loop.
 */
public final class DominatorsTest {

    private DominatorsTest() {
    }

    public static void register(Suite suite) {
        suite.add("Dominators know nothing dominates the entry", DominatorsTest::entryHasNone);
        suite.add("Dominators put a straight line in a chain", DominatorsTest::straightLine);
        suite.add("Dominators meet at a diamond", DominatorsTest::diamond);
        suite.add("Dominators find the loop frontier", DominatorsTest::loop);
        suite.add("Dominators say a block nothing reaches has no dominator",
                DominatorsTest::unreachable);
        suite.add("Dominators dominate reflexively and not sideways", DominatorsTest::dominates);
    }

    /** One line per block: its dominator, its frontier, and the blocks it dominates. */
    private static String describe(String body) {
        Cfg cfg = Cfg.of(CfgTest.parse(body));
        Dominators dominators = Dominators.of(cfg);
        StringBuilder text = new StringBuilder();
        for (Block block : cfg.blocks()) {
            text.append(block);
            if (block.label() != null) {
                text.append('(').append(block.label()).append(')');
            }
            text.append(": idom ").append(dominators.idomOf(block));
            text.append(", frontier");
            append(text, dominators.frontierOf(block));
            text.append(", children");
            append(text, dominators.childrenOf(block));
            text.append('\n');
        }
        return text.toString();
    }

    private static void append(StringBuilder text, Set<Block> blocks) {
        append(text, new java.util.ArrayList<Block>(blocks));
    }

    private static void append(StringBuilder text, List<Block> blocks) {
        if (blocks.isEmpty()) {
            text.append(" -");
            return;
        }
        for (Block block : blocks) {
            text.append(' ').append(block);
        }
    }

    private static void entryHasNone() {
        Cfg cfg = Cfg.of(CfgTest.parse("    var x: u16\n    x = 1\n    ret\n"));
        Assert.assertNull(Dominators.of(cfg).idomOf(cfg.entry()),
                "the entry block dominates everything and nothing dominates it");
    }

    private static void straightLine() {
        Assert.assertEquals("block0(main): idom null, frontier -, children block1\n"
                        + "block1(l0): idom block0, frontier -, children block2\n"
                        + "block2(l1): idom block1, frontier -, children -\n",
                describe("    var x: u16\n    x = 1\nL0:\n    x = 2\nL1:\n    ret\n"));
    }

    private static void diamond() {
        // The join is in the frontier of both arms, because each arm reaches the
        // join and neither dominates it.
        Assert.assertEquals("block0(main): idom null, frontier -, children block1 block2 block3\n"
                        + "block1: idom block0, frontier block3, children -\n"
                        + "block2(..@lbl0): idom block0, frontier block3, children -\n"
                        + "block3(..@lbl1): idom block0, frontier -, children -\n",
                describe("    var x: u16\n    x = 0\n    .if x < 0\n        x = 1\n    .else\n"
                        + "        x = 2\n    .endif\n    ret\n"));
    }

    private static void loop() {
        // The body sits inside the test block, so the test block is in the body's
        // frontier — and in its own, because the body reaches it and it does not
        // strictly dominate itself. That second entry is what makes the φ walk
        // carry on past a loop.
        Assert.assertEquals("block0(main): idom null, frontier -, children block2\n"
                        + "block1(..@lbl0): idom block2, frontier block2, children -\n"
                        + "block2(..@lbl1): idom block0, frontier block2,"
                        + " children block1 block3\n"
                        + "block3: idom block2, frontier -, children -\n",
                describe("    var x: u16\n    var n: u16\n    x = 0\n    n = 3\n"
                        + "    .while x < n\n        x = eval(x + 1)\n    .endw\n    ret\n"));
    }

    private static void unreachable() {
        Cfg cfg = Cfg.of(CfgTest.parse("    ret\nL0:\n    ret\n"));
        Dominators dominators = Dominators.of(cfg);
        Block dead = cfg.blocks().get(1);
        Assert.assertFalse(cfg.isReachable(dead), "nothing reaches the second block");
        Assert.assertNull(dominators.idomOf(dead),
                "a block nothing reaches is in no dominator tree");
        Assert.assertFalse(dominators.dominates(cfg.entry(), dead),
                "there is no path for the entry to be on");
    }

    private static void dominates() {
        Cfg cfg = Cfg.of(CfgTest.parse("    var x: u16\n    x = 1\nL0:\n    x = 2\nL1:\n    ret\n"));
        Dominators dominators = Dominators.of(cfg);
        Block first = cfg.blocks().get(0);
        Block second = cfg.blocks().get(1);
        Block third = cfg.blocks().get(2);
        Assert.assertTrue(dominators.dominates(first, first), "a block dominates itself");
        Assert.assertTrue(dominators.dominates(first, third), "the entry dominates what follows");
        Assert.assertTrue(dominators.dominates(second, third), "and so does the block before it");
        Assert.assertFalse(dominators.dominates(third, second),
                "a block later in the chain does not dominate one before it");
        Assert.assertTrue(dominators.dominates(first, second), "the entry reaches the second block");
    }
}
