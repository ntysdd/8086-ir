package i8086.target;

import i8086.SourcePos;
import i8086.asm.Instruction;
import i8086.asm.Operand;
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
        suite.add("I8086 says what an instruction destroys", I8086Test::destroyedRegisters);
        suite.add("I8086 counts half a register as the whole one", I8086Test::halvesCount);
        suite.add("I8086 says an instruction that writes nothing destroys nothing",
                I8086Test::destroysNothing);
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
