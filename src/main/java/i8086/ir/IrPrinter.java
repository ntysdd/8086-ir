package i8086.ir;

import i8086.asm.Instruction;
import i8086.asm.InstructionPrinter;
import i8086.asm.Numbers;

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
        text.append("target ").append(module.target()).append('\n');
        text.append("org ").append(Numbers.spelling(module.origin())).append('\n');
        text.append("entry ").append(module.entry()).append('\n');

        for (Item item : module.items()) {
            if (namesSomething(item)) {
                text.append('\n');
            }
            printItem(text, item);
        }
        return text.toString();
    }

    /** True for an item that leads with a name, which is what gets a blank line before it. */
    private static boolean namesSomething(Item item) {
        return item instanceof Item.Label
                || (item instanceof Item.Data && ((Item.Data) item).label() != null);
    }

    private static void printItem(StringBuilder text, Item item) {
        if (item instanceof Item.Label) {
            text.append(((Item.Label) item).name()).append(":\n");
        } else if (item instanceof Item.Return) {
            text.append(INDENT).append("ret\n");
        } else if (item instanceof Item.Data) {
            printData(text, (Item.Data) item);
        } else if (item instanceof Item.Var) {
            printVar(text, (Item.Var) item);
        } else if (item instanceof Item.Assign) {
            printAssign(text, (Item.Assign) item);
        } else if (item instanceof Item.Eval) {
            printEvalStatement(text, (Item.Eval) item);
        } else if (item instanceof Item.Compare) {
            printCompare(text, (Item.Compare) item);
        } else if (item instanceof Item.Jump) {
            text.append(INDENT).append("jmp ").append(((Item.Jump) item).target()).append('\n');
        } else if (item instanceof Item.Branch) {
            Item.Branch branch = (Item.Branch) item;
            text.append(INDENT).append(branch.condition()).append(' ')
                    .append(branch.target()).append('\n');
        } else {
            printInlineAsm(text, (Item.InlineAsm) item);
        }
    }

    private static void printCompare(StringBuilder text, Item.Compare compare) {
        text.append(INDENT).append(compare.kind().spelling()).append(' ')
                .append(printValue(compare.left())).append(", ")
                .append(printValue(compare.right())).append('\n');
    }

    private static void printVar(StringBuilder text, Item.Var var) {
        text.append(INDENT).append("var ").append(var.name()).append(": ")
                .append(var.type().spelling()).append('\n');
    }

    private static void printAssign(StringBuilder text, Item.Assign assign) {
        text.append(INDENT).append(printPlace(assign.place())).append(" = ")
                .append(printValue(assign.value())).append('\n');
    }

    private static void printEvalStatement(StringBuilder text, Item.Eval item) {
        text.append(INDENT).append("eval(").append(printExpression(item.expression(), 0, false))
                .append(")\n");
    }

    private static String printPlace(Place place) {
        return place instanceof Place.Name
                ? ((Place.Name) place).name()
                : printMemoryOperand(((Place.Memory) place).operand());
    }

    private static String printValue(Value value) {
        if (value instanceof Value.Name) {
            return ((Value.Name) value).name();
        }
        if (value instanceof Value.Number) {
            return Numbers.spelling(((Value.Number) value).value());
        }
        if (value instanceof Value.Memory) {
            return printMemoryOperand(((Value.Memory) value).operand());
        }
        if (value instanceof Value.Eval) {
            return "eval(" + printExpression(((Value.Eval) value).expression(), 0, false) + ")";
        }
        if (value instanceof Value.Expr) {
            return "expr(" + printExpression(((Value.Expr) value).expression(), 0, false) + ")";
        }
        Value.Convert convert = (Value.Convert) value;
        return convert.conversion().spelling() + " " + printValue(convert.operand());
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
                                          boolean rightOperand) {
        if (expression instanceof Expression.Leaf) {
            return printValue(((Expression.Leaf) expression).value());
        }
        if (expression instanceof Expression.Complement) {
            Expression operand = ((Expression.Complement) expression).operand();
            return "~" + printExpression(operand, Operator.COMPLEMENT.precedence(), true);
        }
        Expression.Apply apply = (Expression.Apply) expression;
        int precedence = apply.operator().precedence();
        String text = printExpression(apply.left(), precedence, false)
                + " " + apply.operator().spelling() + " "
                + printExpression(apply.right(), precedence, true);
        boolean needsBrackets = precedence < parentPrecedence
                || (precedence == parentPrecedence && rightOperand);
        return needsBrackets ? "(" + text + ")" : text;
    }

    /**
     * Prints a memory operand the way {@code docs/ir.md} §3.4 writes one,
     * {@code word [p + 2]}, with spaces around the sign.
     *
     * <p>The assembly text writes the same shape without them, {@code [bx+si+2]},
     * as {@code docs/asm.md} §4 shows. They are two surfaces with two spellings,
     * each canonical for its own, and neither is a mistake for the other.
     */
    private static String printMemoryOperand(MemoryOperand operand) {
        StringBuilder text = new StringBuilder();
        if (operand.size() != null) {
            text.append(operand.size().spelling()).append(' ');
        }
        if (operand.segment() != null) {
            text.append(operand.segment()).append(':');
        }
        text.append('[');
        if (operand.base() != null) {
            text.append(operand.base());
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

    private static void printData(StringBuilder text, Item.Data data) {
        if (data.label() != null) {
            text.append(data.label()).append(": ");
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
