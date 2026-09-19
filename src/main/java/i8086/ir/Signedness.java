package i8086.ir;

/**
 * What signedness a value or an expression speaks for, or nothing when it says
 * nothing at all.
 *
 * <p>Bits are bits until an operation reads them a particular way
 * ({@code docs/ir.md} §3.2). A variable's type says; a literal, a load and a label
 * do not, because they take their meaning from what they meet. An operator that
 * states the signedness in its own spelling settles it for everything above it,
 * which is why the mnemonic forms exist (§5.5).
 *
 * <p>Two callers ask, and this is why the answer is here rather than in one of
 * them: the verifier refuses a symbol that would have to mean two things at once,
 * and selection has to know which division to emit — {@code div} and {@code idiv}
 * are not two spellings of one instruction. A second copy of this rule would be a
 * second answer to one question ({@code docs/ir.md} §6.1).
 */
public final class Signedness {

    private Signedness() {
    }

    /** What signedness a value speaks for, or null when it says nothing. */
    public static Boolean of(Value value, Names names) {
        if (value instanceof Value.Name) {
            Type type = names.typeOf(((Value.Name) value).name());
            return type == null ? null : Boolean.valueOf(type.isSigned());
        }
        return null;
    }

    /** What signedness an expression speaks for, or null when it says nothing. */
    public static Boolean of(Expression expression, Names names) {
        if (expression instanceof Expression.Leaf) {
            return of(((Expression.Leaf) expression).value(), names);
        }
        if (expression instanceof Expression.Unary) {
            return of(((Expression.Unary) expression).operand(), names);
        }
        Expression.Apply apply = (Expression.Apply) expression;
        switch (apply.operator()) {
            case DIVIDE_UNSIGNED:
            case MULTIPLY_UNSIGNED:
            case SHIFT_RIGHT:
                return Boolean.FALSE;
            case DIVIDE_SIGNED:
            case MULTIPLY_SIGNED:
            case SHIFT_ARITHMETIC:
                return Boolean.TRUE;
            default:
                Boolean left = of(apply.left(), names);
                return left != null ? left : of(apply.right(), names);
        }
    }
}
