package i8086.target;

import i8086.asm.Instruction;
import i8086.ir.Names;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 * them — and, like a {@link Form}, it says <em>which</em> flags it leaves
 * differently, because a sequence that leaves the carry alone stands for an
 * operation wherever the carry is not read ({@code docs/ir.md} §4.2).
 */
public final class Expansion {

    /** Every flag an operation leaves, for a sequence that differs without saying which. */
    private static final Set<String> ALL = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(Names.FLAGS, Names.CARRY)));

    private final List<Instruction> instructions;
    private final Set<String> differs;

    /** The same, saying whether the flags afterwards are the operation's own at all. */
    public Expansion(List<Instruction> instructions, boolean keepsFlags) {
        this(instructions, keepsFlags ? Collections.<String>emptySet() : ALL);
    }

    /** The same, naming the flags it leaves differently. */
    public Expansion(List<Instruction> instructions, Set<String> differs) {
        this.instructions = Collections.unmodifiableList(new ArrayList<Instruction>(instructions));
        this.differs = Collections.unmodifiableSet(new LinkedHashSet<String>(differs));
    }

    public List<Instruction> instructions() {
        return instructions;
    }

    /** The flags these instructions leave differently from the operation, by name. */
    public Set<String> differs() {
        return differs;
    }
}
