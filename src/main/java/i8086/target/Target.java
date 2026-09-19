package i8086.target;

import java.util.List;

/**
 * What the rest of the compiler is allowed to know about a machine.
 *
 * <p>This is the boundary that {@code AGENTS.md} invariant 2 draws: register
 * classes, operand constraints, flag effects, addressing modes, segmentation and
 * encodings all belong on this side of it, and a generic pass talks to a target
 * through this interface rather than through what it happens to know about one.
 *
 * <p>It starts small and grows only when something needs it. What is here is
 * what the compiler currently has to ask: whether a name is a register, and
 * whether one is a segment register. Mnemonics, conditions, flag effects and
 * encodings belong here too and are not here yet.
 */
public interface Target {

    /** How the target is named in a module: {@code target 8086} names {@code "8086"}. */
    String name();

    /** Whether the name is one of this target's registers. */
    boolean isRegister(String name);

    /**
     * Whether the name is one of this target's segment registers.
     *
     * <p>A segment register is not a general register: it is segmentation state,
     * which the surface lets a module set directly ({@code docs/ir.md} §8.1).
     */
    boolean isSegmentRegister(String name);

    /**
     * The condition a branch word names, in its canonical spelling, or null when
     * the word names no condition this target has.
     *
     * <p>The condition is returned as a spelling rather than as an enum because
     * that is what the surface writes and what the printer writes back: the
     * compiler has no business inventing a second name for a flag test. Words
     * that mean the same thing — {@code jb}, {@code jc} and {@code jnae} are one
     * test — all answer with the same canonical word, so the answer is also the
     * identity of the condition ({@code docs/ir.md} §4.4).
     */
    String condition(String word);

    /**
     * Every condition this target has, in a fixed order, for a diagnostic that
     * has to list them.
     */
    List<String> conditions();
}
