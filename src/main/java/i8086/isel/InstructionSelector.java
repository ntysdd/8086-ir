package i8086.isel;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.Module;
import i8086.ir.Operation;
import i8086.ir.Operator;
import i8086.ir.Place;
import i8086.ir.Value;
import i8086.target.Expansion;
import i8086.target.Form;
import i8086.target.Shape;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the IR into instructions, choosing the form of each operation.
 *
 * <p>What it does <em>not</em> do is decide where a value lives: every operand
 * that stands for a variable becomes an {@link Operand.Virtual}, and register
 * allocation turns those into registers afterwards. That split is what lets one
 * selection feed any allocator, and it is why an instruction chosen here cannot
 * be printed yet.
 *
 * <p>It asks the target for everything machine-shaped ({@code AGENTS.md},
 * invariant 2). It has no mnemonic of its own: it says "an addition, a register
 * and a literal", takes the smallest form that fits, and takes one that does not
 * keep the flags only when nothing can read them. That last question is answered
 * by the surface itself: an operation written with {@code expr} has already
 * given the flags up — {@code docs/ir.md} §5.2 — so a smaller instruction that
 * leaves different flags is available there and not in {@code eval}.
 *
 * <p>What is not built yet is refused, with a position and a reason: memory
 * operands, conversions, comparisons, branches, and multiplications this machine
 * has no shift trick for.
 */
public final class InstructionSelector {

    /** Temps are named so that no name a person can write can collide with one. */
    private static final String TEMP_PREFIX = "$t";

    private final Target target;
    private int temps;
    private List<Instruction> out;

    public InstructionSelector(Target target) {
        this.target = target;
    }

    public static Selection select(Module module, Target target) {
        return new InstructionSelector(target).run(module);
    }

    private Selection run(Module module) {
        List<Selection.Piece> pieces = new ArrayList<Selection.Piece>();
        for (Item item : module.items()) {
            out = new ArrayList<Instruction>();
            select(item);
            pieces.add(new Selection.Piece(item, out));
        }
        return new Selection(pieces);
    }

    // --- items -------------------------------------------------------------

    private void select(Item item) {
        if (item instanceof Item.Var || item instanceof Item.Label || item instanceof Item.Data) {
            return; // a declaration is not code, and a label is the emitter's to write
        }
        if (item instanceof Item.Return) {
            out.add(new Instruction(item.position(), "ret", new ArrayList<Operand>()));
            return;
        }
        if (item instanceof Item.InlineAsm) {
            // Already instructions, and already decided: an inline block names
            // its registers itself, which is the whole point of it.
            out.addAll(((Item.InlineAsm) item).body());
            return;
        }
        if (item instanceof Item.Assign) {
            selectAssign((Item.Assign) item);
            return;
        }
        if (item instanceof Item.Eval) {
            // The value is thrown away, so it needs somewhere to go that is not
            // anybody's variable; the flags are why the statement was written, so
            // they are wanted here even though the value is not.
            emitOperation(((Item.Eval) item).operation(), temp(), true);
            return;
        }
        throw notYet(item, "this statement");
    }

    private void selectAssign(Item.Assign assign) {
        if (!(assign.place() instanceof Place.Name)) {
            throw notYet(assign, "a store into memory");
        }
        emitValue(assign.value(), ((Place.Name) assign.place()).name(), flagsMayBeRead(assign.value()));
    }

    /** Whether the flags this value leaves can be read by anything afterwards. */
    private static boolean flagsMayBeRead(Value value) {
        return value instanceof Value.Eval;
    }

    // --- values ------------------------------------------------------------

    /** Computes a value into {@code destination}, which names a variable. */
    private void emitValue(Value value, String destination, boolean flagsMayBeRead) {
        if (value instanceof Value.Name) {
            emitMove(destination, ((Value.Name) value).name(), value.position());
            return;
        }
        if (value instanceof Value.Number) {
            Value.Number literal = (Value.Number) value;
            out.add(new Instruction(literal.position(), "mov", operands(
                    virtual(destination, literal.position()),
                    new Operand.Number(literal.position(), literal.value(), literal.spelling()))));
            return;
        }
        if (value instanceof Value.Eval) {
            emitOperation(((Value.Eval) value).operation(), destination, true);
            return;
        }
        if (value instanceof Value.Expr) {
            emitExpression(((Value.Expr) value).expression(), destination);
            return;
        }
        if (value instanceof Value.Convert) {
            throw notYet(value.position(), "a conversion");
        }
        throw notYet(value.position(), "a load");
    }

    /** An expression tree, which is what {@code expr} is for. */
    private void emitExpression(Expression expression, String destination) {
        if (expression instanceof Expression.Leaf) {
            // Inside expr the flags are already given up, so nothing below this
            // point may re-introduce a claim about them.
            emitValue(((Expression.Leaf) expression).value(), destination, false);
            return;
        }
        if (expression instanceof Expression.Complement) {
            Expression operand = ((Expression.Complement) expression).operand();
            emitExpression(operand, destination);
            emitInPlace(Operator.COMPLEMENT, destination, null, expression.position(), false);
            return;
        }

        Expression.Apply apply = (Expression.Apply) expression;
        Operator operator = apply.operator();
        Expansion expansion = expansionFor(operator, literalOf(apply.right()),
                sourceRegister(apply.left()), destination, apply.position());
        if (expansion != null) {
            requireFlagsMayBeLost(expansion.keepsFlags(), apply.position(), operator, false);
            out.addAll(expansion.instructions());
            return;
        }

        if (isLiteral(apply.right())) {
            emitExpression(apply.left(), destination);
            emitInPlace(operator, destination, ((Expression.Leaf) apply.right()).value(),
                    apply.position(), false);
            return;
        }

        // The right-hand side needs a register of its own, so it is computed
        // first, into a temp, and the left goes straight into the destination.
        String scratch = temp();
        emitExpression(apply.right(), scratch);
        emitExpression(apply.left(), destination);
        emitInPlace(operator, destination, new Value.Name(apply.right().position(), scratch),
                apply.position(), false);
    }

    /**
     * A trick the machine has for this node, or null when it has none.
     *
     * <p>Both forms of an operation come through here, {@code eval} included:
     * when the trick exists but its flags are not the ones the operation would
     * leave, the complaint the writer needs is about the flags and not about the
     * missing instruction, and it is this path that can say so.
     */
    private Expansion expansionFor(Operator operator, Value right, String source,
                                   String destination, SourcePos where) {
        if (source == null || !(right instanceof Value.Number)) {
            return null;
        }
        long constant = ((Value.Number) right).value();
        Operand target0 = virtual(destination, where);
        Operand source0 = virtual(source, where);
        if (operator == Operator.MULTIPLY || operator == Operator.MULTIPLY_SIGNED
                || operator == Operator.MULTIPLY_UNSIGNED) {
            return target.multiplyByConstant(where, target0, source0, constant);
        }
        if (operator == Operator.SHIFT_LEFT || operator == Operator.SHIFT_RIGHT
                || operator == Operator.SHIFT_ARITHMETIC) {
            String shift = onlyFormMnemonic(operator);
            return shift == null ? null
                    : target.shiftByConstant(where, shift, target0, source0, constant);
        }
        return null;
    }

    /** The register a leaf already names, or null when it is not one. */
    private static String sourceRegister(Expression expression) {
        if (!(expression instanceof Expression.Leaf)) {
            return null;
        }
        Value value = ((Expression.Leaf) expression).value();
        return value instanceof Value.Name ? ((Value.Name) value).name() : null;
    }

    /** The register an operand already names, or null when it is a literal. */
    private static String sourceRegister(Value value) {
        return value instanceof Value.Name ? ((Value.Name) value).name() : null;
    }

    private static boolean isLiteral(Expression expression) {
        return expression instanceof Expression.Leaf
                && ((Expression.Leaf) expression).value() instanceof Value.Number;
    }

    // --- one operation -----------------------------------------------------

    /**
     * One operation, into {@code destination}.
     *
     * <p>The first operand goes into the destination — that is the move that can
     * be elided when the destination is already where the first operand lives —
     * and the operation then happens in place, with the second operand as it
     * stands.
     */
    private void emitOperation(Operation operation, String destination, boolean flagsMayBeRead) {
        List<Value> operands = operation.operands();
        Operator operator = operation.operator();
        Value second = operands.size() == 1 ? null : operands.get(1);

        Expansion expansion = expansionFor(operator, second,
                sourceRegister(operands.get(0)), destination, operation.position());
        if (expansion != null) {
            requireFlagsMayBeLost(expansion.keepsFlags(), operation.position(), operator,
                    flagsMayBeRead);
            out.addAll(expansion.instructions());
            return;
        }

        emitValue(operands.get(0), destination, flagsMayBeRead);
        emitInPlace(operator, destination, second, operation.position(), flagsMayBeRead);
    }

    /** The literal a value is, or null when it is not one. */
    private static Value literalOf(Expression expression) {
        return isLiteral(expression) ? ((Expression.Leaf) expression).value() : null;
    }

    /** The operation itself, on a value that is already in {@code destination}. */
    private void emitInPlace(Operator operator, String destination, Value second, SourcePos where,
                             boolean flagsMayBeRead) {
        List<Form> forms = target.forms(operator);
        if (forms.isEmpty()) {
            throw new CompileError(where,
                    "no form for '" + operator.spelling() + "' is available yet: the instruction "
                            + "this machine has for it keeps an operand in a fixed register, and "
                            + "implicit operands are not expressible here yet");
        }

        List<Operand> operands = new ArrayList<Operand>();
        operands.add(virtual(destination, where));
        if (second != null) {
            operands.add(operandOf(second));
        }

        Form best = null;
        List<Operand> written = null;
        Form refused = null;
        for (Form form : forms) {
            List<Operand> candidate = writtenOperands(form, operands);
            if (candidate == null) {
                continue;
            }
            if (!form.keepsFlags() && flagsMayBeRead) {
                // Smaller because it does less, and here the less it does is
                // something the program can still look at.
                if (refused == null || form.bytes() < refused.bytes()) {
                    refused = form;
                }
                continue;
            }
            if (best == null || form.bytes() < best.bytes()) {
                best = form;
                written = candidate;
            }
        }
        if (best == null) {
            throw noForm(operator, where, operands, refused);
        }
        out.add(new Instruction(where, best.mnemonic(), written));
    }

    /** Why there is nothing to emit, saying which of the two reasons it is. */
    private static CompileError noForm(Operator operator, SourcePos where, List<Operand> operands,
                                       Form refused) {
        if (refused != null) {
            return new CompileError(where,
                    "the form this machine has for '" + operator.spelling() + "' here is '"
                            + refused.mnemonic() + "', and it leaves different flags than the "
                            + "operation does; these flags can still be read, so write the value "
                            + "with expr(...), which gives them up (docs/ir.md §5.2)");
        }
        return new CompileError(where,
                "no form this target has fits '" + operator.spelling() + "' with these "
                        + "operands: a memory operand is not handled yet, and neither is a "
                        + "shift by a count that is not a small constant");
    }

    /**
     * The operands the instruction writes, or null when this form does not fit.
     *
     * <p>This is what makes {@code inc} reachable for an addition. It does the
     * work of one operand and a literal, so a form may take one operand fewer
     * than the operation has, provided it says which literal it absorbs
     * ({@link Form#literalOnly()}) and that literal is the one standing there.
     */
    private static List<Operand> writtenOperands(Form form, List<Operand> operands) {
        int count = form.operands().size();
        List<Operand> written;
        if (count == operands.size()) {
            written = operands;
        } else if (count + 1 == operands.size()
                && form.literalOnly() != null
                && operands.get(count) instanceof Operand.Number
                && ((Operand.Number) operands.get(count)).value()
                        == form.literalOnly().longValue()) {
            written = new ArrayList<Operand>(operands.subList(0, count));
        } else {
            return null;
        }

        if (form.literalOnly() != null && !mentionsLiteral(operands, form.literalOnly().longValue())) {
            return null;
        }
        for (int i = 0; i < written.size(); i++) {
            if (Shape.of(written.get(i)) != form.operands().get(i)) {
                return null;
            }
        }
        return written;
    }

    private static boolean mentionsLiteral(List<Operand> operands, long value) {
        for (Operand operand : operands) {
            if (operand instanceof Operand.Number && ((Operand.Number) operand).value() == value) {
                return true;
            }
        }
        return false;
    }

    private static Operand operandOf(Value value) {
        if (value instanceof Value.Number) {
            Value.Number literal = (Value.Number) value;
            return new Operand.Number(literal.position(), literal.value(), literal.spelling());
        }
        return virtual(registerNameOf(value), value.position());
    }

    /** Refuses a form that changes the flags where they are still wanted. */
    private static void requireFlagsMayBeLost(boolean keepsFlags, SourcePos where,
                                              Operator operator, boolean flagsMayBeRead) {
        if (!keepsFlags && flagsMayBeRead) {
            throw new CompileError(where,
                    "the way this machine does '" + operator.spelling() + "' leaves different "
                            + "flags from the operation, and here they can still be read; write the "
                            + "value with expr(...), which gives them up (docs/ir.md §5.2), or say "
                            + "which instruction you mean (docs/ir.md §5.5)");
        }
    }

    private String onlyFormMnemonic(Operator operator) {
        List<Form> forms = target.forms(operator);
        return forms.isEmpty() ? null : forms.get(0).mnemonic();
    }

    // --- small pieces ------------------------------------------------------

    private void emitMove(String destination, String source, SourcePos where) {
        if (destination.equals(source)) {
            return; // a value assigned to itself is not an instruction
        }
        out.add(new Instruction(where, "mov",
                operands(virtual(destination, where), virtual(source, where))));
    }

    private static String registerNameOf(Value value) {
        if (value instanceof Value.Name) {
            return ((Value.Name) value).name();
        }
        throw notYet(value.position(), "this operand, which is not a register");
    }

    private static Operand virtual(String name, SourcePos where) {
        return new Operand.Virtual(where, name);
    }

    private static List<Operand> operands(Operand... operands) {
        List<Operand> all = new ArrayList<Operand>(operands.length);
        for (Operand operand : operands) {
            all.add(operand);
        }
        return all;
    }

    private String temp() {
        return TEMP_PREFIX + temps++;
    }

    private static CompileError notYet(Item item, String description) {
        return notYet(item.position(), description);
    }

    private static CompileError notYet(SourcePos where, String description) {
        return new CompileError(where, "instruction selection cannot emit " + description + " yet");
    }
}
