package i8086.regalloc;

import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Item;
import i8086.isel.Selection;
import i8086.ssa.Effects;
import i8086.target.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which values can still be read at each point of a run of instructions.
 *
 * <p>A **point** is one item and the instructions it became, because that is as fine as
 * this can honestly go: what happens *inside* an expansion is the target's business, and
 * the registers it insists on are already known through {@link Target#clobbers}. So a
 * point reads what its instructions mention and writes what its item defines.
 *
 * <p>The edges between points are the ones the module makes: a point carries on to the
 * next one unless its last instruction goes somewhere else, and a branch also reaches the
 * label it names. That is the whole graph. It is also why a branch inside an inline block
 * may only reach the block's own labels: an edge nothing here can see is a value that
 * looks dead on the far side of it, and the verifier refuses one for that reason
 * ({@code docs/ir.md} §9).
 *
 * <p>What comes out is what the allocator needs and no more. Two values live at one point
 * cannot share a register; a value live where a register is destroyed cannot live in that
 * register; and a value that is not live at all is a value nothing reads, which is why a
 * definition with nothing live after it needs no register at all.
 *
 * <p>The computation is the ordinary backward one, and it is here rather than in a basic
 * block at a time because a point is cheap and a program is small: the answer is the same
 * either way and this needs no block at all.
 */
public final class Liveness {

    private final List<Set<String>> liveBefore;
    private final List<Set<String>> liveAfter;

    private Liveness(List<Set<String>> liveBefore, List<Set<String>> liveAfter) {
        this.liveBefore = Collections.unmodifiableList(liveBefore);
        this.liveAfter = Collections.unmodifiableList(liveAfter);
    }

    /**
     * The liveness of a selection: where each value can still be read.
     *
     * <p>A point that mentions a value and does not define it is reading it, so the walk
     * is backwards and the answer is the fixpoint. Nothing here looks at registers: a
     * value destroyed by an instruction is a fact about the target and is asked for
     * where it is needed ({@link #liveAt}).
     */
    public static Liveness of(Selection selection, Target target) {
        List<Selection.Piece> pieces = selection.pieces();
        List<Set<String>> uses = new ArrayList<Set<String>>();
        List<Set<String>> defs = new ArrayList<Set<String>>();
        for (Selection.Piece piece : pieces) {
            // What the point writes is not something it reads, even though the instruction
            // mentions it — every name here has one definition, so a point that both writes
            // and reads a name is reading the value it has just written, and nothing about
            // the value it is given belongs to this question.
            String defined = Effects.writtenVariable(piece.item());
            Set<String> read = new LinkedHashSet<String>();
            for (Instruction instruction : piece.instructions()) {
                read.addAll(mentioned(instruction));
            }
            read.remove(defined);
            Set<String> written = new LinkedHashSet<String>();
            if (defined != null) {
                written.add(defined);
            }
            uses.add(read);
            defs.add(written);
        }

        List<List<Integer>> successors = successors(pieces, target);
        int points = pieces.size();
        List<Set<String>> liveBefore = new ArrayList<Set<String>>();
        List<Set<String>> liveAfter = new ArrayList<Set<String>>();
        for (int i = 0; i < points; i++) {
            liveBefore.add(new LinkedHashSet<String>());
            liveAfter.add(new LinkedHashSet<String>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = points - 1; i >= 0; i--) {
                Set<String> after = new LinkedHashSet<String>();
                for (Integer successor : successors.get(i)) {
                    after.addAll(liveBefore.get(successor.intValue()));
                }
                if (!after.equals(liveAfter.get(i))) {
                    liveAfter.set(i, after);
                    changed = true;
                }
                Set<String> before = new LinkedHashSet<String>(uses.get(i));
                for (String name : after) {
                    if (!defs.get(i).contains(name)) {
                        before.add(name);
                    }
                }
                if (!before.equals(liveBefore.get(i))) {
                    liveBefore.set(i, before);
                    changed = true;
                }
            }
        }
        return new Liveness(liveBefore, liveAfter);
    }

    /**
     * Where each point goes next.
     *
     * <p>Falling through is the default and a branch adds the label it names: a conditional
     * branch has both, a jump has one, and a return or a halt has neither. A branch whose
     * target is not a label of this module reaches a place in this block, so there is no
     * edge to draw — and the verifier has already refused the other case, where the target
     * was a label of the module ({@code docs/ir.md} §9).
     */
    private static List<List<Integer>> successors(List<Selection.Piece> pieces, Target target) {
        Map<String, Integer> labels = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < pieces.size(); i++) {
            String label = Item.labelOf(pieces.get(i).item());
            if (label != null) {
                labels.put(label, Integer.valueOf(i));
            }
        }
        List<List<Integer>> successors = new ArrayList<List<Integer>>();
        for (int i = 0; i < pieces.size(); i++) {
            List<Integer> next = new ArrayList<Integer>();
            Instruction last = last(pieces.get(i));
            if ((last == null || target.fallsThrough(last.mnemonic())) && i + 1 < pieces.size()) {
                next.add(Integer.valueOf(i + 1));
            }
            if (last != null && target.isBranch(last.mnemonic())) {
                for (Operand operand : last.operands()) {
                    if (!(operand instanceof Operand.Name)) {
                        continue;
                    }
                    Integer where = labels.get(((Operand.Name) operand).name());
                    if (where != null) {
                        next.add(where);
                    }
                }
            }
            successors.add(next);
        }
        return successors;
    }

    /** The last instruction of a point, or null when it has none. */
    private static Instruction last(Selection.Piece piece) {
        List<Instruction> instructions = piece.instructions();
        return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1);
    }

    /**
     * Every value an instruction mentions, brackets and all.
     *
     * <p>A memory operand holds names rather than operands, so walking {@code operands()}
     * alone would miss the address a load reads through — and a value no walk sees is a
     * value with no register and no life. A name that is not virtual is a register or a
     * label, which is neither a value nor this question.
     *
     * <p>A {@link Operand.LowByte} mentions its value like any other operand: a narrowing
     * conversion reads it, and a use this walk missed would make a value look dead where it is
     * read — which on the far side of a call that destroys every register is a value read out
     * of a register the call has already overwritten ({@code docs/ir.md} §3.5).
     */
    private static List<String> mentioned(Instruction instruction) {
        List<String> names = new ArrayList<String>();
        for (Operand operand : instruction.operands()) {
            if (operand instanceof Operand.Virtual) {
                names.add(((Operand.Virtual) operand).name());
            } else if (operand instanceof Operand.LowByte) {
                names.add(((Operand.LowByte) operand).name());
            } else if (operand instanceof Operand.Memory) {
                for (Operand.Memory.Atom atom : ((Operand.Memory) operand).atoms()) {
                    if (atom.isVirtual()) {
                        names.add(atom.name());
                    }
                }
            }
        }
        return names;
    }

    /** How many points there are. */
    public int points() {
        return liveBefore.size();
    }

    /** The values that can still be read when this point is reached. */
    public Set<String> liveBefore(int point) {
        return liveBefore.get(point);
    }

    /** The values that can still be read once this point has run. */
    public Set<String> liveAfter(int point) {
        return liveAfter.get(point);
    }

    /**
     * The values alive at this point, before or after it.
     *
     * <p>This is the question both of the allocator's constraints are asked in: a value
     * defined here may not share a register with anything else alive here, and a value
     * alive here may not be in a register this point destroys.
     */
    public Set<String> liveAt(int point) {
        Set<String> alive = new LinkedHashSet<String>(liveBefore.get(point));
        alive.addAll(liveAfter.get(point));
        return alive;
    }
}
