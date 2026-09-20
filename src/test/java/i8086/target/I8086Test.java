package i8086.target;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
import i8086.asm.Size;
import i8086.ir.Operator;
import i8086.testing.Assert;
import i8086.testing.Suite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Tests for what the target says about its own instructions.
 *
 * <p>These are facts about the 8086 and not about the pipeline, so they are asked
 * directly: an instruction that writes a register the selector chose itself destroys
 * that register, and one with an implicit result destroys whatever it leaves the
 * answer in. What the allocator does with the answer is tested where it can be seen,
 * in the assembly that comes out.
 */
public final class I8086Test {

    private static final SourcePos AT = new SourcePos("test.asm", 1, 1);

    private I8086Test() {
    }

    public static void register(Suite suite) {
        suite.add("I8086 says which register can hold a byte", I8086Test::byteHalves);
        suite.add("I8086 widens a byte with the instructions it has", I8086Test::widensBytes);
        suite.add("I8086 builds a zero the way the flags allow",
                I8086Test::buildsZeroesTheWayTheFlagsAllow);
        suite.add("I8086 says a comparison with zero is a test", I8086Test::comparesZeroWithATest);
        suite.add("I8086 says where a multiplication's answer arrives",
                I8086Test::saysWhereTheAnswerArrives);
        suite.add("I8086 writes only registers a value cannot live in",
                I8086Test::writableStateHoldsNoValues);
        suite.add("I8086 says what an instruction destroys", I8086Test::destroyedRegisters);
        suite.add("I8086 names the register a read has to find untouched",
                I8086Test::readsNameTheWholeRegister);
        suite.add("I8086 counts half a register as the whole one", I8086Test::halvesCount);
        suite.add("I8086 says an instruction that writes nothing destroys nothing",
                I8086Test::destroysNothing);
        suite.add("I8086 names the operations its mnemonics spell",
                I8086Test::namesStatementOperations);
        suite.add("I8086 says why a mnemonic is not a statement",
                I8086Test::explainsStatementRefusals);
    }

    /**
     * The registers {@code movreg} may write are exactly the ones a value cannot live in, which is
     * what makes a standalone write to them safe: nothing else can be in the register, so the write
     * does not have to survive a stretch of code to be read, and nothing has to be pinned
     * ({@code docs/ir.md} §8.1, §12 item 12).
     *
     * <p>That is the rule the list means, and this is the test that says so — if the allocator's
     * classes ever grow, the list has to move with them.
     */
    private static void writableStateHoldsNoValues() {
        Target target = Targets.byName("8086");
        for (String register : target.stateRegisters()) {
            Assert.assertTrue(target.isRegister(register),
                    "'" + register + "' is a register this machine has");
            Assert.assertFalse(target.valueRegisters().contains(register),
                    "'" + register + "' is not somewhere a value may live");
            Assert.assertFalse(target.addressRegisters().contains(register),
                    "'" + register + "' is not somewhere an address may live");
            Assert.assertNotNull(target.writeState(AT, register, number(1), false),
                    "and this target can write it: " + register);
        }
        for (String register : target.valueRegisters()) {
            Assert.assertFalse(target.stateRegisters().contains(register),
                    "a register a value lives in is not on the list: " + register);
        }
    }

    /**
     * Where a byte value lives: the low half of a register that has one, and no other register.
     *
     * <p>Four of the six value registers have a low byte and {@code si} and {@code di} do not, which
     * is the whole of why a byte value has fewer places to live ({@code docs/ir.md} §3.2). The high
     * halves are deliberately absent: {@code ah} is not somewhere a value lives, it is a register a
     * machine statement is given.
     */
    private static void byteHalves() {
        Target target = Targets.byName("8086");
        Assert.assertEquals("al", target.byteRegister("ax"));
        Assert.assertEquals("bl", target.byteRegister("bx"));
        Assert.assertEquals("cl", target.byteRegister("cx"));
        Assert.assertEquals("dl", target.byteRegister("dx"));
        Assert.assertNull(target.byteRegister("si"), "si has no low byte");
        Assert.assertNull(target.byteRegister("di"), "nor does di");
        Assert.assertNull(target.byteRegister("ah"), "the high half is not where a value lives");
        for (String register : target.valueRegisters()) {
            Assert.assertTrue(target.byteRegister(register) == null
                            || register.startsWith("a") || register.startsWith("b")
                            || register.startsWith("c") || register.startsWith("d"),
                    "only a register with a low byte names one: " + register);
        }
    }

    /**
     * The register a value would have to be in for a read of this one to see it: the register itself
     * for one a value can live in, the register a byte half belongs to, and nothing for the ones no
     * value is ever given ({@code docs/ir.md} §8.1).
     *
     * <p>This is what a read is checked against — nothing of the compiler's may be in the register,
     * because what the read asks for is what the machine left there — and it answers the way the
     * clobber table does: nothing here can name half a register, so {@code dl} and {@code dx} are one
     * register for this question.
     */
    private static void readsNameTheWholeRegister() {
        Target target = Targets.byName("8086");
        for (String register : target.valueRegisters()) {
            Assert.assertEquals(register, target.valueRegisterOf(register));
            String low = target.byteRegister(register);
            if (low != null) {
                Assert.assertEquals(register, target.valueRegisterOf(low));
            }
        }
        Assert.assertEquals("ax", target.valueRegisterOf("ah"));
        Assert.assertEquals("cx", target.valueRegisterOf("ch"));
        // No value is ever given one of these, so there is nothing for a read of one to keep out.
        for (String register : target.stateRegisters()) {
            Assert.assertNull(target.valueRegisterOf(register),
                    "no value can be in '" + register + "'");
        }
        Assert.assertNull(target.valueRegisterOf("cs"), "nor in cs, which nothing writes");
        Assert.assertNull(target.valueRegisterOf("msg"), "a label holds no register");

        // Reading one is one instruction on this machine, whichever register it is.
        List<Instruction> instructions = target.readState(AT, new Operand.Virtual(AT, "x"), "dl")
                .instructions();
        Assert.assertEquals(1L, instructions.size());
        Assert.assertEquals("mov", instructions.get(0).mnemonic());
        Assert.assertNotNull(target.readState(AT, new Operand.Virtual(AT, "x"), "ds"),
                "a segment register is read the same way");
        Assert.assertNull(target.readState(AT, new Operand.Virtual(AT, "x"), "zz"),
                "and nothing is read from a name that is not a register");
    }

    /**
     * The words a statement may begin with, and which operation each one names.
     *
     * <p>A word is here only when the operation it names on this machine is the
     * operation the surface already has, which is why {@code inc} is not: it is one
     * byte where {@code add} is three, and shorter precisely because it does not touch
     * the carry, so it is not a spelling of {@code d = eval(d + 1)}
     * ({@code docs/ir.md} §7.3).
     */
    private static void namesStatementOperations() {
        Target target = Targets.byName("8086");
        Assert.assertEquals(Operator.ADD, target.statementOperator("add"));
        Assert.assertEquals(Operator.SUBTRACT, target.statementOperator("sub"));
        Assert.assertEquals(Operator.XOR, target.statementOperator("xor"));
        Assert.assertEquals(Operator.COMPLEMENT, target.statementOperator("not"));
        Assert.assertEquals(Operator.NEGATE, target.statementOperator("neg"));
        Assert.assertEquals(Operator.SHIFT_LEFT, target.statementOperator("shl"));
        Assert.assertEquals(Operator.MULTIPLY_UNSIGNED, target.statementOperator("mul"));
        Assert.assertEquals(Operator.DIVIDE_SIGNED, target.statementOperator("idiv"));
        Assert.assertNull(target.statementOperator("inc"), "inc is not an operation");
        Assert.assertNull(target.statementOperator("dec"), "dec is not an operation");
        Assert.assertNull(target.statementOperator("xchg"), "xchg is not an operation");
        Assert.assertNull(target.statementOperator("tuesday"), "nor is a word of its own");
    }

    private static void explainsStatementRefusals() {
        Target target = Targets.byName("8086");
        // The carry is the difference between inc and add 1, and a reader would not
        // see it, so the refusal has to say so rather than list what is allowed.
        Assert.assertTrue(target.statementProblem("inc", 1).contains("CF"),
                target.statementProblem("inc", 1));
        // One operand, and ax and dx read and written behind the writer's back.
        Assert.assertTrue(target.statementProblem("mul", 1).contains("dx"),
                target.statementProblem("mul", 1));
        Assert.assertTrue(target.statementProblem("cwd", 0).contains("ax"),
                target.statementProblem("cwd", 0));
        // Two operands is the operation the surface has, so there is nothing to say.
        Assert.assertNull(target.statementProblem("mul", 2), "mul d, s is the operation");
        Assert.assertNull(target.statementProblem("add", 2), "add d, s is the operation");
        // A word this target has never heard of is the parser's complaint to make.
        Assert.assertNull(target.statementProblem("tuesday", 1), "not a mnemonic at all");
    }

    private static void destroyedRegisters() {
        Assert.assertEquals("[ax, dx]", clobbers("mul", name("ax")).toString());
        Assert.assertEquals("[ax, dx]", clobbers("div", name("ax")).toString());
        Assert.assertEquals("[ax, si]", clobbers("lodsw").toString());
        // A register the selector wrote itself: one no value was ever given.
        Assert.assertEquals("[cx]", clobbers("mov", name("cl"), number(4)).toString());
        // A label is a name too, and no register holds one.
        Assert.assertEquals("[]", clobbers("jmp", name("somewhere")).toString());
    }

    private static void halvesCount() {
        // Nothing here can name half a register, so writing one is writing the whole
        // of it as far as anything outside this package is concerned.
        Assert.assertEquals("[cx]", clobbers("mov", name("ch"), number(0)).toString());
        Assert.assertEquals("[bx]", clobbers("mov", name("bh"), number(0)).toString());
    }

    /**
     * Widening, the direction this machine has no instruction for.
     *
     * <p>The byte goes into {@code al}, the top half is cleared with {@code xor ah, ah} or filled
     * from the sign with {@code cbw}, and the answer is copied out of {@code ax} — because both of
     * those work on {@code ax} and nowhere else. And {@code cbw} says what it takes away behind the
     * allocator's back, which is the register a value alive across it may not be in
     * ({@code docs/ir.md} §3.5).
     */
    private static void widensBytes() {
        Target target = Targets.byName("8086");
        Operand destination = new Operand.Virtual(AT, "wide#1");
        Operand source = new Operand.Virtual(AT, "small#1");

        Expansion zeroes = target.widen(AT, destination, source, false);
        Assert.assertEquals("mov, xor, mov", mnemonics(zeroes));
        Assert.assertEquals("ah", ((Operand.Name) zeroes.instructions().get(1).operands().get(0)).name());

        Expansion signed = target.widen(AT, destination, source, true);
        Assert.assertEquals("mov, cbw, mov", mnemonics(signed));
        Assert.assertEquals("[ax]", clobbers("cbw").toString());
    }

    /**
     * A zero into a register, which the flags decide between: {@code xor r, r} writes them and
     * {@code mov r, 0} leaves them alone, and the clear is shorter only on a word — where the two
     * are the same two bytes and the value stays the instruction it was written as
     * ({@code docs/ir.md} §4.2).
     */
    private static void buildsZeroesTheWayTheFlagsAllow() {
        Target target = Targets.byName("8086");
        Operand register = new Operand.Virtual(AT, "x#1");
        Assert.assertEquals("xor", target.zero(AT, register, Size.WORD, false).mnemonic());
        Assert.assertEquals("mov", target.zero(AT, register, Size.WORD, true).mnemonic());
        Assert.assertEquals("mov", target.zero(AT, register, Size.BYTE, false).mnemonic());

        // The operand is on both sides, which is what makes it a clear rather than an exclusive-or
        // of one register with another.
        Instruction cleared = target.zero(AT, register, Size.WORD, false);
        Assert.assertEquals(cleared.operands().get(0), cleared.operands().get(1));
    }

    /**
     * And a comparison with zero, which this machine says is a test of the operand against itself:
     * the same condition either way, and a byte shorter without the zero ({@code docs/ir.md} §4.2).
     */
    private static void comparesZeroWithATest() {
        Assert.assertTrue(Targets.byName("8086").zeroComparisonIsATest(),
                "both clear CF and OF and take ZF, SF and PF from the operand");
    }

    /**
     * Where this machine leaves a multiplication's or a division's answer, which is what lets two of
     * them in a row be one chain ({@code docs/ir.md} §6.1).
     *
     * <p>The answer of a remainder is the other half of the pair, and that is the whole reason the
     * question is per operator rather than one register for all of them.
     */
    private static void saysWhereTheAnswerArrives() {
        Target target = Targets.byName("8086");
        Assert.assertEquals("ax", target.answerRegister(Operator.MULTIPLY));
        Assert.assertEquals("ax", target.answerRegister(Operator.MULTIPLY_SIGNED));
        Assert.assertEquals("ax", target.answerRegister(Operator.MULTIPLY_UNSIGNED));
        Assert.assertEquals("ax", target.answerRegister(Operator.DIVIDE));
        Assert.assertEquals("ax", target.answerRegister(Operator.DIVIDE_SIGNED));
        Assert.assertEquals("ax", target.answerRegister(Operator.DIVIDE_UNSIGNED));
        Assert.assertEquals("dx", target.answerRegister(Operator.REMAINDER));
        // An operation with an ordinary form answers in an operand the caller chose, so there is
        // nothing for this question to say about it.
        Assert.assertNull(target.answerRegister(Operator.ADD), "an addition has forms");
        Assert.assertNull(target.answerRegister(Operator.SHIFT_LEFT), "and so does a shift");
    }

    /** The mnemonics of a sequence, in order, so that its shape can be compared. */
    private static String mnemonics(Expansion expansion) {
        StringBuilder text = new StringBuilder();
        for (Instruction instruction : expansion.instructions()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(instruction.mnemonic());
        }
        return text.toString();
    }

    private static void destroysNothing() {
        // A comparison answers with the flags, and a branch with where it went.
        Assert.assertEquals("[]", clobbers("cmp", name("ax"), number(0)).toString());
        Assert.assertEquals("[]", clobbers("test", name("ax"), name("bx")).toString());
        Assert.assertEquals("[]", clobbers("ret").toString());
    }

    /** What the target says, in a fixed order so that the comparison is stable. */
    private static List<String> clobbers(String mnemonic, Operand... operands) {
        List<String> sorted = new ArrayList<String>(Targets.byName("8086")
                .clobbers(new Instruction(AT, mnemonic, Arrays.asList(operands))));
        Collections.sort(sorted);
        return sorted;
    }

    private static Operand name(String name) {
        return new Operand.Name(AT, name);
    }

    private static Operand number(long value) {
        return new Operand.Number(AT, value, Long.toString(value));
    }
}
