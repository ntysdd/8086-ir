package i8086.isel;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.ir.Item;
import i8086.ir.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A module's instructions, before anyone has said where the values live.
 *
 * <p>One piece per item of the module, in the same order, so the labels and the
 * data the emitter writes are still where they were: an item that produces no
 * code — a variable declaration — has an empty piece, and one that is already
 * code — an inline block — passes its own instructions through.
 *
 * <p>Pieces are paired with their item rather than indexed against the module,
 * because two lists that have to stay the same length are a bug waiting to
 * happen and a pairing cannot drift.
 */
public final class Selection {

    /** One item and the instructions it became. */
    public static final class Piece {

        private final Item item;
        private final List<Instruction> instructions;

        public Piece(Item item, List<Instruction> instructions) {
            this.item = item;
            this.instructions = Collections.unmodifiableList(
                    new ArrayList<Instruction>(instructions));
        }

        public Item item() {
            return item;
        }

        public List<Instruction> instructions() {
            return instructions;
        }

        /** The same piece with other instructions, which is how allocation rewrites it. */
        public Piece with(List<Instruction> replacements) {
            return new Piece(item, replacements);
        }

        public SourcePos position() {
            return item.position();
        }
    }

    private final List<Piece> pieces;
    private final List<List<String>> registerGroups;
    private final List<Merge> merges;
    private final Map<String, String> variables;
    private final Map<String, String> homes;
    private final Map<String, Type> types;

    /**
     * One φ: where its name is defined, and where each of its operands is read.
     *
     * <p>A merge is a definition and a set of reads, and neither is an item or an instruction. The
     * name is defined at the entry of its block, before that block's first instruction; the operands
     * are read at the <em>end of the predecessor each comes from</em>, which is what keeps a value
     * that arrives along one edge from looking like a value that is live along all of them
     * ({@code docs/ssa.md} §8).
     *
     * <p>Every point is an index into {@link #pieces()}, and {@code operandPoints} is aligned with
     * {@code names} in the group the merge belongs to, without the φ's own name: those two lists are
     * one fact told to two readers — the coalescer wants the names, the liveness wants the points.
     */
    public static final class Merge {

        private final int point;
        private final List<Integer> operandPoints;

        public Merge(int point, List<Integer> operandPoints) {
            this.point = point;
            this.operandPoints = Collections.unmodifiableList(
                    new ArrayList<Integer>(operandPoints));
        }

        /** Where the φ's name is defined: the entry of the block it merges into. */
        public int point() {
            return point;
        }

        /** Where each operand is read, one per name in the group after the first. */
        public List<Integer> operandPoints() {
            return operandPoints;
        }
    }

    public Selection(List<Piece> pieces) {
        this(pieces, Collections.<List<String>>emptyList(),
                Collections.<String, String>emptyMap());
    }

    public Selection(List<Piece> pieces, List<List<String>> registerGroups,
                     Map<String, String> variables) {
        this(pieces, registerGroups, Collections.<Merge>emptyList(), variables,
                Collections.<String, String>emptyMap(), Collections.<String, Type>emptyMap());
    }

    public Selection(List<Piece> pieces, List<List<String>> registerGroups,
                     Map<String, String> variables, Map<String, String> homes,
                     Map<String, Type> types) {
        this(pieces, registerGroups, Collections.<Merge>emptyList(), variables, homes, types);
    }

    public Selection(List<Piece> pieces, List<List<String>> registerGroups, List<Merge> merges,
                     Map<String, String> variables, Map<String, String> homes,
                     Map<String, Type> types) {
        this.pieces = Collections.unmodifiableList(new ArrayList<Piece>(pieces));
        List<List<String>> groups = new ArrayList<List<String>>();
        for (List<String> group : registerGroups) {
            groups.add(Collections.unmodifiableList(new ArrayList<String>(group)));
        }
        this.registerGroups = Collections.unmodifiableList(groups);
        this.merges = Collections.unmodifiableList(new ArrayList<Merge>(merges));
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<String, String>(variables));
        this.homes = Collections.unmodifiableMap(new LinkedHashMap<String, String>(homes));
        this.types = Collections.unmodifiableMap(new LinkedHashMap<String, Type>(types));
    }

    public List<Piece> pieces() {
        return pieces;
    }

    /**
     * Names that have to end up in the same register, because a φ puts one where
     * another is read.
     *
     * <p>There is no copy at a merge ({@code docs/ssa.md} §8), so the register is
     * what carries the value along each path, and the values a φ joins have to be in
     * it. Selection is what knows: it is the one that read the φ's, and the allocator
     * is the one that has to be told. Each group is in the order the names were first
     * seen, so the first of them can stand for the whole group without the answer
     * depending on anything but the program.
     */
    public List<List<String>> registerGroups() {
        return registerGroups;
    }

    /**
     * Where each of those groups is merged, by index into {@link #registerGroups()}.
     *
     * <p>A group exists because a φ joins its names, and a φ is a definition: it happens at the
     * entry of its block, before the block's first instruction. That is a fact about the stream the
     * allocator has to be told for the same reason it is told the group at all — a value whose only
     * definition is a φ would otherwise look like a value with no definition, and be live from the
     * start of the program ({@code docs/ssa.md} §8).
     */
    public List<Integer> mergePoints() {
        List<Integer> points = new ArrayList<Integer>();
        for (Merge merge : merges) {
            points.add(Integer.valueOf(merge.point()));
        }
        return Collections.unmodifiableList(points);
    }

    /** Where the φ's are, and where their operands are read. */
    public List<Merge> merges() {
        return merges;
    }

    /**
     * The variable a name in this selection belongs to, or the name itself when it
     * belongs to none.
     *
     * <p>The names here are the form's, and a version is not something anybody wrote:
     * a refusal that said "no register left for 'n#2'" would be a sentence about the
     * compiler's own bookkeeping. So the form's version table travels with the
     * instructions, for the sake of the one place a name is said out loud.
     */
    public String variableOf(String name) {
        String variable = variables.get(name);
        return variable == null ? name : variable;
    }

    /**
     * The bytes a name may live in, or null when it has no home.
     *
     * <p>A home is the program's answer to "where does this value go when the registers
     * cannot hold it" ({@code docs/ir.md} §3.1.2), and it is a fact about the stream like
     * {@link #registerGroups}: the selector read the declarations, and the allocator is
     * the one that acts on it. It travels as a version-to-cell table for the same reason
     * the variable table does: a version is what the instructions name.
     */
    public String homeOf(String name) {
        return homes.get(name);
    }

    /**
     * The type of a value, or null when the form has none for it.
     *
     * <p>Which is the declared type of the variable it is a version of, and it travels for
     * the same reason the other two tables do: the allocator decides what a value can live
     * in, and a home holds a value that is one register wide ({@code docs/ir.md} §3.1.2).
     */
    public Type typeOf(String name) {
        return types.get(name);
    }

    /** Every instruction, in order, with the pieces flattened away. */
    public List<Instruction> instructions() {
        List<Instruction> all = new ArrayList<Instruction>();
        for (Piece piece : pieces) {
            all.addAll(piece.instructions());
        }
        return all;
    }
}
