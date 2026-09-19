package i8086.regalloc;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Item;
import i8086.isel.Selection;
import i8086.ssa.Effects;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives every value a register, and refuses when there is none left.
 *
 * <p>The method is colouring: a value is a node, two values that are alive at the same
 * point cannot share a register, and the registers are the colours. So the question "does
 * this program fit in six registers" is the question "is this graph six-colourable", and
 * the answer is not a guess — see {@link #order}, where the ordering that makes the greedy
 * pass below optimal comes from.
 *
 * <p>What makes the graph small enough to be exact is that it is built over values the
 * form named, not over names a person wrote: see {@link #groupNames} for the two things
 * that make two names one value, and {@link Liveness} for what alive means. What is left
 * over is everything the old linear scan had to approximate — a value's life used to be
 * the span between its first and last mention, and every value that crossed a label was
 * kept alive to the end of the function because a straight line cannot see a loop. There
 * is no span and no widening here.
 *
 * <p>What it still does not do is spill. A graph that cannot be coloured is a **hard
 * error**, which is the meaning {@code docs/ir.md} §8.2 gives "no spill": no frame, no
 * silent use of the stack.
 */
public final class RegisterAllocator {

    private final Target target;

    /** The selection being allocated, for the one thing a refusal has to say out loud. */
    private Selection selection;

    /** Which name stands for each name's group: see {@link #groupNames}. */
    private final Map<String, String> groupOf = new LinkedHashMap<String, String>();

    /** Where each name is first and last mentioned, before the groups are worked out. */
    private final Map<String, Integer> firstMention = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> lastMention = new LinkedHashMap<String, Integer>();

    /** Where each name was written, for a refusal that has to point at one. */
    private final Map<String, SourcePos> positions = new LinkedHashMap<String, SourcePos>();

    /** The names used as an address, before the groups are worked out. */
    private final Set<String> addressNames = new LinkedHashSet<String>();

    /** The values to give registers to, in the order they were first seen. */
    private final List<String> values = new ArrayList<String>();

    /** The values that are used as an address somewhere, and so need one of those registers. */
    private final Set<String> addresses = new LinkedHashSet<String>();

    /** Who cannot share a register with whom. Symmetric, and complete for both ends. */
    private final Map<String, Set<String>> interferes = new LinkedHashMap<String, Set<String>>();

    /** The registers each value may not be given, because something destroys them. */
    private final Map<String, Set<String>> keepOut = new LinkedHashMap<String, Set<String>>();

    /** Which register each value ended up in. */
    private final Map<String, String> assigned = new LinkedHashMap<String, String>();

    private RegisterAllocator(Target target) {
        this.target = target;
    }

    /** The selection again, with every virtual register replaced by a real one. */
    public static Selection allocate(Selection selection, Target target) {
        return new RegisterAllocator(target).run(selection);
    }

    private Selection run(Selection selection) {
        this.selection = selection;
        groupNames(selection);
        colour(selection);

        List<Selection.Piece> pieces = new ArrayList<Selection.Piece>();
        for (Selection.Piece piece : selection.pieces()) {
            List<Instruction> rewritten = new ArrayList<Instruction>();
            for (Instruction instruction : piece.instructions()) {
                Instruction resolved = resolve(instruction);
                if (!isSelfCopy(resolved)) {
                    rewritten.add(resolved);
                }
            }
            pieces.add(piece.with(rewritten));
        }
        return new Selection(pieces);
    }

    // --- what makes two names one value ------------------------------------

    /**
     * Works out which names share a register, and takes the values from them.
     *
     * <p>Two things say two names are one value, and both are read off the program rather
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
     * <p>Everything else stays apart, which is the whole of what the form buys over
     * renaming every version back to its variable: two versions of one variable that
     * neither meet at a φ nor are a copy of each other are two values, and making them one
     * register would cost a register and buy nothing.
     */
    private void groupNames(Selection selection) {
        int index = 0;
        for (Selection.Piece piece : selection.pieces()) {
            for (Instruction instruction : piece.instructions()) {
                for (String name : mentioned(instruction)) {
                    if (!firstMention.containsKey(name)) {
                        firstMention.put(name, Integer.valueOf(index));
                        positions.put(name, instruction.position());
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

        for (List<String> names : selection.registerGroups()) {
            for (String name : names) {
                join(names.get(0), name);
            }
        }
        index = 0;
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

        Set<String> seen = new LinkedHashSet<String>();
        for (String name : firstMention.keySet()) {
            String value = groupOf(name);
            if (seen.add(value)) {
                values.add(value);
                if (addressNames.contains(name)) {
                    addresses.add(value);
                }
            }
        }
        for (String name : addressNames) {
            addresses.add(groupOf(name));
        }
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
     * <p>A memory operand holds names rather than operands, so walking {@code operands()}
     * alone would miss the address a load reads through — and a value no walk sees is a
     * value with no register and no interval.
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

    /** A copy of one value into another: the machine's own way of writing {@code d = s}. */
    private static boolean isCopy(Instruction instruction) {
        return instruction.mnemonic().equals("mov")
                && instruction.operands().size() == 2
                && instruction.operands().get(0) instanceof Operand.Virtual
                && instruction.operands().get(1) instanceof Operand.Virtual;
    }

    // --- the graph, and the colouring --------------------------------------

    /**
     * Builds the graph and gives every value a colour.
     *
     * <p>Two things put an edge in. A point that defines a value cannot have that value in
     * a register anything else alive at the point is using — the definition would overwrite
     * it, and on a two-address machine the value it is computed from is alive at the same
     * point, which is why a value interferes with everything alive at its definition rather
     * than with what is alive after it alone. And a value alive where a register is
     * destroyed may not be in that register, which is the same edge seen from the other
     * side: what destroys a register is a node of the graph too, and it is one nothing can
     * colour.
     */
    private void colour(Selection selection) {
        Liveness liveness = Liveness.of(selection, target);
        for (int point = 0; point < liveness.points(); point++) {
            Selection.Piece piece = selection.pieces().get(point);
            Set<String> alive = liveness.liveAt(point);
            String defined = Effects.writtenVariable(piece.item());
            String definedValue = defined == null ? null : groupOf(defined);
            // Whether the value this point defines was already alive when it was reached. It
            // usually was not — a point computes what it defines — and then it does not exist
            // yet at the instructions before the last one, which is where the destination is
            // written. What that buys is the target's own sequences: {@code mul} destroys
            // {@code ax} on its way to leaving the answer there, so a value that does not
            // exist until the copy out of {@code ax} may perfectly well live in {@code ax}.
            // When it *was* already alive — because a copy joined it to the value it copies,
            // which is the rule {@link #groupNames} makes — then it is alive throughout, and
            // a register destroyed before it is copied is a register it cannot be given.
            boolean alreadyAlive = definedValue != null
                    && groupOf(liveness.liveBefore(point)).contains(definedValue);
            List<Instruction> instructions = piece.instructions();
            for (int at = 0; at < instructions.size(); at++) {
                Set<String> destroyed = new LinkedHashSet<String>();
                if (at == instructions.size() - 1) {
                    destroyed.addAll(declaredClobbers(piece));
                }
                destroyed.addAll(target.clobbers(instructions.get(at)));
                if (destroyed.isEmpty()) {
                    continue;
                }
                for (String name : alive) {
                    String value = groupOf(name);
                    if (value.equals(definedValue) && !alreadyAlive
                            && at != instructions.size() - 1) {
                        continue;
                    }
                    forbid(value, destroyed);
                }
            }
            if (defined != null) {
                for (String name : alive) {
                    String other = groupOf(name);
                    if (!other.equals(definedValue)) {
                        interfere(definedValue, other);
                    }
                }
            }
        }

        for (String value : order()) {
            List<String> allowed = addresses.contains(value) ? target.addressRegisters()
                    : target.valueRegisters();
            String register = null;
            for (String candidate : allowed) {
                Set<String> forbidden = keepOut.get(value);
                if (forbidden != null && forbidden.contains(candidate)) {
                    continue;
                }
                if (coloursInUse(value).contains(candidate)) {
                    continue;
                }
                register = candidate;
                break;
            }
            if (register == null) {
                throw noRegister(value);
            }
            assigned.put(value, register);
        }
    }

    /**
     * The order the values are coloured in: a maximum cardinality search, reversed.
     *
     * <p>It is here for a reason and not for taste. An SSA form's interference graph is
     * chordal — that is the result the whole of this design leans on — and a chordal graph
     * has a **perfect elimination ordering**, which is an order in which every value's
     * already-coloured neighbours are a clique. Colouring greedily in one therefore uses
     * the fewest registers the graph allows, which on a machine with six registers is the
     * difference between a program that compiles and a program that is refused.
     *
     * <p>Maximum cardinality search finds one: number the values from the last to the
     * first, each step taking the value with the most already-numbered neighbours. Ties go
     * to the value seen first in the program, so the order is a property of the input
     * ({@code AGENTS.md}, invariant 6) — and the search is written out rather than shared
     * with a graph library, because there is no library here and there will not be one.
     *
     * <p>What is not guaranteed is that the graph is chordal: two names are joined when a
     * copy dies at them ({@link #groupNames}), and joining two nodes can take a chordal
     * graph out of the class. So this is the order that makes the greedy pass optimal for
     * the part of the graph the SSA form determines, and a good order for the rest.
     */
    private List<String> order() {
        Map<String, Integer> score = new LinkedHashMap<String, Integer>();
        for (String value : values) {
            score.put(value, Integer.valueOf(0));
        }
        List<String> numbered = new ArrayList<String>();
        Set<String> left = new LinkedHashSet<String>(values);
        while (!left.isEmpty()) {
            String best = null;
            for (String value : values) {
                if (!left.contains(value)) {
                    continue;
                }
                if (best == null || score.get(value).intValue() > score.get(best).intValue()) {
                    best = value;
                }
            }
            numbered.add(best);
            left.remove(best);
            for (String other : neighbours(best)) {
                if (left.contains(other)) {
                    score.put(other, Integer.valueOf(score.get(other).intValue() + 1));
                }
            }
        }
        List<String> order = new ArrayList<String>(numbered);
        Collections.reverse(order);
        return order;
    }

    /** The registers the colours already given to this value's neighbours have used up. */
    private Set<String> coloursInUse(String value) {
        Set<String> used = new LinkedHashSet<String>();
        for (String other : neighbours(value)) {
            String colour = assigned.get(other);
            if (colour != null) {
                used.add(colour);
            }
        }
        return used;
    }

    /** The values this one cannot share a register with. */
    private Set<String> neighbours(String value) {
        Set<String> found = interferes.get(value);
        return found == null ? Collections.<String>emptySet() : found;
    }

    private void interfere(String one, String other) {
        if (one.equals(other)) {
            return;
        }
        addEdge(one, other);
        addEdge(other, one);
    }

    private void addEdge(String one, String other) {
        Set<String> found = interferes.get(one);
        if (found == null) {
            found = new LinkedHashSet<String>();
            interferes.put(one, found);
        }
        found.add(other);
    }

    /** The names that stand for one group: the same question as {@link #groupOf}, asked twice. */
    private Set<String> groupOf(Set<String> names) {
        Set<String> values = new LinkedHashSet<String>();
        for (String name : names) {
            values.add(groupOf(name));
        }
        return values;
    }

    private void forbid(String value, Set<String> registers) {
        for (String register : registers) {
            forbid(value, register);
        }
    }

    private void forbid(String value, String register) {
        Set<String> found = keepOut.get(value);
        if (found == null) {
            found = new LinkedHashSet<String>();
            keepOut.put(value, found);
        }
        found.add(register);
    }

    /**
     * The registers a point's declaration says it destroys.
     *
     * <p>Two kinds of point declare a list and mean the same thing by it — an inline block,
     * which the compiler cannot see into, and a machine statement such as an interrupt —
     * and the declaration is the authority over what the target would otherwise assume.
     * Everything else is the target's answer, asked per instruction, because it is the
     * machine that knows that {@code mov cl, 8} writes a register no value was given.
     */
    private Set<String> declaredClobbers(Selection.Piece piece) {
        List<String> clobbers = null;
        if (piece.item() instanceof Item.InlineAsm) {
            clobbers = ((Item.InlineAsm) piece.item()).clobbers();
        } else if (piece.item() instanceof Item.Machine) {
            clobbers = ((Item.Machine) piece.item()).clobbers();
        }
        Set<String> declared = new LinkedHashSet<String>();
        if (clobbers != null) {
            for (String destroyed : clobbers) {
                if (target.isRegister(destroyed)) {
                    declared.add(destroyed);
                }
            }
        }
        return declared;
    }

    /**
     * Why a value could not be given a register, said in the terms the program is written
     * in.
     *
     * <p>What got in the way is the answer, not a guess: the values alive at the same time
     * are values the register would have to hold at once, and the registers something
     * destroys are the ones it cannot live in. The two things a program can do about it are
     * {@code docs/ir.md} §11.1's, and the message says so rather than leaving the reader to
     * find out.
     */
    private CompileError noRegister(String value) {
        List<String> blockers = new ArrayList<String>();
        Set<String> taken = new LinkedHashSet<String>();
        for (String other : neighbours(value)) {
            String colour = assigned.get(other);
            if (colour != null) {
                blockers.add(selection.variableOf(other));
                taken.add(colour);
            }
        }
        Set<String> forbidden = keepOut.get(value);
        return new CompileError(positions.get(value),
                "there is no register left for '" + selection.variableOf(value) + "': "
                        + (blockers.isEmpty()
                        ? "it is alive at the same time as more values than the machine has "
                        + "registers"
                        : "it is alive at the same time as " + blockers + ", and "
                        + (taken.size() == 1 ? "that one" : "those") + " hold "
                        + taken)
                        + (forbidden == null || forbidden.isEmpty() ? ""
                        : "; something it has to live across destroys " + forbidden
                        + ", so say what it really destroys with 'clobbers(...)', or write the "
                        + "value to memory and read it into a name of its own afterwards")
                        + ": this allocation does not spill, because a program that needs "
                        + "more registers than the machine has is refused rather than given "
                        + "a frame (docs/ir.md §8.2)");
    }

    /**
     * Whether an instruction is a copy of a register into itself.
     *
     * <p>A sequence the target handed over has to name the registers the machine insists
     * on — {@code mov ax, x} before a multiply — and whether that copy is needed is a
     * question about allocation. If {@code x} was given {@code ax}, the copy says nothing
     * and goes; if it was not, the copy is what makes the sequence work. The same rule
     * catches the copy back out when the answer already belongs in {@code ax}.
     *
     * <p>Nothing is lost by dropping one: moving a register into itself changes neither the
     * register nor the flags.
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

    // --- writing the registers in ------------------------------------------

    /**
     * Rewrites an instruction with every value replaced by the register it lives in.
     *
     * <p>The names inside a memory operand are resolved too: an address is a value like any
     * other, and by the time an instruction is printed there is no such thing as a name that
     * has not been decided. A name that is not one of the module's values is a label, and a
     * label is not a register — it is left as it is, for the assembler.
     */
    private Instruction resolve(Instruction instruction) {
        if (instruction.isLabel()) {
            // A label inside a block has no operands and no registers to decide, and
            // rebuilding it would drop the one thing it carries (docs/ir.md §9).
            return instruction;
        }
        List<Operand> operands = new ArrayList<Operand>();
        for (Operand operand : instruction.operands()) {
            if (operand instanceof Operand.Virtual) {
                operands.add(((Operand.Virtual) operand)
                        .resolvedTo(registerOf(((Operand.Virtual) operand).name())));
            } else if (operand instanceof Operand.Memory) {
                operands.add(resolve((Operand.Memory) operand));
            } else {
                operands.add(operand);
            }
        }
        return new Instruction(instruction.position(), instruction.mnemonic(), operands);
    }

    private Operand resolve(Operand.Memory operand) {
        List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
        for (Operand.Memory.Atom atom : operand.atoms()) {
            if (!atom.isVirtual()) {
                // A number, or a name the assembler owns: neither is ours to give a
                // register to.
                atoms.add(atom);
            } else {
                atoms.add(Operand.Memory.Atom.ofName(registerOf(atom.name())));
            }
        }
        return new Operand.Memory(operand.position(), operand.size(), operand.segment(), atoms);
    }

    /**
     * The register a value lives in.
     *
     * <p>Every name in the stream was given one, because the graph was built over all of
     * them — so a name with no register here is a name the allocator never saw, which is a
     * bug rather than a program the compiler cannot compile.
     */
    private String registerOf(String name) {
        String register = assigned.get(groupOf(name));
        if (register == null) {
            throw new IllegalStateException("no register was decided for '" + name + "'");
        }
        return register;
    }
}
