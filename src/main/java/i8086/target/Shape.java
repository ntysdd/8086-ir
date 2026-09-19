package i8086.target;

import i8086.asm.Operand;

/**
 * The shape of an operand, which is as much as selection has to know to ask for
 * an instruction ({@code docs/asm.md} §1).
 *
 * <p>A {@link i8086.asm.Operand.Virtual} is a {@link #REGISTER}: it is a value
 * that will live in one, and selection chooses the form before anyone has said
 * which one. A {@link i8086.asm.Operand.LowByte} is one too: it is the low half of
 * a register, which is a register as far as the instruction is concerned
 * ({@code docs/ir.md} §3.5).
 */
public enum Shape {

    /** A register, including one still to be chosen. */
    REGISTER,

    /** A literal, which some forms insist on. */
    IMMEDIATE,

    /**
     * A memory operand: an address rather than a value.
     *
     * <p>What may be inside the brackets is the target's constraint — which
     * registers may hold an address, and whether a displacement needs one — so this
     * says only that the operand <em>is</em> an address, and the target says the
     * rest ({@link Target#addressRegisters()}).
     */
    MEMORY;

    /**
     * Which shape an operand has.
     *
     * <p>An {@link i8086.asm.Operand.Offset} is an immediate: it is the address of a
     * label, and the assembler is what turns one into bytes. A memory operand has a
     * shape of its own, because a form that takes one is not a form that takes a
     * register.
     */
    public static Shape of(Operand operand) {
        if (operand instanceof Operand.Number || operand instanceof Operand.Offset) {
            return IMMEDIATE;
        }
        if (operand instanceof Operand.Memory) {
            return MEMORY;
        }
        if (operand instanceof Operand.Virtual || operand instanceof Operand.Name
                || operand instanceof Operand.LowByte) {
            return REGISTER;
        }
        throw new IllegalArgumentException("no shape is known for " + operand.getClass().getName());
    }
}
