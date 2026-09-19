package i8086.ir;

import java.util.function.Function;

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
 *
 * <p>The one thing both callers have to supply is what a name's declared type is,
 * and that is all this asks for: a symbol table would be more than the question
 * needs, and the two callers answer it from different tables — the verifier from
 * the module's names, and selection from the SSA form, whose names are versions
 * ({@code docs/ssa.md}).
 */
public final class Signedness {

    private Signedness() {
    }

    /**
     * What signedness a value speaks for, or null when it says nothing.
     *
     * <p>{@code typeOf} is asked for the declared type of a name and answers null when
     * the name is not one: a label has no width and so no signedness either.
     */
    public static Boolean of(Value value, Function<String, Type> typeOf) {
        if (value instanceof Value.Name) {
            Type type = typeOf.apply(((Value.Name) value).name());
            return type == null ? null : Boolean.valueOf(type.isSigned());
        }
        return null;
    }

    /** What signedness an expression speaks for, or null when it says nothing. */
    public static Boolean of(Expression expression, Function<String, Type> typeOf) {
        if (expression instanceof Expression.Leaf) {
            return of(((Expression.Leaf) expression).value(), typeOf);
        }
        if (expression instanceof Expression.Unary) {
            return of(((Expression.Unary) expression).operand(), typeOf);
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
                Boolean left = of(apply.left(), typeOf);
                return left != null ? left : of(apply.right(), typeOf);
        }
    }
}
