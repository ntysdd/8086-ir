package i8086.pass;

import i8086.asm.Numbers;
import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.MemoryOperand;
import i8086.ir.Operation;
import i8086.ir.Place;
import i8086.ir.Type;
import i8086.ir.Value;
import i8086.ssa.Block;
import i8086.ssa.Effects;
import i8086.ssa.Phi;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out which values are known, and writes them where they are read.
 *
 * <p>A version of a variable that always has the same value is replaced, at every
 * use, by that value; once nothing reads the definition, dead value elimination
 * takes it away. SSA is what makes this safe and this simple: a version has one
 * definition, so "this value is 3" is true at every use of it with nothing to
 * check at the use itself.
 *
 * <h3>The flags are the interesting part</h3>
 *
 * <p>Folding an assignment away is not always allowed, because an assignment that
 * computes a value with {@code eval} also defines the flags — the operation's
 * flags, exactly ({@code docs/ir.md} §5.1). Replacing it with a value would drop
 * them, so it may only be done when nothing reads them. In SSA that question has a
 * one-line answer: count the uses of the flags version the statement defines. That
 * is the rule §5.1 states, made checkable by having the flags be a value.
 *
 * <p>Two other ways of dropping a flags effect are always safe, and worth stating
 * because they look like the same thing and are not:
 *
 * <ul>
 *   <li>a value computed with {@code expr} or a conversion does not <em>define</em>
 *       the flags, it <em>gives them up</em>, and the form records that by leaving
 *       the flags undefined after it. Folding such an assignment to a literal drops
 *       that too — and it costs nothing, because a flag value that is undefined
 *       cannot be read: the surface refuses that before any of this runs
 *       ({@code docs/ir.md} §4.3, {@code docs/ssa.md} §4).
 *   <li>A comparison is not folded at all. Its whole result is the flags, and this
 *       pass has no flag values to compute with — the granularity question in
 *       {@code docs/ir.md} §4.3 is open, and inventing a per-whole-set answer here
 *       would be the second implementation of the flags the verifier's notes warn
 *       about.
 * </ul>
 */
public final class ConstantPropagation implements Pass {

    @Override
    public String name() {
        return "constant propagation";
    }

    @Override
    public SsaForm run(SsaForm form) {
        Map<String, Long> constants = new LinkedHashMap<String, Long>();
        collect(form, constants);
        if (constants.isEmpty()) {
            return form;
        }
        Uses uses = Uses.of(form);

        List<List<Phi>> phis = new ArrayList<List<Phi>>();
        List<List<SsaStatement>> statements = new ArrayList<List<SsaStatement>>();
        for (Block block : form.cfg().blocks()) {
            // A φ is untouched: its operands are versions of its own variable, and
            // the constant they share is what its uses are given instead.
            phis.add(new ArrayList<Phi>(form.phis(block)));
            List<SsaStatement> rewritten = new ArrayList<SsaStatement>();
            for (SsaStatement statement : form.statements(block)) {
                rewritten.add(rewrite(statement, constants, uses, form));
            }
            statements.add(rewritten);
        }
        return form.rewriting(phis, statements);
    }

    /**
     * Finds the values that are always the same.
     *
     * <p>Repeated until nothing moves, because one known value can make the next
     * one known: a copy of a constant is a constant, and a φ whose operands are all
     * the same constant is that constant however the loop is written.
     */
    private static void collect(SsaForm form, Map<String, Long> constants) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Block block : form.cfg().blocks()) {
                for (Phi phi : form.phis(block)) {
                    if (constants.containsKey(phi.name())) {
                        continue;
                    }
                    Long value = merged(phi, constants);
                    if (value != null) {
                        constants.put(phi.name(), value);
                        changed = true;
                    }
                }
                for (SsaStatement statement : form.statements(block)) {
                    String written = Effects.writtenVariable(statement.item());
                    if (written == null || constants.containsKey(written)) {
                        continue;
                    }
                    Long value = constantOf(statement.item(), constants, form,
                            form.typeOf(written));
                    if (value != null) {
                        constants.put(written, value);
                        changed = true;
                    }
                }
            }
        }
    }

    /** The value a φ always has: the one its operands agree on, or null. */
    private static Long merged(Phi phi, Map<String, Long> constants) {
        Long found = null;
        for (String operand : phi.operands()) {
            Long value = constants.get(operand);
            if (value == null || (found != null && !found.equals(value))) {
                return null;
            }
            found = value;
        }
        return found;
    }

    /** The value an assignment always produces, or null when it is not known. */
    private static Long constantOf(Item item, Map<String, Long> constants, SsaForm form,
                                   Type type) {
        if (!(item instanceof Item.Assign)) {
            return null;
        }
        Value value = ((Item.Assign) item).value();
        if (value instanceof Value.Number) {
            return Long.valueOf(((Value.Number) value).value());
        }
        if (value instanceof Value.Name) {
            return constants.get(((Value.Name) value).name());
        }
        if (value instanceof Value.Eval) {
            return Folder.operation(((Value.Eval) value).operation(), constants, type, form);
        }
        if (value instanceof Value.Expr) {
            return Folder.expression(((Value.Expr) value).expression(), constants, type, form);
        }
        if (value instanceof Value.Convert) {
            Value.Convert convert = (Value.Convert) value;
            return Folder.conversion(convert.conversion(),
                    Folder.value(convert.operand(), constants, form),
                    bytesOf(convert.operand(), form), type);
        }
        return null;
    }

    /** How wide a value is, when it has a version to take the width from. */
    private static int bytesOf(Value value, SsaForm form) {
        if (value instanceof Value.Name) {
            Type type = form.typeOf(((Value.Name) value).name());
            return type == null ? 0 : type.bytes();
        }
        return 0;
    }

    // --- writing it down ---------------------------------------------------

    private static SsaStatement rewrite(SsaStatement statement, Map<String, Long> constants,
                                        Uses uses, SsaForm form) {
        Item item = replaceConstants(statement.item(), constants, form);
        Map<String, String> definedFlags = new LinkedHashMap<String, String>(statement.definedFlags());

        String written = Effects.writtenVariable(item);
        Long value = written == null ? null : constants.get(written);
        if (value != null && !used(definedFlags, uses)) {
            item = literal((Item.Assign) item, value.longValue());
            definedFlags.clear();
        }
        return new SsaStatement(item, definedFlags);
    }

    /** Whether any of these flag versions is read anywhere. */
    private static boolean used(Map<String, String> definedFlags, Uses uses) {
        for (String version : definedFlags.values()) {
            if (uses.isUsed(version)) {
                return true;
            }
        }
        return false;
    }

    /** The same statement with a value that is a literal instead of a computation. */
    private static Item literal(Item.Assign assign, long value) {
        return new Item.Assign(assign.position(), assign.place(),
                new Value.Number(assign.position(), value, Numbers.spelling(value)));
    }

    private static Item replaceConstants(Item item, Map<String, Long> constants, SsaForm form) {
        if (widthComesFromAValue(item)) {
            // Not one value in this statement may become a literal, for the reason
            // below. What is given up is small: the fold that would have happened is
            // one this statement cannot describe.
            return item;
        }
        if (item instanceof Item.Assign) {
            Item.Assign assign = (Item.Assign) item;
            return new Item.Assign(item.position(), assign.place(),
                    replaceConstants(assign.value(), constants, form));
        }
        if (item instanceof Item.Compare) {
            Item.Compare compare = (Item.Compare) item;
            // A comparison of two literals has no width to take ({@code docs/ir.md}
            // §3.2), so when both sides are known the left one is left as it is and
            // only the right is written out. That is also the side worth
            // substituting: a variable on the left is what lets the machine compare
            // against an immediate at all.
            boolean bothKnown = Folder.value(compare.left(), constants, form) != null
                    && Folder.value(compare.right(), constants, form) != null;
            return new Item.Compare(item.position(), compare.kind(),
                    bothKnown ? compare.left() : replaceConstants(compare.left(), constants, form),
                    replaceConstants(compare.right(), constants, form));
        }
        if (item instanceof Item.Eval) {
            return new Item.Eval(item.position(),
                    replaceConstants(((Item.Eval) item).operation(), constants, form));
        }
        return item;
    }

    /**
     * A value with every known thing standing in it written out as itself.
     *
     * <p>A memory operand's base is not touched. The address in {@code [p]} is an
     * address rather than a value, and a displacement that happens to be known is
     * not a literal — {@code [0x1234]} would be a different program from
     * {@code [p]}.
     */
    private static Value replaceConstants(Value value, Map<String, Long> constants,
                                         SsaForm form) {
        if (value instanceof Value.Name) {
            Value.Name name = (Value.Name) value;
            Long constant = constants.get(name.name());
            return constant == null ? value
                    : new Value.Number(name.position(), constant.longValue(),
                    Numbers.spelling(constant.longValue()));
        }
        if (value instanceof Value.Eval) {
            return new Value.Eval(value.position(),
                    replaceConstants(((Value.Eval) value).operation(), constants, form));
        }
        if (value instanceof Value.Expr) {
            return new Value.Expr(value.position(),
                    replaceConstants(((Value.Expr) value).expression(), constants, form));
        }
        if (value instanceof Value.Convert) {
            Value.Convert convert = (Value.Convert) value;
            return new Value.Convert(value.position(), convert.conversion(),
                    replaceConstants(convert.operand(), constants, form));
        }
        return value;
    }

    /**
     * Whether this statement takes its width from a value rather than from a place.
     *
     * <p>A memory operand with no {@code byte}, {@code word} or {@code dword} prefix
     * has no width of its own: {@code [0x32] = x} is sixteen bits because {@code x} is,
     * and so is {@code cmp [0x32], x} ({@code docs/ir.md} §3.4). Replacing that
     * {@code x} with a literal leaves the statement with no width at all — which the
     * verifier then refuses, correctly, because what it is handed really is not the
     * program that was written. So the values here stay as they are until the operand
     * says its width itself.
     *
     * <p>The other half of the same rule is already written out a few lines above: a
     * comparison of two values that are both known keeps one of them, for exactly this
     * reason.
     */
    private static boolean widthComesFromAValue(Item item) {
        if (item instanceof Item.Assign) {
            return unsizedMemory(((Item.Assign) item).place());
        }
        if (item instanceof Item.Compare) {
            Item.Compare compare = (Item.Compare) item;
            return unsizedMemory(compare.left()) || unsizedMemory(compare.right());
        }
        if (item instanceof Item.Eval) {
            for (Value operand : ((Item.Eval) item).operation().operands()) {
                if (unsizedMemory(operand)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean unsizedMemory(Place place) {
        return place instanceof Place.Memory
                && unsizedMemory(((Place.Memory) place).operand());
    }

    private static boolean unsizedMemory(Value value) {
        return value instanceof Value.Memory
                && unsizedMemory(((Value.Memory) value).operand());
    }

    private static boolean unsizedMemory(MemoryOperand operand) {
        return operand.size() == null;
    }

    private static Operation replaceConstants(Operation operation, Map<String, Long> constants,
                                              SsaForm form) {
        List<Value> operands = new ArrayList<Value>();
        for (Value operand : operation.operands()) {
            operands.add(replaceConstants(operand, constants, form));
        }
        return new Operation(operation.position(), operation.operator(), operands);
    }

    private static Expression replaceConstants(Expression expression,
                                               Map<String, Long> constants, SsaForm form) {
        if (expression instanceof Expression.Leaf) {
            Expression.Leaf leaf = (Expression.Leaf) expression;
            return new Expression.Leaf(expression.position(),
                    replaceConstants(leaf.value(), constants, form));
        }
        if (expression instanceof Expression.Unary) {
            Expression.Unary unary = (Expression.Unary) expression;
            return new Expression.Unary(expression.position(), unary.operator(),
                    replaceConstants(unary.operand(), constants, form));
        }
        Expression.Apply apply = (Expression.Apply) expression;
        return new Expression.Apply(expression.position(), apply.operator(),
                replaceConstants(apply.left(), constants, form),
                replaceConstants(apply.right(), constants, form));
    }
}
