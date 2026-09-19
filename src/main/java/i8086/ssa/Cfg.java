package i8086.ssa;

import i8086.ir.Item;
import i8086.ir.Module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The control flow graph of a module: its blocks, and the ways between them.
 *
 * <p>It is derived from the item list and added to nothing: a block is a run of
 * items, an edge is a jump, a branch or a fall-through, and a label starts a
 * block. The module still prints as it was written ({@code AGENTS.md},
 * invariant 5), because the graph is a reading of it rather than a rewriting.
 *
 * <p>Execution begins at the <b>first</b> block, not at the module's entry label.
 * The image starts where the first item is laid down, and the entry label is the
 * name the emitter puts first rather than a place the hardware is told about; a
 * module that puts some other label first runs the items before it. So the first
 * block is the root of everything that follows from it, and a label that nothing
 * jumps to and nothing falls into is a block nothing reaches.
 */
public final class Cfg {

    private final Module module;
    private final List<Block> blocks;
    private final List<Block> reversePostOrder;
    private final boolean[] reachable;

    private Cfg(Module module) {
        this.module = module;
        List<List<Item>> runs = split(module);
        if (runs.isEmpty()) {
            // The verifier refuses a module with no items, because its entry label
            // cannot be defined; reaching here would mean the two disagree.
            throw new IllegalStateException("a module with no items has no control flow");
        }

        List<Block> created = new ArrayList<Block>();
        for (int i = 0; i < runs.size(); i++) {
            created.add(new Block(i, runs.get(i)));
        }
        this.blocks = Collections.unmodifiableList(created);
        connect();
        this.reversePostOrder = computeReversePostOrder();
        this.reachable = new boolean[blocks.size()];
        for (Block block : reversePostOrder) {
            reachable[block.index()] = true;
        }
    }

    public static Cfg of(Module module) {
        return new Cfg(module);
    }

    /**
     * Splits the items into runs, one per block.
     *
     * <p>A block starts where a name is, where the graph is entered from outside,
     * and after everything that leaves the block: without that last rule the item
     * after a {@code ret} would be part of a block nothing can reach the end of.
     */
    private static List<List<Item>> split(Module module) {
        List<List<Item>> runs = new ArrayList<List<Item>>();
        List<Item> run = null;
        Item previous = null;
        for (Item item : module.items()) {
            if (run == null || namesSomething(item) || leaves(previous)) {
                run = new ArrayList<Item>();
                runs.add(run);
            }
            run.add(item);
            previous = item;
        }
        return runs;
    }

    /** Whether a block can be entered at this item, because it is named. */
    private static boolean namesSomething(Item item) {
        return Item.labelOf(item) != null;
    }

    /** Whether the block this item ends cannot go on to the next item. */
    private static boolean leaves(Item item) {
        return item instanceof Item.Jump || item instanceof Item.Branch
                || item instanceof Item.Return;
    }

    private void connect() {
        Map<String, Block> byLabel = new LinkedHashMap<String, Block>();
        for (Block block : blocks) {
            String label = block.label();
            if (label != null) {
                byLabel.put(label, block);
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            Block next = i + 1 < blocks.size() ? blocks.get(i + 1) : null;
            Item terminator = block.terminator();
            if (terminator instanceof Item.Jump) {
                block.linkTo(byLabel.get(((Item.Jump) terminator).target()));
            } else if (terminator instanceof Item.Branch) {
                block.linkTo(byLabel.get(((Item.Branch) terminator).target()));
                if (next != null) {
                    block.linkTo(next);
                }
            } else if (terminator == null && next != null) {
                block.linkTo(next);
            }
        }
    }

    /**
     * The blocks reachable from the first one, in reverse post-order.
     *
     * <p>The walk is iterative, and takes the successors in order, so the answer
     * depends only on the module and not on how deep it happens to nest
     * ({@code AGENTS.md}, invariant 6).
     */
    private List<Block> computeReversePostOrder() {
        List<Block> postOrder = new ArrayList<Block>();
        List<Block> stack = new ArrayList<Block>();
        List<Integer> taken = new ArrayList<Integer>();
        boolean[] seen = new boolean[blocks.size()];
        stack.add(blocks.get(0));
        taken.add(Integer.valueOf(0));
        seen[0] = true;
        while (!stack.isEmpty()) {
            int top = stack.size() - 1;
            Block block = stack.get(top);
            int next = taken.get(top).intValue();
            if (next < block.successors().size()) {
                taken.set(top, Integer.valueOf(next + 1));
                Block child = block.successors().get(next);
                if (!seen[child.index()]) {
                    seen[child.index()] = true;
                    stack.add(child);
                    taken.add(Integer.valueOf(0));
                }
            } else {
                postOrder.add(block);
                stack.remove(top);
                taken.remove(top);
            }
        }
        Collections.reverse(postOrder);
        return Collections.unmodifiableList(postOrder);
    }

    /** The module this graph was read from. */
    public Module module() {
        return module;
    }

    /** Every block, in source order. */
    public List<Block> blocks() {
        return blocks;
    }

    /** The block execution begins at: the first one. */
    public Block entry() {
        return blocks.get(0);
    }

    /** The blocks reachable from the entry, in reverse post-order. */
    public List<Block> reversePostOrder() {
        return reversePostOrder;
    }

    /** Whether anything reaches this block from the entry. */
    public boolean isReachable(Block block) {
        return reachable[block.index()];
    }
}
