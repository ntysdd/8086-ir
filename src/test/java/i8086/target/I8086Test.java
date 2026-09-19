package i8086.target;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
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
        suite.add("I8086 says what an instruction destroys", I8086Test::destroyedRegisters);
        suite.add("I8086 counts half a register as the whole one", I8086Test::halvesCount);
        suite.add("I8086 says an instruction that writes nothing destroys nothing",
                I8086Test::destroysNothing);
        suite.add("I8086 names the operations its mnemonics spell",
                I8086Test::namesStatementOperations);
        suite.add("I8086 says why a mnemonic is not a statement",
                I8086Test::explainsStatementRefusals);
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
