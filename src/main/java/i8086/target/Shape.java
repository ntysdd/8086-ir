package i8086.target;

import i8086.asm.Operand;

/**
 * The shape of an operand, which is as much as selection has to know to ask for
 * an instruction ({@code docs/asm.md} §1).
 *
 * <p>A {@link i8086.asm.Operand.Virtual} is a {@link #REGISTER}: it is a value
 * that will live in one, and selection chooses the form before anyone has said
 * which one.
 */
public enum Shape {

    /** A register, including one still to be chosen. */
    REGISTER,

    /** A literal, which some forms insist on. */
    IMMEDIATE;

    /** Which shape an operand has. A memory operand has none yet: selection does
     * not handle one, and saying {@code REGISTER} here would let it try. */
    public static Shape of(Operand operand) {
        if (operand instanceof Operand.Number) {
            return IMMEDIATE;
        }
        if (operand instanceof Operand.Virtual || operand instanceof Operand.Name) {
            return REGISTER;
        }
        throw new IllegalArgumentException("no shape is known for " + operand.getClass().getName());
    }
}
