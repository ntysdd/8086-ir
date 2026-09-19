package i8086.ir;

import i8086.asm.Instruction;
import i8086.asm.InstructionPrinter;
import i8086.asm.Numbers;
import i8086.target.Target;
import i8086.target.Targets;

import java.util.List;

/**
 * Writes a module back out in the IR surface syntax.
 *
 * <p>The output is canonical, not a copy of the input: the header comes in a
 * fixed order, names are lower-case, numbers are spelled by {@link Numbers},
 * and a construct that the parser normalised away is not printed. So printing
 * what was parsed and parsing that again gives the same module
 * ({@code AGENTS.md}, invariant 5), while {@code print(parse(text))} may differ
 * from {@code text} in layout.
 */
public final class IrPrinter {

    private static final String INDENT = "    ";

    private IrPrinter() {
    }

    public static String print(Module module) {
        StringBuilder text = new StringBuilder();
        Target target = Targets.byName(module.target());
        text.append("target ").append(module.target()).append('\n');
        text.append("org ").append(Numbers.spelling(module.origin())).append('\n');
        text.append("entry ").append(name(module.entry(), target)).append('\n');

        for (Item item : module.items()) {
            if (namesSomething(item)) {
                text.append('\n');
            }
            printItem(text, item, target);
        }
        return text.toString();
    }

    /** True for an item that leads with a name, which is what gets a blank line before it. */
    private static boolean namesSomething(Item item) {
        return item instanceof Item.Label
                || (item instanceof Item.Data && ((Item.Data) item).label() != null);
    }

    /**
     * Writes one item, in the same syntax the module printer uses for it.
     *
     * <p>One item at a time is what a dump of a derived form needs, and it goes
     * through this printer rather than its own so that the surface has one
     * spelling and not two.
     *
     * <p>A single item has no module to take a target from, so a name is written
     * plain. The module printer, which has one, writes the canonical form.
     */
    public static String print(Item item) {
        StringBuilder text = new StringBuilder();
        printItem(text, item, null);
        return text.toString();
    }

    /**
     * A name, marked when the surface would otherwise read it as one of its own
     * words ({@code docs/ir.md} §3.1).
     *
     * <p>Input is liberal and output is canonical, the same split as the thirty
     * spellings of a condition in §4.4: a program may write {@code eval} as a
     * variable name, and what comes back says {@code $eval}, so a reader never has
     * to work out which of the two it is looking at.
     */
    private static String name(String name, Target target) {
        if (target == null || Vocabulary.generated(name)) {
            return name;
        }
        return Vocabulary.markedWhenPrinted(name, target) ? "$" + name : name;
    }

    private static void printItem(StringBuilder text, Item item, Target target) {
        if (item instanceof Item.Label) {
            text.append(name(((Item.Label) item).name(), target)).append(":\n");
        } else if (item instanceof Item.Return) {
            text.append(INDENT).append("ret\n");
        } else if (item instanceof Item.Data) {
            printData(text, (Item.Data) item, target);
        } else if (item instanceof Item.Var) {
            printVar(text, (Item.Var) item, target);
        } else if (item instanceof Item.Assign) {
            printAssign(text, (Item.Assign) item, target);
        } else if (item instanceof Item.Eval) {
            printEvalStatement(text, (Item.Eval) item, target);
        } else if (item instanceof Item.Compare) {
            printCompare(text, (Item.Compare) item, target);
        } else if (item instanceof Item.Jump) {
            text.append(INDENT).append("jmp ")
                    .append(name(((Item.Jump) item).target(), target)).append('\n');
        } else if (item instanceof Item.Branch) {
            Item.Branch branch = (Item.Branch) item;
            text.append(INDENT).append(branch.condition()).append(' ')
                    .append(name(branch.target(), target)).append('\n');
        } else {
            printInlineAsm(text, (Item.InlineAsm) item);
        }
    }

    private static void printCompare(StringBuilder text, Item.Compare compare, Target target) {
        text.append(INDENT).append(compare.kind().spelling()).append(' ')
                .append(printValue(compare.left(), target)).append(", ")
                .append(printValue(compare.right(), target)).append('\n');
    }

    private static void printVar(StringBuilder text, Item.Var var, Target target) {
        text.append(INDENT).append("var ").append(name(var.name(), target)).append(": ")
                .append(var.type().spelling()).append('\n');
    }

    private static void printAssign(StringBuilder text, Item.Assign assign, Target target) {
        text.append(INDENT).append(printPlace(assign.place(), target)).append(" = ")
                .append(printValue(assign.value(), target)).append('\n');
    }

    private static void printEvalStatement(StringBuilder text, Item.Eval item, Target target) {
        text.append(INDENT).append("eval(").append(printOperation(item.operation(), target))
                .append(")\n");
    }

    /**
     * One operation, written the way it was parsed: operands around the operator,
     * or the operator in front for the one that takes a single operand.
     */
    private static String printOperation(Operation operation, Target target) {
        List<Value> operands = operation.operands();
        if (operation.operator().arity() == 1) {
            return operation.operator().spelling() + printValue(operands.get(0), target);
        }
        return printValue(operands.get(0), target) + " " + operation.operator().spelling() + " "
                + printValue(operands.get(1), target);
    }

    private static String printPlace(Place place, Target target) {
        return place instanceof Place.Name
                ? name(((Place.Name) place).name(), target)
                : printMemoryOperand(((Place.Memory) place).operand(), target);
    }

    private static String printValue(Value value, Target target) {
        if (value instanceof Value.Name) {
            return name(((Value.Name) value).name(), target);
        }
        if (value instanceof Value.Number) {
            return Numbers.spelling(((Value.Number) value).value());
        }
        if (value instanceof Value.Memory) {
            return printMemoryOperand(((Value.Memory) value).operand(), target);
        }
        if (value instanceof Value.Eval) {
            return "eval(" + printOperation(((Value.Eval) value).operation(), target) + ")";
        }
        if (value instanceof Value.Expr) {
            return "expr(" + printExpression(((Value.Expr) value).expression(), 0, false, target)
                    + ")";
        }
        Value.Convert convert = (Value.Convert) value;
        return convert.conversion().spelling() + " " + printValue(convert.operand(), target);
    }

    /**
     * Prints an expression with exactly the brackets the tree needs and no more,
     * so that parsing the result gives the tree back ({@code AGENTS.md},
     * invariant 5).
     *
     * <p>A child needs brackets when it binds looser than its parent, and when it
     * binds equally tightly on the right of a left-associative operator, because
     * that is where the tree would otherwise change shape.
     */
    private static String printExpression(Expression expression, int parentPrecedence,
                                          boolean rightOperand, Target target) {
        if (expression instanceof Expression.Leaf) {
            return printValue(((Expression.Leaf) expression).value(), target);
        }
        int precedence;
        String text;
        if (expression instanceof Expression.Unary) {
            // The operator goes in front and its operand needs the same treatment as
            // any other child, brackets included: - -a is -(-a) written worse.
            Expression.Unary unary = (Expression.Unary) expression;
            precedence = unary.operator().precedence();
            text = unary.operator().spelling()
                    + printExpression(unary.operand(), precedence, true, target);
        } else {
            Expression.Apply apply = (Expression.Apply) expression;
            precedence = apply.operator().precedence();
            text = printExpression(apply.left(), precedence, false, target)
                    + " " + apply.operator().spelling() + " "
                    + printExpression(apply.right(), precedence, true, target);
        }
        boolean needsBrackets = precedence < parentPrecedence
                || (precedence == parentPrecedence && rightOperand);
        return needsBrackets ? "(" + text + ")" : text;
    }

    /**
     * Prints a memory operand the way {@code docs/ir.md} §3.4 writes one,
     * {@code word [p + 2]}, with spaces around the sign, and {@code volatile} in
     * front when the access is one the program needs to happen.
     *
     * <p>The assembly text writes the same shape without them, {@code [bx+si+2]},
     * as {@code docs/asm.md} §4 shows. They are two surfaces with two spellings,
     * each canonical for its own, and neither is a mistake for the other.
     */
    private static String printMemoryOperand(MemoryOperand operand, Target target) {
        StringBuilder text = new StringBuilder();
        if (operand.isVolatile()) {
            text.append("volatile ");
        }
        if (operand.size() != null) {
            text.append(operand.size().spelling()).append(' ');
        }
        if (operand.segment() != null) {
            // A segment register is the machine's word, never the author's, so it is
            // not marked: the name inside the brackets is the one that can be a
            // variable, and the two positions are told apart by the bracket.
            text.append(operand.segment()).append(':');
        }
        text.append('[');
        if (operand.base() != null) {
            text.append(name(operand.base(), target));
        }
        if (operand.base() == null) {
            text.append(Numbers.spelling(operand.displacement()));
        } else if (operand.displacement() != 0) {
            long displacement = operand.displacement();
            text.append(displacement < 0 ? " - " : " + ");
            text.append(Numbers.spelling(Math.abs(displacement)));
        }
        return text.append(']').toString();
    }

    private static void printData(StringBuilder text, Item.Data data, Target target) {
        if (data.label() != null) {
            text.append(name(data.label(), target)).append(": ");
        }
        text.append(data.elementSize().directive()).append(' ');
        for (int i = 0; i < data.atoms().size(); i++) {
            Item.Data.Atom atom = data.atoms().get(i);
            if (i > 0) {
                text.append(", ");
            }
            if (atom.isText()) {
                text.append('"').append(atom.text()).append('"');
            } else {
                text.append(Numbers.spelling(atom.number()));
            }
        }
        text.append('\n');
    }

    private static void printInlineAsm(StringBuilder text, Item.InlineAsm block) {
        text.append(INDENT).append("asm clobbers(");
        for (int i = 0; i < block.clobbers().size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(block.clobbers().get(i));
        }
        text.append(") {\n");
        for (Instruction instruction : block.body()) {
            text.append(INDENT).append(INDENT)
                    .append(InstructionPrinter.print(instruction)).append('\n');
        }
        text.append(INDENT).append("}\n");
    }
}
