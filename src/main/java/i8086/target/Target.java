package i8086.target;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.ir.Comparison;
import i8086.ir.Item;
import i8086.ir.Operator;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * Whether control reaches the instruction after this one.
     *
     * <p>What a walk over the instructions has to know, and the same kind of question
     * {@link #isBranch(String)} is: a conditional branch goes both ways, a jump goes one
     * way, and something that leaves — a return, a halt, an interrupt return — goes
     * nowhere this module can see. An interrupt is a call: it comes back unless the
     * handler does not, and that is not a promise this compiler can make on the
     * program's behalf, so it carries on ({@code docs/ir.md} §11).
     *
     * <p>The default is the one thing the vocabulary already says: everything except the
     * unconditional jump carries on, because a condition was the only other way to go
     * somewhere.
     */
    default boolean fallsThrough(String mnemonic) {
        return !isBranch(mnemonic) || condition(mnemonic) != null;
    }

    /**
     * The operation a mnemonic names when it begins a statement, or null when this
     * target does not accept the instruction-shaped form ({@code docs/ir.md} §7.3).
     *
     * <p>This is the same kind of question as {@link #condition(String)}: the surface
     * has a word and the target knows whether it is one of its own. The answer is an
     * operator rather than a mnemonic, because the operator is the vocabulary the rest
     * of the compiler is written in — which is what keeps the machine's words on this
     * side of the boundary ({@code AGENTS.md}, invariant 2).
     *
     * <p>A word belongs in the table only when the operation it names on this machine
     * is the operation the surface already has. What that rules out is the point of
     * having the table here rather than in the parser: the surface's {@code -} is
     * {@code SUB}, and this machine's {@code inc} is <em>not</em> {@code ADD 1} — it
     * leaves the carry alone — so {@code inc} is not a spelling of anything
     * ({@code docs/ir.md} §7.3).
     */
    default Operator statementOperator(String word) {
        return null;
    }

    /**
     * Every mnemonic this target accepts as a statement, in a fixed order, for the
     * tests that check the whole vocabulary at once — that a word the target knows
     * can still be the author's name with a {@code $} in front of it
     * ({@code docs/ir.md} §3.1), and that each refused word is refused with a
     * reason rather than by falling through.
     */
    default List<String> statementWords() {
        return Collections.emptyList();
    }

    /**
     * Why this mnemonic cannot begin a statement here, or null when the word means
     * nothing to this target and the complaint is the parser's to make.
     *
     * <p>Asked with the number of operands that were written, because on a real
     * machine that is often what decides the answer: {@code mul s, t} is the surface's
     * operation and {@code mul r} is a different instruction that reads and writes
     * {@code ax} and {@code dx} behind the writer's back.
     */
    default String statementProblem(String word, int operands) {
        return null;
    }

    /**
     * The mnemonics this target provides as statements of their own, and the width in
     * bytes of the one immediate each takes, or 0 for none ({@code docs/ir.md} §11).
     *
     * <p>These are the machine's operations that are not arithmetic and are not an
     * operation the surface has a value for: an interrupt, a halt, the interrupt flag,
     * a no-op, an interrupt return. They take no register operands — which is why they
     * can be statements at all, since a value cannot be named as being in a register
     * yet (§12 item 12) — and an author says which registers they destroy when the
     * target's answer is too wide to be useful.
     */
    default Map<String, Integer> machineStatements() {
        return Collections.emptyMap();
    }

    /**
     * What one of those destroys when the author does not say: register names from
     * {@link #valueRegisters()}, and {@code flags} ({@code docs/ir.md} §11).
     *
     * <p>The answer has to be the honest worst case, because only the program knows
     * what a handler keeps: {@code int} therefore destroys everything, and a program
     * that keeps a value across one is refused until it says what is really destroyed.
     */
    default List<String> machineClobbers(String mnemonic) {
        return Collections.emptyList();
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
     * The registers this instruction destroys, beyond the value it writes for the
     * program.
     *
     * <p>This is the other half of an operand: an instruction's operands say what it
     * is given and what it leaves, and this says what it takes away that nobody
     * wrote down. Three ways that happens, and all three are the machine's business:
     *
     * <ul>
     *   <li>an instruction writes its first operand, and the selector wrote a
     *       <em>register</em> there rather than a value — {@code mov cl, 4} writes
     *       part of {@code cx}, and a value living in {@code cx} is gone;
     *   <li>an instruction has a result with no operand saying so — {@code mul r}
     *       leaves its product in {@code dx:ax};
     *   <li>part of a register counts as the whole register, because nothing here
     *       can name half of one, so writing {@code cl} destroys {@code cx}.
     * </ul>
     *
     * <p>The allocator is the caller, and the person it is protecting is the
     * program: a value that is still to be read after this instruction may not be
     * given a register the instruction destroys. Asking the target is what keeps
     * that reasoning out of the allocator ({@code AGENTS.md}, invariant 2).
     *
     * <p>The names are {@link #valueRegisters()} names, so that an eight-bit
     * register answers with the sixteen-bit register that holds it.
     *
     * <p>What this does <em>not</em> say is the other direction: an operand that has
     * to <em>be</em> in a particular register, which is what {@code mul} and
     * {@code div} need for their first operand and what pinning a value would
     * provide ({@code docs/ir.md} §12, item 12).
     */
    default Set<String> clobbers(Instruction instruction) {
        return Collections.emptySet();
    }

    /**
     * A sequence that multiplies two registers, or null when this target has none.
     *
     * <p>The 8086 has one instruction and it is not an ordinary one: {@code mul r}
     * multiplies whatever is in {@code AX} and leaves the low half there, with the
     * high half in {@code DX}. So the sequence is a copy in and a copy out, and the
     * copies are stated even when they turn out to be unnecessary — the allocator is
     * the one that finds out, and it drops a copy of a register into itself.
     *
     * <p>Multiplication does not care which side a literal is on, so a literal on the
     * right is moved to the left rather than refused: it is the side that can be
     * given to the copy, since the instruction takes a register or a memory operand
     * and not an immediate.
     */
    Expansion multiply(SourcePos where, Operand destination, Operand left, Operand right,
                       boolean signed);

    /**
     * A sequence that divides two registers, or null when this target has none.
     *
     * <p>{@code div r} divides {@code DX:AX}, so the top half has to be made the
     * dividend's sign or zero first; the quotient arrives in {@code AX} and the
     * remainder in {@code DX}, and {@code remainder} says which of the two the caller
     * wants.
     *
     * <p>Nothing here checks the divisor. Division by zero and a quotient that does
     * not fit are faults the hardware reports and the program's business
     * ({@code docs/ir.md} §2.2), and the compiler's duty is only not to introduce one
     * the program did not already have — which is why a division is not something a
     * pass may move or duplicate ({@code docs/ir.md} §5.5).
     */
    Expansion divide(SourcePos where, Operand destination, Operand left, Operand right,
                     boolean signed, boolean remainder);

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
