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

    /**
     * The first and last instruction each value appears in.
     *
     * <p>Keyed by the name that stands for a <em>group</em> of names rather than by a name
     * of its own, because names that have to share a register are one life: see
     * {@link #groupNames}. Everything downstream of here — what a value may not be given,
     * whether it is an address, when its register is free again — is about the group for
     * the same reason.
     */
    private final Map<String, Integer> firstSeen = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> lastSeen = new LinkedHashMap<String, Integer>();

    /** The registers each value may not be given, because something destroys them. */
    private final Map<String, Set<String>> keepOut = new LinkedHashMap<String, Set<String>>();

    /** The values that are used as an address somewhere, and so need one of those registers. */
    private final Set<String> addresses = new LinkedHashSet<String>();

    /** Where each name is first and last mentioned, before the groups are worked out. */
    private final Map<String, Integer> firstMention = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> lastMention = new LinkedHashMap<String, Integer>();

    /** The names used as an address, before the groups are worked out. */
    private final Set<String> addressNames = new LinkedHashSet<String>();

    /** Which name stands for each name's group: see {@link #groupNames}. */
    private final Map<String, String> groupOf = new LinkedHashMap<String, String>();

    /** The instruction from which each register is free again. */
    private final Map<String, Integer> freeFrom = new LinkedHashMap<String, Integer>();

    /** The selection being allocated, for the one thing a refusal has to say out loud. */
    private Selection selection;

    private RegisterAllocator(Target target) {
        this.target = target;
    }

    /** The selection again, with every virtual register replaced by a real one. */
    public static Selection allocate(Selection selection, Target target) {
        return new RegisterAllocator(target).run(selection);
    }

    private Selection run(Selection selection) {
        this.selection = selection;
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
     *
     * <p>The mentions are collected per name and then folded into groups, because
     * turning two names into one life is a question about mentions: a copy joins two
     * names when the source is last mentioned at the copy itself.
     */
    private void findSpans(Selection selection) {
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            for (Instruction instruction : piece.instructions()) {
                for (String name : mentioned(instruction)) {
                    if (!firstMention.containsKey(name)) {
                        firstMention.put(name, Integer.valueOf(index));
                    }
                    lastMention.put(name, Integer.valueOf(index));
                }
                for (Operand operand : instruction.operands()) {
                    if (operand instanceof Operand.Memory) {
                        for (Operand.Memory.Atom atom : ((Operand.Memory) operand).atoms()) {
                            if (atom.isVirtual()) {
                                addressNames.add(atom.name());
                            }
                        }
                    }
                }
                index++;
            }
        }
        groupNames(selection);
    }

    /**
     * Works out which names share a register, and spans each group of them.
     *
     * <p>Two things say two names are one life, and both are read off the program rather
     * than guessed at.
     *
     * <p>A φ says it outright. There is no copy at a merge — that is what
     * {@code docs/ssa.md} §8 is about — so the register <em>is</em> what carries the value
     * along each path, and the values a φ joins have to be in it. Selection read the φ's
     * and passed them on, because that is a fact about the stream and not something the
     * allocator can see.
     *
     * <p>A copy says it when the value being copied dies there: {@code mov d, s} with
     * nothing left to read of {@code s} afterwards. Then {@code d} may as well <em>be</em>
     * {@code s}: the copy becomes a register moved into itself, and the ordinary rule that
     * drops those ({@link #isSelfCopy}) takes it away. That is what keeps a two-address
     * machine from paying for a copy per operation — {@code x = eval(x + 1)} is one
     * instruction and not two.
     *
     * <p>Everything else is left alone, and that is the whole of what this buys over
     * renaming every version back to its variable: two versions of one variable that
     * neither meet at a φ nor are a copy of each other are two values, and making them one
     * register costs a register and buys nothing.
     */
    private void groupNames(Selection selection) {
        for (List<String> names : selection.registerGroups()) {
            for (String name : names) {
                join(names.get(0), name);
            }
        }
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            for (Instruction instruction : piece.instructions()) {
                if (isCopy(instruction)) {
                    String destination = ((Operand.Virtual) instruction.operands().get(0)).name();
                    String source = ((Operand.Virtual) instruction.operands().get(1)).name();
                    Integer last = lastMention.get(source);
                    if (last != null && last.intValue() == index) {
                        join(destination, source);
                    }
                }
                index++;
            }
        }

        // Two passes, because the two answers come from two tables: a group's first mention
        // is the earliest of its names' first mentions, and its last is the latest of their
        // last ones. Reading both out of the first-mention table would give every group the
        // span of its earliest name, and a value would look dead while it is still to be
        // read — which is the one mistake here that is not merely a worse allocation.
        for (Map.Entry<String, Integer> entry : firstMention.entrySet()) {
            String name = groupOf(entry.getKey());
            Integer first = firstSeen.get(name);
            if (first == null || entry.getValue().intValue() < first.intValue()) {
                firstSeen.put(name, entry.getValue());
            }
            if (addressNames.contains(entry.getKey())) {
                addresses.add(name);
            }
        }
        for (Map.Entry<String, Integer> entry : lastMention.entrySet()) {
            String name = groupOf(entry.getKey());
            Integer last = lastSeen.get(name);
            if (last == null || entry.getValue().intValue() > last.intValue()) {
                lastSeen.put(name, entry.getValue());
            }
        }
    }

    /**
     * A copy of one value into another: the machine's own way of writing {@code d = s}.
     *
     * <p>Both ends have to be values for the question to mean anything. The other shape
     * {@code mov} takes is a fixed register the selector chose — {@code mov cl, 8},
     * {@code mov ax, x} before a multiply — and there one end is the target's, so there is
     * nothing to join.
     */
    private static boolean isCopy(Instruction instruction) {
        return instruction.mnemonic().equals("mov")
                && instruction.operands().size() == 2
                && instruction.operands().get(0) instanceof Operand.Virtual
                && instruction.operands().get(1) instanceof Operand.Virtual;
    }

    /**
     * Puts two names in one group, named after whichever of them is mentioned first.
     *
     * <p>The group is named after the earlier name so that the answer is a property of the
     * program and not of the order the groups happened to be built in
     * ({@code AGENTS.md}, invariant 6). Joining is written out rather than chased through a
     * parent chain: a group is small, and a table that always points straight at its own
     * representative is one lookup everywhere else.
     */
    private void join(String one, String other) {
        String first = groupOf(one);
        String second = groupOf(other);
        if (first.equals(second)) {
            return;
        }
        Integer firstAt = firstMention.get(first);
        Integer secondAt = firstMention.get(second);
        boolean secondIsEarlier = firstAt != null && secondAt != null
                && secondAt.intValue() < firstAt.intValue();
        String keep = secondIsEarlier ? second : first;
        String drop = secondIsEarlier ? first : second;
        groupOf.put(drop, keep);
        for (Map.Entry<String, String> entry : groupOf.entrySet()) {
            if (entry.getValue().equals(drop)) {
                entry.setValue(keep);
            }
        }
    }

    /** The name that stands for this name's group. */
    private String groupOf(String name) {
        String found = groupOf.get(name);
        return found == null ? name : found;
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
            // An item that declares what it destroys: a block, and a machine statement
            // such as an interrupt. Both mean the same thing by the list, and the
            // declaration is the authority over what the target would assume.
            boolean opaque = piece.item() instanceof Item.InlineAsm
                    || piece.item() instanceof Item.Machine;
            Set<String> declared = new LinkedHashSet<String>();
            if (piece.item() instanceof Item.InlineAsm) {
                for (String destroyed : ((Item.InlineAsm) piece.item()).clobbers()) {
                    if (target.isRegister(destroyed)) {
                        declared.add(destroyed);
                    }
                }
            } else if (piece.item() instanceof Item.Machine) {
                for (String destroyed : ((Item.Machine) piece.item()).clobbers()) {
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
        if (instruction.isLabel()) {
            // A label inside a block has no operands and no registers to decide, and
            // rebuilding it would drop the one thing it carries (docs/ir.md §9).
            return instruction;
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
        // The group, not the name: names that have to share a register are one life, and
        // a life is what a register is given to.
        String group = groupOf(name);
        String register = assigned.get(group);
        if (register != null) {
            return register;
        }

        Integer death = lastSeen.get(group);
        int diesAt = death == null ? index : death.intValue();
        Set<String> forbidden = keepOut.get(group);
        List<String> usable = addresses.contains(group) ? target.addressRegisters()
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
                    "there is no register left for '" + selection.variableOf(name) + "'"
                            + (addresses.contains(group)
                            ? ", which is used as an address and so can only live in one of "
                            + target.addressRegisters()
                            : "")
                            + (forbidden == null ? "" : ", because something it has to live "
                            + "across destroys " + forbidden
                            + "; say what it really destroys with 'clobbers(...)', or write the "
                            + "value to memory and read it into a name of its own afterwards — "
                            + "this value is still to be read after the call, so it has to be in "
                            + "a register the call keeps")
                            + ": this allocation does not spill, because a program that needs "
                            + "more registers than the machine has is refused rather than given "
                            + "a frame (docs/ir.md §8.2)");
        }
        assigned.put(group, taken);
        freeFrom.put(taken, Integer.valueOf(diesAt == Integer.MAX_VALUE ? diesAt : diesAt + 1));
        return taken;
    }
}
