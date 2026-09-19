package i8086.target;

import i8086.SourcePos;
import i8086.asm.Operand;
import i8086.ir.Comparison;
import i8086.ir.Item;
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
     * The condition that is the opposite of this one.
     *
     * <p>The control-flow sugar is written as "branch past this branch when the
     * test fails", so it has to be able to say the opposite of what was written
     * ({@code docs/ir.md} §7.2). Which words are opposites is the machine's
     * business: {@code jb} against {@code jnc} is not a rule anybody could guess.
     */
    String negate(String condition);

    /**
     * The condition that tests a comparison, given whether its operands are
     * signed.
     *
     * <p>This is where {@code <} becomes {@code jb} or {@code jl}, and it is here
     * rather than in the parser because the answer is about flags and not about
     * comparisons.
     */
    String conditionFor(Comparison comparison, boolean signed);

    /**
     * The instruction that goes somewhere unconditionally.
     *
     * <p>A branch is not an operation, so it has no entry in {@link #forms}: its
     * operand is a label, and the shapes there describe registers and literals.
     */
    String jumpMnemonic();

    /**
     * The forms that set the flags from two values: {@code cmp} and {@code test}.
     */
    List<Form> compareForms(Item.Compare.Kind kind);

    /**
     * Whether a mnemonic is one that goes somewhere.
     *
     * <p>Derived from what this target already says — the jump and the conditions
     * are its whole vocabulary of branching — so it needs no table of its own. It
     * is asked about inline assembly as well as about selected code, because a
     * block of it can jump too, and whatever reasons about a run of instructions
     * has to know.
     */
    default boolean isBranch(String mnemonic) {
        return mnemonic.equals(jumpMnemonic()) || condition(mnemonic) != null;
    }

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
     * The registers a value may be addressed through.
     *
     * <p>An address is not a value with extra rules; it is a value that has to
     * live somewhere the machine can put inside the brackets. Which registers those
     * are is this target's business: on the 8086 it is {@code bx}, {@code si} and
     * {@code di}, and a program that needs an address in {@code ax} is a program
     * with an instruction this machine does not have.
     *
     * <p>The allocator is the one that has to know, because it is the one handing
     * out registers: a value used as an address gets a register from here, and a
     * value used as an address <em>and</em> as an ordinary value gets one that is in
     * both lists.
     */
    List<String> addressRegisters();

    /** The forms that read a value out of memory into a register. */
    List<Form> loadForms();

    /** The forms that write a register into memory. */
    List<Form> storeForms();

    /** The forms that write a literal into memory. */
    List<Form> storeLiteralForms();

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
