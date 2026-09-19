package i8086.isel;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.ir.Item;

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
    private final Map<String, String> variables;

    public Selection(List<Piece> pieces) {
        this(pieces, Collections.<List<String>>emptyList(),
                Collections.<String, String>emptyMap());
    }

    public Selection(List<Piece> pieces, List<List<String>> registerGroups,
                     Map<String, String> variables) {
        this.pieces = Collections.unmodifiableList(new ArrayList<Piece>(pieces));
        List<List<String>> groups = new ArrayList<List<String>>();
        for (List<String> group : registerGroups) {
            groups.add(Collections.unmodifiableList(new ArrayList<String>(group)));
        }
        this.registerGroups = Collections.unmodifiableList(groups);
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<String, String>(variables));
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

    /** Every instruction, in order, with the pieces flattened away. */
    public List<Instruction> instructions() {
        List<Instruction> all = new ArrayList<Instruction>();
        for (Piece piece : pieces) {
            all.addAll(piece.instructions());
        }
        return all;
    }
}
