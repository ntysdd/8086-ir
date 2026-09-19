package i8086.target;

import i8086.SourcePos;
import i8086.asm.Operand;
import i8086.ir.Operator;

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

    /**
     * The registers a value may live in, in the order they should be used up.
     *
     * <p>This is the register class the allocator colours against: what is in the
     * list is what a variable may become, and what is left out is left out for a
     * reason that belongs to the machine — {@code sp} is the stack, and the
     * registers that can only be addressed by half their names are not free for
     * the taking.
     */
    List<String> valueRegisters();

    /**
     * The forms that do this operator, smallest first is not promised — the
     * caller sorts — but every form is one the machine really has.
     *
     * <p>The question is asked in the IR's vocabulary, because that is what
     * selection has: it knows an addition happened, not that the instruction for
     * it is called {@code add}. Answering in that vocabulary is exactly what
     * keeps the machine's words on this side of the boundary.
     */
    List<Form> forms(Operator operator);

    /**
     * A sequence that multiplies a register by a constant, or null when this
     * target has no such trick.
     *
     * <p>Returning a sequence rather than a form is the point:
     * {@code AGENTS.md} allows selection to substitute a declared expansion, and
     * this is one. The caller must respect {@link Expansion#keepsFlags()}.
     */
    Expansion multiplyByConstant(SourcePos where, Operand destination, Operand source,
                                 long factor);

    /**
     * A sequence that shifts a register by a constant count, or null when this
     * target has no such trick. {@code mnemonic} is the shift the operation
     * asked for, so {@code shl}, {@code shr} and {@code sar} all come through
     * here and stay the machine's words.
     */
    Expansion shiftByConstant(SourcePos where, String mnemonic, Operand destination,
                              Operand source, long count);
}
