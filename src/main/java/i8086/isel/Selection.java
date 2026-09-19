package i8086.isel;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.ir.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    private final boolean controlFlow;

    public Selection(List<Piece> pieces, boolean controlFlow) {
        this.pieces = Collections.unmodifiableList(new ArrayList<Piece>(pieces));
        this.controlFlow = controlFlow;
    }

    public Selection(List<Piece> pieces) {
        this(pieces, false);
    }

    public List<Piece> pieces() {
        return pieces;
    }

    /**
     * Whether any of the instructions goes somewhere.
     *
     * <p>What has to know is the allocator: a register given away inside a loop
     * body would be read again by the next time round, and the intervals it works
     * with are linear. The selector is what knows, because the selector is what
     * emitted them.
     */
    public boolean controlFlow() {
        return controlFlow;
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
