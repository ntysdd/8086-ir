package i8086.ssa;

import i8086.ir.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One basic block: a run of items that is entered only at its top and left only
 * at its end.
 *
 * <p>The block knows which items it holds and which blocks lead to it and out of
 * it, and nothing else. Where a value is defined and where it is live are not
 * properties of a block, so they are not stored here; a pass computes them, or
 * takes them from an analysis that did ({@code docs/ssa.md}).
 *
 * <p>There is no block without an item, and a block's first item is a label
 * unless the block is the first one and nothing names its top.
 */
public final class Block {

    private final int index;
    private final List<Item> items;
    private final List<Block> predecessors = new ArrayList<Block>();
    private final List<Block> successors = new ArrayList<Block>();

    Block(int index, List<Item> items) {
        this.index = index;
        this.items = Collections.unmodifiableList(new ArrayList<Item>(items));
    }

    /** Where this block sits among the module's blocks, in source order. */
    public int index() {
        return index;
    }

    /** The items, in the order they were written, including the label that starts it. */
    public List<Item> items() {
        return items;
    }

    /**
     * The blocks that lead here, in the order they appear in the module.
     *
     * <p>This order is what a φ's operands are in, so it is part of the SSA form
     * rather than a detail of how the graph was built ({@code docs/ssa.md}).
     */
    public List<Block> predecessors() {
        return Collections.unmodifiableList(predecessors);
    }

    /** The blocks this one leads to, in the order the terminator mentions them. */
    public List<Block> successors() {
        return Collections.unmodifiableList(successors);
    }

    /** The label that starts this block, or null when no name reaches its top. */
    public String label() {
        Item first = items.get(0);
        if (first instanceof Item.Label) {
            return ((Item.Label) first).name();
        }
        if (first instanceof Item.Data) {
            return ((Item.Data) first).label();
        }
        if (first instanceof Item.Pad) {
            return ((Item.Pad) first).label();
        }
        return null;
    }

    /**
     * The item that leaves this block — a jump, a branch, a far jump or a return — or
     * null when the block runs into the next one.
     */
    public Item terminator() {
        Item last = items.get(items.size() - 1);
        if (last instanceof Item.Jump || last instanceof Item.Branch
                || last instanceof Item.FarJump || last instanceof Item.Return) {
            return last;
        }
        return null;
    }

    /**
     * Adds an edge, once.
     *
     * <p>A branch to the label right after it and the fall-through into the same
     * label are one edge, not two: a block either leads to a place or does not,
     * and two edges to one block would be two φ operands for one arrival.
     */
    void linkTo(Block successor) {
        if (!successors.contains(successor)) {
            successors.add(successor);
            successor.predecessors.add(this);
        }
    }

    @Override
    public String toString() {
        return "block" + index;
    }
}
