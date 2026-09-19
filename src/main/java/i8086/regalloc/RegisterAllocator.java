package i8086.regalloc;

import i8086.CompileError;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.isel.Selection;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives every value a register, and refuses when there is none left.
 *
 * <p>The method is linear scan over a straight run of instructions, and it rests
 * on one idea: **a value's register may be reused once the value is last seen**.
 * So the instructions are walked twice — once to find where each value is last
 * seen, once to hand out registers, giving one back as soon as its value has
 * been left behind. Over code with no branches that interval is exact, and the
 * code this runs on has no branches yet, so this is not an approximation.
 *
 * <p>The first idea was one register per value for its whole life, never reused.
 * It was thrown away before it was written, because it fails almost immediately:
 * {@code x + x * 4} wants a register for the temporary the multiply produces, so
 * a three-line program asks for three, and anything with two sub-expressions in
 * it runs out. Last-seen expiry costs a walk of the instructions and makes
 * ordinary code work.
 *
 * <p>What it still does not do is spill. A function that needs more registers
 * than the machine has is a **hard error**, which is the meaning {@code
 * docs/ir.md} §8.2 gives "no spill": no frame, no silent use of the stack.
 */
public final class RegisterAllocator {

    private final Target target;

    /** Which register each value ended up in. */
    private final Map<String, String> assigned = new LinkedHashMap<String, String>();

    /** The last instruction each value appears in. */
    private final Map<String, Integer> lastSeen = new LinkedHashMap<String, Integer>();

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
        lastSeen.putAll(lastSeen(selection));
        if (selection.controlFlow()) {
            keepValuesThatCrossABlockAlive(selection);
        }

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
        Map<String, Integer> first = new LinkedHashMap<String, Integer>();
        List<Integer> boundaries = new ArrayList<Integer>();

        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            if (piece.item() instanceof i8086.ir.Item.Label) {
                boundaries.add(Integer.valueOf(index));
            }
            for (Instruction instruction : piece.instructions()) {
                for (Operand operand : instruction.operands()) {
                    if (operand instanceof Operand.Virtual) {
                        String name = ((Operand.Virtual) operand).name();
                        if (!first.containsKey(name)) {
                            first.put(name, Integer.valueOf(index));
                        }
                    }
                }
                index++;
            }
        }

        for (Map.Entry<String, Integer> entry : first.entrySet()) {
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

    /** Where each value appears for the last time, over the whole run. */
    private static Map<String, Integer> lastSeen(Selection selection) {
        Map<String, Integer> seen = new LinkedHashMap<String, Integer>();
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            for (Instruction instruction : piece.instructions()) {
                for (Operand operand : instruction.operands()) {
                    if (operand instanceof Operand.Virtual) {
                        seen.put(((Operand.Virtual) operand).name(), Integer.valueOf(index));
                    }
                }
                index++;
            }
        }
        return seen;
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

        String taken = null;
        for (String candidate : target.valueRegisters()) {
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
                    "there is no register left for '" + name + "': this allocation does not "
                            + "spill, because a program that needs more registers than the "
                            + "machine has is refused rather than given a frame "
                            + "(docs/ir.md §8.2)");
        }
        assigned.put(name, taken);
        freeFrom.put(taken, Integer.valueOf(diesAt == Integer.MAX_VALUE ? diesAt : diesAt + 1));
        return taken;
    }
}
