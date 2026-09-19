package i8086.pass;

import i8086.ir.Conversion;
import i8086.ir.Expression;
import i8086.ir.Operator;
import i8086.ir.Operation;
import i8086.ir.Type;
import i8086.ir.Value;
import i8086.ssa.SsaForm;

import java.util.List;
import java.util.Map;

/**
 * Works out what an operation comes to when everything it reads is known.
 *
 * <p>Every step is done at the width its operands have and the answer is cut to
 * that width, because that is what the machine does: {@code docs/ir.md} §6.1 says
 * {@code *} truncates to the operand width, and the same is true of every other
 * operation here. A folder that computed in 64 bits and handed back the answer
 * would turn {@code x = 0xFFFF} and {@code eval(x + 1)} into {@code 0x10000}, which
 * fits nowhere and means nothing.
 *
 * <h3>What it refuses to fold</h3>
 *
 * <p>Not everything is worth folding, and refusing is always safe — the operation
 * stays, and the machine does it at run time. Folded is only what is known:
 *
 * <ul>
 *   <li>division and remainder, because their bits depend on signedness and
 *       because dividing by zero is a trap: folding must not turn a program that
 *       would have trapped into one that does not ({@code docs/ir.md} §2.2,
 *       §5.5). Leaving {@code / 0} alone is what keeps the fault where the program
 *       put it;
 *   <li>the operators that read the carry — {@code adc}, {@code sbb}, {@code rcl},
 *       {@code rcr} — because their value depends on flags this folder does not
 *       have;
 *   <li>rotations, which are not folded yet;
 *   <li>a shift by the width or more, because the surface does not say what that
 *       is (it is an open question in {@code docs/ir.md} §12) and a folder may not
 *       invent an answer;
 *   <li>anything whose width is not known, and anything reading a value it has not
 *       worked out.
 * </ul>
 *
 * <p>What is folded, on the other hand, is folded whatever the signedness: the
 * operators that survive the list above give the same bits either way, which is
 * the same property {@link Operator#dependsOnSignedness()} reports.
 */
final class Folder {

    private Folder() {
    }

    /** The value of an operation whose operands are known, or null. */
    static Long operation(Operation operation, Map<String, Long> constants, Type type,
                          SsaForm form) {
        if (type == null || operation.operator().dependsOnSignedness()
                || operation.operator().readsFlags()) {
            return null;
        }
        List<Value> operands = operation.operands();
        Long first = value(operands.get(0), constants, form);
        if (first == null) {
            return null;
        }
        if (operation.operator().arity() == 1) {
            return apply(operation.operator(), first.longValue(), 0, type.bytes());
        }
        Long second = value(operands.get(1), constants, form);
        if (second == null) {
            return null;
        }
        return apply(operation.operator(), first.longValue(), second.longValue(), type.bytes());
    }

    /**
     * The value of a conversion, or null.
     *
     * <p>Widening needs the width it is widening from, which is why the source
     * width is asked for rather than assumed: {@code movzx} of a value whose width
     * nobody stated is not something to guess at.
     */
    static Long conversion(Conversion conversion, Long operand, int sourceBytes, Type type) {
        if (operand == null || type == null) {
            return null;
        }
        long value = operand.longValue();
        switch (conversion) {
            case LOW_BYTE:
            case LOW_WORD:
                return Long.valueOf(mask(value, conversion.resultBytes()));
            case ZERO_EXTEND:
                return sourceBytes <= 0 ? null : Long.valueOf(mask(value, sourceBytes));
            case SIGN_EXTEND:
                return sourceBytes <= 0 ? null
                        : Long.valueOf(mask(signed(value, sourceBytes), type.bytes()));
            default:
                return null;
        }
    }

    /** The value of an expression tree whose leaves are known, or null. */
    static Long expression(Expression expression, Map<String, Long> constants, Type type,
                           SsaForm form) {
        if (type == null) {
            return null;
        }
        if (expression instanceof Expression.Leaf) {
            return value(((Expression.Leaf) expression).value(), constants, form);
        }
        if (expression instanceof Expression.Complement) {
            Long operand = expression(((Expression.Complement) expression).operand(), constants,
                    type, form);
            return operand == null ? null
                    : apply(Operator.COMPLEMENT, operand.longValue(), 0, type.bytes());
        }
        Expression.Apply apply = (Expression.Apply) expression;
        if (apply.operator().dependsOnSignedness() || apply.operator().readsFlags()) {
            return null;
        }
        Long left = expression(apply.left(), constants, type, form);
        Long right = expression(apply.right(), constants, type, form);
        if (left == null || right == null) {
            return null;
        }
        return apply(apply.operator(), left.longValue(), right.longValue(), type.bytes());
    }

    /** The value of something standing on its own, or null when it is not known. */
    static Long value(Value value, Map<String, Long> constants, SsaForm form) {
        if (value instanceof Value.Number) {
            return Long.valueOf(((Value.Number) value).value());
        }
        if (value instanceof Value.Name) {
            // A name that is a label is an address, and nothing here knows where
            // the data will be, so it answers null like any other unknown.
            return constants.get(((Value.Name) value).name());
        }
        return null;
    }

    /**
     * One operation on values already known.
     *
     * <p>The one place a shift is refused is a count that reaches the width, which
     * the surface leaves undefined.
     */
    private static Long apply(Operator operator, long first, long second, int bytes) {
        int bits = bytes * 8;
        switch (operator) {
            case COMPLEMENT:
                return Long.valueOf(mask(~first, bytes));
            case ADD:
                return Long.valueOf(mask(first + second, bytes));
            case SUBTRACT:
                return Long.valueOf(mask(first - second, bytes));
            case MULTIPLY:
            case MULTIPLY_UNSIGNED:
            case MULTIPLY_SIGNED:
                return Long.valueOf(mask(first * second, bytes));
            case AND:
                return Long.valueOf(mask(first & second, bytes));
            case OR:
                return Long.valueOf(mask(first | second, bytes));
            case XOR:
                return Long.valueOf(mask(first ^ second, bytes));
            case SHIFT_LEFT:
                return second < 0 || second >= bits ? null
                        : Long.valueOf(mask(first << second, bytes));
            case SHIFT_RIGHT:
                return second < 0 || second >= bits ? null
                        : Long.valueOf(mask(mask(first, bytes) >>> second, bytes));
            case SHIFT_ARITHMETIC:
                return second < 0 || second >= bits ? null
                        : Long.valueOf(mask(signed(first, bytes) >> second, bytes));
            default:
                return null;
        }
    }

    /** The low bytes of a value, which is what a result of that width is. */
    private static long mask(long value, int bytes) {
        if (bytes >= Long.BYTES) {
            return value;
        }
        return value & ((1L << (bytes * 8)) - 1);
    }

    /** The same bits, read as a signed value of that width. */
    private static long signed(long value, int bytes) {
        int shift = 64 - bytes * 8;
        return (mask(value, bytes) << shift) >> shift;
    }
}
