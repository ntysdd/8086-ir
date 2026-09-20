package i8086.target;

import i8086.ir.Names;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *   <li>{@link #differs()} — a smaller instruction often exists because it
 *       does <em>less</em>. {@code inc} is one byte where {@code add} is three,
 *       and it is smaller precisely because it does not touch the carry: the flag it leaves
 *       differently is the carry, and the form says so. Which flags those are is worth saying one
 *       by one, because {@code inc} stands for an addition wherever the carry is not read — even
 *       where the zero flag it does set is read ({@code docs/ir.md} §4.2).
 *   <li>{@link #literalOnly()} — {@code inc} is also only {@code add 1}. A form
 *       can say which literal it is for, so that a fact about the instruction is
 *       written down once, in the target, instead of in a condition inside
 *       selection.
 * </ul>
 */
public final class Form {

    /** Every flag an operation leaves, for a form that leaves them differently without saying which. */
    private static final Set<String> ALL = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(Names.FLAGS, Names.CARRY)));

    private final String mnemonic;
    private final List<Shape> operands;
    private final Long literalOnly;
    private final Set<String> differs;
    private final int bytes;

    public Form(String mnemonic, List<Shape> operands) {
        this(mnemonic, operands, null, Collections.<String>emptySet(), 1);
    }

    public Form(String mnemonic, List<Shape> operands, int bytes) {
        this(mnemonic, operands, null, Collections.<String>emptySet(), bytes);
    }

    /**
     * The same, saying whether the form leaves the operation's flags at all.
     *
     * <p>{@code false} means every flag of the operation, which is the safe answer for a form that
     * differs without saying how.
     */
    public Form(String mnemonic, List<Shape> operands, Long literalOnly, boolean keepsFlags,
                int bytes) {
        this(mnemonic, operands, literalOnly, keepsFlags ? Collections.<String>emptySet() : ALL,
                bytes);
    }

    /** The same, naming the flags it leaves differently: {@code inc} is {@code [carry]}. */
    public Form(String mnemonic, List<Shape> operands, Long literalOnly, Set<String> differs,
                int bytes) {
        this.mnemonic = mnemonic;
        this.operands = Collections.unmodifiableList(new ArrayList<Shape>(operands));
        this.literalOnly = literalOnly;
        this.differs = Collections.unmodifiableSet(new LinkedHashSet<String>(differs));
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

    /**
     * The flags this form leaves differently from the operation it stands for, by name.
     *
     * <p>Empty when the flags afterwards are the operation's own, which is the ordinary case — a
     * form is usually the operation, or a shorter way of doing it that leaves the same flags.
     */
    public Set<String> differs() {
        return differs;
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
