package i8086.regalloc;

import i8086.CompileError;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Item;
import i8086.isel.Selection;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives every value a register, and refuses when there is none left.
 *
 * <p>The method is linear scan over a straight run of instructions, and it rests
 * on one idea: **a value's register may be reused once the value is last seen**.
 * So the instructions are walked twice — once to find where each value is first
 * and last seen, once to hand out registers, giving one back as soon as its value
 * has been left behind. Over code with no branches that interval is exact; over
 * code with branches it is widened (below).
 *
 * <p>The first idea was one register per value for its whole life, never reused.
 * It was thrown away before it was written, because it fails almost immediately:
 * {@code x + x * 4} wants a register for the temporary the multiply produces, so
 * a three-line program asks for three, and anything with two sub-expressions in
 * it runs out. Last-seen expiry costs a walk of the instructions and makes
 * ordinary code work.
 *
 * <p>Two things widen an interval beyond where a value is written and read, and
 * both are there because the machine can destroy something the linear view does
 * not see:
 *
 * <ul>
 *   <li>a value that lives across a label, because execution does not run
 *       forwards alone but the intervals do;
 *   <li>a value that lives across an inline assembly block, in the registers that
 *       block declares it destroys.
 * </ul>
 *
 * <p>What it still does not do is spill. A function that needs more registers
 * than the machine has is a **hard error**, which is the meaning
 * {@code docs/ir.md} §8.2 gives "no spill": no frame, no silent use of the stack.
 */
public final class RegisterAllocator {

    private final Target target;

    /** Which register each value ended up in. */
    private final Map<String, String> assigned = new LinkedHashMap<String, String>();

    /** The first and last instruction each value appears in. */
    private final Map<String, Integer> firstSeen = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> lastSeen = new LinkedHashMap<String, Integer>();

    /** The registers each value may not be given, because something destroys them. */
    private final Map<String, Set<String>> keepOut = new LinkedHashMap<String, Set<String>>();

    /** The instruction from which each register is free again. */
    private final Map<String, Integer> freeFrom = new LinkedHashMap<String, Integer>();

    private RegisterAllocator(Target target) {
        this.target = target;
    }

    /** The selection again, with every virtual register replaced by a real one. */
    public static Selection allocate(Selection selection, Target target) {
        return new RegisterAllocator(target).run(selection);
    }

    private Selection run(Selection selection) {
        findSpans(selection);
        if (selection.controlFlow()) {
            keepValuesThatCrossABlockAlive(selection);
        }
        keepClobbersOffLiveValues(selection);

        List<Selection.Piece> pieces = new ArrayList<Selection.Piece>();
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            List<Instruction> rewritten = new ArrayList<Instruction>();
            for (Instruction instruction : piece.instructions()) {
                rewritten.add(resolve(instruction, index));
                index++;
            }
            pieces.add(piece.with(rewritten));
        }
        return new Selection(pieces, selection.controlFlow());
    }

    /** Where each value is first and last mentioned, over the whole run. */
    private void findSpans(Selection selection) {
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            for (Instruction instruction : piece.instructions()) {
                for (Operand operand : instruction.operands()) {
                    if (operand instanceof Operand.Virtual) {
                        String name = ((Operand.Virtual) operand).name();
                        if (!firstSeen.containsKey(name)) {
                            firstSeen.put(name, Integer.valueOf(index));
                        }
                        lastSeen.put(name, Integer.valueOf(index));
                    }
                }
                index++;
            }
        }
    }

    /**
     * Stops reusing the register of anything that lives across a block boundary.
     *
     * <p>The intervals here are linear, and execution is not. A value whose life
     * spans a label is therefore given its register for the rest of the function,
     * which is enough to be right: two values that never share a register cannot
     * interfere, whatever the control flow does. Values that live entirely inside
     * one block may still share, because a block is entered at its top and runs
     * forwards.
     */
    private void keepValuesThatCrossABlockAlive(Selection selection) {
        int total = selection.instructions().size();
        List<Integer> boundaries = new ArrayList<Integer>();
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            if (piece.item() instanceof Item.Label) {
                boundaries.add(Integer.valueOf(index));
            }
            index += piece.instructions().size();
        }

        for (Map.Entry<String, Integer> entry : firstSeen.entrySet()) {
            int from = entry.getValue().intValue();
            int to = lastSeen.get(entry.getKey()).intValue();
            for (Integer boundary : boundaries) {
                if (from < boundary.intValue() && boundary.intValue() <= to) {
                    lastSeen.put(entry.getKey(), Integer.valueOf(total));
                    break;
                }
            }
        }
    }

    /**
     * Keeps a value out of the registers an inline block destroys while it lives.
     *
     * <p>A clobber list is a promise that those registers are the ones the block
     * destroys and no others. Keeping it means a value that is still to be read
     * after the block may not be living in one of them, so every register named by
     * any block the value lives across is struck off its list of candidates
     * ({@code docs/ir.md} §9).
     *
     * <p>A value written after the block is not affected, and neither is one whose
     * last read is before it: a block cannot destroy what is not there. What this
     * cannot see is a value the block <em>reads</em>, because a block does not
     * declare its inputs yet, and that is why the conservative reading in
     * {@code docs/ir.md} §2.3 exists.
     */
    private void keepClobbersOffLiveValues(Selection selection) {
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            int at = index;
            index += piece.instructions().size();
            if (!(piece.item() instanceof Item.InlineAsm) || piece.instructions().isEmpty()) {
                continue;
            }
            for (String destroyed : ((Item.InlineAsm) piece.item()).clobbers()) {
                if (!target.isRegister(destroyed)) {
                    // 'flags' is destroyed by the block too, and is not a value
                    // register, so there is no candidate to strike off.
                    continue;
                }
                for (Map.Entry<String, Integer> entry : firstSeen.entrySet()) {
                    String name = entry.getKey();
                    if (entry.getValue().intValue() <= at
                            && at <= lastSeen.get(name).intValue()) {
                        Set<String> forbidden = keepOut.get(name);
                        if (forbidden == null) {
                            forbidden = new LinkedHashSet<String>();
                            keepOut.put(name, forbidden);
                        }
                        forbidden.add(destroyed);
                    }
                }
            }
        }
    }

    private Instruction resolve(Instruction instruction, int index) {
        List<Operand> operands = new ArrayList<Operand>();
        for (Operand operand : instruction.operands()) {
            operands.add(operand instanceof Operand.Virtual
                    ? ((Operand.Virtual) operand).resolvedTo(registerFor((Operand.Virtual) operand,
                            index))
                    : operand);
        }
        return new Instruction(instruction.position(), instruction.mnemonic(), operands);
    }

    /**
     * The register a value lives in, giving it one the first time it is seen.
     *
     * <p>A value is handed back only after the instruction that last mentions it
     * has gone by, so every operand of one instruction is resolved while the
     * registers its operands already hold are still theirs.
     */
    private String registerFor(Operand.Virtual operand, int index) {
        String name = operand.name();
        String register = assigned.get(name);
        if (register != null) {
            return register;
        }

        Integer death = lastSeen.get(name);
        int diesAt = death == null ? index : death.intValue();
        Set<String> forbidden = keepOut.get(name);

        String taken = null;
        for (String candidate : target.valueRegisters()) {
            if (forbidden != null && forbidden.contains(candidate)) {
                continue;
            }
            Integer free = freeFrom.get(candidate);
            // Free *from* this instruction, so usable at it: the value that was
            // here died strictly earlier, or two operands of one instruction
            // could end up sharing a register and an `add` would become `add y, y`.
            if (free == null || free.intValue() <= index) {
                taken = candidate;
                break;
            }
        }
        if (taken == null) {
            throw new CompileError(operand.position(),
                    "there is no register left for '" + name + "'"
                            + (forbidden == null ? "" : ", because an inline assembly block it "
                            + "lives across destroys " + forbidden)
                            + ": this allocation does not spill, because a program that needs "
                            + "more registers than the machine has is refused rather than given "
                            + "a frame (docs/ir.md §8.2)");
        }
        assigned.put(name, taken);
        freeFrom.put(taken, Integer.valueOf(diesAt == Integer.MAX_VALUE ? diesAt : diesAt + 1));
        return taken;
    }
}
