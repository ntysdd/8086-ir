package i8086.target;

import i8086.asm.Instruction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A fixed sequence of instructions that does one operation the machine has no
 * single instruction for ({@code AGENTS.md}, "Expansion").
 *
 * <p>It comes from the target, so selection substitutes a declared sequence
 * rather than inventing one: the sequence is data the target hands over, which
 * is what keeps selection a lookup and its output checkable.
 *
 * <p>An expansion carries the same flag question a form does: repeating
 * {@code shl r, 1} four times is not the same as shifting by four once, and
 * neither is the same as the {@code mul} the writer may have meant. So an
 * expansion that does not keep the flags may only be used where nothing can read
 * them.
 */
public final class Expansion {

    private final List<Instruction> instructions;
    private final boolean keepsFlags;

    public Expansion(List<Instruction> instructions, boolean keepsFlags) {
        this.instructions = Collections.unmodifiableList(new ArrayList<Instruction>(instructions));
        this.keepsFlags = keepsFlags;
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    /** Whether the flags afterwards are the ones the operation itself would leave. */
    public boolean keepsFlags() {
        return keepsFlags;
    }
}
