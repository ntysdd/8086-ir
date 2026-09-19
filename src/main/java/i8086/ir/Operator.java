package i8086.ir;

import java.util.Locale;

/**
 * An operator in an expression.
 *
 * <p>The set is the one {@code docs/ir.md} §5.5 defines, and it is split by one
 * question: does the operation read the flags? An operation that reads them can
 * appear in {@code eval}, which follows program order and leaves the flags the
 * way the written sequence would, and never in {@code expr}, which is pure and
 * throws them away. That is not a house rule: {@code adc}, {@code sbb},
 * {@code rcl} and {@code rcr} all read {@code CF}, so the hardware puts them
 * there.
 *
 * <p>Precedence is the ordinary one every reader already has in their fingers,
 * and every operator is left-associative. A writer who does not want to remember
 * it writes brackets, which cost nothing.
 */
public enum Operator {

    /** Bitwise complement. */
    COMPLEMENT("~", 1, 7, Flags.VALUE),

    /**
     * {@code -x}: negation, which the surface spells with a prefix minus.
     *
     * <p>The same character also spells subtraction, and the two are told apart by
     * position like every prefix operator: {@code a - b} is one subtraction and
     * {@code -b} is one negation. They are not the same operation in the tree —
     * {@code -b} and {@code 0 - b} are equal without being the same thing — but
     * they are the same value, the same flags and, on this machine, the same
     * instruction, so the difference is never observable ({@code docs/ir.md}
     * §5.5).
     */
    NEGATE("-", 1, 7, Flags.VALUE),

    MULTIPLY("*", 2, 6, Flags.VALUE),
    DIVIDE("/", 2, 6, Flags.VALUE),
    REMAINDER("%", 2, 6, Flags.VALUE),

    /** {@code mul a, b}: the mnemonic form, which states the signedness. */
    MULTIPLY_UNSIGNED("mul", 2, 6, Flags.VALUE),
    MULTIPLY_SIGNED("imul", 2, 6, Flags.VALUE),

    /** {@code div a, b}: likewise. */
    DIVIDE_UNSIGNED("div", 2, 6, Flags.VALUE),
    DIVIDE_SIGNED("idiv", 2, 6, Flags.VALUE),

    ADD("+", 2, 5, Flags.VALUE),
    SUBTRACT("-", 2, 5, Flags.VALUE),

    /** {@code adc a, b}: add, and the carry that came in. It reads {@code CF}. */
    ADD_WITH_CARRY("adc", 2, 5, Flags.CARRY),
    SUBTRACT_WITH_BORROW("sbb", 2, 5, Flags.CARRY),

    SHIFT_LEFT("shl", 2, 4, Flags.VALUE),
    SHIFT_RIGHT("shr", 2, 4, Flags.VALUE),
    SHIFT_ARITHMETIC("sar", 2, 4, Flags.VALUE),
    ROTATE_LEFT("rol", 2, 4, Flags.VALUE),
    ROTATE_RIGHT("ror", 2, 4, Flags.VALUE),

    /** {@code rcl a, b}: a rotation through the carry, so it reads {@code CF}. */
    ROTATE_LEFT_THROUGH_CARRY("rcl", 2, 4, Flags.CARRY),
    ROTATE_RIGHT_THROUGH_CARRY("rcr", 2, 4, Flags.CARRY),

    AND("&", 2, 3, Flags.VALUE),
    XOR("^", 2, 2, Flags.VALUE),
    OR("|", 2, 1, Flags.VALUE);

    /** Whether an operation reads the flags, and therefore where it may appear. */
    private enum Flags {
        VALUE,
        CARRY
    }

    private final String spelling;
    private final int arity;
    private final int precedence;
    private final Flags flags;

    Operator(String spelling, int arity, int precedence, Flags flags) {
        this.spelling = spelling;
        this.arity = arity;
        this.precedence = precedence;
        this.flags = flags;
    }

    public String spelling() {
        return spelling;
    }

    /** How many operands it takes: one for the complement, two for the rest. */
    public int arity() {
        return arity;
    }

    /** Larger binds tighter. */
    public int precedence() {
        return precedence;
    }

    /** Whether this operation reads the flags, which confines it to {@code eval}. */
    public boolean readsFlags() {
        return flags == Flags.CARRY;
    }

    /**
     * Whether the operation's result or its flags depend on the signedness of its
     * operands.
     *
     * <p>Only division does: two's-complement addition, subtraction,
     * multiplication and the bitwise operations give the same bits either way,
     * and a truncating multiply gives the same low half. A shift states its own
     * answer in its mnemonic — {@code shr} against {@code sar} — and so do the
     * mnemonic forms of division, which is why they exist.
     */
    public boolean dependsOnSignedness() {
        return this == DIVIDE || this == REMAINDER;
    }

    /** Whether this operator is a division, and so bound by the 16-bit rule (§6.2). */
    public boolean divides() {
        return this == DIVIDE || this == REMAINDER
                || this == DIVIDE_UNSIGNED || this == DIVIDE_SIGNED;
    }

    /**
     * Whether the mnemonic states the signedness itself, so that mixed operands
     * are not a contradiction (§5.5).
     */
    public boolean statesSignedness() {
        switch (this) {
            case MULTIPLY_UNSIGNED:
            case MULTIPLY_SIGNED:
            case DIVIDE_UNSIGNED:
            case DIVIDE_SIGNED:
            case SHIFT_RIGHT:
            case SHIFT_ARITHMETIC:
                return true;
            default:
                return false;
        }
    }

    /**
     * The operator this word names between two operands, or null if it names none.
     *
     * <p>A spelling may be shared by two operators of different arity — the infix
     * minus of subtraction and the prefix minus of negation — so the two-operand one
     * wins here. That is the whole of the convention, and it is why
     * {@link #prefix(String)} exists: what a word means is decided by where it stands,
     * and the caller is what knows where that is.
     */
    public static Operator named(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        Operator unary = null;
        for (Operator operator : values()) {
            if (!operator.spelling.equals(name)) {
                continue;
            }
            if (operator.arity != 1) {
                return operator;
            }
            unary = operator;
        }
        return unary;
    }

    /**
     * The operator this word names where a value would begin: the one that takes a
     * single operand, or null if this word begins none.
     */
    public static Operator prefix(String word) {
        String name = word.toLowerCase(Locale.ROOT);
        for (Operator operator : values()) {
            if (operator.arity == 1 && operator.spelling.equals(name)) {
                return operator;
            }
        }
        return null;
    }

    /** The word this operator is written with, for a diagnostic. */
    public static String words() {
        StringBuilder all = new StringBuilder();
        for (Operator operator : values()) {
            if (all.length() > 0) {
                all.append(' ');
            }
            all.append(operator.spelling);
        }
        return all.toString();
    }

    @Override
    public String toString() {
        return spelling;
    }
}
