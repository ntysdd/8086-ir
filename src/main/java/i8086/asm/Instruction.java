package i8086.asm;

import i8086.SourcePos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One assembly instruction: a mnemonic and its operands.
 *
 * <p>Neither the mnemonic nor the operand names are checked against anything
 * here; that happens against the target description. What this class carries is
 * the shape, which is what the printer needs to write it back out and what the
 * emitter needs to place it ({@code docs/asm.md} §1).
 */
public final class Instruction {

    private final String mnemonic;
    private final List<Operand> operands;
    private final SourcePos position;

    public Instruction(SourcePos position, String mnemonic, List<Operand> operands) {
        this.position = position;
        this.mnemonic = mnemonic;
        this.operands = Collections.unmodifiableList(new ArrayList<Operand>(operands));
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
