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
        } else {
            printInlineAsm(text, (Item.InlineAsm) item);
        }
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
