package i8086.isel;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.ir.Expression;
import i8086.ir.Item;
import i8086.ir.MemoryOperand;
import i8086.ir.Module;
import i8086.ir.Names;
import i8086.ir.Operation;
import i8086.ir.Operator;
import i8086.ir.Place;
import i8086.ir.Signedness;
import i8086.ir.Type;
import i8086.ir.Value;
import i8086.target.Expansion;
import i8086.target.Form;
import i8086.target.Shape;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.Arrays;
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
 * <p>Memory is where the shapes stop being uniform. An address is not a value: what
 * may stand inside the brackets is three registers on this machine, so a value used
 * as one has fewer places to live than a value that is only computed with, and the
 * operand shapes a form states are what say which is which. A load or a store is
 * therefore not "an operator with forms" but its own shape, and the forms for it
 * come from the target like everything else.
 *
 * <p>What is not built yet is refused, with a position and a reason: conversions,
 * an access narrower or wider than a register, a store of something that has to be
 * computed first, and multiplications this machine has no shift trick for.
 */
public final class InstructionSelector {

    /** Temps are named so that no name a person can write can collide with one. */
    private static final String TEMP_PREFIX = "$t";

    private final Target target;
    private final Names names;
    private int temps;
    private List<Instruction> out;
    private boolean controlFlow;

    public InstructionSelector(Module module, Target target) {
        this.target = target;
        this.names = Names.of(module);
    }

    public static Selection select(Module module, Target target) {
        return new InstructionSelector(module, target).run(module);
    }

    private Selection run(Module module) {
        List<Selection.Piece> pieces = new ArrayList<Selection.Piece>();
        for (Item item : module.items()) {
            out = new ArrayList<Instruction>();
            select(item);
            for (Instruction instruction : out) {
                if (target.isBranch(instruction.mnemonic())) {
                    controlFlow = true;
                }
            }
            pieces.add(new Selection.Piece(item, out));
        }
        return new Selection(pieces, controlFlow);
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
        if (item instanceof Item.Compare) {
            selectCompare((Item.Compare) item);
            return;
        }
        if (item instanceof Item.Jump) {
            out.add(new Instruction(item.position(), target.jumpMnemonic(), operands(
                    new Operand.Name(item.position(), ((Item.Jump) item).target()))));
            return;
        }
        if (item instanceof Item.Branch) {
            Item.Branch branch = (Item.Branch) item;
            out.add(new Instruction(item.position(), branch.condition(), operands(
                    new Operand.Name(item.position(), branch.target()))));
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
        if (assign.place() instanceof Place.Memory) {
            selectStore(assign);
            return;
        }
        String destination = ((Place.Name) assign.place()).name();
        if (isLabel(assign.value())) {
            emitLabelAddress(destination, (Value.Name) assign.value());
            return;
        }
        emitValue(assign.value(), destination, flagsMayBeRead(assign.value()));
    }

    /**
     * Writes a value into memory.
     *
     * <p>What can go there is what the machine can write in one instruction: a
     * register or a literal. Anything else is a value that has to be computed first,
     * and the surface has a way to say that — put it in a variable — so a store of a
     * computation is refused rather than given a temporary nobody wrote.
     */
    private void selectStore(Item.Assign assign) {
        Place.Memory place = (Place.Memory) assign.place();
        Value value = assign.value();
        boolean literal = value instanceof Value.Number;
        if (!literal && !(value instanceof Value.Name)) {
            throw new CompileError(assign.position(),
                    "a store of a value that has to be computed first is not something this "
                            + "compiler can emit yet: put the value in a variable and store that "
                            + "(docs/ir.md §5.3)");
        }
        requireWordAccess(place.operand(), "store");

        List<Operand> operands = operands(memory(place.operand()), operandOf(value));
        Form form = smallest(literal ? target.storeLiteralForms() : target.storeForms(),
                operands);
        if (form == null) {
            throw noFormFor("a store", assign.position(), operands);
        }
        out.add(new Instruction(assign.position(), form.mnemonic(), operands));
    }

    /**
     * Reads a value out of memory.
     *
     * <p>The load is where the width of the access has to be said out loud: the
     * register it goes into says it for a word, and the surface says it with a
     * prefix when the access is narrower than the register. A byte load into a
     * sixteen-bit register would need the low half of one, and nothing here knows
     * how to name half a register yet — so it is refused with that as the reason.
     */
    private void emitLoad(Value.Memory load, String destination) {
        requireWordAccess(load.operand(), "load");
        List<Operand> operands = operands(virtual(destination, load.position()),
                memory(load.operand()));
        Form form = smallest(target.loadForms(), operands);
        if (form == null) {
            throw noFormFor("a load", load.position(), operands);
        }
        out.add(new Instruction(load.position(), form.mnemonic(), operands));
    }

    /**
     * Refuses an access narrower or wider than a register.
     *
     * <p>The reason is worth stating rather than hiding: the machine can do a byte
     * load, into {@code al}, and this back end has no way to name {@code al} — the
     * allocator deals in whole registers, and half of one is not a register it can
     * hand out. That is the piece of the target description that is missing, and the
     * message says so.
     */
    private static void requireWordAccess(MemoryOperand operand, String what) {
        if (operand.size() != null && operand.size() != Size.WORD) {
            throw new CompileError(operand.position(),
                    "a " + operand.size().spelling() + " " + what + " is not something this "
                            + "compiler can emit yet: a value lives in a whole register, and "
                            + "nothing here can name half of one (docs/ir.md §3.4)");
        }
    }

    /** {@code p = msg}: the address of a label, as an immediate. */
    private void emitLabelAddress(String destination, Value.Name label) {
        out.add(new Instruction(label.position(), "mov",
                operands(virtual(destination, label.position()),
                        new Operand.Offset(label.position(), label.name()))));
    }

    /** The memory operand an instruction carries, from the one the IR wrote. */
    private Operand memory(MemoryOperand operand) {
        List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
        if (operand.base() != null) {
            // A base that is a variable is a value waiting for a register; a base
            // that is a label is a name the assembler resolves. The syntax cannot
            // tell them apart, and this is where the answer is known.
            atoms.add(names.isVariable(operand.base())
                    ? Operand.Memory.Atom.ofVirtual(operand.base())
                    : Operand.Memory.Atom.ofName(operand.base()));
        }
        if (operand.base() == null || operand.displacement() != 0) {
            atoms.add(Operand.Memory.Atom.ofNumber(operand.displacement()));
        }
        return new Operand.Memory(operand.position(), operand.size(), operand.segment(), atoms);
    }

    /** The smallest form whose operand shapes fit, or null when none does. */
    private static Form smallest(List<Form> forms, List<Operand> operands) {
        Form best = null;
        for (Form form : forms) {
            if (writtenOperands(form, operands) == null) {
                continue;
            }
            if (best == null || form.bytes() < best.bytes()) {
                best = form;
            }
        }
        return best;
    }

    private static CompileError noFormFor(String what, SourcePos where, List<Operand> operands) {
        return new CompileError(where,
                "this target has no form for " + what + " with these operands: " + operands);
    }

    private boolean isLabel(Value value) {
        return value instanceof Value.Name && names.isLabel(((Value.Name) value).name());
    }

    /**
     * {@code cmp} or {@code test}: two operands and no value.
     *
     * <p>It is an operation in the same sense {@code eval} is — one operation,
     * done as written — so the flags it leaves are the ones the writer asked for
     * and the branch after it reads those. The machine's forms take a register
     * first, so a literal on the left is put into one: {@code cmp 5, x} is not
     * something this machine can say, and saying it another way is cheap.
     */
    private void selectCompare(Item.Compare compare) {
        String first;
        if (compare.left() instanceof Value.Number) {
            first = temp();
            emitValue(compare.left(), first, true);
        } else {
            first = registerNameOf(compare.left());
        }

        List<Operand> operands = new ArrayList<Operand>();
        operands.add(virtual(first, compare.position()));
        operands.add(operandOf(compare.right()));

        Form best = null;
        List<Operand> written = null;
        for (Form form : target.compareForms(compare.kind())) {
            List<Operand> candidate = writtenOperands(form, operands);
            if (candidate != null && (best == null || form.bytes() < best.bytes())) {
                best = form;
                written = candidate;
            }
        }
        if (best == null) {
            throw new CompileError(compare.position(),
                    "no way to compare these operands is available yet: a memory operand is not "
                            + "handled yet");
        }
        out.add(new Instruction(compare.position(), best.mnemonic(), written));
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
        if (value instanceof Value.Memory) {
            emitLoad((Value.Memory) value, destination);
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
            emitInPlace(Operator.COMPLEMENT, destination, null, null, expression.position(),
                    false);
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

        // Multiply and divide are done in registers the machine names itself, which
        // means a sequence rather than an instruction — and the sequence wants its
        // operands as operands, so it is asked before either side is moved anywhere.
        Value leftLeaf = leafOf(apply.left());
        Value rightLeaf = leafOf(apply.right());
        if (leftLeaf != null && rightLeaf != null) {
            Expansion sequence = implicitSequence(operator, Arrays.asList(leftLeaf, rightLeaf),
                    destination);
            if (sequence != null) {
                requireFlagsMayBeLost(sequence.keepsFlags(), apply.position(), operator, false);
                out.addAll(sequence.instructions());
                return;
            }
        }

        if (isLiteral(apply.right())) {
            emitExpression(apply.left(), destination);
            emitInPlace(operator, destination, ((Expression.Leaf) apply.right()).value(),
                    Signedness.of(apply, names), apply.position(), false);
            return;
        }

        // The right-hand side needs a register of its own, so it is computed
        // first, into a temp, and the left goes straight into the destination.
        String scratch = temp();
        emitExpression(apply.right(), scratch);
        emitExpression(apply.left(), destination);
        emitInPlace(operator, destination, new Value.Name(apply.right().position(), scratch),
                Signedness.of(apply, names), apply.position(), false);
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
        if (expansion == null) {
            expansion = implicitSequence(operator, operands, destination);
        }
        if (expansion != null) {
            requireFlagsMayBeLost(expansion.keepsFlags(), operation.position(), operator,
                    flagsMayBeRead);
            out.addAll(expansion.instructions());
            return;
        }

        emitValue(operands.get(0), destination, flagsMayBeRead);
        emitInPlace(operator, destination, second, signedness(operator, operands, destination),
                operation.position(), flagsMayBeRead);
    }

    /** The value an expression is, when it is one, or null when it is a tree. */
    private static Value leafOf(Expression expression) {
        return expression instanceof Expression.Leaf
                ? ((Expression.Leaf) expression).value() : null;
    }

    /**
     * Multiplication and division done on their own, where the operands are still
     * operands.
     *
     * <p>This is worth doing before either side is moved anywhere, and the reason is
     * what the allocator sees. A sequence that copies into the destination and back
     * out of it makes the destination live across the whole thing, which costs
     * registers; one that reads its operands where they already are gives the
     * destination its first mention at the end, when the work is done. The in-place
     * route is still there for the cases this cannot take: an operand that is itself a
     * tree, and a division in the middle of one.
     */
    private Expansion implicitSequence(Operator operator, List<Value> operands,
                                       String destination) {
        boolean multiplies = operator == Operator.MULTIPLY
                || operator == Operator.MULTIPLY_UNSIGNED
                || operator == Operator.MULTIPLY_SIGNED;
        if (operands.size() < 2 || (!multiplies && !operator.divides())) {
            return null;
        }
        for (Value operand : operands) {
            if (!(operand instanceof Value.Name) && !(operand instanceof Value.Number)) {
                return null; // a load in an operand: not something this back end can hand on
            }
        }

        SourcePos where = operands.get(0).position();
        boolean signed = Boolean.TRUE.equals(signedness(operator, operands, destination));
        Operand left = operandOf(operands.get(0));
        Operand right = operandOf(operands.get(1));
        Operand target0 = virtual(destination, where);
        if (multiplies) {
            // A literal is fine on either side: multiplication does not care, and the
            // target moves one it finds on the right.
            return target.multiply(where, target0, left, right, signed);
        }
        if (right instanceof Operand.Number) {
            // A division cannot swap its sides, so a literal divisor needs a register —
            // and a register it is, not a value: the selector is the one saying where
            // this goes, and the allocator is told by the ordinary rule that a register
            // written by hand is destroyed.
            List<Instruction> instructions = new ArrayList<Instruction>();
            instructions.add(new Instruction(where, "mov",
                    operands(new Operand.Name(where, LITERAL_SCRATCH), right)));
            Expansion rest = target.divide(where, target0, left,
                    new Operand.Name(where, LITERAL_SCRATCH), signed,
                    operator == Operator.REMAINDER);
            if (rest == null) {
                return null;
            }
            instructions.addAll(rest.instructions());
            return new Expansion(instructions, rest.keepsFlags());
        }
        return target.divide(where, target0, left, right, signed,
                operator == Operator.REMAINDER);
    }

    /**
     * Whether a multiply or a divide is the signed one, or null when nothing says.
     *
     * <p>Two of the four ways to write one say so in the mnemonic; the other two
     * follow the operands ({@code docs/ir.md} §6.1), which means looking at what the
     * operands are. A literal has no signedness of its own, so the other side
     * decides, and if neither says, the place the answer is going has the same
     * signedness as what it is computed from.
     */
    private Boolean signedness(Operator operator, List<Value> operands, String destination) {
        switch (operator) {
            case MULTIPLY_SIGNED:
            case DIVIDE_SIGNED:
                return Boolean.TRUE;
            case MULTIPLY_UNSIGNED:
            case DIVIDE_UNSIGNED:
                return Boolean.FALSE;
            default:
                break;
        }
        for (Value operand : operands) {
            Boolean signed = Signedness.of(operand, names);
            if (signed != null) {
                return signed;
            }
        }
        Type type = names.typeOf(destination);
        return type == null ? null : Boolean.valueOf(type.isSigned());
    }

    /**
     * Multiplication and division, which the machine does in registers it names
     * itself.
     *
     * <p>{@code mul r} multiplies whatever is in {@code ax} and leaves the low half
     * there; {@code div r} divides {@code dx:ax}. So the sequence copies the operand
     * that is already in the destination into {@code ax}, does the operation, and
     * copies the answer back — and those copies name {@code ax} and {@code dx} as
     * operands the selector wrote, which is how the allocator knows to keep other
     * values out of them while this runs.
     *
     * <p>The divisor or multiplier being a literal is no obstacle: the machine takes
     * a register or a memory operand, so the literal is put in one. That register is
     * clobbered, and the allocator is told so by the same rule.
     */
    private Expansion sequenceFor(Operator operator, String destination, Value second,
                                  Boolean signed, SourcePos where) {
        boolean multiplies = operator == Operator.MULTIPLY
                || operator == Operator.MULTIPLY_UNSIGNED
                || operator == Operator.MULTIPLY_SIGNED;
        if (second == null || (!multiplies && !operator.divides())) {
            return null;
        }
        Operand inPlace = virtual(destination, where);
        Operand right = operandOf(second);
        boolean isSigned = signed != null && signed.booleanValue();
        if (right instanceof Operand.Number) {
            // The machine takes a register, so the literal needs one; the sequence
            // says which and the allocator treats it as destroyed.
            List<Instruction> withLiteral = new ArrayList<Instruction>();
            withLiteral.add(new Instruction(where, "mov",
                    operands(new Operand.Name(where, LITERAL_SCRATCH), right)));
            Operand scratch = new Operand.Virtual(where, LITERAL_SCRATCH);
            Expansion rest = multiplies
                    ? target.multiply(where, inPlace, inPlace, scratch, isSigned)
                    : target.divide(where, inPlace, inPlace, scratch, isSigned,
                    operator == Operator.REMAINDER);
            if (rest == null) {
                return null;
            }
            withLiteral.addAll(rest.instructions());
            return new Expansion(withLiteral, rest.keepsFlags());
        }
        if (multiplies) {
            return target.multiply(where, inPlace, inPlace, right, isSigned);
        }
        return target.divide(where, inPlace, inPlace, right, isSigned,
                operator == Operator.REMAINDER);
    }

    /** The literal a value is, or null when it is not one. */
    private static Value literalOf(Expression expression) {
        return isLiteral(expression) ? ((Expression.Leaf) expression).value() : null;
    }

    /**
     * The operation itself, on a value that is already in {@code destination}.
     *
     * <p>Most operations are one instruction, and the target's forms say which. An
     * operation the machine has no ordinary form for — multiplication and division,
     * which it does in registers it names itself — gets a sequence from the target
     * instead ({@code AGENTS.md}, "Expansion"). By the time either is asked, the first
     * operand is already in the destination, so a sequence copies out of it and back
     * into it; a copy that turns out to be a copy of a register into itself is
     * dropped by the allocator, which is the only part of this that knows where
     * anything lives.
     */
    private void emitInPlace(Operator operator, String destination, Value second, Boolean signed,
                             SourcePos where, boolean flagsMayBeRead) {
        List<Form> forms = target.forms(operator);
        if (forms.isEmpty()) {
            Expansion sequence = sequenceFor(operator, destination, second, signed, where);
            if (sequence == null) {
                throw new CompileError(where,
                        "no form for '" + operator.spelling() + "' is available yet: the "
                                + "instruction this machine has for it keeps an operand in a "
                                + "fixed register, and this back end cannot express that");
            }
            requireFlagsMayBeLost(sequence.keepsFlags(), where, operator, flagsMayBeRead);
            out.addAll(sequence.instructions());
            return;
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

    /**
     * A register a literal goes into when the machine will not take it directly.
     *
     * <p>Multiplying and dividing take a register or a memory operand, so a literal
     * has to be put somewhere. {@code bx} is the choice because it is a value register
     * and not an address register: a value that is only computed with is more common
     * than one that is addressed through, so this is the one that gets in the way of
     * the fewest programs. That it is destroyed is stated by the ordinary rule —
     * operand zero is a register the selector wrote — and the allocator keeps other
     * values out of it around here.
     */
    private static final String LITERAL_SCRATCH = "bx";

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
