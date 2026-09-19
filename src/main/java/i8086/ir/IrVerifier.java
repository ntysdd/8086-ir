package i8086.ir;

import i8086.CompileError;
import i8086.SourcePos;
import i8086.asm.Size;
import i8086.target.Target;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks that a parsed module means something, before anything acts on it.
 *
 * <p>Two kinds of question are asked here, and they are different questions:
 *
 * <ul>
 *   <li><b>What is a name?</b> A name is a variable, a label, or nothing.
 *       A variable is a virtual register; a label is an address. Nothing in the
 *       syntax says which, so this is where it is decided, once, for every use.
 *   <li><b>How wide is it?</b> The two sides of an assignment must have the same
 *       width, and a width has to be knowable: {@code [p] = [q]} states none, so
 *       it is refused rather than guessed ({@code docs/ir.md} §3.4, §3.5).
 * </ul>
 *
 * <p>And it checks the flags ({@code docs/ir.md} §4.3), as far as anything can
 * yet: a branch that reads a flag value nothing has defined is refused, because
 * reading an undefined flag must be a hard error and not a jump in an arbitrary
 * direction. Two rules make that decidable without a control flow graph, and
 * both are conservative on purpose:
 *
 * <ul>
 *   <li>Only what the surface can do so far defines the flags — {@code cmp} and
 *       {@code test} — plus an inline block that does not say it clobbers them.
 *   <li>A label clears them, because another path may arrive there, and nothing
 *       here knows which one did.
 * </ul>
 *
 * <p>The one thing still not checked anywhere is the other half of §9: a block
 * has to declare the flags it <em>reads</em>, and the surface has no syntax for
 * that yet, so a block that reads a flag it does not declare cannot be caught.
 *
 * <p>Every message names the position it was found at ({@code AGENTS.md},
 * invariant 7), and the target is passed in rather than looked up so that this
 * class never names a machine.
 */
public final class IrVerifier {

    /** The flag set: the one name a module uses without declaring it (§4.1). */
    private static final String FLAGS = "flags";

    /** The width of a label used as a value: a near pointer, so two bytes (§3.3). */
    private static final int POINTER_BYTES = 2;

    private final Module module;
    private final Target target;
    private final Map<String, Type> variables = new LinkedHashMap<String, Type>();
    private final Set<String> labels = new LinkedHashSet<String>();

    private IrVerifier(Module module, Target target) {
        this.module = module;
        this.target = target;
    }

    public static void verify(Module module, Target target) {
        new IrVerifier(module, target).run();
    }

    private void run() {
        collectNames();
        checkEntry();
        checkItems();
    }

    // --- names -------------------------------------------------------------

    private void collectNames() {
        for (Item item : module.items()) {
            if (item instanceof Item.Label) {
                declareLabel(((Item.Label) item).name(), item.position());
            } else if (item instanceof Item.Data) {
                String label = ((Item.Data) item).label();
                if (label != null) {
                    declareLabel(label, item.position());
                }
            } else if (item instanceof Item.Var) {
                declareVariable((Item.Var) item);
            }
        }
    }

    private void declareLabel(String name, SourcePos where) {
        require(!labels.contains(name), where, "the label '" + name + "' is already defined");
        require(!variables.containsKey(name), where,
                "'" + name + "' is already a variable, so it cannot also be a label");
        labels.add(name);
    }

    private void declareVariable(Item.Var variable) {
        require(!variable.name().equals(FLAGS), variable.position(),
                "'" + FLAGS + "' is the flag set, which every module already has, so it cannot "
                        + "be declared");
        require(!variables.containsKey(variable.name()), variable.position(),
                "the variable '" + variable.name() + "' is already declared");
        require(!labels.contains(variable.name()), variable.position(),
                "'" + variable.name() + "' is already a label, so it cannot also be a variable");
        variables.put(variable.name(), variable.type());
    }

    private void checkEntry() {
        require(labels.contains(module.entry()), module.entryPosition(),
                "the entry label '" + module.entry() + "' is never defined");
    }

    // --- items -------------------------------------------------------------

    private void checkItems() {
        boolean flagsDefined = false;
        for (Item item : module.items()) {
            flagsDefined = checkItem(item, flagsDefined);
            if (namesSomething(item)) {
                flagsDefined = false;
            }
        }
    }

    /**
     * Checks one item, and answers with whether the flags are defined after it.
     *
     * <p>Which is the whole of the flags rule for now: what defines them, what
     * throws them away, and what leaves them alone. A move leaves them alone
     * (§4.2), {@code eval} defines them, {@code expr} gives them up (§5.2), and a
     * conversion is assumed to disturb them (§3.5).
     */
    private boolean checkItem(Item item, boolean flagsDefined) {
        if (item instanceof Item.Assign) {
            Item.Assign assign = (Item.Assign) item;
            checkAssign(assign);
            return flagsAfterValue(assign.value(), flagsDefined);
        }
        if (item instanceof Item.Eval) {
            Expression expression = ((Item.Eval) item).expression();
            widthOfExpression(expression, null, ExpressionForm.EVAL);
            requireCarryIsDefined(expressionReadsFlags(expression), flagsDefined, item.position());
            return true;
        }
        if (item instanceof Item.Compare) {
            Item.Compare compare = (Item.Compare) item;
            checkCompare(compare);
            requireCarryIsDefined(
                    expressionReadsFlagsOf(compare.left()) || expressionReadsFlagsOf(compare.right()),
                    flagsDefined, item.position());
            return true;
        }
        if (item instanceof Item.InlineAsm) {
            Item.InlineAsm block = (Item.InlineAsm) item;
            checkInlineAsm(block);
            return block.clobbers().contains(FLAGS) ? false : flagsDefined;
        }
        if (item instanceof Item.Jump) {
            checkLabelTarget(((Item.Jump) item).target(), item.position());
            return flagsDefined;
        }
        if (item instanceof Item.Branch) {
            Item.Branch branch = (Item.Branch) item;
            checkLabelTarget(branch.target(), item.position());
            require(flagsDefined, branch.position(),
                    "this branch reads the flags, but nothing on the way here defines them; "
                            + "only 'cmp' and 'test' do so far, and a label clears them, "
                            + "because another path may arrive there (docs/ir.md §4.3)");
        }
        return flagsDefined;
    }

    /** Whether an operation needs the flags to be defined before it runs. */
    private void requireCarryIsDefined(boolean readsFlags, boolean flagsDefined, SourcePos where) {
        require(!readsFlags || flagsDefined, where,
                "this operation reads the carry flag, so something has to define the flags "
                        + "before it; only 'cmp' and 'test' do so far (docs/ir.md §4.3)");
    }

    /** Whether the flags are defined after a value has been computed. */
    private boolean flagsAfterValue(Value value, boolean flagsDefined) {
        if (value instanceof Value.Eval) {
            Expression expression = ((Value.Eval) value).expression();
            requireCarryIsDefined(expressionReadsFlags(expression), flagsDefined, value.position());
            return true;
        }
        if (value instanceof Value.Expr) {
            return false;
        }
        if (value instanceof Value.Convert) {
            // Assume the worst: on this machine widening is an instruction that
            // touches the flags. Which it is, is the target's to say (§4.2), and
            // until it does, the safe answer is the one that refuses more.
            return false;
        }
        return flagsDefined;
    }

    private static boolean expressionReadsFlagsOf(Value value) {
        if (value instanceof Value.Eval) {
            return expressionReadsFlags(((Value.Eval) value).expression());
        }
        return false;
    }

    private static boolean expressionReadsFlags(Expression expression) {
        if (expression instanceof Expression.Apply) {
            Expression.Apply apply = (Expression.Apply) expression;
            return apply.operator().readsFlags()
                    || expressionReadsFlags(apply.left())
                    || expressionReadsFlags(apply.right());
        }
        if (expression instanceof Expression.Complement) {
            return expressionReadsFlags(((Expression.Complement) expression).operand());
        }
        return expressionReadsFlagsOf(((Expression.Leaf) expression).value());
    }

    /** Whether an item leads with a name, and so can be branched to. */
    private static boolean namesSomething(Item item) {
        return item instanceof Item.Label
                || (item instanceof Item.Data && ((Item.Data) item).label() != null);
    }

    private void checkCompare(Item.Compare compare) {
        Integer left = widthOf(compare.left(), null);
        Integer right = widthOf(compare.right(), left);
        if (left == null) {
            left = widthOf(compare.left(), right);
        }
        if (left == null) {
            throw new CompileError(compare.position(),
                    "cannot tell how wide this comparison is; a comparison of two literals has "
                            + "no width to take");
        }
        require(right == null || left.equals(right), compare.right().position(),
                "a " + left + "-byte value cannot be compared with a " + right
                        + "-byte one; both sides of a comparison have one width");
    }

    private void checkLabelTarget(String target, SourcePos where) {
        if (labels.contains(target)) {
            return;
        }
        require(!variables.containsKey(target), where,
                "'" + target + "' is a variable, so it names no place to branch to");
        throw new CompileError(where,
                "the branch target '" + target + "' is never defined as a label");
    }

    private void checkAssign(Item.Assign assign) {
        Integer placeBytes = widthOf(assign.place());
        Integer valueBytes = widthOf(assign.value(), placeBytes);
        if (placeBytes == null) {
            placeBytes = valueBytes;
        }
        if (placeBytes != null && valueBytes != null) {
            require(placeBytes.equals(valueBytes), assign.value().position(),
                    "a " + placeBytes + "-byte place cannot take a " + valueBytes
                            + "-byte value; the two sides must have the same width, and a change "
                            + "of width is written as a conversion (docs/ir.md §3.5)");
        }

        Integer width = placeBytes != null ? placeBytes : valueBytes;
        if (width == null) {
            throw new CompileError(assign.position(),
                    "cannot tell how wide this is; give the memory operand a 'byte', 'word' or "
                            + "'dword' prefix");
        }
        if (assign.value() instanceof Value.Number) {
            Value.Number literal = (Value.Number) assign.value();
            require(fits(width.intValue(), literal.value()), literal.position(),
                    "the value " + literal.spelling() + " does not fit in "
                            + width + " byte(s)");
        }
    }

    private void checkInlineAsm(Item.InlineAsm block) {
        for (String clobber : block.clobbers()) {
            require(target.isRegister(clobber) || clobber.equals(FLAGS), block.position(),
                    "'" + clobber + "' is neither a register nor '" + FLAGS + "'");
        }
    }

    // --- widths ------------------------------------------------------------

    /** How many bytes a place occupies, or null when the text does not say. */
    private Integer widthOf(Place place) {
        if (place instanceof Place.Name) {
            Place.Name named = (Place.Name) place;
            return Integer.valueOf(variable(named.name(), named.position()).bytes());
        }
        MemoryOperand operand = ((Place.Memory) place).operand();
        checkAddress(operand);
        return operand.size() == null ? null : Integer.valueOf(operand.size().bytes());
    }

    /**
     * How a value's width is worked out, and what the two forms promise.
     *
     * <p>The difference is the whole point of having two of them: {@code eval}
     * may touch memory and may read the flags, {@code expr} may do neither, and
     * that is what buys it the freedom to be reassociated and shared
     * ({@code docs/ir.md} §5).
     */
    private enum ExpressionForm {
        EVAL,
        EXPR
    }

    /**
     * The width of an expression, or null when nothing has said yet.
     *
     * <p>Every operand in one expression has the same width, so the first one
     * that states a width settles it for the rest: a literal and an anonymous
     * memory operand take that width, and a value that states a different one is
     * refused. This is where "no implicit promotion" is actually enforced
     * (§3.2), and where the two forms' rules about memory and flags are checked.
     */
    private Integer widthOfExpression(Expression expression, Integer implied, ExpressionForm form) {
        if (expression instanceof Expression.Leaf) {
            return widthOfLeaf(((Expression.Leaf) expression).value(), implied, form);
        }
        if (expression instanceof Expression.Complement) {
            return widthOfExpression(((Expression.Complement) expression).operand(), implied, form);
        }
        Expression.Apply apply = (Expression.Apply) expression;
        checkOperator(apply, form);
        Integer left = widthOfExpression(apply.left(), implied, form);
        Integer right = widthOfExpression(apply.right(), left == null ? implied : left, form);
        if (left == null) {
            left = widthOfExpression(apply.left(), right, form);
        }
        if (left != null && right != null) {
            require(left.equals(right), apply.right().position(),
                    "a " + left + "-byte operand cannot meet a " + right + "-byte one in one "
                            + "expression; every operand in an expression has the same width "
                            + "(docs/ir.md §3.2)");
        }
        return left != null ? left : right;
    }

    /**
     * What signedness an expression speaks for, or null when nothing says.
     *
     * <p>A variable's type says; a literal, a load and a label do not, because
     * bits are bits until an operation reads them a particular way (§3.2). A
     * mnemonic that states the signedness settles it for everything above it,
     * which is what the mnemonic forms are for (§5.5).
     */
    private Boolean signednessOf(Expression expression) {
        if (expression instanceof Expression.Leaf) {
            Value value = ((Expression.Leaf) expression).value();
            if (value instanceof Value.Name) {
                Type type = variables.get(((Value.Name) value).name());
                return type == null ? null : Boolean.valueOf(type.isSigned());
            }
            return null;
        }
        if (expression instanceof Expression.Complement) {
            return signednessOf(((Expression.Complement) expression).operand());
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
                Boolean left = signednessOf(apply.left());
                return left != null ? left : signednessOf(apply.right());
        }
    }

    /** What an operator may do in this form, and the signedness its operands may mix. */
    private void checkOperator(Expression.Apply apply, ExpressionForm form) {
        Operator operator = apply.operator();
        if (operator.readsFlags()) {
            require(form == ExpressionForm.EVAL, apply.position(),
                    "'" + operator.spelling() + "' reads the carry flag, so it belongs in eval "
                            + "and not in expr, which reads no flags at all (docs/ir.md §5.5)");
        }
        if (operator.divides()) {
            Integer width = widthOfExpression(apply.left(), null, form);
            if (width == null) {
                width = widthOfExpression(apply.right(), null, form);
            }
            if (width != null) {
                require(width.intValue() == 2, apply.position(),
                        "division is 16 bits in v1, and this is " + width + " bytes; a wider "
                                + "division is written out by hand (docs/ir.md §6.2)");
            }
        }
        if (!operator.statesSignedness()) {
            Boolean left = signednessOf(apply.left());
            Boolean right = signednessOf(apply.right());
            require(left == null || right == null || left.equals(right), apply.position(),
                    "'" + operator.spelling() + "' has one operand signed and one unsigned, and "
                            + "written as a symbol it does not say which it means; use the "
                            + "mnemonic form, or let one value live in a variable of the other "
                            + "type, which costs nothing (docs/ir.md §5.5)");
        }
    }

    /** A leaf of an expression: what it may be, and how wide it is. */
    private Integer widthOfLeaf(Value value, Integer implied, ExpressionForm form) {
        if (form == ExpressionForm.EXPR && value instanceof Value.Memory) {
            throw new CompileError(value.position(),
                    "expr works on values that are already in registers, so it cannot contain a "
                            + "load; put the load in a variable first (docs/ir.md §5.2)");
        }
        if (form == ExpressionForm.EXPR && value instanceof Value.Name
                && !variables.containsKey(((Value.Name) value).name())) {
            require(!labels.contains(((Value.Name) value).name()), value.position(),
                    "expr works on variables, and '" + ((Value.Name) value).name()
                            + "' is a label, which is an address; put it in a variable first "
                            + "(docs/ir.md §5.2)");
        }
        return widthOf(value, implied);
    }

    /**
     * The width of a conversion, which is the width of whatever it is going into
     * unless the conversion names one of its own ({@code docs/ir.md} §3.5).
     */
    private Integer widthOfConversion(Value.Convert convert, Integer implied) {
        Conversion conversion = convert.conversion();
        Integer source = widthOf(convert.operand(), null);
        require(source != null, convert.operand().position(),
                "the value being converted has to say how wide it is; give the memory operand a "
                        + "prefix, or convert a variable");

        if (conversion.direction() == Conversion.Direction.WIDEN) {
            require(implied != null, convert.position(),
                    "'" + conversion.spelling() + "' widens, so something has to say how wide the "
                            + "result is: put it somewhere of the width you want");
            require(implied.intValue() > source.intValue(), convert.position(),
                    "'" + conversion.spelling() + "' widens, and turning a " + source
                            + "-byte value into a " + implied + "-byte one is not widening");
            return implied;
        }

        int result = conversion.resultBytes();
        if (implied != null) {
            require(result == implied.intValue(), convert.position(),
                    "'" + conversion.spelling() + "' gives a " + result + "-byte value, which "
                            + "does not fit where it is going; narrowing and then widening needs "
                            + "two conversions, and the surface writes them one at a time");
        }
        require(source.intValue() > result, convert.position(),
                "'" + conversion.spelling() + "' narrows, and the value is already " + source
                        + " bytes");
        return Integer.valueOf(result);
    }

    /**
     * How wide a value is, or null when nothing has said yet.
     *
     * <p>{@code implied} is the width the context asks for, which is what a
     * literal and an anonymous memory operand take: {@code x: u16 = [p]} is a
     * two-byte load because {@code x} is two bytes, not because anything else
     * said so ({@code docs/ir.md} §3.4).
     */
    private Integer widthOf(Value value, Integer implied) {
        if (value instanceof Value.Number) {
            return implied;
        }
        if (value instanceof Value.Name) {
            Value.Name named = (Value.Name) value;
            if (variables.containsKey(named.name())) {
                return Integer.valueOf(variables.get(named.name()).bytes());
            }
            require(labels.contains(named.name()), named.position(),
                    "unknown name '" + named.name() + "': no variable or label has that name");
            return Integer.valueOf(POINTER_BYTES);
        }
        if (value instanceof Value.Memory) {
            MemoryOperand operand = ((Value.Memory) value).operand();
            checkAddress(operand);
            return operand.size() == null ? implied : Integer.valueOf(operand.size().bytes());
        }
        if (value instanceof Value.Convert) {
            return widthOfConversion((Value.Convert) value, implied);
        }
        Value.Eval eval = value instanceof Value.Eval ? (Value.Eval) value : null;
        Expression expression = eval != null ? eval.expression() : ((Value.Expr) value).expression();
        return widthOfExpression(expression, implied,
                eval == null ? ExpressionForm.EXPR : ExpressionForm.EVAL);
    }

    private void checkAddress(MemoryOperand operand) {
        String segment = operand.segment();
        if (segment != null) {
            require(target.isSegmentRegister(segment), operand.position(),
                    "'" + segment + "' is not a segment register on this target");
        }
        String base = operand.base();
        if (base == null || variables.containsKey(base) || labels.contains(base)) {
            return;
        }
        throw new CompileError(operand.position(),
                "unknown name '" + base + "': no variable or label has that name");
    }

    /** The type of a variable, complaining usefully when the name is something else. */
    private Type variable(String name, SourcePos where) {
        Type type = variables.get(name);
        if (type != null) {
            return type;
        }
        if (labels.contains(name)) {
            throw new CompileError(where,
                    "'" + name + "' is a label, and a label is an address, so it cannot be "
                            + "assigned to");
        }
        throw new CompileError(where,
                "unknown name '" + name + "': no variable or label has that name");
    }

    private static boolean fits(int bytes, long value) {
        return value >= 0 && value < (1L << (bytes * 8));
    }

    private void require(boolean condition, SourcePos where, String message) {
        if (!condition) {
            throw new CompileError(where, message);
        }
    }
}
