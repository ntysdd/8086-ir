package i8086.regalloc;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.ir.Item;
import i8086.ir.Place;
import i8086.ir.Type;
import i8086.isel.Selection;
import i8086.ssa.Effects;
import i8086.target.Form;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.Collection;
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
 *
 * <p>What it does instead, when the program said it may, is use a **home**. A variable
 * declared {@code var x: u16 in cell} says those bytes may hold its value
 * ({@code docs/ir.md} §3.1.2), so a cell is one more colour a value can be given — after
 * the registers, because a value that fits in one stays in one and the bytes are never
 * touched. A value given a cell is loaded out of it wherever it is read and stored back
 * into it where it is written, through a register picked for that access, so the value does
 * not hold a register for its life and the pressure it was causing is gone. That register is
 * the one thing a value in a cell still needs, and it is needed at a point rather than for a
 * life — which is why {@link #colour} decides where values live and what they are moved
 * through in one attempt, and {@link #withHomes} is where the cost of a home lands.
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

    /**
     * The register each value would rather have, where the target says one is worth asking for.
     *
     * <p>Not a constraint and not a colour: a hint, tried before the target's own order, so that a
     * value the machine would be a byte cheaper holding can have that register when it is free and
     * the usual order when it is not ({@link Target#preferredRegister}).
     */
    private final Map<String, String> preferred = new LinkedHashMap<String, String>();

    /** Which register each value ended up in. */
    private final Map<String, String> assigned = new LinkedHashMap<String, String>();

    /** Which cell each value ended up in, when it did not get a register. */
    private final Map<String, String> inHome = new LinkedHashMap<String, String>();

    /** The cell each value may live in, as {@link #takeHomes} decides from the declaration. */
    private final Map<String, String> groupHomes = new LinkedHashMap<String, String>();

    /** The cells a value may not be given, because the program writes them in the middle. */
    private final Map<String, Set<String>> keepOutHome = new LinkedHashMap<String, Set<String>>();

    /** The liveness of the selection, built once for the graph and the rewrite both. */
    private Liveness liveness;

    /**
     * The register each value in a cell is moved through, per point.
     *
     * <p>One map per point, because that is where the answer is decided: what has to be free to
     * read or write a cell is a fact about the point, and the attempt that places the values is
     * the one that can tell whether it holds. Empty for a point that touches no cell.
     */
    private final List<Map<String, String>> scratches = new ArrayList<Map<String, String>>();

    /**
     * The register each point reads from the machine, by point: the name as it was written, and only
     * where a value could be in it at all ({@code docs/ir.md} §8.1).
     */
    private final Map<Integer, String> readRegisters = new LinkedHashMap<Integer, String>();

    /**
     * The registers the reads a point can still reach need free.
     *
     * <p>What a read asks for is the machine's content, and a scratch register the allocator picks
     * for a value in a home writes over exactly that — so a register a read can still ask for is not
     * one to pick ({@code docs/ir.md} §8.1).
     */
    private final Map<Integer, Set<String>> registersReadLater =
            new LinkedHashMap<Integer, Set<String>>();

    /**
     * The registers a read needs free, so that a refusal can say that is why and not guess at a
     * clobber list the program never wrote ({@code docs/ir.md} §8.1).
     */
    private final Set<String> readHolds = new LinkedHashSet<String>();

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
        requireValuesFitInARegister(selection);
        this.liveness = Liveness.of(selection, target);
        for (int point = 0; point < selection.pieces().size(); point++) {
            scratches.add(new LinkedHashMap<String, String>());
        }
        takeHomes(selection);
        keepValuesOutOfWrittenCells();
        takeReadRegisters();
        keepValuesOutOfReadRegisters();
        requireTheReadRegistersAreTheMachines();
        takePreferences(selection);
        colour();

        List<Selection.Piece> pieces = new ArrayList<Selection.Piece>();
        for (int point = 0; point < selection.pieces().size(); point++) {
            Selection.Piece piece = selection.pieces().get(point);
            List<Instruction> rewritten = new ArrayList<Instruction>();
            for (Instruction instruction : withHomes(piece, point)) {
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
            names.addAll(names(operand));
        }
        return names;
    }

    /** The virtual names one operand mentions: itself, or the address it is made of. */
    private static List<String> names(Operand operand) {
        List<String> names = new ArrayList<String>();
        if (operand instanceof Operand.Virtual || operand instanceof Operand.LowByte) {
            names.add(nameOf(operand));
        } else if (operand instanceof Operand.Memory) {
            for (Operand.Memory.Atom atom : ((Operand.Memory) operand).atoms()) {
                if (atom.isVirtual()) {
                    names.add(atom.name());
                }
            }
        }
        return names;
    }

    /** The value an operand names, which the two virtual kinds both carry. */
    private static String nameOf(Operand operand) {
        return operand instanceof Operand.LowByte
                ? ((Operand.LowByte) operand).name()
                : ((Operand.Virtual) operand).name();
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
     * than with what is alive after it alone. And a value a register is destroyed under may
     * not be in that register, which is the same edge seen from the other side: what destroys
     * a register is a node of the graph too, and it is one nothing can colour.
     *
     * <p>That last edge is drawn per <em>instruction</em> and not per point, because a point is
     * as coarse as this can honestly go when it comes to what a register holds versus when it
     * is taken away. What a statement destroys is destroyed when it runs — an {@code int} takes
     * {@code ax}, {@code bx}, {@code cx} and {@code dx} away, and the {@code with} clause that
     * handed it three of them read those values before it did. A value that is read by the
     * clause and dead afterwards may therefore live in a register the interrupt destroys; one
     * that is still to be read after the point may not, and neither may one read by a later
     * instruction of the same sequence.
     *
     * <p>The question is what the point still reads <em>after</em> the instruction, because a
     * value is read before the register it is read from is written ({@code docs/ir.md} §5.1):
     * {@code mov dl, c} with {@code c} living in {@code dl} is a move of a register into itself
     * and no instruction at all, and the allocator is the one that gets to find that out.
     */
    private void buildGraph() {
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
            // What the point still reads after one instruction, and from its successors after the
            // last. Walking backwards is what makes it one pass: the instruction being looked at adds
            // its own names only once the registers it destroys have been held against it.
            Set<String> stillRead = groupOf(liveness.liveAfter(point));
            for (int at = instructions.size() - 1; at >= 0; at--) {
                Set<String> destroyed = destroyedAt(piece, at);
                if (!destroyed.isEmpty()) {
                    for (String name : alive) {
                        String value = groupOf(name);
                        if (value.equals(definedValue) && !alreadyAlive
                                && at != instructions.size() - 1) {
                            continue;
                        }
                        if (!stillRead.contains(value)) {
                            continue; // dead at this instruction, which is where a register it is in
                                      // may be destroyed
                        }
                        forbid(value, destroyed);
                    }
                }
                for (String name : mentioned(instructions.get(at))) {
                    stillRead.add(groupOf(name));
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
    }

    /**
     * Every value a colour: a register, or the cell it may live in.
     *
     * <p>Registers first — a value that fits in one stays in one and its home is never touched —
     * and a home only for a value with no register left. One pass is not enough, for two
     * reasons, and both are the same shape: the decision is made value by value, while what the
     * program needs is a property of a point.
     *
     * <p>A value that <em>could</em> have lived in a home may take the last register on its way
     * past, and a value with no home then has nowhere to go. And a value in a home needs a
     * register at every point that reads or writes it — that is how it is got in and out of the
     * cell — which the value's own colour says nothing about. So an attempt that fails either way
     * is thrown away and run again with one more value sent to its home: the value that is in the
     * way, which is a value with a home of its own, since a register can only be freed here by
     * one. Each attempt pins one more, so the loop ends.
     *
     * <p>What comes out of a successful attempt is the registers, the cells, and — per point — the
     * register each value in a cell is moved through, because the attempt that decided where the
     * values live is the one that can tell whether they can be got at.
     */
    private void colour() {
        buildGraph();
        List<String> order = order();
        Map<String, String> pinned = new LinkedHashMap<String, String>();
        while (true) {
            assigned.clear();
            inHome.clear();
            inHome.putAll(pinned);
            for (Map<String, String> at : scratches) {
                at.clear();
            }

            String failed = assign(order);
            if (failed != null) {
                String value = pinnedFor(failed, order);
                if (value == null) {
                    throw noRegister(failed);
                }
                pinned.put(value, groupHomes.get(value));
                continue;
            }

            Stuck stuck = assignScratches();
            if (stuck == null) {
                return;
            }
            String value = pinnedAt(stuck.point, order);
            if (value == null) {
                throw noScratch(stuck);
            }
            pinned.put(value, groupHomes.get(value));
        }
    }

    /**
     * Where an attempt to place everything stops: the point that could not be served, and the
     * value in a home that could not be moved at it.
     */
    private static final class Stuck {

        private final int point;
        private final String value;

        Stuck(int point, String value) {
            this.point = point;
            this.value = value;
        }
    }

    /**
     * One attempt: a register or a home for every value, and the first value that got neither.
     */
    private String assign(List<String> order) {
        for (String value : order) {
            if (inHome.containsKey(value)) {
                continue; // an earlier attempt already sent this one to its home
            }
            String register = freeRegister(value);
            if (register != null) {
                assigned.put(value, register);
                continue;
            }
            String cell = usableHome(value);
            if (cell != null) {
                inHome.put(value, cell);
                continue;
            }
            return value;
        }
        return null;
    }

    /** The first register this value may have and no neighbour is using, or null. */
    private String freeRegister(String value) {
        List<String> allowed = registersFor(value);
        Set<String> inUse = coloursInUse(value);
        Set<String> forbidden = keepOut.get(value);
        String wanted = preferred.get(value);
        if (wanted != null && allowed.contains(wanted) && !inUse.contains(wanted)
                && (forbidden == null || !forbidden.contains(wanted))) {
            return wanted; // what the machine asked for, when the machine can have it
        }
        for (String candidate : allowed) {
            if (forbidden != null && forbidden.contains(candidate)) {
                continue;
            }
            if (inUse.contains(candidate)) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * The register each value would rather have, where the machine says one is worth asking for.
     *
     * <p>A machine is not uniform about its registers. This one counts a loop down in {@code cx} and
     * nowhere else, so a value an instruction counts can be a byte cheaper there — and the register
     * this allocator reaches for first is the accumulator, whose direct-address and immediate forms
     * are shorter than the general ones. The answer is therefore asked for and tried, and where it
     * does not fit the order is what is left.
     *
     * <p>What the target answers is about one instruction, so what it is asked for is the register a
     * counted value is worth trying, and not proof that a loop will use it: a value that is counted
     * and never looped over costs the same either way ({@code inc}, {@code dec}, {@code test r, r}
     * and a small immediate are all the same in either register), which is what makes the ask
     * harmless where it is not useful. The counted instruction is a byte, and it is the one the
     * machine has no general form of.
     *
     * <p>Three shapes withdraw the request, and all three are shapes rather than facts about the
     * machine, because shapes are what this can see: an address with no register in it, which a
     * machine may have a direct form of — {@code mov [x], ax} is a byte shorter than
     * {@code mov [x], cx} — a move between a value and a register the target named by hand, where a
     * sequence has already decided which register it wants, and a literal too wide for the short
     * immediate form. A byte value asks for nothing either: this machine's counted instruction counts
     * a word, so a byte in the register the target named would buy nothing and could cost the byte an
     * immediate comparison saves.
     *
     * <p>What is asked about is the <b>value</b> and not the name, so the request is remembered for
     * the whole group: a φ's names are one life, and which of them the colouring sees is the
     * colouring's business.
     */
    private void takePreferences(Selection selection) {
        Map<String, String> asked = new LinkedHashMap<String, String>();
        Set<String> withdrawn = new LinkedHashSet<String>();
        for (Selection.Piece piece : selection.pieces()) {
            for (Instruction instruction : piece.instructions()) {
                boolean accumulatorIsShorter = accumulatorMayBeShorter(instruction);
                String wanted = target.preferredRegister(instruction);
                if (wanted == null && !accumulatorIsShorter) {
                    continue;
                }
                for (String name : mentioned(instruction)) {
                    String value = groupOf(name);
                    if (accumulatorIsShorter) {
                        withdrawn.add(value);
                    }
                    if (wanted != null) {
                        asked.put(value, wanted);
                    }
                }
            }
        }
        for (Map.Entry<String, String> entry : asked.entrySet()) {
            String value = entry.getKey();
            if (withdrawn.contains(value) || isByteWide(value)
                    || addresses.contains(value)) {
                continue;
            }
            preferred.put(value, entry.getValue());
        }
    }

    /**
     * Whether this machine may have a shorter form of this instruction in one register.
     *
     * <p>Three shapes, and they are the shapes a value's register can be felt in: an access to an
     * address with no register in it, which the accumulator has a direct form for; a move between a
     * value and a register the target named by hand, which is a sequence that has already chosen;
     * and a literal too wide for the short immediate form, where the accumulator's own immediate is
     * the shorter one. Anything else is a question for the target, and the target is asked the other
     * half of it ({@link Target#preferredRegister}).
     */
    private static boolean accumulatorMayBeShorter(Instruction instruction) {
        boolean namesARegister = false;
        boolean mentionsAValue = false;
        boolean wideImmediate = false;
        for (Operand operand : instruction.operands()) {
            if (operand instanceof Operand.Memory) {
                if (!hasRegister((Operand.Memory) operand)) {
                    return true;
                }
            } else if (operand instanceof Operand.Name) {
                namesARegister = true;
            } else if (operand instanceof Operand.Virtual || operand instanceof Operand.LowByte) {
                mentionsAValue = true;
            } else if (operand instanceof Operand.Number
                    && !fitsInAByte(((Operand.Number) operand).value())) {
                wideImmediate = true;
            }
        }
        return (namesARegister || wideImmediate) && mentionsAValue;
    }

    /** Whether a literal is one the machine's short immediate form can hold. */
    private static boolean fitsInAByte(long value) {
        return value >= -128 && value <= 127;
    }

    /** Whether an access is made through a register, as opposed to a bare address. */
    private static boolean hasRegister(Operand.Memory memory) {
        for (Operand.Memory.Atom atom : memory.atoms()) {
            if (atom.isVirtual()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The registers a value may live in.
     *
     * <p>The target's own class, cut down when the value is a byte: a byte value occupies a register
     * whose low half has a name, because that is the half an instruction reads and writes, and this
     * machine has no byte half for {@code si}, {@code di} or {@code bp} at all
     * ({@code docs/ir.md} §3.2). A value used as an address needs one of the address registers, and
     * a byte value is never one: an address is a pointer, and a pointer is a word (§3.3).
     */
    private List<String> registersFor(String value) {
        boolean byteWide = isByteWide(value);
        List<String> allowed = new ArrayList<String>();
        for (String register : addresses.contains(value) ? target.addressRegisters()
                : target.valueRegisters()) {
            if (!byteWide || target.byteRegister(register) != null) {
                allowed.add(register);
            }
        }
        return allowed;
    }

    /** Whether this value is narrower than the register that holds it. */
    private boolean isByteWide(String value) {
        Type type = selection.typeOf(value);
        return type != null && type.bytes() < Size.WORD.bytes();
    }

    /**
     * The cell this value may live in, or null when it may not live in one at all.
     *
     * <p>Three things can say no, and {@link #noRegister} says which one it was: the value is
     * not a register's width, the program writes those bytes while the value is alive, or a value
     * it interferes with is in the same cell already.
     */
    private String usableHome(String value) {
        String cell = groupHomes.get(value);
        if (cell == null || !fitsInARegister(value)) {
            return null;
        }
        if (keepOutHome(value).contains(cell) || cellsInUse(value).contains(cell)) {
            return null;
        }
        return cell;
    }

    /**
     * A value to move into its home so that one that could not be coloured can have a register.
     *
     * <p>The failed value itself first: if it has a home, that is what the home is for. Otherwise
     * a value it interferes with, since those are the registers it could not have — and the first
     * of them in the colouring order, which is a rule rather than a preference. Which values
     * should pay for memory when only some of them fit is a question about how often each is read,
     * and this allocator has no cost model ({@code docs/ir.md} §3.1.2).
     *
     * <p>A value already in its home is not a candidate: pinning it again would be an attempt
     * that changes nothing, and the loop that runs this would not end.
     */
    private String pinnedFor(String failed, List<String> order) {
        for (String value : order) {
            if (inHome.containsKey(value)) {
                continue; // already sent to its home, by an earlier attempt or by this one
            }
            if (value.equals(failed) || neighbours(failed).contains(value)) {
                if (usableHome(value) != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * The cell each value may live in: what the declaration said, per group of names.
     *
     * <p>A value is a group of names, and the names of one group may come from different
     * variables — a copy joins them when the source dies at it — so the cell is taken from
     * the first name that declares one, in the order the names are first mentioned. Any of
     * their homes is a cell the program asked for, so which one is a matter of which
     * declaration comes first rather than of anything guessed at.
     */
    private void takeHomes(Selection selection) {
        for (String name : firstMention.keySet()) {
            String value = groupOf(name);
            if (groupHomes.containsKey(value)) {
                continue;
            }
            String home = selection.homeOf(name);
            if (home != null) {
                groupHomes.put(value, home);
            }
        }
    }

    /**
     * Keeps a value out of a cell the program writes while the value is alive.
     *
     * <p>A value lives in a home because the allocator put it there, and a store of the
     * program's to those bytes — {@code [cell] = x}, the save §3.1.2 describes — writes over
     * it. The program is allowed to do that, so the value has to be somewhere else across the
     * write, which is the one direction of the home rules that is a hard constraint and not a
     * warning: a register or a refusal, never a value quietly lost.
     */
    private void keepValuesOutOfWrittenCells() {
        for (int point = 0; point < liveness.points(); point++) {
            String cell = writtenCell(selection.pieces().get(point).item());
            if (cell == null) {
                continue;
            }
            for (String name : liveness.liveAt(point)) {
                String value = groupOf(name);
                if (cell.equals(groupHomes.get(value))) {
                    forbidHome(value, cell);
                }
            }
        }
    }

    /** The cell an item of the program writes, or null when it writes something else. */
    private static String writtenCell(Item item) {
        if (!(item instanceof Item.Assign)) {
            return null;
        }
        Place place = ((Item.Assign) item).place();
        return place instanceof Place.Memory
                ? ((Place.Memory) place).operand().addressedLabel()
                : null;
    }

    /**
     * Where the machine's own registers are read, and which register each read needs free
     * ({@code docs/ir.md} §8.1).
     *
     * <p>The register that has to be free is the target's answer and not the name that was written:
     * nothing here can name half a register, so a read of {@code dl} is about {@code dx}. A register
     * no value is ever given — the segmentation state and the stack — answers with null, because
     * nothing the compiler does is ever in it.
     *
     * <p>Two rules come out of this, and they are the two ways the compiler can have written the
     * register before the read: it puts values in registers, which
     * {@link #keepValuesOutOfReadRegisters} keeps out of this one, and it picks scratch registers
     * for values in homes, which {@link #assignScratches} will not pick from here.
     */
    private void takeReadRegisters() {
        for (int point = 0; point < liveness.points(); point++) {
            String register = readRegister(selection.pieces().get(point).item());
            if (register == null) {
                continue;
            }
            String held = target.valueRegisterOf(register);
            if (held == null) {
                continue; // a register no value can be in, so one the compiler never writes
            }
            readRegisters.put(Integer.valueOf(point), register);
            readHolds.add(held);
            for (Integer before : liveness.pointsReaching(point)) {
                Set<String> needed = registersReadLater.get(before);
                if (needed == null) {
                    needed = new LinkedHashSet<String>();
                    registersReadLater.put(before, needed);
                }
                needed.add(held);
            }
        }
    }

    /** The register a point reads from the machine, or null when it reads none. */
    private static String readRegister(Item item) {
        return item instanceof Item.MovRegRead ? ((Item.MovRegRead) item).register() : null;
    }

    /**
     * Keeps values out of the registers a statement reads from the machine.
     *
     * <p>A read asks for the content the machine left in the register, and a value the compiler put
     * there is what it would get instead ({@code docs/ir.md} §8.1). What is in the way is not only
     * what is alive at the read: a value that ended before it was still written there, and nothing
     * clears a register when a value dies. So the register is closed to every value alive, or
     * defined, at any point that can reach the read — that is everything the compiler can have
     * written before it on some path — while a value defined afterwards may use the register: the
     * copy the read makes is what defines the one value there, and the register is the machine's
     * only up to the read.
     */
    private void keepValuesOutOfReadRegisters() {
        for (Map.Entry<Integer, String> read : readRegisters.entrySet()) {
            String register = target.valueRegisterOf(read.getValue());
            for (Integer earlier : liveness.pointsReaching(read.getKey().intValue())) {
                Selection.Piece piece = selection.pieces().get(earlier.intValue());
                for (String name : liveness.liveAt(earlier.intValue())) {
                    forbid(groupOf(name), register);
                }
                String defined = Effects.writtenVariable(piece.item());
                if (defined != null) {
                    forbid(groupOf(defined), register);
                }
            }
        }
    }

    /**
     * Refuses a read of a register the compiler has already written.
     *
     * <p>A value can be kept out of a read's way, but a sequence cannot. The machine insists on
     * particular registers for some operations — {@code div} leaves its quotient in {@code ax} and
     * its remainder in {@code dx}, a shift of more than one wants its count in {@code cl} — and what
     * the target declares for those is the compiler's own work and not the machine's. A read that
     * would return that work is refused, because the arithmetic in it is nobody's answer.
     *
     * <p>What puts a register back in the machine's hands is an item that says what goes into a
     * register: a {@code with} clause, which the program writes, and a clobber list, which says the
     * machine has left something there. That is what makes the case this direction exists for work —
     * the answer an interrupt leaves in a register is read after it, whatever the compiler did
     * before, and a register the program has just written is read back.
     */
    private void requireTheReadRegistersAreTheMachines() {
        int points = liveness.points();
        List<Set<String>> dirty = new ArrayList<Set<String>>();
        for (int point = 0; point < points; point++) {
            dirty.add(new LinkedHashSet<String>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int point = 0; point < points; point++) {
                Set<String> before = new LinkedHashSet<String>();
                for (Integer earlier : liveness.predecessors(point)) {
                    before.addAll(dirty.get(earlier.intValue()));
                }
                Selection.Piece piece = selection.pieces().get(point);
                Set<String> after = new LinkedHashSet<String>(before);
                if (isAnInterface(piece.item())) {
                    after.removeAll(destroyed(piece));
                } else {
                    after.addAll(destroyed(piece));
                }
                if (!after.equals(dirty.get(point))) {
                    dirty.set(point, after);
                    changed = true;
                }
            }
        }
        for (Map.Entry<Integer, String> read : readRegisters.entrySet()) {
            String register = target.valueRegisterOf(read.getValue());
            if (dirty.get(read.getKey().intValue()).contains(register)) {
                throw new CompileError(selection.pieces().get(read.getKey().intValue()).position(),
                        "'" + read.getValue() + "' is read here, and the compiler has already "
                                + "written '" + register + "': the way this machine does something "
                                + "this program asked for uses registers nothing else will do, so "
                                + "what is in it now is the compiler's own working and not what the "
                                + "machine left. Read it earlier, or after a statement that puts "
                                + "something into the register — an interrupt, which says the machine "
                                + "has left its answer there (docs/ir.md §8.1)");
            }
        }
    }

    /**
     * Whether an item's writes are the program's rather than the compiler's: a statement that says
     * what goes into its registers, and one whose clobber list says what the machine has left.
     */
    private static boolean isAnInterface(Item item) {
        return item instanceof Item.Machine || item instanceof Item.InlineAsm
                || item instanceof Item.FarJump;
    }

    /**
     * Whether a value fits in a register, which is what a home holds.
     *
     * <p>A home is moved in and out with a machine access, so the value has to be one of the two
     * widths an access can be: a byte or a word ({@code docs/ir.md} §3.1.2, §3.4). Wider than that
     * is refused rather than quietly truncated.
     */
    private boolean fitsInARegister(String value) {
        Type type = selection.typeOf(value);
        return type != null && type.bytes() <= Size.WORD.bytes();
    }

    /**
     * Refuses a value whose width is known to be more than a register, before anything places one.
     *
     * <p>A register holds one register's worth of value, and the only answer for more is two of
     * them — which nothing here can name, so a value of four bytes is refused where it is first
     * mentioned rather than given half a register. What that would otherwise be is a silent
     * truncation: {@code x = 0} for a {@code u32} would clear sixteen bits of a value the program
     * thinks it has thirty-two of, and a store of it would write half of it out
     * ({@code docs/ir.md} §3.4).
     *
     * <p>A value whose width is <em>not</em> known is not refused: the widths a target's sequences
     * name by hand have no type at all, and a value nobody stated the width of is placed as a whole
     * register, which is what the mode bits of an instruction follow ({@code docs/ir.md} §3.2).
     *
     * <p>The work the value is doing can be done, and the message says how: as two halves the
     * program moves itself, or as bytes in memory ({@code docs/ir.md} §10).
     */
    private void requireValuesFitInARegister(Selection selection) {
        for (String value : values) {
            Type type = selection.typeOf(value);
            if (type == null || type.bytes() <= Size.WORD.bytes()) {
                continue;
            }
            throw new CompileError(positions.get(value),
                    "a " + type.spelling() + " is wider than a register, and nothing here can name "
                            + "a pair: write it as two halves the program moves itself, or keep it "
                            + "in memory and work on its parts (docs/ir.md §3.4)");
        }
    }

    /** The registers the values alive at a point are in. A value in a home is in none. */
    private Set<String> registersInUse(int point) {
        Set<String> used = new LinkedHashSet<String>();
        for (String name : liveness.liveAt(point)) {
            String register = assigned.get(groupOf(name));
            if (register != null) {
                used.add(register);
            }
        }
        return used;
    }

    /** The cells the values this one interferes with are in. */
    private Set<String> cellsInUse(String value) {
        Set<String> used = new LinkedHashSet<String>();
        for (String other : neighbours(value)) {
            String cell = inHome.get(other);
            if (cell != null) {
                used.add(cell);
            }
        }
        return used;
    }

    private Set<String> keepOutHome(String value) {
        Set<String> found = keepOutHome.get(value);
        return found == null ? Collections.<String>emptySet() : found;
    }

    private void forbidHome(String value, String cell) {
        Set<String> found = keepOutHome.get(value);
        if (found == null) {
            found = new LinkedHashSet<String>();
            keepOutHome.put(value, found);
        }
        found.add(cell);
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
     * The registers a point's instruction destroys, or that the point's own declaration says it
     * destroys.
     *
     * <p>Two kinds of point carry a list and mean the same thing by it — an inline block, which
     * the compiler cannot see into, and a machine statement such as an interrupt — and the
     * declaration is the authority over what the target would otherwise assume. It is asked for
     * the last instruction because that is what the list was written on.
     */
    private Set<String> destroyedAt(Selection.Piece piece, int at) {
        Set<String> destroyed = new LinkedHashSet<String>();
        if (at == piece.instructions().size() - 1) {
            destroyed.addAll(declaredClobbers(piece));
        }
        destroyed.addAll(target.clobbers(piece.instructions().get(at)));
        return destroyed;
    }

    /** Every register a point destroys, whichever of its instructions does it. */
    private Set<String> destroyed(Selection.Piece piece) {
        Set<String> destroyed = new LinkedHashSet<String>();
        for (int at = 0; at < piece.instructions().size(); at++) {
            destroyed.addAll(destroyedAt(piece, at));
        }
        return destroyed;
    }

    /**
     * The registers a point destroys before a value's last mention of it, which a scratch for that
     * value has to survive.
     *
     * <p>A value moved between its home and the machine is moved through a register that has to hold
     * it for as long as the point still reads it — and what a register is destroyed <em>by</em> is
     * the instruction that runs, so only the destructions before that last mention are in the way.
     * Whether one of them is: {@code int 0x13} destroys {@code ax}, {@code bx}, {@code cx} and
     * {@code dx}, and the {@code with} clause that hands it its arguments has read them before it
     * runs — which is what lets a boot loader fill a packet and interrupt in one statement
     * ({@code docs/ir.md} §11).
     *
     * <p>The mention that matters is the last one, because the load is emitted before the first and
     * the store after the last write ({@link #withHomes}), and a value is read before the register it
     * is read from is written ({@code docs/ir.md} §5.1).
     */
    private Set<String> destroyedBeforeLastMention(Selection.Piece piece, String value) {
        List<Instruction> instructions = piece.instructions();
        int last = -1;
        for (int at = 0; at < instructions.size(); at++) {
            for (String name : mentioned(instructions.get(at))) {
                if (groupOf(name).equals(value)) {
                    last = at;
                    break;
                }
            }
        }
        Set<String> destroyed = new LinkedHashSet<String>();
        for (int at = 0; at < last; at++) {
            destroyed.addAll(destroyedAt(piece, at));
        }
        return destroyed;
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
     * destroys are the ones it cannot live in. A home the value could have had is asked why it
     * did not, so that a program which declared one is told what became of it. The things a
     * program can do about any of this are {@code docs/ir.md} §11.1's, and the message says so
     * rather than leaving the reader to find out.
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
        Set<String> destroyed = new LinkedHashSet<String>();
        Set<String> held = new LinkedHashSet<String>();
        if (forbidden != null) {
            for (String register : forbidden) {
                (readHolds.contains(register) ? held : destroyed).add(register);
            }
        }
        return new CompileError(positions.get(value),
                "there is no register left for '" + selection.variableOf(value) + "': "
                        + (blockers.isEmpty()
                        ? "it is alive at the same time as more values than the machine has "
                        + "registers"
                        : "it is alive at the same time as " + blockers + ", and "
                        + (taken.size() == 1 ? "that one" : "those") + " hold "
                        + taken)
                        + (destroyed.isEmpty() ? ""
                        : "; something it has to live across destroys " + destroyed
                        + ", so say what it really destroys with 'clobbers(...)', or write the "
                        + "value to memory and read it into a name of its own afterwards")
                        + (held.isEmpty() ? ""
                        : "; and " + held + " is what a 'movreg' reads, which asks for what the "
                        + "machine left there, so no value may be in it up to that point "
                        + "(docs/ir.md §8.1)")
                        + homeProblem(value)
                        + byteProblem(value)
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

    // --- values that live in a home (§3.1.2) -------------------------------

    /**
     * A point's instructions, with the values that live in a cell loaded where they are read
     * and stored where they are written.
     *
     * <p>This is the whole cost of a home. A value in memory is not in a register, so every
     * read of it is a load into a register the allocator picks for that one access, and its one
     * definition is computed in a register and then stored. The value does not hold a register
     * for its life — that is what makes the pressure it was causing go away — so the register is
     * not part of the graph, and what it has to be is free: not one a live value is in, and not
     * one this point destroys ({@link #scratch}).
     *
     * <p>One register per value per point, so that {@code x = eval(x - 1)} with {@code x} in a
     * cell is a load, the subtraction and a store, all in that one register: the read that is
     * the value's own definition is not a load, because the computation is already putting the
     * answer there.
     */
    private List<Instruction> withHomes(Selection.Piece piece, int point) {
        List<Instruction> instructions = piece.instructions();
        String defined = Effects.writtenVariable(piece.item());
        String definedValue = defined == null ? null : groupOf(defined);

        Set<String> cells = new LinkedHashSet<String>();
        for (Instruction instruction : instructions) {
            for (String name : mentioned(instruction)) {
                String value = groupOf(name);
                if (inHome.containsKey(value)) {
                    cells.add(value);
                }
            }
        }
        if (cells.isEmpty()) {
            return instructions;
        }

        Map<String, String> at = scratches.get(point);
        int storeAfter = lastWriter(instructions, definedValue, cells);
        List<Instruction> out = new ArrayList<Instruction>();
        Set<String> loaded = new LinkedHashSet<String>();
        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = instructions.get(index);
            for (String value : readsHere(instruction, definedValue, cells)) {
                if (loaded.add(value)) {
                    out.add(load(instruction.position(), value, scratchOf(point, value),
                            inHome.get(value)));
                }
            }
            out.add(withScratches(instruction, at));
            if (index == storeAfter) {
                out.add(store(instruction.position(), definedValue, inHome.get(definedValue),
                        scratchOf(point, definedValue)));
            }
        }
        return out;
    }

    /**
     * The last instruction that writes the value this point defines, or -1 when it defines none
     * that lives in a cell.
     *
     * <p>That instruction is where the store goes and not the end of the point, because the
     * definition of a value is the last thing the point does to it: a multiply copies its answer
     * out of {@code ax} at the end, and an addition adds in place.
     */
    private int lastWriter(List<Instruction> instructions, String definedValue,
                           Set<String> cells) {
        if (definedValue == null || !cells.contains(definedValue)) {
            return -1;
        }
        int last = -1;
        for (int at = 0; at < instructions.size(); at++) {
            if (writes(instructions.get(at), definedValue)) {
                last = at;
            }
        }
        return last;
    }

    /**
     * Whether this instruction writes the value: its first operand is a name of that group.
     *
     * <p>Which operand is written is the machine's rule and not this class's — {@code cmp}
     * answers with the flags, a jump answers with where it went — and every instruction the
     * selector builds that does write one writes the first. A value being defined is written
     * and never read by its own definition, which SSA guarantees and which is why the first
     * operand is enough to recognise it.
     */
    private boolean writes(Instruction instruction, String value) {
        List<Operand> operands = instruction.operands();
        if (operands.isEmpty() || !(operands.get(0) instanceof Operand.Virtual)) {
            return false;
        }
        return groupOf(((Operand.Virtual) operands.get(0)).name()).equals(value);
    }

    /**
     * The values this instruction reads which live in a cell.
     *
     * <p>Every mention of one is a read except the first operand of the instruction that writes
     * it, which is the definition being computed. A value the point itself defines is otherwise
     * not mentioned by the point at all: in SSA a definition is written once and read afterwards
     * ({@code docs/ssa.md}).
     */
    private List<String> readsHere(Instruction instruction, String definedValue,
                                  Set<String> cellValues) {
        List<String> read = new ArrayList<String>();
        List<Operand> operands = instruction.operands();
        for (int at = 0; at < operands.size(); at++) {
            for (String name : names(operands.get(at))) {
                String value = groupOf(name);
                if (!cellValues.contains(value)) {
                    continue;
                }
                if (at == 0 && value.equals(definedValue)) {
                    continue;
                }
                if (!read.contains(value)) {
                    read.add(value);
                }
            }
        }
        return read;
    }

    /**
     * Gives every value in a cell the register it is moved through at each point that mentions it,
     * and answers with the first point it cannot be done at, or null when it can be done at all of
     * them.
     *
     * <p>This is where the local cost of a home is settled, and it is asked *inside* the attempt
     * that decides where values live rather than after it. The reason is that the two questions
     * are the same question asked at different scales: a value in a cell needs no register for its
     * life, but it needs one at each point that reads or writes it, and whether one is free there
     * is a property of that point — of which values are alive and which registers the point
     * destroys — and of nothing about the value itself.
     *
     * <p>What has to be free is a register no value alive at the point is in, because that value
     * would be destroyed by the load or the store, and one the point does not destroy, because a
     * sequence the target declared may need {@code ax} or {@code dx} itself. Two values in cells at
     * one point get two registers, since both are live across the instruction that computes with
     * them. Which registers are tried is the target's order, so which one is chosen is a property
     * of the program.
     */
    private Stuck assignScratches() {
        for (int point = 0; point < liveness.points(); point++) {
            Selection.Piece piece = selection.pieces().get(point);
            Map<String, String> at = scratches.get(point);
            Set<String> busy = registersInUse(point);
            // A register some read can still ask for is the machine's until that read: a scratch
            // writes over what it would return (docs/ir.md §8.1).
            Set<String> readLater = registersReadLater.get(Integer.valueOf(point));
            if (readLater != null) {
                busy.addAll(readLater);
            }
            for (Instruction instruction : piece.instructions()) {
                for (String name : mentioned(instruction)) {
                    String value = groupOf(name);
                    if (!inHome.containsKey(value) || at.containsKey(value)) {
                        continue;
                    }
                    Set<String> forbidden = new LinkedHashSet<String>(busy);
                    forbidden.addAll(destroyedBeforeLastMention(piece, value));
                    String register = partnerFor(piece, value, at);
                    if (register != null && (reads(piece, value)
                            ? forbidden.contains(register)
                            : destroyedBeforeLastMention(piece, value).contains(register))) {
                        register = null;
                    }
                    if (register == null) {
                        register = freeRegisterFor(value, forbidden);
                    }
                    if (register == null) {
                        return new Stuck(point, value);
                    }
                    at.put(value, register);
                    busy.add(register);
                }
            }
        }
        return null;
    }

    /** The first register of the class this value needs that nothing in {@code busy} is using. */
    private String freeRegisterFor(String value, Set<String> busy) {
        for (String candidate : registersFor(value)) {
            if (!busy.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The register this point moves a value in a cell to or from, when that register can be the
     * scratch it is moved through.
     *
     * <p>A home costs a copy at every access where the register is already decided. A clause that
     * wants a byte in {@code dl} is a load into a scratch and then a move — five bytes, where
     * loading it straight into {@code dl} is four — and a value defined by a copy into a cell is the
     * copy and then the store. Either way it is two instructions doing one, and what says so is the
     * move being a register moved into itself, which the allocator already knows how to drop
     * ({@link #isSelfCopy}). So the register the point wants the value in is the register to move it
     * through.
     *
     * <p>What is answered here is only the register: whether it may be used is the caller's, because
     * it depends on whether the point reads the value or writes it. A load writes the scratch and so
     * may not destroy a live value; a store only reads it, and the register a store wants to name is
     * the one the value being stored is already in — which is a live value, and the whole point.
     */
    private String partnerFor(Selection.Piece piece, String value, Map<String, String> at) {
        for (Instruction instruction : piece.instructions()) {
            List<Operand> operands = instruction.operands();
            if (!instruction.mnemonic().equals("mov") || operands.size() != 2) {
                continue;
            }
            String register = null;
            if (mentions(operands.get(0), value)) {
                register = registerOf(operands.get(1));
            } else if (mentions(operands.get(1), value)) {
                register = registerOf(operands.get(0));
            }
            if (register != null && registersFor(value).contains(register)
                    && !at.containsValue(register)) {
                return register;
            }
        }
        return null;
    }

    /**
     * The register an operand is in, as a word register, or null when it is not in one.
     *
     * <p>A name the selector wrote is a register and a byte name is half of one, which is the same
     * pair of rules the machine's own answer uses ({@code Target#valueRegisterOf}). A value is in
     * whatever the allocator gave it — the point this is asked at is after the colouring — and a
     * value read through its low half is in that same register.
     */
    private String registerOf(Operand operand) {
        if (operand instanceof Operand.Name) {
            return target.valueRegisterOf(((Operand.Name) operand).name());
        }
        if (operand instanceof Operand.Virtual) {
            return assigned.get(groupOf(((Operand.Virtual) operand).name()));
        }
        if (operand instanceof Operand.LowByte) {
            return assigned.get(groupOf(((Operand.LowByte) operand).name()));
        }
        return null;
    }

    /** Whether this point reads the value anywhere, as opposed to only writing it. */
    private boolean reads(Selection.Piece piece, String value) {
        for (Instruction instruction : piece.instructions()) {
            if (writes(instruction, value)) {
                List<Operand> operands = instruction.operands();
                for (int at = 1; at < operands.size(); at++) {
                    if (mentions(operands.get(at), value)) {
                        return true;
                    }
                }
                continue;
            }
            for (String name : mentioned(instruction)) {
                if (groupOf(name).equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether this operand mentions the value. */
    private boolean mentions(Operand operand, String value) {
        for (String name : names(operand)) {
            if (groupOf(name).equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The register a value in a cell is moved through at a point.
     *
     * <p>Every pair this is asked about was decided by {@link #assignScratches}, which ran before
     * anything was rewritten — so a pair with no answer here is a bug in this class rather than a
     * program it cannot compile.
     */
    private String scratchOf(int point, String value) {
        String register = scratches.get(point).get(value);
        if (register == null) {
            throw new IllegalStateException("no register was decided to move '" + value + "'");
        }
        return register;
    }

    /** The instruction with every value that lives in a cell written as its scratch register. */
    private Instruction withScratches(Instruction instruction, Map<String, String> scratches) {
        List<Operand> operands = new ArrayList<Operand>();
        for (Operand operand : instruction.operands()) {
            operands.add(withScratches(operand, scratches));
        }
        return new Instruction(instruction.position(), instruction.mnemonic(), operands);
    }

    private Operand withScratches(Operand operand, Map<String, String> scratches) {
        if (operand instanceof Operand.Virtual) {
            String name = ((Operand.Virtual) operand).name();
            String register = scratches.get(groupOf(name));
            return register == null ? operand
                    : new Operand.Name(operand.position(), written(groupOf(name), register));
        }
        if (operand instanceof Operand.LowByte) {
            // The value is read through its low half, so the register it was moved into is read
            // through its low half too — which is where that half of the value is (docs/ir.md §3.5).
            String name = ((Operand.LowByte) operand).name();
            String register = scratches.get(groupOf(name));
            if (register == null) {
                return operand;
            }
            String half = target.byteRegister(register);
            if (half == null) {
                throw new IllegalStateException("'" + register + "' has no low byte to read");
            }
            return new Operand.Name(operand.position(), half);
        }
        if (operand instanceof Operand.Memory) {
            Operand.Memory memory = (Operand.Memory) operand;
            List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
            for (Operand.Memory.Atom atom : memory.atoms()) {
                atoms.add(withScratches(atom, scratches));
            }
            return new Operand.Memory(memory.position(), memory.size(), memory.segment(), atoms);
        }
        return operand;
    }

    private Operand.Memory.Atom withScratches(Operand.Memory.Atom atom,
                                             Map<String, String> scratches) {
        if (!atom.isVirtual()) {
            return atom;
        }
        String register = scratches.get(groupOf(atom.name()));
        if (register == null) {
            return atom;
        }
        Operand.Memory.Atom resolved =
                Operand.Memory.Atom.ofName(written(groupOf(atom.name()), register));
        return atom.isSubtracted() ? resolved.subtracted() : resolved;
    }

    /**
     * A load of a cell into a register, and a store of a register into a cell.
     *
     * <p>Both are the target's: it declares what a load and a store are
     * ({@link Target#loadForms}, {@link Target#storeForms}), and this takes the one it declares.
     * Choosing between several would be the smallest-form question selection answers, and this
     * machine has one of each.
     */
    private Instruction load(SourcePos where, String value, String register, String cell) {
        return access(target.loadForms(), "load", where,
                pair(new Operand.Name(where, written(value, register)),
                        cell(where, cell, accessWidth(value))));
    }

    private Instruction store(SourcePos where, String value, String cell, String register) {
        return access(target.storeForms(), "store", where,
                pair(cell(where, cell, accessWidth(value)),
                        new Operand.Name(where, written(value, register))));
    }

    private static Instruction access(List<Form> forms, String what, SourcePos where,
                                      List<Operand> operands) {
        if (forms.isEmpty()) {
            throw new CompileError(where,
                    "this target declares no form for a " + what + ", so a value cannot be kept "
                            + "in memory on it (docs/ir.md §3.1.2)");
        }
        return new Instruction(where, forms.get(0).mnemonic(), operands);
    }

    /** The bytes of a home, as the address an access is made through, one access wide. */
    private static Operand.Memory cell(SourcePos where, String cell, Size size) {
        List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
        atoms.add(Operand.Memory.Atom.ofName(cell));
        return new Operand.Memory(where, size, null, atoms);
    }

    /**
     * How wide an access to this value's cell is: the width of the value itself, which is a byte or
     * a word because nothing wider can live in a home.
     */
    private Size accessWidth(String value) {
        Type type = selection.typeOf(value);
        Size size = type == null ? null : Size.ofBytes(type.bytes());
        if (size == null) {
            throw new IllegalStateException("no access width is known for '" + value + "'");
        }
        return size;
    }

    private static List<Operand> pair(Operand first, Operand second) {
        List<Operand> operands = new ArrayList<Operand>();
        operands.add(first);
        operands.add(second);
        return operands;
    }

    /**
     * Why a value in a cell could not be moved through a register at a point.
     *
     * <p>The home is there so that the value does not need a register for its whole life, and this
     * is a moment it still needs one: every register is either holding a value that is alive here
     * or destroyed here, so there is nowhere to put the value while it is read or written. What
     * this allocator would do about that anywhere else is nothing — it does not invent storage,
     * and a register can only be freed here by moving a value the program itself gave somewhere to
     * wait, which none of the values alive here has. So the program is refused, with the point it
     * was refused at and the values that were in the way ({@code docs/ir.md} §3.1.2, §8.2).
     */
    private CompileError noScratch(Stuck stuck) {
        Set<String> alive = groupOf(liveness.liveAt(stuck.point));
        List<String> blockers = new ArrayList<String>();
        for (String other : alive) {
            if (assigned.containsKey(other)) {
                blockers.add(selection.variableOf(other));
            }
        }
        return new CompileError(selection.pieces().get(stuck.point).position(),
                "there is no register free to move '" + selection.variableOf(stuck.value)
                        + "' between its home '" + inHome.get(stuck.value) + "' and the machine "
                        + "here: " + blockers + " are alive at this point and hold every register "
                        + "the machine has"
                        + (readHolds.isEmpty() ? ""
                        : ", and " + readHolds + " has to hold what a 'movreg' reads "
                        + "(docs/ir.md §8.1)")
                        + ", and a register is freed here only by moving a value "
                        + "into a home the program declared, which none of them has "
                        + "(docs/ir.md §3.1.2, §8.2)");
    }

    /**
     * A value to move into its home so that a value in a cell can be got at where it is needed.
     *
     * <p>This is {@link #pinnedFor}'s trade asked at a point rather than at a value: what has to
     * change is which values hold the registers here, and the only value that can give one up is
     * one alive at this point with a home of its own to go to. The first such value in the
     * colouring order is taken, which is a rule rather than a preference — the same rule, for the
     * same reason: this allocator has no cost model ({@code docs/ir.md} §3.1.2).
     */
    private String pinnedAt(int point, List<String> order) {
        Set<String> alive = groupOf(liveness.liveAt(point));
        for (String value : order) {
            if (!alive.contains(value) || inHome.containsKey(value)) {
                continue;
            }
            if (usableHome(value) != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Why a byte value has fewer places to live than a word, when that is what went wrong.
     *
     * <p>A byte value occupies the low half of a register, so only the registers that have one can
     * hold it — four of them, where a word has six ({@code docs/ir.md} §3.2). An author who counts
     * six registers and wonders why {@code si} and {@code di} are idle deserves the sentence.
     */
    private String byteProblem(String value) {
        if (!isByteWide(value)) {
            return "";
        }
        return "; and a byte value can only live in the registers that have a low half, which is "
                + "four of them (docs/ir.md §3.2)";
    }

    /**
     * What became of the home this value was given, when it could not have it.
     *
     * <p>A program that declared a home and is then refused deserves to be told which of the
     * three rules said no, because each of them is something the program can change: the width of
     * the value, a store of the program's to those bytes while the value is alive, or another
     * value alive at the same time whose home those bytes are.
     */
    private String homeProblem(String value) {
        String cell = groupHomes.get(value);
        if (cell == null) {
            return "";
        }
        if (!fitsInARegister(value)) {
            return "; its home '" + cell + "' cannot hold it either, because a value is moved in "
                    + "and out of a home one access at a time, and this one is wider than a "
                    + "register (docs/ir.md §3.1.2, §3.4)";
        }
        if (keepOutHome(value).contains(cell)) {
            return "; its home '" + cell + "' is written by the program while the value is alive, "
                    + "so the value cannot be kept there (docs/ir.md §3.1.2)";
        }
        if (cellsInUse(value).contains(cell)) {
            return "; its home '" + cell + "' is the home of a value alive at the same time, and a "
                    + "cell holds one value at a time (docs/ir.md §3.1.2)";
        }
        return "";
    }

    // --- writing the registers in ------------------------------------------

    /**
     * Rewrites an instruction with every value replaced by the register it lives in.
     *
     * <p>A byte value is written by the name of the half of the register it lives in — {@code al}
     * for {@code ax} — because that half is what an instruction reads and writes
     * ({@code docs/ir.md} §3.2).
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
                String name = ((Operand.Virtual) operand).name();
                operands.add(((Operand.Virtual) operand).resolvedTo(written(name)));
            } else if (operand instanceof Operand.LowByte) {
                operands.add(new Operand.Name(operand.position(),
                        lowHalf(((Operand.LowByte) operand).name())));
            } else if (operand instanceof Operand.Memory) {
                operands.add(resolve((Operand.Memory) operand));
            } else {
                operands.add(operand);
            }
        }
        return new Instruction(instruction.position(), instruction.mnemonic(), operands);
    }

    /**
     * The name of the low half of the register a value lives in.
     *
     * <p>A narrowing conversion reads a value through its low half
     * ({@link Operand.LowByte}, {@code docs/ir.md} §3.5), and the half is named by the target: a
     * register with no byte half would be a bug in {@link #registersFor} rather than a program this
     * cannot compile, so it is said that way.
     */
    private String lowHalf(String name) {
        String register = registerOf(name);
        String half = target.byteRegister(register);
        if (half == null) {
            throw new IllegalStateException("'" + register + "' has no low byte to read");
        }
        return half;
    }

    private Operand resolve(Operand.Memory operand) {
        List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
        for (Operand.Memory.Atom atom : operand.atoms()) {
            if (!atom.isVirtual()) {
                // A number, or a name the assembler owns: neither is ours to give a
                // register to.
                atoms.add(atom);
            } else {
                Operand.Memory.Atom resolved = Operand.Memory.Atom.ofName(written(atom.name()));
                atoms.add(atom.isSubtracted() ? resolved.subtracted() : resolved);
            }
        }
        return new Operand.Memory(operand.position(), operand.size(), operand.segment(), atoms);
    }

    /**
     * The name a register is written by for this value: itself, or its low half when the value is a
     * byte.
     *
     * <p>A byte value lives in the low half of a register, and that half is what an instruction
     * reads and writes ({@code docs/ir.md} §3.2). A byte value given a register with no byte half
     * would be a bug in {@link #registersFor} rather than a program this cannot compile, so it is
     * said that way.
     */
    private String written(String value, String register) {
        if (!isByteWide(value)) {
            return register;
        }
        String half = target.byteRegister(register);
        if (half == null) {
            throw new IllegalStateException("'" + register + "' was given a byte value but has no "
                    + "byte half");
        }
        return half;
    }

    /** The name a value's register is written by in an instruction. */
    private String written(String name) {
        return written(groupOf(name), registerOf(name));
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
