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

    private final Prefix prefix;
    private final String mnemonic;
    private final List<Operand> operands;
    private final SourcePos position;
    private final boolean label;

    /** The same, with no prefix: what almost every instruction is. */
    public Instruction(SourcePos position, String mnemonic, List<Operand> operands) {
        this(position, null, mnemonic, operands, false);
    }

    /** The same, with a prefix in front of it: {@code rep movsb} ({@link Prefix}). */
    public Instruction(SourcePos position, Prefix prefix, String mnemonic, List<Operand> operands) {
        this(position, prefix, mnemonic, operands, false);
    }

    private Instruction(SourcePos position, Prefix prefix, String mnemonic, List<Operand> operands,
                        boolean label) {
        this.position = position;
        this.prefix = prefix;
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
        return new Instruction(position, null, name, Collections.<Operand>emptyList(), true);
    }

    /** Whether this line names a place rather than doing something. */
    public boolean isLabel() {
        return label;
    }

    /**
     * The prefix the machine applies to it, or null when there is none.
     *
     * <p>A label carries none: a prefix says something about the instruction it is in front of, and
     * a label is not one.
     */
    public Prefix prefix() {
        return prefix;
    }

    /**
     * The same instruction with these operands instead, and nothing else changed.
     *
     * <p>An instruction is immutable, so the allocator — which decides where every operand goes and
     * drops the copies that turn out to be of a register into itself — builds one of these for every
     * instruction it keeps. It is here, next to the fields, rather than at the two places that ask
     * for it: a field added to this class is then a field one method has to carry over, and the
     * property test that renames every name to itself and compares the text is what says it did
     * ({@code i8086.ssa.RenamerTest}). Getting that wrong once already lost an instruction's prefix,
     * which turned a repeated copy into a single one.
     */
    public Instruction withOperands(List<Operand> operands) {
        return new Instruction(position, prefix, mnemonic, operands, label);
    }

    /**
     * The lower-cased mnemonic, which is the form everything compares against.
     */
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
