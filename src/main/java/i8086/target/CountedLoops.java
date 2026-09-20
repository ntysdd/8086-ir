package i8086.target;

import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Item;
import i8086.ir.Names;
import i8086.isel.Selection;
import i8086.ssa.Block;
import i8086.ssa.Liveness;
import i8086.ssa.SsaForm;
import i8086.ssa.SsaStatement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A loop that counts down pays for flags it does not need, and for a branch it may not need
 * either.
 *
 * <p>{@code x = eval(x - 1)}, {@code cmp x, 0}, {@code jnz top} is three instructions and five
 * bytes, and two of them are about the flags. The comparison asks the question the decrement has
 * already answered — a decrement leaves ZF saying whether its result is zero, and this machine's
 * {@code test r, r} asks exactly that — so where nothing else can read those flags the comparison
 * is an instruction spent repeating what the register already says.
 *
 * <p>{@code loop} goes further, and it is why this runs where it does. It decrements {@code cx},
 * branches if the result is not zero, and touches no flag at all: the whole countdown in two bytes
 * instead of five. Whether it can be used is a question about a <b>register</b>, and which
 * register a value was given is not known until the allocator has run. So this is the
 * target-specific tail <em>after</em> allocation, which is the one place a fact about a register
 * can be stated at all. {@code AGENTS.md} allows a target up to three such passes and says the
 * 8086's are late and live with the target; where "late" is, is this pass's business, and it is
 * where the facts it needs exist.
 *
 * <p>Three things are asked before either rewrite, and two of them are about the flags.
 *
 * <ul>
 *   <li><b>Nothing may read the flags the comparison leaves.</b> An instruction that reads the
 *       carry would see the difference between {@code test}, which clears CF, and what the
 *       decrement left. The question is asked of the form the program came from rather than of the
 *       instructions: {@code ssa.Liveness} answers "can the flags still be read here", which is the
 *       surface's own model ({@code docs/ir.md} §4.2, one whole flag set).
 *   <li><b>The branch may only ask about the zero flag.</b> {@code jz} and {@code jnz} ask the one
 *       question the decrement answers the same way; {@code jc} or {@code ja} ask about bits the
 *       two instructions disagree on.
 *   <li><b>The loop has to be short enough to count.</b> {@code loop}'s displacement is a signed
 *       byte where a jump's is not. Nothing here knows the final addresses — the assembler chooses
 *       encodings ({@code README.md} step 9) — so the distance is bounded from above instead: every
 *       instruction counts as {@link #WIDEST_INSTRUCTION} bytes, and a span holding bytes this pass
 *       cannot size (data, or a {@code pad to}) is left alone. An upper bound over the limit is not
 *       a refusal but a reason to keep the three instructions.
 * </ul>
 */
final class CountedLoops {

    /**
     * The widest an instruction of this machine encodes to: a prefix, an opcode, an addressing
     * byte, a sixteen-bit displacement and a sixteen-bit immediate. A bound, never a size — the
     * assembler is what decides what anything actually takes.
     */
    private static final int WIDEST_INSTRUCTION = 8;

    /** How far {@code loop} reaches, in bytes, either way. */
    private static final int LOOP_REACH = 127;

    /** The register {@code loop} counts in, which is what makes it unusable for any other. */
    private static final String COUNTING_REGISTER = "cx";

    private final SsaForm form;
    private final List<Selection.Piece> pieces;
    private final Map<Item, Block> blockOf = new LinkedHashMap<Item, Block>();
    private final Map<String, Integer> pieceOfLabel = new LinkedHashMap<String, Integer>();
    private final Liveness flags;

    private CountedLoops(SsaForm form, Selection selection) {
        this.form = form;
        this.pieces = selection.pieces();
        this.flags = Liveness.of(form.cfg(), Names.of(form.module()));
        for (Block block : form.cfg().blocks()) {
            for (SsaStatement statement : form.statements(block)) {
                blockOf.put(statement.item(), block);
            }
        }
        for (int at = 0; at < pieces.size(); at++) {
            String label = Item.labelOf(pieces.get(at).item());
            if (label != null && !pieceOfLabel.containsKey(label)) {
                pieceOfLabel.put(label, Integer.valueOf(at));
            }
        }
    }

    /** The same code, with the countdowns this machine writes shorter done the shorter way. */
    static Selection clean(SsaForm form, Selection selection) {
        return new CountedLoops(form, selection).run();
    }

    private Selection run() {
        List<Selection.Piece> rewritten = new ArrayList<Selection.Piece>(pieces);
        for (int at = 0; at + 2 < pieces.size(); at++) {
            countdown(rewritten, at);
        }
        return new Selection(rewritten);
    }

    /**
     * The countdown beginning at this piece, done the way the machine can do it.
     *
     * <p>Three pieces, because that is what the surface's three statements became: the value being
     * counted, the comparison of it with zero, and the branch that reads the comparison's flags.
     * Anything that is not that shape is left as it is.
     */
    private void countdown(List<Selection.Piece> rewritten, int at) {
        String counted = counted(rewritten.get(at));
        if (counted == null) {
            return;
        }
        if (!counted.equals(testedForZero(rewritten.get(at + 1)))) {
            return;
        }
        Instruction branch = onlyInstruction(rewritten.get(at + 2));
        Item where = rewritten.get(at + 2).item();
        if (branch == null || !(where instanceof Item.Branch)) {
            return;
        }
        Item.Branch branchItem = (Item.Branch) where;
        if (!"jz".equals(branchItem.condition()) && !"jnz".equals(branchItem.condition())) {
            return;
        }
        if (flagsAreReadAfter(branchItem)) {
            return;
        }

        // The comparison repeats what the register already says, and the branch can read it there.
        rewritten.set(at + 1, revisited(rewritten.get(at + 1), Collections.<Instruction>emptyList()));

        if (!"jnz".equals(branchItem.condition()) || !COUNTING_REGISTER.equals(counted)
                || !reaches(branchItem, at + 2)) {
            return;
        }
        // `loop` is the decrement and the branch at once, so both of them go.
        List<Instruction> counted2 = rewritten.get(at).instructions();
        rewritten.set(at, revisited(rewritten.get(at),
                counted2.subList(0, counted2.size() - 1)));
        rewritten.set(at + 2, revisited(rewritten.get(at + 2), Collections.singletonList(
                new Instruction(branch.position(), "loop", branch.operands()))));
    }

    /** The same piece with these instructions and nothing else. */
    private static Selection.Piece revisited(Selection.Piece piece, List<Instruction> instructions) {
        return new Selection.Piece(piece.item(), new ArrayList<Instruction>(instructions));
    }

    /** The piece's only instruction, or null when it holds another number of them. */
    private static Instruction onlyInstruction(Selection.Piece piece) {
        return piece.instructions().size() == 1 ? piece.instructions().get(0) : null;
    }

    /**
     * The register this piece counts up or down, or null when it counts nothing.
     *
     * <p>{@code dec} and {@code inc} are the forms selection chooses when the flags of the
     * operation are not wanted; {@code sub r, 1} and {@code add r, 1} are the same arithmetic and
     * are left where the flags were wanted after all. All four leave ZF saying whether the result
     * is zero, which is the whole of what this pass relies on them for.
     */
    private static String counted(Selection.Piece piece) {
        List<Instruction> instructions = piece.instructions();
        if (instructions.isEmpty()) {
            return null;
        }
        Instruction last = instructions.get(instructions.size() - 1);
        List<Operand> operands = last.operands();
        if (operands.size() == 1
                && (last.mnemonic().equals("dec") || last.mnemonic().equals("inc"))) {
            return register(operands.get(0));
        }
        if (operands.size() == 2 && one(operands.get(1))
                && (last.mnemonic().equals("sub") || last.mnemonic().equals("add"))) {
            return register(operands.get(0));
        }
        return null;
    }

    /** The register this piece compares with zero, or null when it compares something else. */
    private static String testedForZero(Selection.Piece piece) {
        Instruction only = onlyInstruction(piece);
        if (only == null) {
            return null;
        }
        List<Operand> operands = only.operands();
        if (only.mnemonic().equals("test") && operands.size() == 2) {
            String one = register(operands.get(0));
            return one != null && one.equals(register(operands.get(1))) ? one : null;
        }
        if (only.mnemonic().equals("cmp") && operands.size() == 2 && zero(operands.get(1))) {
            return register(operands.get(0));
        }
        return null;
    }

    /**
     * Whether the flags this branch reads can still be read after it.
     *
     * <p>The flags the comparison leaves are one value, and this asks the question the form was
     * built to answer: can anything on the far side of the branch read them before they are
     * defined again. A block nothing follows reads nothing.
     */
    private boolean flagsAreReadAfter(Item branch) {
        Block block = blockOf.get(branch);
        if (block == null) {
            return true;
        }
        for (Block successor : block.successors()) {
            if (flags.isLiveIn(successor, Names.FLAGS)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code loop} reaches this branch's target from here, as far as this can tell. */
    private boolean reaches(Item.Branch branch, int from) {
        Integer target = pieceOfLabel.get(branch.target());
        if (target == null) {
            return false;
        }
        int span = bytesBetween(target.intValue(), from);
        return span >= 0 && span <= LOOP_REACH;
    }

    /**
     * An upper bound on the bytes these pieces take, or -1 when they cannot be bounded.
     *
     * <p>Data is exact and a {@code pad to} is not known until everything is placed, so a span
     * holding either is refused rather than guessed at; an inline block is text of a length this
     * pass does not know either. Everything else is counted at the widest an instruction can be,
     * which over-counts and is the direction a bound has to err in.
     */
    private int bytesBetween(int one, int other) {
        int low = Math.min(one, other);
        int high = Math.max(one, other);
        int total = 0;
        for (int at = low; at <= high; at++) {
            Item item = pieces.get(at).item();
            if (item instanceof Item.Data || item instanceof Item.Pad
                    || item instanceof Item.InlineAsm) {
                return -1;
            }
            total += WIDEST_INSTRUCTION * pieces.get(at).instructions().size();
            if (total > LOOP_REACH) {
                return total;
            }
        }
        return total;
    }

    /** The register an operand names, or null when it is not a register. */
    private static String register(Operand operand) {
        return operand instanceof Operand.Name ? ((Operand.Name) operand).name() : null;
    }

    /** Whether an operand is the literal one. */
    private static boolean one(Operand operand) {
        return operand instanceof Operand.Number && ((Operand.Number) operand).value() == 1;
    }

    /** Whether an operand is the literal zero. */
    private static boolean zero(Operand operand) {
        return operand instanceof Operand.Number && ((Operand.Number) operand).value() == 0;
    }
}
