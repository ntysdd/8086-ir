package i8086.ssa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which blocks dominate which, and the dominance frontier.
 *
 * <p>Dominance is the question SSA construction is made of: a value defined in
 * one place is usable in another exactly when the first dominates the second, and
 * the places where a value gains a second definition are the dominance frontier
 * of where it was defined ({@code docs/ssa.md}).
 *
 * <p>The immediate dominators are computed by the iterative method — start every
 * block off with its first predecessor and intersect until nothing moves — and
 * the frontier is then read off the dominator tree, which is the same answer with
 * less machinery than computing frontiers from scratch.
 *
 * <p>Only blocks the entry reaches have dominators. A block nothing reaches has
 * no path to be on, so it is dominated by nothing but itself; asking about it
 * answers false rather than inventing a tree it is not in.
 */
public final class Dominators {

    private final Cfg cfg;
    private final Block[] idom;
    private final int[] rank;
    private final List<List<Block>> frontier;
    private final List<List<Block>> children;

    private Dominators(Cfg cfg) {
        this.cfg = cfg;
        int count = cfg.blocks().size();
        this.idom = new Block[count];
        this.rank = new int[count];
        this.frontier = new ArrayList<List<Block>>();
        this.children = new ArrayList<List<Block>>();
        for (int i = 0; i < count; i++) {
            rank[i] = -1;
            frontier.add(new ArrayList<Block>());
            children.add(new ArrayList<Block>());
        }

        List<Block> order = cfg.reversePostOrder();
        for (int i = 0; i < order.size(); i++) {
            rank[order.get(i).index()] = i;
        }
        computeIdoms(order);
        computeChildren();
        computeFrontier();
    }

    public static Dominators of(Cfg cfg) {
        return new Dominators(cfg);
    }

    /**
     * The immediate dominator of every block, by intersecting predecessors until
     * a fixed point is reached.
     *
     * <p>A block is processed in reverse post-order, which is what makes the
     * iteration converge instead of merely terminating: a block's dominator comes
     * before it in that order, so the answer is built from answers that are
     * already there. Every iteration visits the blocks in the same order, so the
     * result does not depend on how the sets happen to be laid out.
     */
    private void computeIdoms(List<Block> order) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 1; i < order.size(); i++) {
                Block block = order.get(i);
                Block newIdom = null;
                for (Block predecessor : block.predecessors()) {
                    if (rank[predecessor.index()] < 0) {
                        // An unreachable predecessor says nothing about where the
                        // paths that do arrive came from.
                        continue;
                    }
                    newIdom = newIdom == null ? predecessor
                            : intersect(predecessor, newIdom);
                }
                if (newIdom != null && idom[block.index()] != newIdom) {
                    idom[block.index()] = newIdom;
                    changed = true;
                }
            }
        }
    }

    /** The closest common dominator of two blocks, by walking both up together. */
    private Block intersect(Block left, Block right) {
        Block a = left;
        Block b = right;
        while (a != b) {
            while (rank[a.index()] > rank[b.index()]) {
                a = up(a);
            }
            while (rank[b.index()] > rank[a.index()]) {
                b = up(b);
            }
        }
        return a;
    }

    /**
     * One step up the dominator tree.
     *
     * <p>A block whose dominator is not worked out yet is treated as hanging off
     * the root, which is what it is until the fixed point places it. That keeps
     * the walk total instead of needing a null check in the middle of an
     * intersection.
     */
    private Block up(Block block) {
        Block parent = idom[block.index()];
        return parent == null ? cfg.entry() : parent;
    }

    private void computeChildren() {
        for (Block block : cfg.blocks()) {
            Block parent = idom[block.index()];
            if (parent != null && parent != block) {
                children.get(parent.index()).add(block);
            }
        }
    }

    /**
     * The dominance frontier, read off the tree.
     *
     * <p>A block is in the frontier of X when X dominates one of its predecessors
     * without dominating the block itself: that is where two definitions of one
     * value meet and a φ has to merge them. Walking from each predecessor up to
     * the block's own dominator visits exactly the blocks with that property, and
     * the walk includes the root when the block has no dominator at all, which is
     * how a loop back to the entry block gets its φ.
     */
    private void computeFrontier() {
        for (Block block : cfg.blocks()) {
            if (!cfg.isReachable(block)) {
                continue;
            }
            Block stop = idom[block.index()];
            for (Block predecessor : block.predecessors()) {
                if (!cfg.isReachable(predecessor)) {
                    continue;
                }
                Block runner = predecessor;
                while (runner != null && runner != stop) {
                    frontier.get(runner.index()).add(block);
                    if (runner == cfg.entry()) {
                        break;
                    }
                    runner = idom[runner.index()];
                }
            }
        }
    }

    /** The block that dominates this one and is closest to it, or null. */
    public Block idomOf(Block block) {
        Block parent = idom[block.index()];
        return parent == block ? null : parent;
    }

    /** The blocks dominated by this one and by nothing closer, in source order. */
    public List<Block> childrenOf(Block block) {
        return Collections.unmodifiableList(children.get(block.index()));
    }

    /** The blocks where this one's dominance ends: a value defined here may need a φ. */
    public Set<Block> frontierOf(Block block) {
        return Collections.unmodifiableSet(
                new LinkedHashSet<Block>(frontier.get(block.index())));
    }

    /**
     * Whether every path from the entry to {@code block} goes through {@code other}.
     *
     * <p>A block is dominated by itself. A block nothing reaches is dominated by
     * nothing but itself, because there is no path to be on.
     */
    public boolean dominates(Block other, Block block) {
        if (other == block) {
            return true;
        }
        if (!cfg.isReachable(block)) {
            return false;
        }
        Block walk = block;
        while (walk != null) {
            if (walk == other) {
                return true;
            }
            if (walk == cfg.entry()) {
                return false;
            }
            walk = idom[walk.index()];
        }
        return false;
    }
}
