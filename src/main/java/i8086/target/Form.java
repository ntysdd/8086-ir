package i8086.target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One way to do an operation: a mnemonic, the operand shapes it takes, and what
 * it costs.
 *
 * <p>Forms are the vocabulary selection asks in. Selection never names an
 * instruction itself — it asks its target for the forms of an operator
 * ({@code AGENTS.md}, invariant 2) — and it picks the smallest form whose shapes
 * fit, which is the objective {@code README.md} states.
 *
 * <p>Two fields make that choice honest rather than lucky:
 *
 * <ul>
 *   <li>{@link #keepsFlags()} — a smaller instruction often exists because it
 *       does <em>less</em>. {@code inc} is one byte where {@code add} is three,
 *       and it is smaller precisely because it does not touch the carry. A form
 *       that does not keep the flags may only be used where nothing can read
 *       them.
 *   <li>{@link #literalOnly()} — {@code inc} is also only {@code add 1}. A form
 *       can say which literal it is for, so that a fact about the instruction is
 *       written down once, in the target, instead of in a condition inside
 *       selection.
 * </ul>
 */
public final class Form {

    private final String mnemonic;
    private final List<Shape> operands;
    private final Long literalOnly;
    private final boolean keepsFlags;
    private final int bytes;

    public Form(String mnemonic, List<Shape> operands) {
        this(mnemonic, operands, null, true, 1);
    }

    public Form(String mnemonic, List<Shape> operands, int bytes) {
        this(mnemonic, operands, null, true, bytes);
    }

    public Form(String mnemonic, List<Shape> operands, Long literalOnly, boolean keepsFlags,
                int bytes) {
        this.mnemonic = mnemonic;
        this.operands = Collections.unmodifiableList(new ArrayList<Shape>(operands));
        this.literalOnly = literalOnly;
        this.keepsFlags = keepsFlags;
        this.bytes = bytes;
    }

    public String mnemonic() {
        return mnemonic;
    }

    public List<Shape> operands() {
        return operands;
    }

    /** The only literal this form is for, or null when it takes any operand. */
    public Long literalOnly() {
        return literalOnly;
    }

    /** Whether the flags afterwards are the ones the operation itself would leave. */
    public boolean keepsFlags() {
        return keepsFlags;
    }

    /** How many bytes the form takes at least, which is what makes one form better than another. */
    public int bytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return mnemonic + operands;
    }
}
