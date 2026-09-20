package i8086.target;

import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Item;
import i8086.isel.Selection;
import i8086.ssa.Block;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A constant built into a register that already holds it is not an instruction.
 *
 * <p>The first thing every boot loader does is set its segment registers, and this machine cannot put
 * an immediate into one: {@code movreg ds, 0} is a zero built in {@code ax} and then a move. So three
 * of them are three builds and three moves, twelve bytes where a person writes one build and three
 * moves — eight — because a person knows {@code ax} still holds the zero it put there a moment ago,
 * and a statement cannot see another statement's literal.
 *
 * <p>That knowledge is about a <b>register</b>, which is why this is a target-specific pass and why it
 * runs where it does. It is the second of the 8086's ({@code AGENTS.md} allows at most three), and
 * like the first it belongs after allocation: the sequences that build these constants name
 * {@code ax} by hand, and "what does this instruction put in that register" is a question the machine
 * answers about instructions, which is what selection produces.
 *
 * <p>What is tracked is a word register and the constant in it, over one basic block. Three things
 * end the knowledge, and the first is the one that keeps this honest:
 *
 * <ul>
 *   <li><b>a block starts.</b> The block boundary is the graph's own ({@code ssa.Cfg}) rather than a
 *       second opinion about where one is, and a place another path arrives at is a place where the
 *       register may hold anything at all;
 *   <li><b>a register is destroyed</b>, which the target answers for every instruction already:
 *       {@code clobbers} is what the allocator asks, and it maps a byte half up to the register it is
 *       half of, so writing {@code al} takes the knowledge of {@code ax} with it;
 *   <li><b>the register is a byte or a segment register</b>, which is not tracked at all: a constant
 *       in {@code al} would have to be invalidated by a write to {@code ah} as well, and a segment
 *       register is not a place a value can be.
 * </ul>
 *
 * <p>The flags need no question. A zero is built with {@code xor r, r} only where selection found
 * that nothing reads the flags, and where something does it is moved in instead — and either way the
 * instruction being taken away leaves the flags as they were, because a move does not touch them and
 * a clear leaves the same zero the clear before it did.
 */
final class RepeatedConstants {

    private final Target target;
    private final SsaForm form;
    private final Map<Item, Block> blockOf = new LinkedHashMap<Item, Block>();

    private RepeatedConstants(Target target, SsaForm form) {
        this.target = target;
        this.form = form;
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                blockOf.put(statement.item(), block);
            }
        }
    }

    /** The same code, with the constants that were already in the register taken out of it. */
    static Selection clean(Target target, SsaForm form, Selection selection) {
        return new RepeatedConstants(target, form).run(selection);
    }

    private Selection run(Selection selection) {
        List<Selection.Piece> rewritten = new ArrayList<Selection.Piece>();
        Map<String, Long> known = new LinkedHashMap<String, Long>();
        Block block = null;
        for (Selection.Piece piece : selection.pieces()) {
            Block here = blockOf.get(piece.item());
            if (here != null && here != block) {
                known.clear(); // another path may arrive here, so nothing is known at the top
                block = here;
            }
            List<Instruction> kept = new ArrayList<Instruction>();
            for (Instruction instruction : piece.instructions()) {
                Map<String, Long> built = constantsIn(instruction);
                if (!built.isEmpty() && known.entrySet().containsAll(built.entrySet())) {
                    // The instruction writes a constant the register already holds, and that is the
                    // whole of what it does: the two shapes this reads are a register cleared with
                    // itself and a word literal moved in, and the only register either destroys is
                    // the one it writes.
                    continue;
                }
                known.keySet().removeAll(target.clobbers(instruction));
                known.putAll(built);
                kept.add(instruction);
            }
            if (declaresClobbers(piece.item())) {
                known.clear(); // see below: this pass does not read a declared clobber list
            }
            rewritten.add(new Selection.Piece(piece.item(), kept));
        }
        return new Selection(rewritten);
    }

    /**
     * Whether this statement declares what it destroys.
     *
     * <p>Two kinds do — an inline block, which the compiler cannot see into, and a machine statement
     * such as an interrupt — and what the list <em>means</em> is the allocator's question, which
     * reads it through the target: what the instruction destroys implicitly, and the declaration on
     * top of that ({@code RegisterAllocator.destroyedAt}). Nothing here reads it, because a second
     * reading of a clobber list is a second thing to be wrong about which register a name stands
     * for — and forgetting every constant is always safe, at the cost of a byte in the rare program
     * that wants a zero on both sides of an interrupt.
     */
    private static boolean declaresClobbers(Item item) {
        return item instanceof Item.InlineAsm || item instanceof Item.Machine;
    }

    /**
     * The constant this instruction puts in a register, when it is one this machine writes out.
     *
     * <p>Two shapes, and they are the two the target's own sequences are written with: clearing a
     * register with itself, which is how a zero is built where the flags can go, and moving a word
     * literal in, which is how it is built where they cannot. Everything else answers nothing, which
     * is most instructions — and nothing is tracked for a byte or a segment register.
     */
    private Map<String, Long> constantsIn(Instruction instruction) {
        List<Operand> operands = instruction.operands();
        if (instruction.mnemonic().equals("xor") && operands.size() == 2) {
            String register = wordRegister(operands.get(0));
            if (register != null && register.equals(wordRegister(operands.get(1)))) {
                return Collections.singletonMap(register, Long.valueOf(0));
            }
            return Collections.emptyMap();
        }
        if (instruction.mnemonic().equals("mov") && operands.size() == 2
                && operands.get(1) instanceof Operand.Number) {
            String register = wordRegister(operands.get(0));
            if (register != null) {
                return Collections.singletonMap(register,
                        Long.valueOf(((Operand.Number) operands.get(1)).value()));
            }
        }
        return Collections.emptyMap();
    }

    /** The word register this operand names, or null when it is not one a value can live in. */
    private String wordRegister(Operand operand) {
        if (!(operand instanceof Operand.Name)) {
            return null;
        }
        String name = ((Operand.Name) operand).name();
        return target.valueRegisters().contains(name) ? name : null;
    }
}
