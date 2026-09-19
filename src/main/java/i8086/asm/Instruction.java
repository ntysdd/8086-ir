package i8086.asm;

import i8086.SourcePos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One line of assembly: an instruction, or a label that stands on its own.
 *
 * <p>Neither the mnemonic nor the operand names are checked against anything
 * here; that happens against the target description. What this class carries is
 * the shape, which is what the printer needs to write it back out and what the
 * emitter needs to place it ({@code docs/asm.md} §1).
 *
 * <p>A label is a line of its own and not an instruction with a colon in its name:
 * a label is not something the machine does, and the distinction is one this project
 * keeps everywhere else ({@code docs/ir.md} §10.2). It carries a name and no
 * operands, and the printer knows which of the two it is.
 */
public final class Instruction {

    private final String mnemonic;
    private final List<Operand> operands;
    private final SourcePos position;
    private final boolean label;

    public Instruction(SourcePos position, String mnemonic, List<Operand> operands) {
        this(position, mnemonic, operands, false);
    }

    private Instruction(SourcePos position, String mnemonic, List<Operand> operands,
                        boolean label) {
        this.position = position;
        this.mnemonic = mnemonic;
        this.operands = Collections.unmodifiableList(new ArrayList<Operand>(operands));
        this.label = label;
    }

    /**
     * A label, the name a branch inside the same block can reach
     * ({@code docs/ir.md} §9). It carries the name rather than a mnemonic, so
     * {@link #mnemonic()} answers with the name and {@link #isLabel()} tells them
     * apart.
     */
    public static Instruction label(SourcePos position, String name) {
        return new Instruction(position, name, Collections.<Operand>emptyList(), true);
    }

    /** Whether this line names a place rather than doing something. */
    public boolean isLabel() {
        return label;
    }

    /** The lower-cased mnemonic, which is the form everything compares against. */
    public String mnemonic() {
        return mnemonic;
    }

    public List<Operand> operands() {
        return operands;
    }

    public SourcePos position() {
        return position;
    }
}
