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
        this.liveness = Liveness.of(selection, target);
        for (int point = 0; point < selection.pieces().size(); point++) {
            scratches.add(new LinkedHashMap<String, String>());
        }
        takeHomes(selection);
        keepValuesOutOfWrittenCells();
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
        if (operand instanceof Operand.Virtual) {
            names.add(((Operand.Virtual) operand).name());
        } else if (operand instanceof Operand.Memory) {
            for (Operand.Memory.Atom atom : ((Operand.Memory) operand).atoms()) {
                if (atom.isVirtual()) {
                    names.add(atom.name());
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
            for (int at = 0; at < instructions.size(); at++) {
                Set<String> destroyed = destroyedAt(piece, at);
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
            busy.addAll(destroyed(piece));
            for (Instruction instruction : piece.instructions()) {
                for (String name : mentioned(instruction)) {
                    String value = groupOf(name);
                    if (!inHome.containsKey(value) || at.containsKey(value)) {
                        continue;
                    }
                    String register = freeRegisterFor(value, busy);
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
                        + "the machine has, and a register is freed here only by moving a value "
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
