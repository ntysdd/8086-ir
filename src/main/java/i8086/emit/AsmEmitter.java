package i8086.emit;

import i8086.CompileError;
import i8086.asm.Instruction;
import i8086.asm.InstructionPrinter;
import i8086.asm.Numbers;
import i8086.ir.Item;
import i8086.ir.Module;

/**
 * Writes a module as assemblable 8086 assembly text.
 *
 * <p>This is the other half of "text in, text out": the IR goes in, and ordinary
 * assembly comes out, in the syntax of {@code docs/asm.md} — the same syntax the
 * bundled assembler reads and the same syntax an inline block is written in, so
 * the project has one assembly language rather than two.
 *
 * <p>What is dropped on the way is exactly what the assembly language cannot
 * say: {@code target} and {@code entry} are the module's own metadata, and a
 * clobber list is a promise the IR makes to the optimiser rather than something
 * the CPU is told. Items keep their source order, so a module that puts its
 * entry point first produces an image that begins with it.
 */
public final class AsmEmitter {

    private static final String INDENT = "    ";

    private AsmEmitter() {
    }

    public static String emit(Module module) {
        StringBuilder text = new StringBuilder();
        text.append("org ").append(Numbers.spelling(module.origin())).append('\n');

        for (Item item : module.items()) {
            if (namesSomething(item)) {
                text.append('\n');
            }
            emitItem(text, item);
        }
        return text.toString();
    }

    private static boolean namesSomething(Item item) {
        return item instanceof Item.Label
                || (item instanceof Item.Data && ((Item.Data) item).label() != null);
    }

    private static void emitItem(StringBuilder text, Item item) {
        if (item instanceof Item.Label) {
            text.append(((Item.Label) item).name()).append(":\n");
        } else if (item instanceof Item.Return) {
            text.append(INDENT).append("ret\n");
        } else if (item instanceof Item.InlineAsm) {
            for (Instruction instruction : ((Item.InlineAsm) item).body()) {
                text.append(INDENT).append(InstructionPrinter.print(instruction)).append('\n');
            }
        } else if (item instanceof Item.Data) {
            emitData(text, (Item.Data) item);
        } else {
            throw notYet(item);
        }
    }

    /**
     * Refuses what the back end cannot turn into instructions yet.
     *
     * <p>A variable lives in a register nobody has allocated and a store needs an
     * addressing mode nobody has chosen, so these wait for instruction selection
     * and register allocation. The refusal is here, at the point that cannot do
     * the work, rather than earlier where the program is still fine.
     */
    private static CompileError notYet(Item item) {
        String what = item instanceof Item.Var ? "a variable declaration"
                : item instanceof Item.Assign ? "an assignment"
                : "this item";
        return new CompileError(item.position(),
                "the emitter cannot write code for " + what + " yet: instruction selection and "
                        + "register allocation come first, so only programs whose body is inline "
                        + "assembly can be emitted so far");
    }

    private static void emitData(StringBuilder text, Item.Data data) {
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
}
