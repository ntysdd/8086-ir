package i8086.target;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.ir.Comparison;
import i8086.ir.FlagUse;
import i8086.ir.Item;
import i8086.ir.Operator;
import i8086.isel.Selection;
import i8086.ssa.SsaForm;

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
     * Whether a comparison with zero may be written as a test of the operand against itself, or
     * false when this target has no such instruction or cannot promise the flags
     * ({@code docs/ir.md} §4.2).
     *
     * <p>Two different instructions, one answer, and only the machine can say so: on the 8086 both
     * clear the carry and the overflow flag and both take ZF, SF and PF from the operand, so every
     * condition it can test reads the same after either. The one flag they do not promise the same
     * thing about is the auxiliary carry, and no condition this machine has reads it — which is
     * what a target that answers yes is claiming, and why the answer is a fact about the target
     * rather than a trick of selection. A target that answers no keeps the comparison.
     *
     * <p>Which instruction it is, is the target's to write: the caller takes the operand from the
     * comparison and asks for the forms of a {@code test} ({@link #compareForms}), so the machine's
     * own words stay on this side of the boundary ({@code AGENTS.md}, invariant 2).
     */
    default boolean zeroComparisonIsATest() {
        return false;
    }

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
     * What a machine statement does with the flags, by name ({@code docs/ir.md} §11): the ones it
     * leaves a value of its own in, and the ones whose value decides what it does.
     *
     * <p>A clobber list says what a statement destroys, and a flag is one of the things it can
     * name. What no list can say is the difference between the two ways a statement fails to name
     * one, and on this machine those are opposite things. Clearing the interrupt flag leaves the
     * arithmetic flags exactly as they were, so the comparison in front of it is still the
     * comparison a branch behind it reads; the same statement makes the direction flag its own,
     * because a copy that follows it goes where this one said. An interrupt goes into code this
     * module has never seen, and what the handler leaves in the arithmetic flags is a value the
     * statement <em>made</em> — which is what an author writing {@code jc} after it means.
     *
     * <p>The other half is what a statement <em>reads</em>, which is the half a copy needs: a
     * repeated move decides which way to walk from the direction flag, so a program that uses one
     * has to have set it, and saying so is what lets that be checked ({@code docs/ir.md} §4.3).
     *
     * <p>Answering with nothing is the safe direction: a definition the compiler still believes in
     * is code that stays, and code that stays is not a wrong program. A target whose statements
     * produce or read flags of their own says so.
     */
    default FlagUse machineFlags(String mnemonic) {
        return FlagUse.none();
    }

    /**
     * A shift by one of the values this machine has, or null when the count has to come from
     * somewhere else ({@code docs/ir.md} §5.6).
     *
     * <p>This machine's count is a literal or nothing: {@code SHL r/m, imm8} arrived with the 80186,
     * so a value has to go to {@code cl} first — the same register whatever the value is, which is
     * the other half of the reason the sequence is the target's to declare and not selection's to
     * invent.
     */
    default Expansion shiftByValue(SourcePos where, String mnemonic, Operand destination,
                                   Operand source, String count) {
        return null;
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

    /**
     * The name the low byte of a value register is written by, or null when this register has
     * none.
     *
     * <p>It is how a byte value lives: the surface has no half-registers, so a value is a whole
     * register, and an instruction that reads or writes one byte of it names the byte half —
     * {@code al} for {@code ax} ({@code docs/ir.md} §3.2). A register with no such name cannot
     * hold a byte value at all, which is why this is asked rather than assumed.
     */
    String byteRegister(String register);

    /**
     * The register a value would have to be in for this one's content to be part of it, or null
     * when no value can be in the register at all.
     *
     * <p>What a statement that reads one of the machine's own registers needs said about it: the
     * register holds what the machine left there, so nothing of the compiler's may be in it
     * ({@code docs/ir.md} §8.1). Nothing here can name half a register, so a value in {@code dx} and
     * the {@code dl} a read is about are the same register and the answer for {@code dl} is
     * {@code dx} — and {@code ds} or {@code sp}, where no value is ever put, answer with null,
     * because there is nothing to keep out.
     */
    String valueRegisterOf(String register);

    /**
     * How many bytes a register is, or zero when the name is not one of this target's registers.
     *
     * <p>What a {@code with} clause is checked against: the value put into a register has to fit it,
     * which is the rule the two sides of an assignment follow too ({@code docs/ir.md} §3.2). A byte
     * half is one byte; every other register this machine has is two.
     */
    int registerBytes(String register);

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
     * The register this instruction would rather its operand were in, or null when it does not care.
     *
     * <p>A machine is not uniform about its registers. On this one, {@code loop} counts in {@code cx}
     * and nowhere else, so a value an instruction counts down is a byte cheaper there — while the
     * register the allocator reaches for first is the accumulator, whose direct-address and immediate
     * forms are shorter than the general ones. What that makes this is a <b>hint</b> rather than a
     * constraint: the allocator tries the answer before its own order and falls back to the order when
     * it does not fit, which is the difference between preferring a register and pinning a value to
     * one ({@code docs/ir.md} §12 item 12).
     *
     * <p>Which instructions those are is the target's to say, and so is whether it says anything at
     * all: a target whose registers are interchangeable answers null for everything and its allocator
     * behaves as it did.
     */
    default String preferredRegister(Instruction instruction) {
        return null;
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
     * A sequence that divides by a constant, or null when this target has no trick for that value
     * ({@code docs/ir.md} §6.2).
     *
     * <p>A divisor the program wrote out is one the compiler can look at, and some of them are worth
     * more than the divide: a power of two is a shift, and a remainder of one is a mask — a quarter
     * of the bytes, and none of the registers the machine's own division insists on for its answer.
     * That is why the question is about a <em>constant</em>: a divisor that is a value is a division,
     * and there is nothing to look at.
     *
     * <p>{@code signed} is the caller saying which division this is. A target that can only do this
     * for one of them answers null for the other, because an arithmetic shift and the machine's
     * division disagree about negative numbers.
     */
    default Expansion divideByConstant(SourcePos where, Operand destination, Operand source,
                                       long divisor, boolean signed, boolean remainder) {
        return null;
    }

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
     * The register this target's sequence for an operator leaves its answer in, or null when the
     * caller says where the answer goes ({@code docs/ir.md} §6.1).
     *
     * <p>Some operations have no ordinary form: the machine does them in a register it names itself,
     * so the sequence copies its operands in and its answer out. Two of those in a row —
     * {@code d * a / b} — are asked for with the first one's answer going straight into the second
     * one, and then the copies between them are moves of a register into itself
     * ({@code docs/ir.md} §5.5). Which register that is, is the machine's business and the reason
     * this is a question rather than a constant: the caller may not know that a multiply works in
     * {@code ax}, and a target whose machine works elsewhere says so here.
     *
     * <p>A target that answers null keeps the copies. The answer is the register the operation's
     * <em>answer</em> is in, which is not always the one it computes in: a remainder arrives in the
     * other half of it.
     */
    default String answerRegister(Operator operator) {
        return null;
    }

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

    /**
     * A sequence that widens a byte into a register, or null when this target declares none.
     *
     * <p>Widening is the direction this machine has no instruction for: {@code MOVZX} and
     * {@code MOVSX} arrived with the 386, so what the surface calls a conversion is a sequence here
     * — {@code xor ah, ah} to fill the top with zeroes, {@code cbw} to fill it with a copy of the
     * sign bit. Both of those work on {@code ax} and nowhere else, so the sequence is written the
     * way this target's other sequences are: the byte goes where the machine wants it, the
     * extension happens, and the answer is copied out ({@code docs/ir.md} §3.5).
     */
    Expansion widen(SourcePos where, Operand destination, Operand source, boolean signed);

    /**
     * The registers a {@code movreg} statement may write, and the only ones: this target's machine
     * state that no value can live in, in the order a diagnostic should list them
     * ({@code docs/ir.md} §8.1).
     *
     * <p>The rule is the list's meaning: a standalone write to a register is safe exactly when no
     * value can be in that register, because otherwise the write would have to survive until some
     * later statement reads it — which is pinning, and a different question. So this is every
     * register the allocator never hands out that this target can write.
     */
    List<String> stateRegisters();

    /**
     * A sequence that puts an operand into one of {@link #stateRegisters()}, or null when this
     * target cannot set that one.
     *
     * <p>A parameter and not an instruction, on this machine: a segment register takes neither an
     * immediate nor a memory operand, so setting one means going through a general register, and
     * which register that is, is the machine's business. The operand the caller passes may be a
     * value it chose, a literal, or a register it wrote by hand — the last being how
     * {@code movreg ds, cs} reaches here.
     *
     * <p>{@code flagsMayBeRead} is whether anything can still look at the flags afterwards
     * ({@code docs/ir.md} §4.2). A state register takes no immediate, so a value without a register
     * of its own has to be built first, and the shortest way to build a zero writes the flags
     * rather than leaving them alone. When they can still be read the sequence has to keep them,
     * which is the {@code mov} this target would otherwise have used.
     */
    Expansion writeState(SourcePos where, String name, Operand value, boolean flagsMayBeRead);

    /**
     * A sequence that reads one of this target's own registers into a value, or null when this
     * target cannot read that one ({@code docs/ir.md} §8.1).
     *
     * <p>The other direction of {@link #writeState}, and here for the same reason: which
     * instruction reads a register, and whether one does, is the machine's business. On this
     * machine it is one {@code mov} — a value register or a segment register moves into a value
     * directly — and a machine on which it is more says so by answering with the sequence.
     */
    Expansion readState(SourcePos where, Operand destination, String register);

    /**
     * A sequence that puts two bytes into one word, or null when this target has no idiom for it.
     *
     * <p>Both are values the compiler knows are a byte with zeroes above them, because a statement
     * said so ({@link i8086.ir.Conversion#ZERO_EXTEND}), so the word they make is the low byte of one
     * in the high half and the low byte of the other in the low half — and on a machine whose halves
     * are addressable that is two moves, where the arithmetic it stands for is a shift and an add.
     *
     * <p>The destination is a value and the two sources are values, so which register each has is the
     * allocator's to decide, and the copies that turn out to be unnecessary are its to drop. What the
     * sequence does to the flags is its own business and is declared with it: selection substitutes
     * this where nothing is reading them, and a machine whose idiom leaves the flags alone can say so
     * by answering with an expansion that keeps them.
     */
    default Expansion combineBytes(SourcePos where, Operand destination, Operand high, Operand low) {
        return null;
    }

    /**
     * The instruction that puts a zero of this width into a register ({@code docs/ir.md} §4.2).
     *
     * <p>{@code flagsMayBeRead} is the half of the question that is about the flags: a zero can be
     * moved in, which leaves them alone, or cleared, which writes them — and a target that clears
     * has to say so by answering with an instruction the caller may only use where they are dead.
     *
     * <p>The width is the other half, and it is why the caller passes it: clearing a word with
     * {@code xor r, r} is shorter than moving the literal there, and on a byte the two cost the
     * same, so a target that has both answers with the shorter one for the width it is given.
     */
    Instruction zero(SourcePos where, Operand register, Size size, boolean flagsMayBeRead);

    /**
     * The cleanup this machine's own code needs, once every value has a register.
     *
     * <p>This is the target-specific tail {@code AGENTS.md} invariant 2 allows — at most three
     * passes for one target, marked as such in the pipeline listing, living with the target rather
     * than in the generic pass package. What it is for is stating an encoding-level fact where the
     * alternative is another abstraction, and the reason it is <em>here</em>, after selection and
     * allocation, is that the facts worth stating are about registers: which register a value was
     * given is not known any earlier than this. The IR is passed along with the code because the
     * questions a rewrite has to ask are questions about the program — whether the flags it is
     * about to stop setting can be read anywhere afterwards, most of all — and a target that asks
     * them of the form asks them once ({@code docs/ir.md} §4.2).
     *
     * <p>A target with nothing to say answers with the code it was given, and that is most of them:
     * this is the place for a machine whose cheapest instructions are not its most general ones.
     */
    default Selection tail(SsaForm form, Selection selection) {
        return selection;
    }
}
