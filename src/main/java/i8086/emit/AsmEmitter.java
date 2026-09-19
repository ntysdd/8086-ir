package i8086.emit;

import i8086.asm.Instruction;
import i8086.asm.InstructionPrinter;
import i8086.asm.Numbers;
import i8086.ir.Item;
import i8086.ir.Module;
import i8086.isel.Selection;

/**
 * Writes a module out as assemblable 8086 assembly text.
 *
 * <p>This is the other half of "text in, text out": ordinary assembly comes out,
 * in the syntax of {@code docs/asm.md} — the same syntax the bundled assembler
 * reads and the same syntax an inline block is written in, so the project has one
 * assembly language rather than two.
 *
 * <p>An item's code comes from the {@link Selection}: instruction selection chose
 * the instructions and register allocation decided where the values live, which
 * is why an instruction arriving here can be printed at all. Items that are not
 * code — labels and data — are written from the module, because that is where
 * their shape lives.
 *
 * <p>What is dropped on the way is exactly what the assembly language cannot say:
 * {@code target} and {@code entry} are the module's own metadata, and a clobber
 * list is a promise the IR makes to the optimiser rather than something the CPU
 * is told. Items keep their source order, so a module that puts its entry point
 * first produces an image that begins with it.
 */
public final class AsmEmitter {

    private static final String INDENT = "    ";

    private AsmEmitter() {
    }

    public static String emit(Module module, Selection selection) {
        StringBuilder text = new StringBuilder();
        text.append("org ").append(Numbers.spelling(module.origin())).append('\n');
        for (Selection.Piece piece : selection.pieces()) {
            if (namesSomething(piece.item())) {
                text.append('\n');
            }
            emitPiece(text, piece);
        }
        return text.toString();
    }

    private static boolean namesSomething(Item item) {
        return Item.labelOf(item) != null;
    }

    private static void emitPiece(StringBuilder text, Selection.Piece piece) {
        Item item = piece.item();
        if (item instanceof Item.Label) {
            text.append(((Item.Label) item).name()).append(":\n");
            return;
        }
        if (item instanceof Item.Data) {
            emitData(text, (Item.Data) item);
            return;
        }
        if (item instanceof Item.Pad) {
            emitPad(text, (Item.Pad) item);
            return;
        }
        for (Instruction instruction : piece.instructions()) {
            text.append(INDENT).append(InstructionPrinter.print(instruction)).append('\n');
        }
    }

    /**
     * {@code pad 32} and {@code pad to 510}, written out as the same words.
     *
     * <p>Not expanded into zero bytes: the assembly text has the word too
     * ({@code docs/asm.md}), so a four-hundred byte run stays one line, and
     * {@code pad to} is not something this emitter could expand anyway — it is the
     * assembler that knows how long the code before it is.
     */
    private static void emitPad(StringBuilder text, Item.Pad pad) {
        if (pad.label() != null) {
            text.append(pad.label()).append(": ");
        }
        text.append("pad ");
        if (pad.to()) {
            text.append("to ");
        }
        text.append(Numbers.spelling(pad.amount()));
        if (pad.fill() != 0) {
            text.append(", ").append(Numbers.spelling(pad.fill()));
        }
        text.append('\n');
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
