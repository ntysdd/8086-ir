package i8086.ir;

import i8086.SourcePos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One operator applied to its operands: the whole of what {@code eval(...)} may
 * be ({@code docs/ir.md} §5.1).
 *
 * <p>The type carries the rule, rather than the verifier having to check it: an
 * operation has exactly as many operands as its operator takes, and its operands
 * are values, so a tree cannot be built here at all. That is the point of the
 * form — with one operation there is no evaluation order to reason about and no
 * question which operation's flags come out.
 *
 * <p>An operand may be a load: {@code eval(a + [p])} is one addition whose second
 * operand happens to come from memory. A load is an operand and not an operation,
 * so this stays one operation.
 */
public final class Operation {

    private final SourcePos position;
    private final Operator operator;
    private final List<Value> operands;

    public Operation(SourcePos position, Operator operator, List<Value> operands) {
        if (operands.size() != operator.arity()) {
            throw new IllegalArgumentException(
                    operator.spelling() + " takes " + operator.arity() + " operands, got "
                            + operands.size());
        }
        this.position = position;
        this.operator = operator;
        this.operands = Collections.unmodifiableList(new ArrayList<Value>(operands));
    }

    public SourcePos position() {
        return position;
    }

    public Operator operator() {
        return operator;
    }

    /** The operands, in the order the operator takes them. */
    public List<Value> operands() {
        return operands;
    }

    /** Whether this operation reads the flags, and so needs them defined first. */
    public boolean readsFlags() {
        return operator.readsFlags();
    }
}
