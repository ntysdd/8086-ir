package i8086.emit;

import i8086.asm.Dialect;
import i8086.asm.Instruction;
import i8086.asm.InstructionPrinter;
import i8086.asm.Numbers;
import i8086.ir.Item;
import i8086.ir.Module;
import i8086.isel.Selection;

/**
 * Writes a module out as assembly text, in the dialect NASM reads.
 *
 * <p>This is the other half of "text in, text out": ordinary assembly comes out,
 * and the pieces of a flat binary are the assembler's business, so the spelling is
 * NASM's ({@link Dialect}). Our own dialect is still the one an inline block is
 * written in and the one {@code docs/asm.md} describes; the difference is four
 * substitutions, listed in {@link Dialect}, and none of them is a matter of taste.
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

    private static final Dialect DIALECT = Dialect.NASM;

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
            text.append(INDENT).append(InstructionPrinter.print(instruction, DIALECT))
                    .append('\n');
        }
    }

    /**
     * {@code pad} is the one construct the two dialects do not share a word for:
     * NASM spells both forms with {@code times}, and its second form is where the
     * layout arithmetic lives — {@code 510-($-$$)} says "until the image is 510 long"
     * in the only place that knows how long the image is.
     *
     * <p>Not expanded into zero bytes: a four-hundred byte run stays one line in the
     * text a person reads, and {@code to} could not be expanded here anyway.
     */
    private static void emitPad(StringBuilder text, Item.Pad pad) {
        if (pad.label() != null) {
            text.append(pad.label()).append(": ");
        }
        text.append("times ");
        if (pad.to()) {
            text.append(Numbers.spelling(pad.amount())).append("-($-$$)");
        } else {
            text.append(Numbers.spelling(pad.amount()));
        }
        text.append(" db ").append(Numbers.spelling(pad.fill())).append('\n');
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
            } else if (atom.isName()) {
                // NASM wants a label in data written plainly too: there a bare symbol is
                // already its address, and the bracketed form is what it points at.
                text.append(atom.name());
            } else {
                text.append(Numbers.spelling(atom.number()));
            }
        }
        text.append('\n');
    }
}
