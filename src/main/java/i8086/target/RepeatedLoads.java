package i8086.target;

import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Item;
import i8086.isel.Selection;
import i8086.ssa.Block;
import i8086.ssa.Effects;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A load of what a register already holds is not a load.
 *
 * <p>A value in a home is read by loading it out of the cell at every point that mentions it, because
 * a value in memory is not in a register and the allocator cannot know that the register it picked
 * still holds it. Sometimes it plainly does: two statements next to each other that both read the
 * same cell — the two halves of a partition entry, read one after the other — load it twice, and the
 * second load is four bytes for a register that already has the answer, with nothing in between that
 * could have written the cell.
 *
 * <p>So this is a pass over the code the allocator produced rather than over the form, and it is the
 * third of the 8086's ({@code AGENTS.md} allows three). It has to be here and not in the allocator,
 * because what it asks is whether anything between the two loads could have written memory, and the
 * machinery for that is the machine's: an access with a memory operand written first is a store, and
 * a statement or a block whose clobbers the compiler cannot see into may write anywhere at all. That
 * is target knowledge and it is allowed exactly here.
 *
 * <p>What it tracks is one thing: for each register, the access whose result it holds, if any. Three
 * things end that:
 *
 * <ul>
 *   <li><b>the register is written</b>, which the target answers for every instruction already —
 *       {@code clobbers}, the same question the allocator and the repeated-constant pass ask;
 *   <li><b>memory is written</b>, which is what the machine says about the instruction and what the
 *       statement says about itself, and which throws away every register's answer at once;
 *   <li><b>a block starts</b>, the graph's own boundary, because another path arrives there.
 * </ul>
 *
 * <p>A volatile access is left alone, and that is a question about the form rather than about the
 * instructions: the mark is not on the load ({@code docs/ir.md} §3.4), so a statement that touches
 * volatile memory is not touched at all — a load the program needs to happen is not one this pass may
 * quietly decide it already has.
 */
final class RepeatedLoads {

    private final Target target;
    private final SsaForm form;
    private final Map<Item, Block> blockOf = new LinkedHashMap<Item, Block>();

    private RepeatedLoads(Target target, SsaForm form) {
        this.target = target;
        this.form = form;
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                blockOf.put(statement.item(), block);
            }
        }
    }

    /** The same code, with the loads whose answer is already in the register taken out of it. */
    static Selection clean(Target target, SsaForm form, Selection selection) {
        return new RepeatedLoads(target, form).run(selection);
    }

    private Selection run(Selection selection) {
        List<Selection.Piece> rewritten = new ArrayList<Selection.Piece>();
        Map<String, Operand.Memory> holds = new LinkedHashMap<String, Operand.Memory>();
        Block block = null;
        for (Selection.Piece piece : selection.pieces()) {
            Block here = blockOf.get(piece.item());
            if (here != null && here != block) {
                holds.clear(); // another path arrives here, so no register is known
                block = here;
            }
            boolean volatileHere = touchesVolatileMemory(piece.item());
            List<Instruction> kept = new ArrayList<Instruction>();
            for (Instruction instruction : piece.instructions()) {
                if (answersWithAMemoryWrite(piece.item(), instruction)) {
                    holds.clear();
                }
                Operand.Memory read = volatileHere ? null : loaded(instruction);
                String register = read == null ? null
                        : ((Operand.Name) instruction.operands().get(0)).name();
                // The question comes before the clobbers are taken away, because a load is a
                // clobber of its own destination: the register's answer is replaced by this one,
                // and asking after would be asking about a register that had just been emptied.
                boolean alreadyHeld = read != null && sameAccess(read, holds.get(register));
                holds.keySet().removeAll(target.clobbers(instruction));
                if (alreadyHeld) {
                    continue; // the register holds it, and nothing has written the cell since
                }
                if (read != null) {
                    holds.put(register, read);
                }
                kept.add(instruction);
            }
            rewritten.add(new Selection.Piece(piece.item(), kept));
        }
        return new Selection(rewritten);
    }

    /**
     * The access this instruction reads into a register, or null when it is not that.
     *
     * <p>A load is a move whose destination is a register and whose source is memory. Whether the
     * register is one a value lives in or one the target named by hand is not asked: what matters is
     * that the same register is loaded from the same access twice, which is the same instruction
     * either way.
     */
    private Operand.Memory loaded(Instruction instruction) {
        List<Operand> operands = instruction.operands();
        if (!instruction.mnemonic().equals("mov") || operands.size() != 2
                || !(operands.get(0) instanceof Operand.Name)
                || !(operands.get(1) instanceof Operand.Memory)) {
            return null;
        }
        return (Operand.Memory) operands.get(1);
    }

    /**
     * Whether two accesses are the same access.
     *
     * <p>Neither the operand nor a term of it has an identity to compare — an access is printed
     * rather than looked up, and two of them that print the same way are two objects — so this is the
     * comparison written out: the same width, the same segment, and the same terms in the same order,
     * each added or subtracted the same way.
     */
    private static boolean sameAccess(Operand.Memory one, Operand.Memory other) {
        if (one == null || other == null || one.size() != other.size()
                || one.atoms().size() != other.atoms().size()) {
            return false;
        }
        if (one.segment() == null ? other.segment() != null
                : !one.segment().equals(other.segment())) {
            return false;
        }
        for (int at = 0; at < one.atoms().size(); at++) {
            if (!sameAtom(one.atoms().get(at), other.atoms().get(at))) {
                return false;
            }
        }
        return true;
    }

    /** Whether two terms of an address are the same term. */
    private static boolean sameAtom(Operand.Memory.Atom one, Operand.Memory.Atom other) {
        if (one.isSubtracted() != other.isSubtracted() || one.isVirtual() != other.isVirtual()
                || one.isNumber() != other.isNumber()) {
            return false;
        }
        return one.isNumber() ? one.number() == other.number() : one.name().equals(other.name());
    }

    /**
     * Whether this instruction may have written memory, which makes every register's answer stale.
     *
     * <p>Two answers, and both are the machine's. An access written first is what this machine
     * stores through — every instruction the selector builds that writes memory writes its first
     * operand, and the ones that read it there are the comparisons, which are a refusal and not a
     * mistake. And a statement that declares what it destroys declares registers: an interrupt, a
     * block of assembly, anything the compiler cannot see into may write anywhere, so its piece is
     * treated as if it had.
     */
    private boolean answersWithAMemoryWrite(Item item, Instruction instruction) {
        if (item instanceof Item.InlineAsm || item instanceof Item.Machine) {
            return true;
        }
        List<Operand> operands = instruction.operands();
        return !operands.isEmpty() && operands.get(0) instanceof Operand.Memory;
    }

    /** Whether this statement touches memory the program needs to happen where it was written. */
    private boolean touchesVolatileMemory(Item item) {
        if (item == null) {
            return false;
        }
        for (Effects.Occurrence occurrence : Effects.occurrences(item)) {
            if (occurrence.isVolatile()) {
                return true;
            }
        }
        return false;
    }
}
