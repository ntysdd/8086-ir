package i8086.regalloc;

import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.isel.Selection;
import i8086.ssa.SsaForm;

import java.util.ArrayList;
import java.util.List;

/**
 * The instruction stream with every version back to the variable it is a version of.
 *
 * <p>Allocation works in one name per variable: a name is a life, and the life is the
 * interval between its first and last mention. An SSA form has one name per
 * <em>definition</em>, so a variable written twice is two names, and two names are
 * two lives — which is exactly right until the two meet at a φ, where they have to be
 * the same register or the value is not there after the merge. Merging the versions of
 * a variable is what makes the arms of a φ agree without the allocator having to know
 * that φ's exist.
 *
 * <p>It merges more than the φ's require: two versions that never meet at one could
 * perfectly well hold different registers. That is the deliberate part of it — one
 * name per variable is the conservative answer, and it is the answer the allocator
 * already understands, so this is a translation rather than a new constraint. What it
 * costs is precision: a variable's versions are one life even where the value is dead
 * between them, which is what {@code docs/ssa.md} §8 records.
 *
 * <p>A name the form does not know is left alone. After selection those are the
 * labels, since every value in the stream is a name the form handed out; renaming is
 * about values ({@code docs/ssa.md}).
 */
public final class MergeVersions {

    private MergeVersions() {
    }

    /** The same selection, with every virtual name written as the variable it belongs to. */
    public static Selection merge(Selection selection, SsaForm form) {
        List<Selection.Piece> pieces = new ArrayList<Selection.Piece>();
        for (Selection.Piece piece : selection.pieces()) {
            List<Instruction> rewritten = new ArrayList<Instruction>();
            for (Instruction instruction : piece.instructions()) {
                rewritten.add(merge(instruction, form));
            }
            pieces.add(piece.with(rewritten));
        }
        return new Selection(pieces, selection.controlFlow());
    }

    private static Instruction merge(Instruction instruction, SsaForm form) {
        if (instruction.isLabel()) {
            // A label has no operands, and what it carries is its name
            // (docs/ir.md §9): rebuilding it would drop the one thing it has.
            return instruction;
        }
        List<Operand> operands = new ArrayList<Operand>();
        for (Operand operand : instruction.operands()) {
            operands.add(merge(operand, form));
        }
        return new Instruction(instruction.position(), instruction.mnemonic(), operands);
    }

    private static Operand merge(Operand operand, SsaForm form) {
        if (operand instanceof Operand.Virtual) {
            return new Operand.Virtual(operand.position(),
                    variableOf(((Operand.Virtual) operand).name(), form));
        }
        if (operand instanceof Operand.Memory) {
            Operand.Memory memory = (Operand.Memory) operand;
            List<Operand.Memory.Atom> atoms = new ArrayList<Operand.Memory.Atom>();
            for (Operand.Memory.Atom atom : memory.atoms()) {
                atoms.add(atom.isVirtual()
                        ? Operand.Memory.Atom.ofVirtual(variableOf(atom.name(), form))
                        : atom);
            }
            return new Operand.Memory(memory.position(), memory.size(), memory.segment(), atoms);
        }
        return operand;
    }

    /**
     * The variable a name is a version of, or the name itself when the form has never
     * heard of it.
     *
     * <p>This is where the undefined value lands too: {@code x#undef} is a name for
     * "x, with no value", and in the form the allocator works in that is simply x
     * ({@code docs/ssa.md} §5).
     */
    private static String variableOf(String name, SsaForm form) {
        String variable = form.variableOf(name);
        return variable == null ? name : variable;
    }
}
