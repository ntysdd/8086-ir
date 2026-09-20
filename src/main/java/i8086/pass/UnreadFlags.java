package i8086.pass;

import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.Names;
import i8086.ir.Operation;
import i8086.ir.Value;
import i8086.ssa.Block;
import i8086.ssa.Phi;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * An operation whose flags nobody reads stops claiming them.
 *
 * <p>The surface has two ways to write arithmetic, and the difference is what they
 * promise about the flags: {@code eval} leaves them defined, as the operation's
 * ({@code docs/ir.md} §5.1), and {@code expr} gives them up (§5.2). That promise is
 * not free. A machine form that keeps the flags is often longer than one that does
 * not — {@code add ax, 1} is three bytes and {@code inc ax} is one — so an
 * operation that says it left the flags behind can only be given the short form
 * when the selector can show that nothing looks at them.
 *
 * <p>The selector cannot show that. It sees the module, where the flags are a thing
 * that gets overwritten, and the question of whether a particular overwrite is
 * observable is a question about control flow. <b>In SSA it is a question about one
 * value</b>: the version this statement defines is either read or it is not. So
 * this pass answers it, once, and rewrites the operation as the same operation
 * written with {@code expr} — which is precisely the way to say "and I do not care
 * about the flags".
 *
 * <p>What it will not touch is an operation {@code expr} cannot hold: one that
 * reads the flags itself ({@code adc}, {@code sbb}, {@code rcl}, {@code rcr}), one
 * with a load in it, since {@code expr} works on values already in registers
 * (§5.2), or one whose operands are themselves trees.
 *
 * <p>The rewrite is legal because it only ever <em>removes</em> a claim: the flags
 * become undefined where they used to be defined, and by the time this runs nobody
 * was reading them.
 */
public final class UnreadFlags implements Pass {

    @Override
    public String name() {
        return "unread flags";
    }

    @Override
    public SsaForm run(SsaForm form) {
        Uses uses = Uses.of(form);
        List<List<Phi>> phis = new ArrayList<List<Phi>>();
        List<List<SsaStatement>> statements = new ArrayList<List<SsaStatement>>();
        for (Block block : form.cfg().blocks()) {
            phis.add(new ArrayList<Phi>(form.phis(block)));
            List<SsaStatement> rewritten = new ArrayList<SsaStatement>();
            for (SsaStatement statement : form.statements(block)) {
                rewritten.add(relax(statement, uses));
            }
            statements.add(rewritten);
        }
        return form.rewriting(phis, statements);
    }

    private static SsaStatement relax(SsaStatement statement, Uses uses) {
        String flags = statement.definedFlag(Names.FLAGS);
        if (flags == null || uses.isUsed(flags)
                || !(statement.item() instanceof Item.Assign)) {
            return statement;
        }
        Item.Assign assign = (Item.Assign) statement.item();
        if (!(assign.value() instanceof Value.Eval)) {
            return statement;
        }
        Operation operation = ((Value.Eval) assign.value()).operation();
        if (!mayBeAnExpression(operation)) {
            return statement;
        }
        Value expression = new Value.Expr(assign.value().position(), tree(operation));
        return new SsaStatement(
                new Item.Assign(assign.position(), assign.place(), expression),
                Names.FLAGS, null);
    }

    /** Whether {@code expr} can hold this operation. */
    private static boolean mayBeAnExpression(Operation operation) {
        if (operation.readsFlags()) {
            return false;
        }
        for (Value operand : operation.operands()) {
            if (operand instanceof Value.Memory || operand instanceof Value.Eval
                    || operand instanceof Value.Expr) {
                return false;
            }
        }
        return true;
    }

    private static Expression tree(Operation operation) {
        List<Value> operands = operation.operands();
        if (operation.operator().arity() == 1) {
            return new Expression.Unary(operation.position(), operation.operator(),
                    leaf(operands.get(0)));
        }
        return new Expression.Apply(operation.position(), operation.operator(),
                leaf(operands.get(0)), leaf(operands.get(1)));
    }

    private static Expression leaf(Value value) {
        return new Expression.Leaf(value.position(), value);
    }
}
