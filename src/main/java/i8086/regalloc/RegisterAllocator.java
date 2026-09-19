package i8086.regalloc;

import i8086.CompileError;
import i8086.SourcePos;
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

    /** The values that are used as an address somewhere, and so need one of those registers. */
    private final Set<String> addresses = new LinkedHashSet<String>();

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
                Instruction resolved = resolve(instruction, index);
                if (!isSelfCopy(resolved)) {
                    rewritten.add(resolved);
                }
                index++;
            }
            pieces.add(piece.with(rewritten));
        }
        return new Selection(pieces, selection.controlFlow());
    }

    /**
     * Where each value is first and last mentioned, over the whole run, and which
     * values are used as an address.
     *
     * <p>A value inside the brackets of a memory operand is mentioned like any other
     * operand, and it is the one place where what a value <em>is</em> changes where
     * it may live: an address has three registers to choose from and not six, because
     * {@code [ax]} is not something this machine can say.
     */
    private void findSpans(Selection selection) {
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            for (Instruction instruction : piece.instructions()) {
                for (String name : mentioned(instruction)) {
                    if (!firstSeen.containsKey(name)) {
                        firstSeen.put(name, Integer.valueOf(index));
                    }
                    lastSeen.put(name, Integer.valueOf(index));
                }
                for (Operand operand : instruction.operands()) {
                    if (operand instanceof Operand.Memory) {
                        for (Operand.Memory.Atom atom : ((Operand.Memory) operand).atoms()) {
                            if (atom.isVirtual()) {
                                addresses.add(atom.name());
                            }
                        }
                    }
                }
                index++;
            }
        }
    }

    /**
     * Every value an instruction mentions, brackets and all.
     *
     * <p>A memory operand holds names rather than operands, so walking
     * {@code operands()} alone would miss the address a load reads through — and a
     * value no walk sees is a value with no register and no interval.
     */
    private static List<String> mentioned(Instruction instruction) {
        List<String> names = new ArrayList<String>();
        for (Operand operand : instruction.operands()) {
            if (operand instanceof Operand.Virtual) {
                names.add(((Operand.Virtual) operand).name());
            } else if (operand instanceof Operand.Memory) {
                for (Operand.Memory.Atom atom : ((Operand.Memory) operand).atoms()) {
                    if (atom.isVirtual()) {
                        names.add(atom.name());
                    }
                }
            }
        }
        return names;
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
     * Keeps a value out of the registers something destroys while it lives.
     *
     * <p>Two things destroy registers without a value being written there. An inline
     * assembly block declares the registers it destroys and nothing else, because the
     * compiler cannot see inside it. And an instruction can destroy one itself —
     * {@code mov cl, 4} writes a register no value was given, {@code mul} leaves half
     * its answer in {@code dx} — which is the target's to say and nobody else's to
     * guess.
     *
     * <p>A value is affected when the destruction happens inside its life: written
     * before it and read after it. A value written afterwards is not there yet, and
     * one whose last read is before is gone already — a block cannot destroy what is
     * not there, which is why this is not simply "no value may live in these
     * registers".
     *
     * <p>What this cannot see is a value the instruction <em>reads</em>, because an
     * operand that has to be in a particular register is not expressible yet: that is
     * the other half of {@code docs/ir.md} §12 item 12, and it is what {@code mul}
     * still needs before it can be selected at all.
     */
    private void keepClobbersOffLiveValues(Selection selection) {
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            boolean opaque = piece.item() instanceof Item.InlineAsm;
            Set<String> declared = new LinkedHashSet<String>();
            if (opaque) {
                for (String destroyed : ((Item.InlineAsm) piece.item()).clobbers()) {
                    if (target.isRegister(destroyed)) {
                        declared.add(destroyed);
                    }
                }
            }
            for (Instruction instruction : piece.instructions()) {
                Set<String> destroyed = new LinkedHashSet<String>(declared);
                destroyed.addAll(target.clobbers(instruction));
                if (!destroyed.isEmpty()) {
                    keepValuesOff(destroyed, index);
                }
                index++;
            }
        }
    }

    /** Stops every value alive at this instruction from living in one of these registers. */
    private void keepValuesOff(Set<String> destroyed, int index) {
        for (Map.Entry<String, Integer> entry : firstSeen.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue().intValue() <= index && index <= lastSeen.get(name).intValue()) {
                Set<String> forbidden = keepOut.get(name);
                if (forbidden == null) {
                    forbidden = new LinkedHashSet<String>();
                    keepOut.put(name, forbidden);
                }
                forbidden.addAll(destroyed);
            }
        }
    }

    /**
     * Whether an instruction is a copy of a register into itself.
     *
     * <p>A sequence the target handed over has to name the registers the machine
     * insists on — {@code mov ax, x} before a multiply — and whether that copy is
     * needed is a question about allocation. If {@code x} was given {@code ax}, the
     * copy says nothing and goes; if it was not, the copy is what makes the sequence
     * work. The same rule catches the copy back out when the answer already belongs in
     * {@code ax}.
     *
     * <p>Nothing is lost by dropping one: moving a register into itself changes
     * neither the register nor the flags.
     */
    private static boolean isSelfCopy(Instruction instruction) {
        if (!instruction.mnemonic().equals("mov") || instruction.operands().size() != 2) {
            return false;
        }
        Operand destination = instruction.operands().get(0);
        Operand source = instruction.operands().get(1);
        return destination instanceof Operand.Name && source instanceof Operand.Name
                && ((Operand.Name) destination).name().equals(((Operand.Name) source).name());
    }

    /**
     * Rewrites an instruction with every value replaced by the register it lives in.
     *
     * <p>The names inside a memory operand are resolved too: an address is a value
     * like any other, and by the time an instruction is printed there is no such
     * thing as a name that has not been decided. A name that is not one of the
     * module's values is a label, and a label is not a register — it is left as it
     * is, for the assembler.
     */
    private Instruction resolve(Instruction instruction, int index) {
        List<Operand> operands = new ArrayList<Operand>();
        for (Operand operand : instruction.operands()) {
            if (operand instanceof Operand.Virtual) {
                operands.add(((Operand.Virtual) operand)
                        .resolvedTo(registerFor((Operand.Virtual) operand, index)));
            } else if (operand instanceof Operand.Memory) {
                operands.add(resolve((Operand.Memory) operand, index));
            } else {
                operands.add(operand);
            }
        }
        return new Instruction(instruction.position(), instruction.mnemonic(), operands);
    }

    private Operand resolve(Operand.Memory operand, int index) {
        List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
        for (Operand.Memory.Atom atom : operand.atoms()) {
            if (!atom.isVirtual()) {
                // A number, or a name the assembler owns: neither is ours to give a
                // register to.
                atoms.add(atom);
            } else {
                atoms.add(Operand.Memory.Atom.ofName(
                        registerFor(atom.name(), operand.position(), index)));
            }
        }
        return new Operand.Memory(operand.position(), operand.size(), operand.segment(), atoms);
    }

    /**
     * The register a value lives in, giving it one the first time it is seen.
     *
     * <p>A value is handed back only after the instruction that last mentions it
     * has gone by, so every operand of one instruction is resolved while the
     * registers its operands already hold are still theirs.
     */
    private String registerFor(Operand.Virtual operand, int index) {
        return registerFor(operand.name(), operand.position(), index);
    }

    private String registerFor(String name, SourcePos where, int index) {
        String register = assigned.get(name);
        if (register != null) {
            return register;
        }

        Integer death = lastSeen.get(name);
        int diesAt = death == null ? index : death.intValue();
        Set<String> forbidden = keepOut.get(name);
        List<String> usable = addresses.contains(name) ? target.addressRegisters()
                : target.valueRegisters();

        String taken = null;
        for (String candidate : usable) {
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
            throw new CompileError(where,
                    "there is no register left for '" + name + "'"
                            + (addresses.contains(name)
                            ? ", which is used as an address and so can only live in one of "
                            + target.addressRegisters()
                            : "")
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
