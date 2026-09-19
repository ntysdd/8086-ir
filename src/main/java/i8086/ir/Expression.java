package i8086.ir;

import i8086.SourcePos;

/**
 * An expression: the tree written between the brackets of {@code eval(...)} or
 * {@code expr(...)}.
 *
 * <p>A leaf is a {@link Value}, which is why the same shapes serve both: a name
 * is a name whether it stands alone or is added to something. What differs
 * between {@code eval} and {@code expr} is not the tree but what may be in it and
 * what the whole thing promises ({@code docs/ir.md} §5).
 *
 * <p>The tree is kept as written rather than flattened to a sequence, because
 * precedence decides what was written and the printer has to write it back
 * ({@code AGENTS.md}, invariant 5).
 */
public abstract class Expression {

    private final SourcePos position;

    Expression(SourcePos position) {
        this.position = position;
    }

    public SourcePos position() {
        return position;
    }

    /** A value standing on its own. */
    public static final class Leaf extends Expression {

        private final Value value;

        public Leaf(SourcePos position, Value value) {
            super(position);
            this.value = value;
        }

        public Value value() {
            return value;
        }
    }

    /** Two operands and the operator between them. */
    public static final class Apply extends Expression {

        private final Operator operator;
        private final Expression left;
        private final Expression right;

        public Apply(SourcePos position, Operator operator, Expression left, Expression right) {
            super(position);
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        public Operator operator() {
            return operator;
        }

        public Expression left() {
            return left;
        }

        public Expression right() {
            return right;
        }
    }

    /** {@code ~x}, the one operator that takes a single operand. */
    public static final class Complement extends Expression {

        private final Expression operand;

        public Complement(SourcePos position, Expression operand) {
            super(position);
            this.operand = operand;
        }

        public Expression operand() {
            return operand;
        }
    }
}
